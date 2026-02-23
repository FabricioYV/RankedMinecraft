package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.fabricioyv.match.MapManager;

import java.util.List;

/**
 * Comando administrativo para gestionar el sistema de mapas.
 * Sin emojis/unicode raro para compilar bien en Windows/Java 8.
 */
public class MapAdminCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission("rankedmc.admin") && !sender.isOp()) {
            sender.sendMessage("§cX No tienes permisos para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        if ("mode".equals(subCommand)) {
            handleMode(sender, args);
            return true;
        }

        if ("reload".equals(subCommand) || "reloadmaps".equals(subCommand)) {
            MapManager.reload();
            sender.sendMessage("§aOK maps.yml recargado. Modo actual: §f" + MapManager.getSelectionMode());
            return true;
        }

        if ("stats".equals(subCommand)) {
            showMapStats(sender);
            return true;
        }

        if ("recent".equals(subCommand)) {
            showRecentMaps(sender, args);
            return true;
        }

        if ("clear".equals(subCommand)) {
            clearRecentMaps(sender, args);
            return true;
        }

        if ("list".equals(subCommand)) {
            listAvailableMaps(sender, args);
            return true;
        }

        if ("test".equals(subCommand)) {
            testMapSelection(sender, args);
            return true;
        }

        showHelp(sender);
        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6=== Sistema de Mapas - Admin ===");
        sender.sendMessage("§e/mapadmin stats §7- Ver estadisticas");
        sender.sendMessage("§e/mapadmin recent [2v2|5v5|8v8] §7- Ver recientes");
        sender.sendMessage("§e/mapadmin clear [2v2|5v5|8v8|all] §7- Limpiar recientes");
        sender.sendMessage("§e/mapadmin list [2v2|5v5|8v8] §7- Listar mapas");
        sender.sendMessage("§e/mapadmin test [2v2|5v5|8v8] §7- Test de seleccion (NO marca recientes)");
        sender.sendMessage("§e/mapadmin mode <VOTING|VETO|RANDOM> §7- Cambiar modo (y guardar)");
        sender.sendMessage("§e/mapadmin reload §7- Recargar maps.yml");
    }

    private void handleMode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§6Modo actual: §f" + MapManager.getSelectionMode());
            sender.sendMessage("§eUso: §f/mapadmin mode <VOTING|VETO|RANDOM>");
            return;
        }

        String mode = args[1].toUpperCase();
        boolean ok = MapManager.setSelectionMode(mode, true);
        if (!ok) {
            sender.sendMessage("§cX Modo invalido. Usa: VOTING, VETO o RANDOM.");
            return;
        }

        // Recargar por si tambien editaron maps.yml manualmente
        MapManager.reload();

        sender.sendMessage("§aOK Modo actualizado a §f" + MapManager.getSelectionMode() + "§a.");
        if (!MapManager.isPlayerVotingEnabled()) {
            sender.sendMessage("§7Nota: player voting esta §cOFF§7 (normal si no estas en VOTING).");
        }
    }

    private void showMapStats(CommandSender sender) {
        String stats = MapManager.getMapStats();
        sender.sendMessage("§6=== Stats Sistema de Mapas ===");
        String[] lines = stats.split("\n");
        for (String line : lines) {
            sender.sendMessage("§7" + line);
        }
    }

    private void showRecentMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cX Uso: /mapadmin recent [2v2|5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("2v2") && !matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§cX Tipo invalido. Usa: 2v2, 5v5 o 8v8");
            return;
        }

        List<String> recent = MapManager.getRecentMaps(matchType);

        sender.sendMessage("§6=== Recientes para " + matchType + " ===");
        if (recent.isEmpty()) {
            sender.sendMessage("§7No hay recientes para " + matchType);
        } else {
            sender.sendMessage("§eCantidad: §f" + recent.size());
            for (int i = 0; i < recent.size(); i++) {
                sender.sendMessage("§7" + (i + 1) + ". §f" + recent.get(i));
            }
        }
    }

    private void clearRecentMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cX Uso: /mapadmin clear [2v2|5v5|8v8|all]");
            return;
        }

        String target = args[1].toLowerCase();

        if ("2v2".equals(target)) {
            MapManager.clearRecentMaps("2v2");
            sender.sendMessage("§aOK recientes 2v2 limpiados");
            return;
        }
        if ("5v5".equals(target)) {
            MapManager.clearRecentMaps("5v5");
            sender.sendMessage("§aOK recientes 5v5 limpiados");
            return;
        }
        if ("8v8".equals(target)) {
            MapManager.clearRecentMaps("8v8");
            sender.sendMessage("§aOK recientes 8v8 limpiados");
            return;
        }
        if ("all".equals(target)) {
            MapManager.clearAllRecentMaps();
            sender.sendMessage("§aOK todos los recientes limpiados");
            return;
        }

        sender.sendMessage("§cX Opcion invalida. Usa: 2v2, 5v5, 8v8 o all");
    }

    private void listAvailableMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cX Uso: /mapadmin list [2v2|5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("2v2") && !matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§cX Tipo invalido. Usa: 2v2, 5v5 o 8v8");
            return;
        }

        List<String> available = MapManager.getAvailableMaps(matchType);
        List<String> recent = MapManager.getRecentMaps(matchType);

        sender.sendMessage("§6=== Mapas para " + matchType + " ===");
        sender.sendMessage("§eTotal: §f" + available.size());

        for (String map : available) {
            String status = recent.contains(map) ? "§c[RECIENTE]" : "§a[OK]";
            sender.sendMessage("§7- §f" + map + " " + status);
        }
    }

    private void testMapSelection(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cX Uso: /mapadmin test [2v2|5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("2v2") && !matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§cX Tipo invalido. Usa: 2v2, 5v5 o 8v8");
            return;
        }

        sender.sendMessage("§6=== Test Seleccion de Mapa ===");
        sender.sendMessage("§eTipo: §f" + matchType);
        sender.sendMessage("§eModo: §f" + MapManager.getSelectionMode());

        List<String> recent = MapManager.getRecentMaps(matchType);
        sender.sendMessage("§eRecientes: §f" + recent.size());

        String selected = MapManager.getRandomMap(matchType);
        sender.sendMessage("§aMapa seleccionado: §f" + selected);
        sender.sendMessage("§7Nota: este test NO marca recientes. Eso ocurre al iniciar el match oficial.");
    }
}
