package org.fabricioyv.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TitleUtil {

    private TitleUtil() {}

    public static void send(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (player == null || !player.isOnline()) return;

        String t = title == null ? "" : ChatColor.translateAlternateColorCodes('&', title);
        String s = subtitle == null ? "" : ChatColor.translateAlternateColorCodes('&', subtitle);

        // 1) Intentar API moderna si existe (algunos forks la traen)
        if (tryBukkitSendTitle(player, t, s, fadeIn, stay, fadeOut)) return;

        // 2) Fallback 1.8.8 por NMS (reflexión)
        tryNmsTitle(player, t, s, fadeIn, stay, fadeOut);
    }

    private static boolean tryBukkitSendTitle(Player player, String t, String s, int fadeIn, int stay, int fadeOut) {
        try {
            Method m = player.getClass().getMethod("sendTitle",
                    String.class, String.class, int.class, int.class, int.class);
            m.invoke(player, t, s, fadeIn, stay, fadeOut);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static void tryNmsTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

            // CraftPlayer + getHandle + playerConnection
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object craftPlayer = craftPlayerClass.cast(player);
            Object handle = craftPlayerClass.getMethod("getHandle").invoke(craftPlayer);
            Field pcField = handle.getClass().getField("playerConnection");
            Object playerConnection = pcField.get(handle);

            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Method sendPacket = playerConnection.getClass().getMethod("sendPacket", packetClass);

            // IChatBaseComponent
            Class<?> iChatBaseComponent = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");

            // Serializer: ChatSerializer (si existe) o IChatBaseComponent$ChatSerializer (1.8.8 típico)
            Method aMethod;
            try {
                Class<?> chatSerializer = Class.forName("net.minecraft.server." + version + ".ChatSerializer");
                aMethod = chatSerializer.getMethod("a", String.class);
            } catch (Throwable ignored) {
                Class<?> chatSerializer = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent$ChatSerializer");
                aMethod = chatSerializer.getMethod("a", String.class);
            }

            Object titleComp = aMethod.invoke(null, "{\"text\":\"" + escapeJson(title) + "\"}");
            Object subComp   = aMethod.invoke(null, "{\"text\":\"" + escapeJson(subtitle) + "\"}");

            // PacketPlayOutTitle + EnumTitleAction
            Class<?> packetTitleClass = Class.forName("net.minecraft.server." + version + ".PacketPlayOutTitle");
            Class<?> enumTitleAction  = Class.forName("net.minecraft.server." + version + ".PacketPlayOutTitle$EnumTitleAction");

            Object TIMES    = Enum.valueOf((Class<? extends Enum>) enumTitleAction, "TIMES");
            Object TITLE    = Enum.valueOf((Class<? extends Enum>) enumTitleAction, "TITLE");
            Object SUBTITLE = Enum.valueOf((Class<? extends Enum>) enumTitleAction, "SUBTITLE");

            Constructor<?> timesCtor = null;
            Constructor<?> textCtor  = null;

            for (Constructor<?> c : packetTitleClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 5 && p[0] == enumTitleAction && p[1] == iChatBaseComponent
                        && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                    timesCtor = c;
                } else if (p.length == 2 && p[0] == enumTitleAction && p[1] == iChatBaseComponent) {
                    textCtor = c;
                }
            }

            // TIMES (si existe ctor)
            if (timesCtor != null) {
                Object pktTimes = timesCtor.newInstance(TIMES, titleComp, fadeIn, stay, fadeOut);
                sendPacket.invoke(playerConnection, pktTimes);
            }

            // TITLE / SUBTITLE
            if (textCtor != null) {
                if (!title.isEmpty()) {
                    Object pktTitle = textCtor.newInstance(TITLE, titleComp);
                    sendPacket.invoke(playerConnection, pktTitle);
                }
                if (!subtitle.isEmpty()) {
                    Object pktSub = textCtor.newInstance(SUBTITLE, subComp);
                    sendPacket.invoke(playerConnection, pktSub);
                }
            }

        } catch (Throwable t) {
            // fallback final: no romper flujo
            try {
                if (!title.isEmpty()) player.sendMessage(title);
                if (!subtitle.isEmpty()) player.sendMessage(subtitle);
            } catch (Throwable ignored2) {}
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}