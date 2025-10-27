package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.AdvancedPlacementAnalyzer;

/**
 * Comando para probar y verificar el sistema de análisis avanzado de placement matches
 * Este comando es para administradores y permite analizar el historial de placement
 * de cualquier jugador usando el AdvancedPlacementAnalyzer
 *
 * Created by FabricioYV
 * @author FabricioYV
 */
public class TestPlacementAnalysisCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Verificar permisos de administrador
        if (!sender.hasPermission("ranked.admin")) {
            sender.sendMessage("§c❌ No tienes permisos para usar este comando.");
            return true;
        }

        // Verificar argumentos
        if (args.length < 1) {
            sender.sendMessage("§e📋 Uso: /testplacement <jugador>");
            sender.sendMessage("§7Analiza el historial de placement matches de un jugador");
            sender.sendMessage("§7Este comando ejecuta un análisis avanzado completo");
            return true;
        }

        String targetPlayerName = args[0];
        sender.sendMessage("§e🔍 Buscando jugador: " + targetPlayerName + "...");

        // Buscar jugador por nombre
        PlayerData targetPlayer = findPlayerByName(targetPlayerName);
        if (targetPlayer == null) {
            sender.sendMessage("§c❌ Jugador '" + targetPlayerName + "' no encontrado en la base de datos.");
            sender.sendMessage("§7Asegúrate de que el jugador haya jugado al menos una vez en el servidor.");
            return true;
        }

        // Verificar que el jugador haya completado placement matches
        if (targetPlayer.getPlacementMatchesPlayed() < 8) {
            sender.sendMessage("§c❌ El jugador '" + targetPlayerName + "' no ha completado las 8 partidas de placement.");
            sender.sendMessage("§7Partidas completadas: " + targetPlayer.getPlacementMatchesPlayed() + "/8");
            sender.sendMessage("§7Este análisis requiere que el jugador haya completado todas las partidas de placement.");
            return true;
        }

        sender.sendMessage("§e🔍 Analizando historial de placement de " + targetPlayerName + "...");
        sender.sendMessage("§7Esto puede tomar unos segundos...");

        try {
            // Ejecutar análisis avanzado
            AdvancedPlacementAnalyzer.PlacementAnalysisResult analysis = 
                AdvancedPlacementAnalyzer.analyzeCompleteHistory(targetPlayer.getMinecraftUuid());

            // Mostrar resultados
            sender.sendMessage("§a✅ Análisis completado:");
            sender.sendMessage("§7" + "=".repeat(50));
            
            // Enviar el reporte línea por línea para mejor formato
            String[] reportLines = analysis.analysisReport.split("\n");
            for (String line : reportLines) {
                if (!line.trim().isEmpty()) {
                    sender.sendMessage("§f" + line);
                }
            }
            
            sender.sendMessage("§7" + "=".repeat(50));
            sender.sendMessage(String.format("§e📊 ELO Calculado: §a%d §7(MMR: %.0f)", 
                             analysis.finalElo, analysis.finalMMR));
            sender.sendMessage(String.format("§e🎯 Puntuación Final: §a%.0f/1000", analysis.score));
            sender.sendMessage(String.format("§e🏆 Rango Asignado: §a%s", analysis.assignedRank.getDisplayName()));

            // Comparar con datos actuales del jugador
            sender.sendMessage("");
            sender.sendMessage("§b📈 Comparación con datos actuales:");
            int eloDiff = analysis.finalElo - targetPlayer.getElo();
            double mmrDiff = analysis.finalMMR - targetPlayer.getMmr();

            sender.sendMessage(String.format("§7• ELO Actual: §f%d §7→ Calculado: §a%d §7(Diferencia: %s%d§7)",
                             targetPlayer.getElo(), analysis.finalElo,
                             eloDiff >= 0 ? "§a+" : "§c", eloDiff));
            sender.sendMessage(String.format("§7• MMR Actual: §f%.0f §7→ Calculado: §a%.0f §7(Diferencia: %s%.0f§7)",
                             targetPlayer.getMmr(), analysis.finalMMR,
                             mmrDiff >= 0 ? "§a+" : "§c", mmrDiff));

            // Mostrar recomendaciones si hay diferencias significativas
            if (Math.abs(eloDiff) > 100) {
                sender.sendMessage("");
                sender.sendMessage("§e⚠️ Diferencia significativa detectada:");
                if (eloDiff > 100) {
                    sender.sendMessage("§7El análisis sugiere que el jugador podría estar subestimado.");
                } else {
                    sender.sendMessage("§7El análisis sugiere que el jugador podría estar sobrestimado.");
                }
            }

        } catch (IllegalStateException e) {
            sender.sendMessage("§c❌ Error de estado: " + e.getMessage());
        } catch (Exception e) {
            sender.sendMessage("§c❌ Error durante el análisis: " + e.getMessage());
            sender.sendMessage("§7Por favor reporta este error a un desarrollador.");
            e.printStackTrace();
        }

        return true;
    }

    /**
     * Busca un jugador por nombre en la base de datos
     * Primero intenta encontrar al jugador online, luego busca en jugadores offline
     */
    private PlayerData findPlayerByName(String playerName) {
        try {
            // Intentar encontrar al jugador si está online
            Player onlinePlayer = org.bukkit.Bukkit.getPlayer(playerName);
            if (onlinePlayer != null) {
                return DatabaseManager.getPlayerByMinecraftUuid(onlinePlayer.getUniqueId().toString());
            }

            // Si no está online, buscar en jugadores offline
            org.bukkit.OfflinePlayer offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(playerName);
            if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                return DatabaseManager.getPlayerByMinecraftUuid(offlinePlayer.getUniqueId().toString());
            }

            // Si no se encuentra, podríamos implementar una búsqueda por nombre en la DB
            // Por ahora retornamos null
            return null;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
