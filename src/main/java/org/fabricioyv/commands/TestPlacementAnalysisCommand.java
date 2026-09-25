package org.fabricioyv.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
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

    private final RankedMinecraft plugin;

    public TestPlacementAnalysisCommand(RankedMinecraft plugin) {
        this.plugin = plugin;
    }

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

        // Resolución de UUID: solo llamadas Bukkit en memoria, sin tocar la BD todavía
        String targetUuid = resolvePlayerUuid(targetPlayerName);
        if (targetUuid == null) {
            sender.sendMessage("§c❌ Jugador '" + targetPlayerName + "' no encontrado en la base de datos.");
            sender.sendMessage("§7Asegúrate de que el jugador haya jugado al menos una vez en el servidor.");
            return true;
        }

        sender.sendMessage("§e🔍 Analizando historial de placement de " + targetPlayerName + "...");
        sender.sendMessage("§7Esto puede tomar unos segundos...");

        // El fetch a BD y analyzeCompleteHistory() son trabajo pesado (I/O + cálculo) --
        // se sacan del hilo principal para no congelar el servidor mientras corren.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerData targetPlayer = DatabaseManager.getPlayerByMinecraftUuid(targetUuid);

            if (targetPlayer == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§c❌ Jugador '" + targetPlayerName + "' no encontrado en la base de datos."));
                return;
            }

            if (targetPlayer.getPlacementMatchesPlayed() < 8) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§c❌ El jugador '" + targetPlayerName + "' no ha completado las 8 partidas de placement.");
                    sender.sendMessage("§7Partidas completadas: " + targetPlayer.getPlacementMatchesPlayed() + "/8");
                    sender.sendMessage("§7Este análisis requiere que el jugador haya completado todas las partidas de placement.");
                });
                return;
            }

            try {
                AdvancedPlacementAnalyzer.PlacementAnalysisResult analysis =
                        AdvancedPlacementAnalyzer.analyzeCompleteHistory(targetPlayer.getMinecraftUuid());

                Bukkit.getScheduler().runTask(plugin, () -> displayAnalysis(sender, targetPlayer, analysis));

            } catch (IllegalStateException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§c❌ Error de estado: " + e.getMessage()));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§c❌ Error durante el análisis: " + e.getMessage());
                    sender.sendMessage("§7Por favor reporta este error a un desarrollador.");
                });
                e.printStackTrace();
            }
        });

        return true;
    }

    private void displayAnalysis(CommandSender sender, PlayerData targetPlayer,
                                  AdvancedPlacementAnalyzer.PlacementAnalysisResult analysis) {
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
    }

    /**
     * Resuelve el UUID de un jugador por nombre usando solo lookups en memoria de Bukkit
     * (online u offline-con-historial). No toca la base de datos.
     */
    private String resolvePlayerUuid(String playerName) {
        Player onlinePlayer = Bukkit.getPlayer(playerName);
        if (onlinePlayer != null) {
            return onlinePlayer.getUniqueId().toString();
        }

        org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        if (offlinePlayer.hasPlayedBefore()) {
            return offlinePlayer.getUniqueId().toString();
        }

        return null;
    }
}
