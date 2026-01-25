package org.fabricioyv.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

public final class MessageUtil {

    // Corto para que no se parta en dos líneas
    public static final String PREFIX = c("&8[&6Keke &fRanked&8]&r ");

    private MessageUtil() {}

    public static String c(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static void send(CommandSender to, String msg) {
        to.sendMessage(PREFIX + c(msg));
    }

    public static void sendRaw(CommandSender to, String msgNoPrefix) {
        to.sendMessage(c(msgNoPrefix));
    }

    public static void broadcast(String msg) {
        Bukkit.broadcastMessage(PREFIX + c(msg));
    }

    public static String sep() {
        return c("&8&m────────────────────────");
    }
}