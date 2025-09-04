package org.fabricioyv.database;

import org.bukkit.Bukkit;
import org.fabricioyv.listeners.MatchStatsListener;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Clase utilitaria para integrar fácilmente el sistema de logs de partidas
 * con el código existente sin afectar la funcionalidad actual
 */
public class MatchLogsIntegration {

    /**
     * Inicia el tracking de estadísticas para una nueva partida
     * Llamar este método cuando se inicie una partida
     */
    public static void startMatchTracking(String matchId, Map<Team, List<PlayerData>> teams, String matchType, String mapName) {
        // Primero inicializar el registro de partida en la base de datos
        MatchLogsManager.initializeMatch(matchId, matchType, mapName).thenAccept(success -> {
            if (success) {
                Map<String, String> playerTeams = new HashMap<>();

                // Convertir estructura de equipos a formato para el listener
                for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
                    String teamName = entry.getKey().name(); // "BLUE" o "RED"

                    for (PlayerData player : entry.getValue()) {
                        playerTeams.put(player.getMinecraftUuid(), teamName);
                    }
                }

                // Inicializar estadísticas en el listener
                MatchStatsListener.initializeMatchStats(matchId, playerTeams);

                Bukkit.getConsoleSender().sendMessage(
                    "§a✅ Iniciado tracking de estadísticas para partida " + matchId
                );
            } else {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al inicializar tracking de partida " + matchId
                );
            }
        });
    }

    /**
     * Registra la muerte de un jugador en la partida
     * Llamar este método cuando un jugador muera
     */
    public static void recordPlayerDeath(String matchId, UUID victimUuid, UUID killerUuid) {
        MatchStatsListener.recordPlayerDeath(matchId, victimUuid, killerUuid);
    }

    /**
     * Actualiza los cambios de rating (ELO/MMR) para un jugador
     * Llamar este método después de calcular los nuevos ratings
     * IMPORTANTE: Este método solo registra los datos, NO modifica la base de datos principal
     */
    public static void updatePlayerRating(String matchId, PlayerData player, int oldElo, double oldMmr, int newElo, double newMmr) {
        MatchStatsListener.setPlayerRatingChanges(
            matchId,
            UUID.fromString(player.getMinecraftUuid()),
            oldElo,        // ELO anterior (antes de la partida)
            newElo,        // ELO nuevo (después de la partida)
            oldMmr,        // MMR anterior (antes de la partida)
            newMmr         // MMR nuevo (después de la partida)
        );
    }

    /**
     * Marca el resultado de la partida para todos los jugadores
     * Llamar este método cuando termine la partida
     */
    public static void setMatchResults(String matchId, Map<Team, List<PlayerData>> teams, Team winnerTeam) {
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            boolean won = entry.getKey().equals(winnerTeam);

            for (PlayerData player : entry.getValue()) {
                MatchStatsListener.setPlayerMatchResult(
                    matchId,
                    UUID.fromString(player.getMinecraftUuid()),
                    won
                );
            }
        }
    }

    /**
     * Finaliza la partida y guarda todos los datos en la base de datos
     * Llamar este método cuando la partida termine completamente
     */
    public static CompletableFuture<Boolean> finalizeAndSaveMatch(
            String matchId,
            String matchType,
            String mapName,
            Team winnerTeam,
            LocalDateTime startTime,
            Map<Team, List<PlayerData>> teams) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Finalizar estadísticas y obtener datos
                Map<UUID, MatchLogsManager.PlayerMatchStats> playerStats =
                    MatchStatsListener.finalizeMatchStats(matchId);

                if (playerStats == null || playerStats.isEmpty()) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c⚠️ No se encontraron estadísticas para la partida " + matchId
                    );
                    return false;
                }

                // Convertir estadísticas a formato de MatchSummary
                Map<String, MatchLogsManager.PlayerMatchStats> statsMap = new HashMap<>();
                for (Map.Entry<UUID, MatchLogsManager.PlayerMatchStats> entry : playerStats.entrySet()) {
                    statsMap.put(entry.getKey().toString(), entry.getValue());
                }

                // Crear resumen de la partida
                MatchLogsManager.MatchSummary matchSummary = new MatchLogsManager.MatchSummary(
                    matchId,
                    matchType,
                    mapName,
                    winnerTeam.name(),
                    startTime,
                    LocalDateTime.now(),
                    statsMap
                );

                // Guardar en base de datos
                Boolean saved = MatchLogsManager.saveMatchData(matchSummary).get();

                if (saved) {
                    // Log de éxito con estadísticas resumidas
                    logMatchSummary(matchSummary);
                }

                return saved;

            } catch (Exception e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al finalizar y guardar partida " + matchId + ": " + e.getMessage()
                );
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * Método de conveniencia para usar con ActiveMatch
     */
    public static CompletableFuture<Boolean> finalizeActiveMatch(ActiveMatch match, Team winnerTeam) {
        return finalizeAndSaveMatch(
            match.getMatchId(),
            match.getMatchType(),
            match.getSelectedMap(),
            winnerTeam,
            match.getStartTime(),
            match.getTeams()
        );
    }

    /**
     * Obtiene estadísticas en tiempo real de una partida activa
     */
    public static MatchLogsManager.PlayerMatchStats getCurrentPlayerStats(String matchId, UUID playerUuid) {
        return MatchStatsListener.getPlayerStats(matchId, playerUuid);
    }

    /**
     * Registra un evento personalizado en la partida
     */
    public static void logCustomEvent(String matchId, String eventType, UUID playerUuid, String eventData) {
        MatchLogsManager.logMatchEvent(matchId, eventType,
            playerUuid != null ? playerUuid.toString() : null, eventData);
    }

    /**
     * Registra eventos importantes de la partida
     */
    public static void logMatchStart(String matchId, String mapName, int playerCount) {
        logCustomEvent(matchId, "MATCH_START", null,
            String.format("Partida iniciada en mapa %s con %d jugadores", mapName, playerCount));
    }

    public static void logMatchEnd(String matchId, Team winnerTeam, long durationSeconds) {
        logCustomEvent(matchId, "MATCH_END", null,
            String.format("Partida finalizada - Ganador: %s - Duración: %d segundos",
                winnerTeam.name(), durationSeconds));
    }

    public static void logForfeit(String matchId, UUID playerUuid, String reason) {
        logCustomEvent(matchId, "FORFEIT", playerUuid, reason);
    }

    public static void logPlayerReconnect(String matchId, UUID playerUuid) {
        logCustomEvent(matchId, "PLAYER_RECONNECT", playerUuid, "Jugador se reconectó a la partida");
    }

    public static void logPlayerDisconnect(String matchId, UUID playerUuid) {
        logCustomEvent(matchId, "PLAYER_DISCONNECT", playerUuid, "Jugador se desconectó de la partida");
    }

    /**
     * Log detallado del resumen de la partida
     */
    private static void logMatchSummary(MatchLogsManager.MatchSummary matchSummary) {
        Bukkit.getConsoleSender().sendMessage("§a" + "=".repeat(50));
        Bukkit.getConsoleSender().sendMessage("§a✅ PARTIDA GUARDADA EN BASE DE DATOS");
        Bukkit.getConsoleSender().sendMessage("§7ID: §f" + matchSummary.getMatchId());
        Bukkit.getConsoleSender().sendMessage("§7Tipo: §f" + matchSummary.getMatchType());
        Bukkit.getConsoleSender().sendMessage("§7Mapa: §f" + matchSummary.getMapName());
        Bukkit.getConsoleSender().sendMessage("§7Ganador: §f" + matchSummary.getWinnerTeam());
        Bukkit.getConsoleSender().sendMessage("§7Duración: §f" + matchSummary.getDurationSeconds() + " segundos");
        Bukkit.getConsoleSender().sendMessage("§7Jugadores: §f" + matchSummary.getPlayerStats().size());

        // Estadísticas resumidas
        int totalKills = 0;
        int totalDeaths = 0;
        double totalDamage = 0;
        int totalArrows = 0;

        for (MatchLogsManager.PlayerMatchStats stats : matchSummary.getPlayerStats().values()) {
            totalKills += stats.getKills();
            totalDeaths += stats.getDeaths();
            totalDamage += stats.getDamageDealt();
            totalArrows += stats.getArrowsShot();
        }

        Bukkit.getConsoleSender().sendMessage("§7Total Kills: §f" + totalKills);
        Bukkit.getConsoleSender().sendMessage("§7Total Deaths: §f" + totalDeaths);
        Bukkit.getConsoleSender().sendMessage("§7Total Damage: §f" + String.format("%.1f", totalDamage));
        Bukkit.getConsoleSender().sendMessage("§7Total Arrows: §f" + totalArrows);
        Bukkit.getConsoleSender().sendMessage("§a" + "=".repeat(50));
    }

    /**
     * Método para obtener estadísticas históricas de un jugador
     */
    public static CompletableFuture<List<MatchLogsManager.MatchSummary>> getPlayerMatchHistory(
            String playerUuid, int limit) {
        return MatchLogsManager.getPlayerRecentMatches(playerUuid, limit);
    }

    /**
     * Método para obtener estadísticas de una partida específica
     */
    public static CompletableFuture<MatchLogsManager.MatchSummary> getMatchDetails(String matchId) {
        return MatchLogsManager.getMatchStats(matchId);
    }
}
