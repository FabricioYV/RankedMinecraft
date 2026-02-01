package org.fabricioyv.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ActionBarUtil {

    private ActionBarUtil() {}

    public static void send(Player player, String msg) {
        if (player == null || !player.isOnline() || msg == null) return;

        final String colored = ChatColor.translateAlternateColorCodes('&', msg);

        // 1) Intentar API spigot/bungee (si existe en TU jar)
        if (trySpigotActionBar(player, colored)) return;

        // 2) Fallback NMS por reflexión (1.8.8 compatible)
        tryNmsActionBar(player, colored);
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static boolean trySpigotActionBar(Player player, String colored) {
        try {
            Object spigot = player.getClass().getMethod("spigot").invoke(player);

            Class<?> chatMessageType = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Object actionBarEnum = Enum.valueOf((Class<? extends Enum>) chatMessageType, "ACTION_BAR");

            Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Object component = textComponent.getConstructor(String.class).newInstance(colored);

            for (Method m : spigot.getClass().getMethods()) {
                if (!m.getName().equals("sendMessage")) continue;

                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (!p[0].equals(chatMessageType)) continue;

                // sendMessage(ChatMessageType, BaseComponent)
                if (!p[1].isArray()) {
                    m.invoke(spigot, actionBarEnum, component);
                    return true;
                }

                // sendMessage(ChatMessageType, BaseComponent[]) (varargs)
                Object arr = Array.newInstance(p[1].getComponentType(), 1);
                Array.set(arr, 0, component);
                m.invoke(spigot, actionBarEnum, arr);
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void tryNmsActionBar(Player player, String colored) {
        try {
            String version = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];

            // CraftPlayer + getHandle
            Class<?> craftPlayerClass = Class.forName("org.bukkit.craftbukkit." + version + ".entity.CraftPlayer");
            Object craftPlayer = craftPlayerClass.cast(player);
            Object handle = craftPlayerClass.getMethod("getHandle").invoke(craftPlayer);

            // playerConnection
            Field pcField = handle.getClass().getField("playerConnection");
            Object playerConnection = pcField.get(handle);

            // Serializer: probar ChatSerializer (si existe) y si no, IChatBaseComponent$ChatSerializer (1.8.8 típico)
            Method aMethod = null;
            Class<?> serializerClass = null;

            try {
                serializerClass = Class.forName("net.minecraft.server." + version + ".ChatSerializer");
                aMethod = serializerClass.getMethod("a", String.class);
            } catch (Throwable ignored) {
                serializerClass = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent$ChatSerializer");
                aMethod = serializerClass.getMethod("a", String.class);
            }

            String json = "{\"text\":\"" + escapeJson(colored) + "\"}";
            Object icbc = aMethod.invoke(null, json);

            Class<?> iChatBaseComponent = Class.forName("net.minecraft.server." + version + ".IChatBaseComponent");
            Class<?> packetPlayOutChat = Class.forName("net.minecraft.server." + version + ".PacketPlayOutChat");

            // PacketPlayOutChat(IChatBaseComponent, byte)
            Constructor<?> ctor = packetPlayOutChat.getConstructor(iChatBaseComponent, byte.class);
            Object packet = ctor.newInstance(icbc, (byte) 2); // 2 = ACTION_BAR

            // sendPacket(Packet)
            Class<?> packetClass = Class.forName("net.minecraft.server." + version + ".Packet");
            Method sendPacket = playerConnection.getClass().getMethod("sendPacket", packetClass);
            sendPacket.invoke(playerConnection, packet);

        } catch (Throwable t) {
            // Fallback final: no rompas el plugin
            try { player.sendMessage(colored); } catch (Throwable ignored) {}
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}