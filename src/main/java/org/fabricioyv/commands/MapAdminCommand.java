package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.MapManager;

/**
 * Comando administrativo para gestionar el sistema de mapas
 */
public class MapAdminCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        // Verificar permisos de administrador
        if (!sender.hasPermission("rankedmc.admin") && !sender.isOp()) {
            sender.sendMessage("§c❌ No tienes permisos para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "stats":
                showMapStats(sender);
                break;

            case "recent":
                showRecentMaps(sender, args);
                break;

            case "clear":
                clearRecentMaps(sender, args);
                break;

            case "list":
                listAvailableMaps(sender, args);
                break;

            case "test":
                testMapSelection(sender, args);
                break;

            default:
                showHelp(sender);
                break;
        }

        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6=== Sistema de Mapas - Comandos Admin ===");
        sender.sendMessage("§e/mapadmin stats §7- Mostrar estadísticas del sistema");
        sender.sendMessage("§e/mapadmin recent [5v5|8v8] §7- Ver mapas recientes");
        sender.sendMessage("§e/mapadmin clear [5v5|8v8|all] §7- Limpiar mapas recientes");
        sender.sendMessage("§e/mapadmin list [5v5|8v8] §7- Listar mapas disponibles");
        sender.sendMessage("§e/mapadmin test [5v5|8v8] §7- Probar selección de mapa");
    }

    private void showMapStats(CommandSender sender) {
        String stats = MapManager.getMapStats();
        sender.sendMessage("§6=== Estadísticas del Sistema de Mapas ===");

        // Convertir el formato Discord a formato Minecraft
        String[] lines = stats.split("\n");
        for (String line : lines) {
            String formatted = line.replace("**", "§e").replace("📊", "§6📊").replace("🔄", "§a🔄").replace("🗳️", "§b🗳️");
            sender.sendMessage(formatted);
        }
    }

    private void showRecentMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c❌ Uso: /mapadmin recent [5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§c❌ Tipo de partida inválido. Usa: 5v5 o 8v8");
            return;
        }

        var recentMaps = MapManager.getRecentMaps(matchType);

        sender.sendMessage("§6=== Mapas Recientes para " + matchType + " ===");
        if (recentMaps.isEmpty()) {
            sender.sendMessage("§7No hay mapas recientes registrados para " + matchType);
        } else {
            sender.sendMessage("§eCantidad: §f" + recentMaps.size() + " mapas");
            for (int i = 0; i < recentMaps.size(); i++) {
                sender.sendMessage("§7" + (i + 1) + ". §f" + recentMaps.get(i));
            }
        }
    }

    private void clearRecentMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c❌ Uso: /mapadmin clear [5v5|8v8|all]");
            return;
        }

        String target = args[1].toLowerCase();

        switch (target) {
            case "5v5":
                MapManager.clearRecentMaps("5v5");
                sender.sendMessage("§a✅ Lista de mapas recientes para 5v5 limpiada");
                break;

            case "8v8":
                MapManager.clearRecentMaps("8v8");
                sender.sendMessage("§a✅ Lista de mapas recientes para 8v8 limpiada");
                break;

            case "all":
                MapManager.clearAllRecentMaps();
                sender.sendMessage("§a✅ Todas las listas de mapas recientes limpiadas");
                break;

            default:
                sender.sendMessage("§c❌ Opción inválida. Usa: 5v5, 8v8 o all");
                break;
        }
    }

    private void listAvailableMaps(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c❌ Uso: /mapadmin list [5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§c❌ Tipo de partida inválido. Usa: 5v5 o 8v8");
            return;
        }

        var availableMaps = MapManager.getAvailableMaps(matchType);
        var recentMaps = MapManager.getRecentMaps(matchType);

        sender.sendMessage("§6=== Mapas Disponibles para " + matchType + " ===");
        sender.sendMessage("§eTotal: §f" + availableMaps.size() + " mapas");

        for (String map : availableMaps) {
            String status = recentMaps.contains(map) ? "§c[RECIENTE]" : "§a[DISPONIBLE]";
            sender.sendMessage("§7• §f" + map + " " + status);
        }
    }

    private void testMapSelection(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c❌ Uso: /mapadmin test [5v5|8v8]");
            return;
        }

        String matchType = args[1];
        if (!matchType.equals("5v5") && !matchType.equals("8v8")) {
            sender.sendMessage("§c❌ Tipo de partida inválido. Usa: 5v5 o 8v8");
            return;
        }

        sender.sendMessage("§6=== Prueba de Selección de Mapa ===");
        sender.sendMessage("§eTipo de partida: §f" + matchType);

        // Mostrar estado actual
        var recentMaps = MapManager.getRecentMaps(matchType);
        sender.sendMessage("§eMapas recientes: §f" + recentMaps.size() + " (" + String.join(", ", recentMaps) + ")");

        // Probar selección
        String selectedMap = MapManager.getRandomMap(matchType);
        sender.sendMessage("§aMapa seleccionado: §f" + selectedMap);

        // Mostrar nuevo estado
        var newRecentMaps = MapManager.getRecentMaps(matchType);
        sender.sendMessage("§eNuevos mapas recientes: §f" + newRecentMaps.size() + " (" + String.join(", ", newRecentMaps) + ")");
    }
}