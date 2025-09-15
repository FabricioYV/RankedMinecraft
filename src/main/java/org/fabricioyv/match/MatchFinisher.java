package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.database.MatchLogsIntegration;
import org.fabricioyv.database.MatchLogsManager;
import org.fabricioyv.listeners.MatchStatsListener;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.rating.MMRCalculator;
import org.fabricioyv.rating.ProgressiveEloCalculator;
import org.fabricioyv.rating.Rank;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class MatchFinisher {

    public static void finishMatch(ActiveMatch activeMatch, Team winnerTeam, RankedMinecraft plugin, DiscordLogger logger){
        String matchId = activeMatch.getMatchId();
        long startTime = System.currentTimeMillis();

        // ESTABLECER EL GANADOR EN ACTIVEMATCH
        activeMatch.setWinnerTeam(winnerTeam);

        logger.matchEvent(matchId, "Finalizando Partida",
                "Iniciando proceso de finalización", activeMatch.getAllPlayers().size());

        // ========================================
        // OPERACIONES CRÍTICAS INSTANTÁNEAS (MAIN THREAD)
        // Usando métodos existentes para no duplicar código
        // ========================================

        try {
            // 1. INMEDIATO: Marcar jugadores como NO en partida y listos para otra
            List<PlayerData> allPlayers = activeMatch.getAllPlayers();
            for (PlayerData player : allPlayers) {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
                // OPTIMIZACIÓN: Actualizar cache usando BatchProcessor
                try {
                    DatabaseManager.updatePlayerMatchStatusAsync(
                        player.getMinecraftUuid(), false, null);
                } catch (Exception e) {
                    // Log warning pero continúa
                    logger.warning("Cache Update Failed",
                        "Error actualizando cache para " + player.getMinecraftUuid());
                }
            }

            // 2. INMEDIATO: Limpiar jugadores de la cola (REUTILIZAR método existente)
            cleanupPlayersFromQueue(activeMatch, logger);

            // 3. INMEDIATO: Notificar resultado básico en Minecraft (mensaje simple)
            String winMessage = "§a✅ PARTIDA TERMINADA - Ganó " + winnerTeam.getDisplayName();
            String availableMessage = "§e⚡ Estás disponible para una nueva partida";

            Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
            for (List<PlayerData> teamPlayers : teams.values()) {
                for (PlayerData playerData : teamPlayers) {
                    try {
                        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                        if (mcPlayer != null && mcPlayer.isOnline()) {
                            mcPlayer.sendMessage(winMessage);
                            mcPlayer.sendMessage(availableMessage);
                        }
                    } catch (Exception e) {
                        // Continúa si hay error con un jugador específico
                        logger.warning("Player Message Failed",
                            "Error enviando mensaje a " + playerData.getMinecraftUuid());
                    }
                }
            }

            // 4. INMEDIATO: Mover jugadores de Discord (REUTILIZAR método existente)
            try {
                movePlayersToWaitingRoom(activeMatch, plugin, logger);
            } catch (Exception e) {
                logger.warning("Discord Move Failed",
                    "Error moviendo jugadores a sala de espera: " + e.getMessage());
            }

            // 5. INMEDIATO: Finalizar estado de partida
            activeMatch.setStatus(ActiveMatch.MatchStatus.FINISHED);
            // CORREGIDO: Usar el método correcto para remover la partida activa
            activeMatch.cleanup(); // Este método debería manejar la remoción
            MatchState.endMatch();

            logger.success("Critical Operations Complete",
                    "Operaciones críticas completadas en " + (System.currentTimeMillis() - startTime) + "ms");

        } catch (Exception e) {
            logger.systemError("MatchFinisher", "Error en operaciones críticas " + matchId, e.getMessage());
            emergencyCleanup(activeMatch, plugin, logger);
            return;
        }

        // ========================================
        // OPERACIONES SECUNDARIAS ASÍNCRONAS
        // Reutilizando métodos existentes
        // ========================================

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Calcular duración de la partida
                Duration matchDuration = Duration.between(activeMatch.getStartTime(), LocalDateTime.now());
                long durationSeconds = matchDuration.getSeconds();

                // ASYNC: Actualizar ELO y MMR (REUTILIZAR método existente)
                Map<String, Integer> eloChanges = updatePlayerStatistics(activeMatch, winnerTeam, logger, plugin);

                // ASYNC: Enviar resultados completos a Discord (REUTILIZAR método existente)
                sendDiscordResults(activeMatch, winnerTeam, eloChanges, durationSeconds, logger);

                // ASYNC: Notificar jugadores con detalles completos (REUTILIZAR método existente)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    notifyPlayersInMinecraft(activeMatch, winnerTeam, eloChanges);
                });

                // ASYNC: Limpiar recursos no críticos (REUTILIZAR método existente)
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    cleanupMatchResources(activeMatch, plugin, logger);
                }, 60L); // Esperar 3 segundos antes de limpiar canales

                // ASYNC: Finalizar estado completo (REUTILIZAR método existente)
                finalizeMatchState(activeMatch, logger);

            } catch (Exception e) {
                logger.systemError("MatchFinisher", "Error en operaciones secundarias " + matchId, e.getMessage());
            }
        });
    }
    /**
     * Actualiza estadísticas de todos los jugadores y calcula cambios de ELO y MMR
     */
    private static Map<String, Integer> updatePlayerStatistics(ActiveMatch activeMatch,
                                                               Team winnerTeam, DiscordLogger logger,
                                                               RankedMinecraft plugin) {
        Map<String, Integer> eloChanges = new HashMap<>();
        Map<String, ProgressiveEloCalculator.EloChange> detailedChanges = new HashMap<>(); // Nuevo
        // Lista para updates normales y placement
        List<DatabaseManager.PlayerStatUpdate> batchUpdates = new ArrayList<>();
        List<DatabaseManager.PlayerStatUpdateWithPlacement> placementBatchUpdates = new ArrayList<>();

        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        // Obtener tipo de partida
        ProgressiveEloCalculator.MatchType matchType = activeMatch.getMatchTypeEnum();

        // Calcular MMR promedio de cada equipo para balanceo
        double blueTeamAvgMMR = MMRCalculator.calculateAverageMMR(teams.get(Team.BLUE));
        double redTeamAvgMMR = MMRCalculator.calculateAverageMMR(teams.get(Team.RED));

        logger.info("Match Type & Team Analysis",
                String.format("Tipo: %s | Azul: %.1f MMR, Rojo: %.1f MMR | Modificadores: Win x%.2f, Loss x%.2f",
                        matchType.getDisplayName(), blueTeamAvgMMR, redTeamAvgMMR,
                        matchType.getWinMultiplier(), matchType.getLossMultiplier()));

        // ESTABLECER RESULTADOS DE LA PARTIDA PARA LOS LOGS
        MatchLogsIntegration.setMatchResults(activeMatch.getMatchId(), teams, winnerTeam);

        // Procesar cada equipo
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean won = team == winnerTeam;
            double teamAvgMMR = team == Team.BLUE ? blueTeamAvgMMR : redTeamAvgMMR;
            double opponentAvgMMR = team == Team.BLUE ? redTeamAvgMMR : blueTeamAvgMMR;

            for (PlayerData player : players) {
                try {
                    // Guardar ELO/MMR antiguos para logs
                    int oldElo = player.getElo();
                    double oldMmr = player.getMmr();

                    // ARREGLO: Verificar si está en placement ANTES de calcular ELO
                    ProgressiveEloCalculator.EloChange eloChange;
                    MMRCalculator.MMRChange mmrChange;

                    if (player.isInPlacement()) {
                        // Jugador en placement: NO cambiar ELO, usar rango especial PLACEMENT
                        eloChange = new ProgressiveEloCalculator.EloChange(
                            0,                                                              // eloChange = 0
                            oldElo,                                                         // newElo = mismo ELO
                            Rank.PLACEMENT,                                                 // oldRank = En Evaluación
                            Rank.PLACEMENT,                                                 // newRank = En Evaluación
                            false,                                                          // promoted = false
                            false                                                           // demoted = false
                        );

                        // Calcular MMR especializado para placement
                        mmrChange = MMRCalculator.calculateMMRChange(player, won, teamAvgMMR, opponentAvgMMR);
                    } else {
                        // Jugador establecido: calcular ELO y MMR normales
                        eloChange = ProgressiveEloCalculator.calculateEloChange(
                                player.getElo(), opponentAvgMMR, won, matchType);

                        mmrChange = MMRCalculator.calculateMMRChange(player, won, teamAvgMMR, opponentAvgMMR);
                    }

                    eloChanges.put(player.getMinecraftUuid(), eloChange.getEloChange());
                    detailedChanges.put(player.getMinecraftUuid(), eloChange);

                    // ACTUALIZAR CAMBIOS DE RATING EN LOS LOGS
                    MatchLogsIntegration.updatePlayerRating(
                        activeMatch.getMatchId(),
                        player,
                        oldElo,
                        oldMmr,
                        eloChange.getNewElo(),
                        mmrChange.getNewMMR()
                    );

                    // 3. Actualizar en base de datos
                    int matchKills = player.getCurrentMatchKills();
                    int matchDeaths = player.getCurrentMatchDeaths();

                    if (player.isInPlacement()) {
                        // Jugador en placement: usar update especializado
                        // CORREGIR: Incrementar placement matches correctamente
                        int newPlacementCount = player.getPlacementMatchesPlayed() + 1;

                        placementBatchUpdates.add(new DatabaseManager.PlayerStatUpdateWithPlacement(
                                player.getMinecraftUuid(),
                                won,
                                eloChange.getNewElo(), // Mantiene el ELO igual
                                mmrChange.getNewMMR(),
                                matchKills,
                                matchDeaths,
                                newPlacementCount < PlayerData.getPlacementMatchesRequired(), // isInPlacement actualizado
                                newPlacementCount // Contador correcto
                        ));

                        // CRÍTICO: Actualizar objeto en memoria inmediatamente
                        player.incrementPlacementMatches();
                    } else {
                        // Jugador normal: usar update tradicional
                        batchUpdates.add(new DatabaseManager.PlayerStatUpdate(
                                player.getMinecraftUuid(),
                                won,
                                eloChange.getNewElo(),
                                mmrChange.getNewMMR(),
                                matchKills,
                                matchDeaths
                        ));
                    }

                    // Actualizar PlayerData en memoria
                    player.setElo(eloChange.getNewElo());
                    player.setMmr(mmrChange.getNewMMR());

                    // Incrementar partidas jugadas para placement
                    if (player.isInPlacement()) {
                        int newPlacementMatches = player.getPlacementMatchesPlayed() + 1;
                        player.setPlacementMatchesPlayed(newPlacementMatches);

                        // Verificar si completó placement matches
                        if (newPlacementMatches >= PlayerData.getPlacementMatchesRequired()) {
                            player.setInPlacement(false);
                            logger.info("Placement Completed",
                                player.getMinecraftUuid() + " completó placement matches");
                        }
                    }


                    // OPTIMIZACIÓN: Usar BatchProcessor para updates asíncronos
                    if (player.isInPlacement()) {
                        // Update con datos de placement
                        DatabaseManager.PlayerStatUpdateWithPlacement placementUpdate =
                            new DatabaseManager.PlayerStatUpdateWithPlacement(
                                player.getMinecraftUuid(),
                                won,
                                eloChange.getNewElo(),
                                mmrChange.getNewMMR(),
                                matchKills,
                                matchDeaths,
                                player.isInPlacement(),
                                player.getPlacementMatchesPlayed()
                            );
                        placementBatchUpdates.add(placementUpdate);
                    } else {
                        // Update normal
                        DatabaseManager.PlayerStatUpdate normalUpdate =
                            new DatabaseManager.PlayerStatUpdate(
                                player.getMinecraftUuid(),
                                won,
                                eloChange.getNewElo(),
                                mmrChange.getNewMMR(),
                                matchKills,
                                matchDeaths
                            );
                        batchUpdates.add(normalUpdate);
                    }

                    // OPTIMIZACIÓN: Actualizar estado de partida usando BatchProcessor
                    DatabaseManager.updatePlayerMatchStatusAsync(
                        player.getMinecraftUuid(), false, null);

                } catch (Exception e) {
                    logger.systemError("MatchFinisher",
                        "Error actualizando jugador " + player.getMinecraftUuid(), e.getMessage());
                }
            }
        }

        // OPTIMIZACIÓN: Ejecutar batch updates asíncrono
        if (!batchUpdates.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                DatabaseManager.updatePlayerStats(batchUpdates);
            });
        }

        if (!placementBatchUpdates.isEmpty()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                DatabaseManager.updatePlayerStatsWithPlacement(placementBatchUpdates);
            });
        }

        // GUARDAR DATOS COMPLETOS DE LA PARTIDA EN LA BASE DE DATOS MATCH_LOGS
        saveMatchToDatabase(activeMatch, winnerTeam, logger);

        // ACTUALIZAR ROLES DE DISCORD DESPUÉS DE BATCH UPDATE
        updateDiscordRoles(activeMatch, detailedChanges, logger);
        return eloChanges;
    }

    /**
     * Envía los resultados completos a Discord
     */
    private static void sendDiscordResults(ActiveMatch activeMatch, Team winnerTeam,
                                           Map<String, Integer> eloChanges, long durationSeconds,
                                           DiscordLogger logger) {

        logger.matchComplete(
                activeMatch.getMatchId(),
                activeMatch.getMatchType(),
                activeMatch.getSelectedMap(),
                winnerTeam,
                activeMatch.getTeams(),
                eloChanges,
                durationSeconds
        );

        // También enviar log separado de cambios de ELO
        // Log específico con modificadores aplicados
        ProgressiveEloCalculator.MatchType matchType = activeMatch.getMatchTypeEnum();
        logger.info("ELO Modifiers Applied",
                String.format("Modificadores %s aplicados - Victoria: +%.0f%%, Derrota: %.0f%%",
                        matchType.getDisplayName(),
                        (matchType.getWinMultiplier() - 1.0) * 100,
                        (matchType.getLossMultiplier() - 1.0) * 100));
    }
    /**
     * Notifica a todos los jugadores en Minecraft sobre el resultado
     */
    private static void notifyPlayersInMinecraft(ActiveMatch activeMatch, Team winnerTeam,
                                                 Map<String, Integer> eloChanges) {

        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        // Mensaje general
        String generalMessage = "§6§l=== PARTIDA FINALIZADA ===";
        String winnerMessage = "§a🏆 Ganador: " + winnerTeam.getFormattedName();
        String mapMessage = "§e📍 Mapa: §f" + activeMatch.getSelectedMap();

        // Enviar a todos los jugadores
        for (List<PlayerData> teamPlayers : teams.values()) {
            for (PlayerData playerData : teamPlayers) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(generalMessage);
                    mcPlayer.sendMessage(winnerMessage);
                    mcPlayer.sendMessage(mapMessage);

                    // NUEVO: Mensaje personalizado dependiendo del estado del jugador
                    if (playerData.isInPlacement()) {
                        // Mensaje para jugadores en placement
                        int matchesPlayed = playerData.getPlacementMatchesPlayed();
                        int totalRequired = PlayerData.getPlacementMatchesRequired();
                        int remaining = totalRequired - matchesPlayed;

                        mcPlayer.sendMessage("§b🔍 PERÍODO DE EVALUACIÓN");
                        mcPlayer.sendMessage("§e📊 Progreso: §f" + matchesPlayed + "/" + totalRequired + " partidas");

                        if (remaining > 0) {
                            mcPlayer.sendMessage("§a✨ Te faltan §e" + remaining + "§a partidas para obtener tu rango inicial");
                        } else {
                            mcPlayer.sendMessage("§a🎉 ¡Completaste todas las partidas de evaluación! Tu rango se asignará pronto.");
                        }

                        mcPlayer.sendMessage("§7💡 Durante la evaluación no pierdes ni ganas ELO");
                    } else {
                        // Mensaje normal con cambio de ELO
                        Integer eloChange = eloChanges.get(playerData.getMinecraftUuid());
                        if (eloChange != null) {
                            String eloMessage = eloChange > 0 ?
                                    "§a📈 +" + eloChange + " ELO!" :
                                    "§c📉 " + eloChange + " ELO";
                            mcPlayer.sendMessage(eloMessage);
                            mcPlayer.sendMessage("§7💰 ELO actual: §f" + (playerData.getElo() + eloChange));
                        }
                    }

                    mcPlayer.sendMessage("§6§l========================");
                }
            }
        }
    }
    /**
     * Mueve todos los jugadores al canal de espera en Discord
     */
    private static void movePlayersToWaitingRoom(ActiveMatch activeMatch,
                                                 RankedMinecraft plugin, DiscordLogger logger) {

        Guild guild = plugin.getDiscordBot().getJda().getGuilds().get(0);
        VoiceChannel waitingRoom = guild.getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);

        if (waitingRoom == null) {
            logger.error("Canal de Espera No Encontrado",
                    "No se pudo encontrar el canal de espera para mover jugadores");
            return;
        }

        // Mover todos los jugadores
        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                try {
                    Member member = guild.getMemberById(playerData.getDiscordId());
                    if (member != null && member.getVoiceState().inAudioChannel()) {
                        guild.moveVoiceMember(member, waitingRoom).queue(
                                success -> logger.info("Jugador Movido a Espera",
                                        member.getEffectiveName() + " movido al canal de espera"),
                                error -> logger.warning("Error Moviendo Jugador",
                                        "No se pudo mover " + member.getEffectiveName() + ": " + error.getMessage())
                        );
                    }
                } catch (Exception e) {
                    logger.systemError("MatchFinisher",
                            "Error moviendo jugador a canal de espera", e.getMessage());
                }
            }
        }
    }
    /**
     * Limpia todos los recursos de la partida (canales, memoria, etc.)
     */
    private static void cleanupMatchResources(ActiveMatch activeMatch,
                                              RankedMinecraft plugin, DiscordLogger logger) {

        activeMatch.cleanup();
        // Programar eliminación de canales temporales después de 30 segundos
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            try {
                if (activeMatch.getBlueTeamChannel() != null) {
                    activeMatch.getBlueTeamChannel().delete().queue(
                            success -> logger.info("Canal Eliminado", "Canal azul eliminado exitosamente"),
                            error -> logger.warning("Error Eliminando Canal",
                                    "No se pudo eliminar canal azul: " + error.getMessage())
                    );
                }

                if (activeMatch.getRedTeamChannel() != null) {
                    activeMatch.getRedTeamChannel().delete().queue(
                            success -> logger.info("Canal Eliminado", "Canal rojo eliminado exitosamente"),
                            error -> logger.warning("Error Eliminando Canal",
                                    "No se pudo eliminar canal rojo: " + error.getMessage())
                    );
                }
            } catch (Exception e) {
                logger.systemError("MatchFinisher",
                        "Error programando eliminación de canales", e.getMessage());
            }
        }, 1200L); // 60 segundos

        // Limpiar de memoria inmediatamente
        activeMatch.cleanup();
    }
    /**
     * Finaliza el estado global de la partida
     */
    private static void finalizeMatchState(ActiveMatch activeMatch, DiscordLogger logger) {
        // GUARDAR DATOS COMPLETOS DE LA PARTIDA EN LA BASE DE DATOS
        Team winnerTeam = determineWinnerFromCurrentState(activeMatch);
        if (winnerTeam != null) {
            // Finalizar y guardar partida en base de datos match_logs
            MatchLogsIntegration.finalizeActiveMatch(activeMatch, winnerTeam)
                .thenAccept(saved -> {
                    if (saved) {
                        logger.success("Match Data Saved",
                            "Datos completos de partida " + activeMatch.getMatchId() + " guardados en match_logs");
                    } else {
                        logger.error("Match Data Save Failed",
                            "Error guardando datos de partida " + activeMatch.getMatchId());
                    }
                });

            // Log del evento de finalización
            long durationSeconds = java.time.Duration.between(activeMatch.getStartTime(),
                java.time.LocalDateTime.now()).getSeconds();
            MatchLogsIntegration.logMatchEnd(activeMatch.getMatchId(), winnerTeam, durationSeconds);
        }

        ForfeitManager.cleanupMatchVotes(activeMatch.getMatchId());
        // Finalizar estado global
        MatchState.endMatch();

        logger.success("Estado Finalizado",
                "Estado global de partida finalizado - Sistema listo para nuevas colas");
    }

    /**
     * Determina el equipo ganador del estado actual de la partida
     */
    private static Team determineWinnerFromCurrentState(ActiveMatch activeMatch) {
        // Este método debería ser implementado según cómo determines el ganador
        // Por ahora retornamos null, pero debería obtener el ganador actual
        // Podrías guardarlo en ActiveMatch cuando se determina el ganador
        return activeMatch.getWinnerTeam(); // Necesitas agregar este campo a ActiveMatch
    }


    /**
     * Limpieza de emergencia en caso de errores críticos
     */
    private static void emergencyCleanup(ActiveMatch activeMatch,
                                         RankedMinecraft plugin, DiscordLogger logger) {

        logger.error("Limpieza de Emergencia",
                "Ejecutando limpieza de emergencia para partida " + activeMatch.getMatchId());

        // Limpiar estado de jugadores
        List<PlayerData> allPlayers = new ArrayList<>();
        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                playerData.setInMatch(false);
                playerData.setCurrentMatchId(null);
                allPlayers.add(playerData);
            }
        }

        // TAMBIÉN LIMPIAR DE LA COLA EN EMERGENCIA
        try {
            QueueManager.removePlayersFromQueueAfterMatch(allPlayers);
            logger.info("Emergency Queue Cleanup",
                    "Limpiados " + allPlayers.size() + " jugadores de cola en limpieza de emergencia");
        } catch (Exception e) {
            logger.systemError("MatchFinisher",
                    "Error en limpieza de emergencia de cola", e.getMessage());
        }

        // Finalizar estado global
        MatchState.endMatch();

        // Limpiar memoria
        activeMatch.cleanup();

        logger.warning("Emergencia Completada",
                "Limpieza de emergencia completada - puede requerir intervención manual");
    }
    /**
     * Obtiene el nombre de display de un jugador
     */
    private static String getPlayerName(PlayerData playerData) {
        try {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }
        } catch (Exception e) {
            // Fallback
        }
        return "UUID:" + playerData.getMinecraftUuid().substring(0, 8);
    }

    private static void updateDiscordRoles(ActiveMatch activeMatch,
                                           Map<String, ProgressiveEloCalculator.EloChange> eloChanges,
                                           DiscordLogger logger) {

        Guild guild;
        try {
            guild = RankedMinecraft.getInstance().getDiscordBot().getJda().getGuilds().get(0);
        } catch (Exception e) {
            logger.warning("Discord Guild Not Found", "No se pudo obtener el guild para actualizar roles");
            return;
        }

        if (guild == null) {
            logger.warning("Discord Guild Not Found", "Guild es null, no se pueden actualizar roles");
            return;
        }

        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                try {
                    ProgressiveEloCalculator.EloChange eloChange = eloChanges.get(playerData.getMinecraftUuid());
                    if (eloChange == null) continue;

                    // Solo actualizar si hubo cambio de rango
                    if (!eloChange.isPromoted() && !eloChange.isDemoted()) continue;

                    Member member = guild.getMemberById(playerData.getDiscordId());
                    if (member == null) {
                        logger.warning("Discord Member Not Found",
                                "No se encontró el miembro de Discord: " + playerData.getDiscordId());
                        continue;
                    }

                    // Remover rol de rango anterior
                    String oldRoleId = VoiceChannelConfig.RANK_ROLE_IDS.get(eloChange.getOldRank());
                    if (oldRoleId != null) {
                        Role oldRole = guild.getRoleById(oldRoleId);
                        if (oldRole != null && member.getRoles().contains(oldRole)) {
                            guild.removeRoleFromMember(member, oldRole).queue(
                                    success -> logger.info("Role Removed",
                                            String.format("Rol %s removido de %s",
                                                    eloChange.getOldRank().getDisplayName(), member.getEffectiveName())),
                                    error -> logger.warning("Error Removing Role",
                                            "Error removiendo rol anterior: " + error.getMessage())
                            );
                        }
                    }

                    // Añadir nuevo rol de rango
                    String newRoleId = VoiceChannelConfig.RANK_ROLE_IDS.get(eloChange.getNewRank());
                    if (newRoleId != null) {
                        Role newRole = guild.getRoleById(newRoleId);
                        if (newRole != null) {
                            guild.addRoleToMember(member, newRole).queue(
                                    success -> {
                                        String rankChangeType = eloChange.isPromoted() ? "PROMOCIÓN" : "DEGRADACIÓN";
                                        logger.success("Role Updated",
                                                String.format("%s - %s ahora tiene el rol %s",
                                                        rankChangeType, member.getEffectiveName(),
                                                        eloChange.getNewRank().getDisplayName()));
                                    },
                                    error -> logger.warning("Error Adding Role",
                                            "Error añadiendo nuevo rol: " + error.getMessage())
                            );
                        } else {
                            logger.warning("Role Not Found",
                                    "No se encontró el rol para el rango: " + eloChange.getNewRank().getDisplayName());
                        }
                    }

                } catch (Exception e) {
                    logger.systemError("MatchFinisher",
                            "Error actualizando roles de Discord para jugador: " + playerData.getDiscordId(),
                            e.getMessage());
                }
            }
        }
    }
    /**
     * NUEVO: Maneja partidas que terminan en empate (sin cambios de ELO)
     */
    public static void handleDrawMatch(ActiveMatch activeMatch, RankedMinecraft plugin, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();

        logger.matchEvent(matchId, "Partida Empatada",
                "Procesando empate sin cambios de ELO", activeMatch.getAllPlayers().size());

        try {
            // 1. CRÍTICO: Limpiar estados antes de cualquier operación asíncrona
            for (PlayerData player : activeMatch.getAllPlayers()) {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
            }

            // 2. Finalizar estado de match
            activeMatch.setStatus(ActiveMatch.MatchStatus.FINISHED);

            // 3. Limpiar estado global de match
            MatchState.endMatch();

            logger.success("Match State Cleared", "Estados de partida y jugadores limpiados");

            // 4. Operaciones asíncronas
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Duration matchDuration = Duration.between(activeMatch.getStartTime(), LocalDateTime.now());

                    // Notificar en Discord sobre empate
                    sendDiscordDrawNotification(activeMatch, matchDuration.getSeconds(), logger);

                    // Operaciones síncronas en el hilo principal
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        try {
                            // Mover jugadores de vuelta al lobby
                            movePlayersToWaitingRoom(activeMatch, plugin, logger);

                            // Limpiar recursos del match (canales, etc.)
                            cleanupMatchResources(activeMatch, plugin, logger);

                            // Limpieza final
                            activeMatch.cleanup();

                            logger.success("Draw Match Completed",
                                    String.format("Empate procesado completamente para match %s", matchId));

                        } catch (Exception e) {
                            logger.systemError("MatchFinisher",
                                    "Error en limpieza final de empate", e.getMessage());
                        }
                    }, 20L);

                } catch (Exception e) {
                    logger.systemError("MatchFinisher", "Error procesando empate " + matchId, e.getMessage());
                }
            });

        } catch (Exception e) {
            logger.systemError("MatchFinisher", "Error crítico en handleDrawMatch", e.getMessage());

            // Limpieza de emergencia
            Bukkit.getScheduler().runTask(plugin, () -> {
                emergencyCleanup(activeMatch, plugin, logger);
            });
        }
    }



    /**
     * Envía notificación de empate a Discord
     */
    private static void sendDiscordDrawNotification(ActiveMatch activeMatch, long durationSeconds,
                                                    DiscordLogger logger) {

        // Crear un mapa vacío para indicar que no hay cambios de ELO
        Map<String, Integer> noEloChanges = new HashMap<>();

        logger.matchDraw(
                activeMatch.getMatchId(),
                activeMatch.getMatchType(),
                activeMatch.getSelectedMap(),
                activeMatch.getTeams(),
                durationSeconds
        );
    }


    /**
     * NUEVO: Guarda los datos completos de la partida en la base de datos match_logs
     */
    private static void saveMatchToDatabase(ActiveMatch activeMatch, Team winnerTeam, DiscordLogger logger) {
        try {
            // Finalizar y guardar partida en base de datos match_logs
            MatchLogsIntegration.finalizeActiveMatch(activeMatch, winnerTeam)
                .thenAccept(saved -> {
                    if (saved) {
                        logger.success("Match Data Saved",
                            "Datos completos de partida " + activeMatch.getMatchId() + " guardados en match_logs");
                    } else {
                        logger.error("Match Data Save Failed",
                            "Error guardando datos de partida " + activeMatch.getMatchId());
                    }
                });

            // Log del evento de finalización
            long durationSeconds = java.time.Duration.between(activeMatch.getStartTime(),
                java.time.LocalDateTime.now()).getSeconds();
            MatchLogsIntegration.logMatchEnd(activeMatch.getMatchId(), winnerTeam, durationSeconds);

        } catch (Exception e) {
            logger.systemError("MatchFinisher",
                "Error guardando datos de partida " + activeMatch.getMatchId(), e.getMessage());
        }
    }

    /**
     * Limpia a los jugadores de la cola después de que termine la partida
     */
    private static void cleanupPlayersFromQueue(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // Obtener todos los jugadores de la partida
            List<PlayerData> allPlayers = new ArrayList<>();
            for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
                allPlayers.addAll(teamPlayers);
            }

            // NUEVO: Solo limpiar tracking, no remover de colas (ya fueron removidos al iniciar partida)
            QueueManager.cleanupPlayerTrackingAfterMatch(allPlayers);

            logger.info("Queue Tracking Cleanup",
                    "Limpiado tracking de " + allPlayers.size() + " jugadores tras finalizar partida " +
                    activeMatch.getMatchId() + " (jugadores en espera no afectados)");

        } catch (Exception e) {
            logger.systemError("MatchFinisher",
                    "Error limpiando tracking de cola tras partida", e.getMessage());
        }
    }
}
