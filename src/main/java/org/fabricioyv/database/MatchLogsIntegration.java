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
import java.util.concurrent.TimeUnit;

/**
 * Clase utilitaria para integrar fácilmente el sistema de logs de partidas
 * con el código existente sin afectar la funcionalidad actual
 */
public class MatchLogsIntegration {

    /**
     * Inicia el tracking de estadísticas para una nueva partida
     * Llamar este método cuando se inicie una partida
     * MODIFICADO: Inicializa estadísticas en memoria INMEDIATAMENTE, BD es secundaria
     */
    public static void startMatchTracking(String matchId, Map<Team, List<PlayerData>> teams, String matchType, String mapName) {
        // **LOGGING CRÍTICO**: Verificar que el método se está llamando
        System.out.println("[DEBUG] startMatchTracking llamado para " + matchId);
        Bukkit.getConsoleSender().sendMessage(
            "§c[DEBUG] startMatchTracking EJECUTÁNDOSE para " + matchId
        );

        try {
            Bukkit.getConsoleSender().sendMessage(
                "§e[MatchLogs] Iniciando tracking para partida " + matchId + " con " + teams.size() + " equipos"
            );

            // **CRÍTICO**: Verificar que teams no esté vacío
            if (teams == null || teams.isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c[MatchLogs] ERROR: teams está vacío o nulo"
                );
                return;
            }

            // **CRÍTICO**: Convertir equipos PRIMERO con logging detallado
            Map<String, String> playerTeams = new HashMap<>();
            int totalPlayers = 0;

            for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
                String teamName = entry.getKey().name(); // "BLUE" o "RED"
                List<PlayerData> teamPlayers = entry.getValue();

                Bukkit.getConsoleSender().sendMessage(
                    "§e[MatchLogs] Procesando equipo " + teamName + " con " + teamPlayers.size() + " jugadores"
                );

                for (PlayerData player : teamPlayers) {
                    playerTeams.put(player.getMinecraftUuid(), teamName);
                    totalPlayers++;

                    Bukkit.getConsoleSender().sendMessage(
                        "§a[MatchLogs] ✓ Agregado " + player.getMinecraftName() + " al equipo " + teamName
                    );
                }
            }

            Bukkit.getConsoleSender().sendMessage(
                "§e[MatchLogs] Convertidos " + totalPlayers + " jugadores a formato de tracking"
            );

            // **CRÍTICO**: Verificar que playerTeams no esté vacío
            if (playerTeams.isEmpty()) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c[MatchLogs] ERROR: playerTeams está vacío después de conversión"
                );
                return;
            }

            // **CRÍTICO**: Inicializar estadísticas EN MEMORIA INMEDIATAMENTE
            Bukkit.getConsoleSender().sendMessage(
                "§e[MatchLogs] Llamando a MatchStatsListener.initializeMatchStats..."
            );

            MatchStatsListener.initializeMatchStats(matchId, playerTeams);

            Bukkit.getConsoleSender().sendMessage(
                "§a✅ Estadísticas en memoria inicializadas para partida " + matchId
            );

            // **SECUNDARIO**: Inicializar en BD de forma asíncrona (no bloquear)
            CompletableFuture.supplyAsync(() -> {
                try {
                    Bukkit.getConsoleSender().sendMessage(
                        "§e[MatchLogs] Iniciando inicialización en BD para " + matchId
                    );
                    return MatchLogsManager.initializeMatch(matchId, matchType, mapName).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c⚠️ Error al inicializar en BD (no crítico): " + e.getMessage()
                    );
                    return false;
                }
            }).thenAccept(success -> {
                if (success) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§a✅ Inicialización en BD completada para " + matchId
                    );
                } else {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c⚠️ Falló inicialización en BD para " + matchId + " (estadísticas en memoria OK)"
                    );
                }
            });

        } catch (Exception e) {
            Bukkit.getConsoleSender().sendMessage(
                "§c❌ Error crítico al inicializar tracking de partida " + matchId + ": " + e.getMessage()
            );
            e.printStackTrace();

            // **LOGGING DETALLADO DEL ERROR**
            System.out.println("[ERROR] Excepción en startMatchTracking:");
            e.printStackTrace();
        }

        Bukkit.getConsoleSender().sendMessage(
            "§c[DEBUG] startMatchTracking FINALIZADO para " + matchId
        );
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
     * MODIFICADO: Ahora acepta estadísticas pre-finalizadas para evitar race conditions
     */
    public static CompletableFuture<Boolean> finalizeAndSaveMatch(
            String matchId,
            String matchType,
            String mapName,
            Team winnerTeam,
            LocalDateTime startTime,
            Map<Team, List<PlayerData>> teams,
            Map<UUID, MatchLogsManager.PlayerMatchStats> preFinalizedStats) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // **CRÍTICO FIX**: Usar estadísticas pre-finalizadas si están disponibles
                // Esto evita la race condition donde múltiples threads intentan finalizar las mismas stats
                Map<UUID, MatchLogsManager.PlayerMatchStats> playerStats = preFinalizedStats;

                // Fallback: Si no se proporcionaron stats pre-finalizadas, intentar finalizarlas aquí
                if (playerStats == null) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§e⚠️ Usando fallback para finalizar stats de partida " + matchId
                    );
                    playerStats = MatchStatsListener.finalizeMatchStats(matchId);
                }

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
     * MODIFICADO: Ahora acepta estadísticas pre-finalizadas
     */
    public static CompletableFuture<Boolean> finalizeActiveMatch(ActiveMatch match, Team winnerTeam,
                                                                 Map<UUID, MatchLogsManager.PlayerMatchStats> preFinalizedStats) {
        return finalizeAndSaveMatch(
            match.getMatchId(),
            match.getMatchType(),
            match.getSelectedMap(),
            winnerTeam,
            match.getStartTime(),
            match.getTeams(),
            preFinalizedStats
        );
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



    /**
     * Log detallado del resumen de la partida - ELIMINADO para evitar spam
     */
    private static void logMatchSummary(MatchLogsManager.MatchSummary matchSummary) {
        // ELIMINADO: Logs excesivos que generaban spam por cada partida
        // Solo mantener el registro en base de datos, no spam en consola
    }

}
