package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
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
import org.fabricioyv.cache.PlayerDataCache;

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
        // Solo operaciones en memoria - BD se actualiza asíncronamente
        // ========================================

        try {
            // 1. INMEDIATO: Marcar jugadores como NO en partida EN MEMORIA (instantáneo)
            List<PlayerData> allPlayers = activeMatch.getAllPlayers();
            for (PlayerData player : allPlayers) {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
            }

            // 2. ACTUALIZAR BD DE FORMA ASÍNCRONA (no bloquea el servidor)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (PlayerData player : allPlayers) {
                    try {
                        DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), false, null);
                    } catch (Exception e) {
                        logger.warning("Async DB Update Failed",
                                "Error en actualización asíncrona para " + player.getMinecraftUuid().substring(0, 8) + ": " + e.getMessage());
                    }
                }
                logger.debug("DB Updates Complete", "Actualizaciones de BD completadas en segundo plano");
            });

            // 3. INMEDIATO: Invalidar cache de Discord (operación rápida en memoria)
            java.util.List<String> discordIds = allPlayers.stream()
                    .map(PlayerData::getDiscordId)
                    .collect(java.util.stream.Collectors.toList());

            org.fabricioyv.discord.VoiceChannelListener.invalidatePlayersCache(discordIds);
            logger.info("Cache Discord Invalidado",
                    "Cache invalidado para " + discordIds.size() + " jugadores - BD actualizándose en segundo plano");

            // 4. INMEDIATO: Limpiar jugadores de la cola (operación rápida en memoria)
            cleanupPlayersFromQueue(activeMatch, logger);

            // 5. INMEDIATO: Notificar resultado básico en Minecraft (mensaje simple, rápido)
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

            // 6) Mover jugadores a Sala de espera (una sola vez)
            try {
                movePlayersToWaitingRoom(activeMatch, plugin, logger);
            } catch (Exception e) {
                logger.warning("Discord Move Failed",
                        "Error moviendo jugadores a sala de espera: " + e.getMessage());
            }

            // 7) Marcar match terminado (una sola vez)
            activeMatch.setStatus(ActiveMatch.MatchStatus.FINISHED);
            MatchState.endMatch();

            // 8) Programar limpieza de canales cuando ya estén vacíos (UNA sola vez)
            cleanupAfterDiscordMove(activeMatch, plugin, logger);


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
                Duration matchDuration = Duration.between(activeMatch.getStartTime(), LocalDateTime.now());
                long durationSeconds = matchDuration.getSeconds();

                Map<String, Integer> eloChanges = updatePlayerStatistics(activeMatch, winnerTeam, logger, plugin);

                sendDiscordResults(activeMatch, winnerTeam, eloChanges, durationSeconds, logger);

                // ✅ NUEVO: snapshot en async (1 query)
                DatabaseManager.MatchProtectionSnapshot protectionSnap =
                        DatabaseManager.getMatchProtectionSnapshot(activeMatch.getMatchId());

                // ✅ Mensajes en main thread SIN DB
                Bukkit.getScheduler().runTask(plugin, () -> {
                    notifyPlayersInMinecraft(activeMatch, winnerTeam, eloChanges, protectionSnap);
                });

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    //cleanupMatchResources(activeMatch, plugin, logger);
                }, 60L);

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

        // **CRÍTICO FIX**: ESTABLECER RESULTADOS ANTES DE FINALIZAR ESTADÍSTICAS
        // Esto asegura que el campo "won" se establezca correctamente
        MatchLogsIntegration.setMatchResults(activeMatch.getMatchId(), teams, winnerTeam);

        // **ESPERAR UN MOMENTO**: Para asegurar que todos los eventos asíncronos terminen
        try {
            Thread.sleep(100); // 100ms para que se procesen eventos pendientes de daño/arrows
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // **CRÍTICO FIX**: Finalizar estadísticas DESPUÉS de establecer todos los datos
        // Esto previene race conditions y asegura que todos los campos estén completos
        Map<UUID, MatchLogsManager.PlayerMatchStats> finalizedStats = null;
        try {
            finalizedStats = MatchStatsListener.finalizeMatchStats(activeMatch.getMatchId());
            if (finalizedStats != null && !finalizedStats.isEmpty()) {
                logger.success("Match Stats Finalized",
                        String.format("✅ Estadísticas finalizadas para %d jugadores en match %s",
                                finalizedStats.size(), activeMatch.getMatchId()));
            } else {
                logger.warning("Match Stats Empty",
                        String.format("⚠️ No se encontraron estadísticas para match %s - usando valores por defecto",
                                activeMatch.getMatchId()));
            }
        } catch (Exception e) {
            logger.error("Match Stats Finalization Failed",
                    String.format("❌ Error finalizando estadísticas: %s - continuando con valores por defecto",
                            e.getMessage()));
        }

        // **OPTIMIZACIÓN**: Crear mapa de referencia para lookup rápido
        final Map<UUID, MatchLogsManager.PlayerMatchStats> statsCache = finalizedStats;

        // Procesar cada equipo
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean won = team == winnerTeam;
            double teamAvgMMR = team == Team.BLUE ? blueTeamAvgMMR : redTeamAvgMMR;
            double opponentAvgMMR = team == Team.BLUE ? redTeamAvgMMR : blueTeamAvgMMR;

            for (PlayerData player : players) {
                try {
                    // **CRÍTICO**: Verificar si el jugador ya fue procesado por abandono
                    boolean wasAbandonmentProcessed = DatabaseManager.isPlayerAbandonmentProcessed(
                            player.getMinecraftUuid(),
                            activeMatch.getMatchId()
                    );

                    // **PROTECCIÓN**: Si el jugador abandonó, ya fue penalizado - NO procesar más cambios
                    if (wasAbandonmentProcessed) {
                        logger.info("Jugador Ya Penalizado Por Abandono",
                                String.format("Jugador %s (%s) ya fue penalizado por abandono - SKIP procesamiento de ELO",
                                        player.getMinecraftName(),
                                        player.getMinecraftUuid().substring(0, 8)));

                        // Registrar cambio de ELO = 0 para el mensaje final
                        eloChanges.put(player.getMinecraftUuid(), 0);

                        // Marcar explícitamente que es abandono para mensaje personalizado
                        ProgressiveEloCalculator.EloChange abandonmentChange =
                                new ProgressiveEloCalculator.EloChange(
                                        0,  // Sin cambio de ELO
                                        player.getElo(),  // ELO actual (sin cambios)
                                        Rank.getRankByElo(player.getElo()),  // Rango actual
                                        Rank.getRankByElo(player.getElo()),  // Rango actual (sin cambios)
                                        false,  // No promoción
                                        false   // No degradación
                                );
                        detailedChanges.put(player.getMinecraftUuid(), abandonmentChange);

                        // Refrescar cache
                        PlayerDataCache.cachePlayer(player);
                        // Continuar con siguiente jugador SIN procesamiento
                        continue;
                    }

                    // **VERIFICAR PROTECCIÓN POR ABANDONO DE COMPAÑERO**
                    boolean isProtectedFromLoss = DatabaseManager.isPlayerProtectedFromLoss(
                            player.getMinecraftUuid(),
                            activeMatch.getMatchId()
                    );

                    // Si está protegido Y perdió, no aplicar cambios negativos
                    if (isProtectedFromLoss && !won) {
                        logger.info("Jugador Protegido De Pérdida",
                                String.format("Jugador %s (%s) protegido de pérdida por abandono de compañero",
                                        player.getMinecraftName(),
                                        player.getMinecraftUuid().substring(0, 8)));

                        // No cambiar ELO
                        eloChanges.put(player.getMinecraftUuid(), 0);

                        // Crear cambio neutral
                        ProgressiveEloCalculator.EloChange protectedChange =
                                new ProgressiveEloCalculator.EloChange(
                                        0,  // Sin cambio
                                        player.getElo(),
                                        Rank.getRankByElo(player.getElo()),
                                        Rank.getRankByElo(player.getElo()),
                                        false,
                                        false
                                );
                        detailedChanges.put(player.getMinecraftUuid(), protectedChange);

                        // Actualizar solo contador de placement si aplica, sin penalización
                        if (player.isInPlacement()) {
                            int newPlacementCount = player.getPlacementMatchesPlayed() + 1;
                            boolean stillInPlacement = newPlacementCount < PlayerData.getPlacementMatchesRequired();

                            player.setPlacementMatchesPlayed(newPlacementCount);
                            player.setInPlacement(stillInPlacement);

                            DatabaseManager.updatePlayerPlacementData(
                                    player.getMinecraftUuid(),
                                    stillInPlacement,
                                    newPlacementCount
                            );
                        }

                        // Refrescar cache
                        PlayerDataCache.cachePlayer(player);
                        // Continuar con siguiente jugador
                        continue;
                    }

                    // **PROCESAMIENTO NORMAL** - Jugador no abandonó ni está protegido
                    // **CRÍTICO**: Establecer el match ID actual ANTES de los cálculos
                    player.setCurrentMatchId(activeMatch.getMatchId());

                    // **MEJORADO**: Obtener estadísticas finales del cache ya finalizado
                    MatchLogsManager.PlayerMatchStats finalStats = null;
                    if (statsCache != null) {
                        try {
                            UUID playerUuid = UUID.fromString(player.getMinecraftUuid());
                            finalStats = statsCache.get(playerUuid);
                        } catch (Exception e) {
                            logger.warning("Stats Cache Lookup Failed",
                                    "Error obteniendo stats del cache para " + player.getMinecraftName());
                        }
                    }

                    // **CRÍTICO**: Actualizar PlayerData con las estadísticas finales completas
                    if (finalStats != null) {
                        // Sincronizar estadísticas finales
                        player.setCurrentMatchKills(finalStats.getKills());
                        player.setCurrentMatchDeaths(finalStats.getDeaths());
                        player.setCurrentMatchDamage(finalStats.getDamageDealt());

                        // **MEJORADO**: Log detallado para debugging y monitoreo
                        logger.info("Stats Sync Complete",
                                String.format("✅ %s | Daño: %.1f | K/D: %d/%d | Team: %s",
                                        player.getMinecraftName(),
                                        finalStats.getDamageDealt(),
                                        finalStats.getKills(),
                                        finalStats.getDeaths(),
                                        finalStats.getTeam()));
                    } else {
                        // **MEJORADO**: Fallback con estadísticas actuales y logging detallado
                        logger.warning("Stats Sync Fallback",
                                String.format("⚠️ %s | Usando stats locales: K/D: %d/%d | Daño: %.1f",
                                        player.getMinecraftName(),
                                        player.getCurrentMatchKills(),
                                        player.getCurrentMatchDeaths(),
                                        player.getCurrentMatchDamage()));

                        // Validar si las estadísticas locales están vacías
                        if (player.getCurrentMatchKills() == 0 &&
                                player.getCurrentMatchDeaths() == 0 &&
                                player.getCurrentMatchDamage() == 0.0) {
                            logger.error("Stats Sync Critical Error",
                                    String.format("❌ %s | NO HAY ESTADÍSTICAS - Posible error en tracking",
                                            player.getMinecraftName()));
                        }
                    }

                    // Guardar ELO/MMR antiguos para logs
                    int oldElo = player.getElo();
                    double oldMmr = player.getMmr();

                    // ARREGLO: Verificar si está en placement ANTES de calcular ELO
                    ProgressiveEloCalculator.EloChange eloChange;
                    MMRCalculator.MMRChange mmrChange;

                    if (player.isInPlacement()) {
                        // Jugador en placement: NO cambiar ELO, usar rango especial PLACEMENT
                        eloChange = new ProgressiveEloCalculator.EloChange(
                                0,              // eloChange = 0
                                oldElo,         // newElo = mismo ELO
                                Rank.PLACEMENT, // oldRank = En Evaluación
                                Rank.PLACEMENT, // newRank = En Evaluación
                                false,
                                false
                        );

                        // Calcular MMR especializado para placement
                        mmrChange = MMRCalculator.calculateMMRChange(
                                player, won, teamAvgMMR, opponentAvgMMR);
                    } else {
                        // Jugador establecido: calcular ELO y MMR normales
                        eloChange = ProgressiveEloCalculator.calculateEloChange(
                                player.getElo(), opponentAvgMMR, won, matchType);

                        mmrChange = MMRCalculator.calculateMMRChange(
                                player, won, teamAvgMMR, opponentAvgMMR);
                    }

                    // DEBUG: log del cálculo de ELO para este jugador
                    logger.info("ELO DEBUG",
                            String.format(
                                    "%s | Match=%s | Won=%s | Placement=%s | Rank=%s | OldElo=%d | Change=%+d | NewElo=%d",
                                    player.getMinecraftName(),
                                    activeMatch.getMatchId().substring(0, 8),
                                    won,
                                    player.isInPlacement(),
                                    Rank.getRankByElo(oldElo).name(),
                                    oldElo,
                                    eloChange.getEloChange(),
                                    eloChange.getNewElo()
                            )
                    );

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

                    // LÓGICA CONSOLIDADA: Manejar placement matches una sola vez
                    boolean finalizedPlacement = false;

                    if (player.isInPlacement()) {
                        // Calcular nuevo contador de placement
                        int newPlacementCount = player.getPlacementMatchesPlayed() + 1;
                        boolean stillInPlacement =
                                newPlacementCount < PlayerData.getPlacementMatchesRequired();

                        // **CRÍTICO**: Si completa las 8 partidas, asignar ELO y rango final
                        if (!stillInPlacement) {
                            assignFinalPlacementRank(
                                    player,
                                    activeMatch.getMatchId(),
                                    logger,
                                    won,
                                    RankedMinecraft.getInstance().getDiscordBot()
                            );
                            finalizedPlacement = true;

                            // Asegurar que la tabla ranked_players refleje que ya no está en placement
                            try {
                                DatabaseManager.updatePlayerPlacementData(
                                        player.getMinecraftUuid(),
                                        false,
                                        PlayerData.getPlacementMatchesRequired()
                                );
                            } catch (Exception e) {
                                logger.warning("Placement DB Flag Failed",
                                        "No se pudo actualizar placement flags en DB: " + e.getMessage());
                            }

                            // Asignar rol en Discord por si no se asignó correctamente en el método
                            try {
                                Rank assignedRank = Rank.getRankByElo(player.getElo());
                                String discordId = player.getDiscordId();
                                if (discordId != null && !discordId.isEmpty()) {
                                    RankedMinecraft.getInstance()
                                            .getDiscordBot()
                                            .assignRankRole(discordId, assignedRank);
                                }
                            } catch (Exception e) {
                                logger.warning("Discord Role Assignment Failed",
                                        "No se pudo asignar el rol de Discord tras placement: " + e.getMessage());
                            }
                        }

                        // Actualizar base de datos con placement data
                        placementBatchUpdates.add(
                                new DatabaseManager.PlayerStatUpdateWithPlacement(
                                        player.getMinecraftUuid(),
                                        won,
                                        player.getElo(),  // usando valores actuales
                                        player.getMmr(),
                                        matchKills,
                                        matchDeaths,
                                        stillInPlacement,
                                        newPlacementCount
                                )
                        );

                        // Actualizar objeto en memoria UNA SOLA VEZ
                        player.setPlacementMatchesPlayed(newPlacementCount);
                        player.setInPlacement(stillInPlacement);

                        // Log de progreso de placement
                        if (!stillInPlacement) {
                            logger.success("Placement Completed",
                                    String.format("🎉 %s completó placement matches (%d/%d) - ELO final: %d | Rango: %s",
                                            player.getMinecraftName(),
                                            newPlacementCount,
                                            PlayerData.getPlacementMatchesRequired(),
                                            player.getElo(),
                                            Rank.getRankByElo(player.getElo()).getDisplayName()));

                            Bukkit.getConsoleSender().sendMessage(
                                    "[RankedMC] Player completed placements: "
                                            + player.getMinecraftName()
                                            + " (uuid=" + player.getMinecraftUuid() + ") -> ELO="
                                            + player.getElo()
                                            + " | Rank="
                                            + Rank.getRankByElo(player.getElo()).getDisplayName()
                            );
                        } else {
                            logger.info("Placement Progress",
                                    "📊 " + player.getMinecraftName()
                                            + " progreso placement: "
                                            + newPlacementCount + "/"
                                            + PlayerData.getPlacementMatchesRequired());
                        }
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
                    // Si se finalizó el placement, NO sobreescribir el ELO/MMR ya asignado
                    if (!finalizedPlacement) {
                        player.setElo(eloChange.getNewElo());
                        player.setMmr(mmrChange.getNewMMR());
                    }

                    // OPTIMIZACIÓN: Actualizar estado de partida usando BatchProcessor
                    DatabaseManager.updatePlayerMatchStatusAsync(
                            player.getMinecraftUuid(), false, null);

                    // 🔥 NUEVO: refrescar el cache con el PlayerData FINAL
                    PlayerDataCache.cachePlayer(player);

                } catch (Exception e) {
                    logger.systemError("MatchFinisher",
                            "Error actualizando jugador " + player.getMinecraftUuid(),
                            e.getMessage());
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

        // ACTUALIZAR ROLES DE DISCORD DESPUÉS DE BATCH UPDATE
        updateDiscordRoles(activeMatch, detailedChanges, logger);

        // Mostrar estadísticas finales y limpiar el cache SIEMPRE en el hilo principal
        Bukkit.getScheduler().runTask(plugin, () -> {
            // **CRÍTICO**: Mostrar estadísticas finales CON rating changes establecidos
            // Esto muestra los valores correctos de ELO/MMR después de que se hayan establecido
            try {
                MatchStatsListener.displayFinalMatchStats(activeMatch.getMatchId());
            } catch (Exception e) {
                logger.warning("Final Stats Display Error",
                        "Error mostrando estadísticas finales: " + e.getMessage());
            }

            // **CRÍTICO**: Limpiar cache de estadísticas DESPUÉS de que se hayan establecido todos los rating changes
            // Esto previene el error "No se encontraron stats para match al establecer rating changes"
            try {
                MatchStatsListener.cleanupMatchStats(activeMatch.getMatchId());
            } catch (Exception e) {
                logger.warning("Cache Cleanup Error",
                        "Error limpiando cache de estadísticas: " + e.getMessage());
            }
        });

        // **CRÍTICO**: GUARDAR EN BASE DE DATOS DESPUÉS de establecer rating changes
        // Esto asegura que se guarden los valores correctos de ELO/MMR en lugar de 0→0
        saveMatchToDatabase(activeMatch, winnerTeam, logger, statsCache);

        return eloChanges;
    }

    /**
     * Envía los resultados completos a Discord
     */
    private static void sendDiscordResults(ActiveMatch activeMatch, Team winnerTeam,
                                           Map<String, Integer> eloChanges, long durationSeconds,
                                           DiscordLogger logger) {

        // NUEVO: Verificar si fue partida con sistema de picks
        if (activeMatch.isPicksMatch()) {
            // Usar método con información de capitanes
            logger.matchComplete(
                    activeMatch.getMatchId(),
                    activeMatch.getMatchType(),
                    activeMatch.getSelectedMap(),
                    winnerTeam,
                    activeMatch.getTeams(),
                    eloChanges,
                    durationSeconds,
                    true, // isPicksMatch
                    activeMatch.getBlueCaptain(),
                    activeMatch.getRedCaptain()
            );
        } else {
            logger.matchComplete(
                    activeMatch.getMatchId(),
                    activeMatch.getMatchType(),
                    activeMatch.getSelectedMap(),
                    winnerTeam,
                    activeMatch.getTeams(),
                    eloChanges,
                    durationSeconds
            );
        }

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
    private static void notifyPlayersInMinecraft(ActiveMatch activeMatch,
                                                 Team winnerTeam,
                                                 Map<String, Integer> eloChanges,
                                                 DatabaseManager.MatchProtectionSnapshot protectionSnap) {

        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        String generalMessage = "§6§l=== PARTIDA FINALIZADA ===";
        String winnerMessage = "§a🏆 Ganador: " + winnerTeam.getFormattedName();
        String mapMessage = "§e📍 Mapa: §f" + activeMatch.getSelectedMap();

        // ✅ ganador sin streams (más rápido y claro)
        List<PlayerData> winners = teams.getOrDefault(winnerTeam, java.util.Collections.emptyList());

        for (List<PlayerData> teamPlayers : teams.values()) {
            for (PlayerData playerData : teamPlayers) {
                Player mcPlayer;
                try {
                    mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                } catch (Exception ex) {
                    continue;
                }

                if (mcPlayer == null || !mcPlayer.isOnline()) continue;

                mcPlayer.sendMessage(generalMessage);
                mcPlayer.sendMessage(winnerMessage);
                mcPlayer.sendMessage(mapMessage);

                // ✅ SIN SQL: solo lookup en memoria
                boolean wasAbandonmentProcessed =
                        protectionSnap != null &&
                                protectionSnap.abandonmentProcessed.contains(playerData.getMinecraftUuid());

                boolean isProtectedFromLoss =
                        protectionSnap != null &&
                                protectionSnap.lossProtected.contains(playerData.getMinecraftUuid());

                boolean isWinner = winners.contains(playerData);

                // MENSAJE PERSONALIZADO SEGÚN EL ESTADO DEL JUGADOR
                if (wasAbandonmentProcessed) {
                    mcPlayer.sendMessage("§c§l❌ ABANDONASTE LA PARTIDA");
                    mcPlayer.sendMessage("§7Ya fuiste penalizado al abandonar:");
                    mcPlayer.sendMessage("§c  • Pérdida inmediata de ELO");
                    mcPlayer.sendMessage("§c  • Cooldown aplicado");
                    mcPlayer.sendMessage("§7§oEste resultado no afecta tu ELO adicional");
                    mcPlayer.sendMessage("§e💡 Tip: Reconéctate dentro de 1:30 min para evitar penalizaciones");

                } else if (isProtectedFromLoss && !isWinner) {
                    mcPlayer.sendMessage("§a✅ PROTEGIDO DE PÉRDIDA");
                    mcPlayer.sendMessage("§7Un compañero abandonó la partida:");
                    mcPlayer.sendMessage("§a  • Sin pérdida de ELO");
                    mcPlayer.sendMessage("§a  • Sin registro como derrota");
                    mcPlayer.sendMessage("§7Esta partida no afecta tu estadística negativa");

                } else if (playerData.isInPlacement()) {
                    int matchesPlayed = playerData.getPlacementMatchesPlayed();
                    int totalRequired = PlayerData.getPlacementMatchesRequired();
                    int remaining = totalRequired - matchesPlayed;

                    mcPlayer.sendMessage("§b🔍 PERÍODO DE EVALUACIÓN");
                    mcPlayer.sendMessage("§e📊 Progreso: §f" + matchesPlayed + "/" + totalRequired + " partidas");

                    if (remaining > 0) {
                        mcPlayer.sendMessage("§a✨ Te faltan §e" + remaining + "§a partidas para obtener tu rango inicial");
                    } else {
                        mcPlayer.sendMessage("§a🎉 ¡Completaste todas las partidas de evaluación!");
                        mcPlayer.sendMessage("§7Tu rango final se calculó basado en tu rendimiento");
                    }

                    mcPlayer.sendMessage("§7💡 Durante la evaluación no se muestran cambios de ELO");

                } else {
                    Integer eloChange = eloChanges.get(playerData.getMinecraftUuid());
                    if (eloChange != null && eloChange != 0) {
                        String eloMessage = (eloChange > 0)
                                ? "§a📈 +" + eloChange + " ELO"
                                : "§c📉 " + eloChange + " ELO";
                        mcPlayer.sendMessage(eloMessage);
                        mcPlayer.sendMessage("§7Usa §e/stats §7en Discord para ver tu ELO actualizado");
                    } else if (eloChange != null) {
                        mcPlayer.sendMessage("§e⚖️ Sin cambios de ELO");
                        mcPlayer.sendMessage("§7Tu rendimiento mantuvo tu ELO estable");
                    }
                }

                mcPlayer.sendMessage("§6§l========================");
            }
        }
    }
    /**
     * Mueve todos los jugadores al canal de espera en Discord
     */
    public static void movePlayersToWaitingRoom(ActiveMatch activeMatch,
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
                    if (member != null && member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
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

    private static void cleanupAfterDiscordMove(ActiveMatch activeMatch, RankedMinecraft plugin, DiscordLogger logger) {
        final int maxTries = 10;        // 10 intentos
        final long periodTicks = 10L;   // cada 0.5s

        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;

                try {
                    boolean blueHasMembers = activeMatch.getBlueTeamChannel() != null
                            && activeMatch.getBlueTeamChannel().getMembers() != null
                            && !activeMatch.getBlueTeamChannel().getMembers().isEmpty();

                    boolean redHasMembers = activeMatch.getRedTeamChannel() != null
                            && activeMatch.getRedTeamChannel().getMembers() != null
                            && !activeMatch.getRedTeamChannel().getMembers().isEmpty();

                    // ✅ Si ya están vacíos, ejecutar cleanup() EN MAIN THREAD
                    if (!blueHasMembers && !redHasMembers) {
                        logger.success("Discord Move Confirmed", "Canales vacíos, ejecutando cleanup()");
                        Bukkit.getScheduler().runTask(plugin, activeMatch::cleanup);
                        cancel();
                        return;
                    }

                    // ✅ Timeout: igual forzar cleanup() EN MAIN THREAD
                    if (tries >= maxTries) {
                        logger.warning("Discord Move Timeout",
                                "No se vaciaron canales tras " + tries + " intentos, forzando cleanup()");
                        Bukkit.getScheduler().runTask(plugin, activeMatch::cleanup);
                        cancel();
                    }

                } catch (Exception e) {
                    logger.warning("cleanupAfterDiscordMove Error", "Forzando cleanup(): " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, activeMatch::cleanup);
                    cancel();
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, periodTicks); // el poll puede ser async, cleanup NO
    }
    /**
     * Limpia todos los recursos de la partida (canales, memoria, etc.)
     */
    private static void cleanupMatchResources(ActiveMatch activeMatch,
                                              RankedMinecraft plugin, DiscordLogger logger) {
        cleanupAfterDiscordMove(activeMatch, plugin, logger);
    }

    /**
     * Finaliza el estado global de la partida
     * MEJORADO: Ya no guarda la partida aquí porque ya se guardó en updatePlayerStatistics
     */
    private static void finalizeMatchState(ActiveMatch activeMatch, DiscordLogger logger) {
        // **ELIMINADO**: La partida ya fue guardada en updatePlayerStatistics() con las stats correctas
        // No necesitamos guardarla de nuevo aquí

        // Limpiar votos de forfeit
        ForfeitManager.cleanupMatchVotes(activeMatch.getMatchId());

        // Finalizar estado global
        //MatchState.endMatch();

        logger.success("Estado Finalizado",
                "Estado global de partida finalizado - Sistema listo para nuevas colas");
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
     * MEJORADO: Ahora espera confirmación antes de log de eventos
     * MODIFICADO: Acepta estadísticas pre-finalizadas para evitar race condition
     */
    private static void saveMatchToDatabase(ActiveMatch activeMatch, Team winnerTeam, DiscordLogger logger,
                                            Map<UUID, MatchLogsManager.PlayerMatchStats> preFinalizedStats) {
        try {
            // Calcular duración antes de operaciones asíncronas
            long durationSeconds = java.time.Duration.between(activeMatch.getStartTime(),
                    java.time.LocalDateTime.now()).getSeconds();

            // MEJORADO: Guardar asíncronamente pero con confirmación ordenada
            // **CRÍTICO**: Pasar estadísticas pre-finalizadas para evitar race condition
            MatchLogsIntegration.finalizeActiveMatch(activeMatch, winnerTeam, preFinalizedStats)
                    .thenAccept(saved -> {
                        if (saved) {
                            logger.success("Match Data Saved",
                                    "Datos completos de partida " + activeMatch.getMatchId() + " guardados en match_logs");

                            // CRÍTICO: Solo log de evento DESPUÉS de guardar con éxito
                            MatchLogsIntegration.logMatchEnd(activeMatch.getMatchId(), winnerTeam, durationSeconds);
                        } else {
                            logger.error("Match Data Save Failed",
                                    "Error guardando datos de partida " + activeMatch.getMatchId());
                        }
                    })
                    .exceptionally(throwable -> {
                        logger.systemError("MatchFinisher",
                                "Excepción guardando datos de partida " + activeMatch.getMatchId(),
                                throwable.getMessage());
                        return null;
                    });

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

    /**
     * **MÉTODO MEJORADO**: Obtiene estadísticas finales con sincronización robusta
     * Soluciona problemas de datos incompletos y cálculo incorrecto de daño
     */
    private static MatchLogsManager.PlayerMatchStats getFinalPlayerStatsWithSync(String matchId, UUID playerUuid, DiscordLogger logger) {
        try {
            // **PASO 1**: Forzar procesamiento de eventos pendientes
            int attemptCount = 0;
            int maxAttempts = 3;
            MatchLogsManager.PlayerMatchStats finalStats = null;

            while (attemptCount < maxAttempts && finalStats == null) {
                attemptCount++;

                // Obtener estadísticas actuales
                finalStats = MatchStatsListener.getFinalPlayerStats(matchId, playerUuid);

                if (finalStats != null) {
                    // **PASO 2**: Validar que las estadísticas no estén vacías
                    boolean hasValidStats = finalStats.getKills() > 0 ||
                            finalStats.getDeaths() > 0 ||
                            finalStats.getDamageDealt() > 0.0;

                    if (hasValidStats) {
                        logger.info("Stats Sync Success",
                                String.format("📊 Intento %d/%d - Stats válidas obtenidas para %s",
                                        attemptCount, maxAttempts, finalStats.getPlayerName()));
                        break;
                    } else {
                        logger.warning("Stats Sync Empty",
                                String.format("⚠️ Intento %d/%d - Stats vacías, reintentando para %s",
                                        attemptCount, maxAttempts, finalStats.getPlayerName()));
                        finalStats = null; // Reintentar
                    }
                }

                if (finalStats == null && attemptCount < maxAttempts) {
                    logger.warning("Stats Sync Retry",
                            String.format("⏳ Intento %d/%d - Esperando sincronización para jugador %s",
                                    attemptCount, maxAttempts, playerUuid.toString().substring(0, 8)));

                    // **PASO 3**: Esperar procesamiento de eventos pendientes
                    try {
                        Thread.sleep(100L * attemptCount); // Espera incremental: 100ms, 200ms, 300ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // **PASO 4**: Log final del resultado
            if (finalStats != null) {
                logger.success("Stats Sync Final",
                        String.format("✅ Sincronización exitosa - %s | K/D: %d/%d | Daño: %.1f",
                                finalStats.getPlayerName(),
                                finalStats.getKills(),
                                finalStats.getDeaths(),
                                finalStats.getDamageDealt()));
            } else {
                logger.error("Stats Sync Failed",
                        String.format("❌ Falló sincronización después de %d intentos para jugador %s",
                                maxAttempts, playerUuid.toString().substring(0, 8)));
            }

            return finalStats;

        } catch (Exception e) {
            logger.systemError("MatchFinisher",
                    "Error crítico en getFinalPlayerStatsWithSync: " + e.getMessage(), e.toString());
            return null;
        }
    }

    /**
     * Asigna el rango final después de completar las 8 partidas de placement
     * Implementa el sistema completo de evaluación basado en las 8 partidas
     */
    private static void assignFinalPlacementRank(PlayerData player, String matchId, DiscordLogger logger, boolean wonLastMatch, org.fabricioyv.discord.DiscordBot discordBot) {
        try {
            logger.info("Placement Evaluation Start",
                    String.format("🔍 Iniciando evaluación final para %s tras completar 8 partidas",
                            player.getMinecraftName()));

            // PASO 1: Obtener historial completo de placement matches desde la base de datos
            List<PlacementMatchData> placementHistory = getPlayerPlacementHistory(player.getMinecraftUuid());

            if (placementHistory.size() < PlayerData.getPlacementMatchesRequired()) {
                logger.error("Placement History Incomplete",
                        String.format("❌ %s - Solo se encontraron %d partidas, se requieren %d",
                                player.getMinecraftName(), placementHistory.size(), PlayerData.getPlacementMatchesRequired()));
                // Fallback: asignar ELO base
                assignFallbackElo(player, logger);
                return;
            }

            // PASO 2: Calcular métricas de rendimiento
            PlacementMetrics metrics = calculatePlayerPlacementMetrics(placementHistory);

            // PASO 3: Determinar ELO base según win rate
            int baseElo = calculateBaseEloFromWinRate(metrics.winRate);

            // PASO 4: Aplicar modificadores por rendimiento individual
            double performanceMultiplier = calculatePerformanceMultiplier(metrics);

            // PASO 5: Aplicar modificadores por consistencia
            double consistencyMultiplier = calculateConsistencyMultiplier(metrics);

            // PASO 6: Calcular ELO final
            int finalElo = (int) Math.round(baseElo * performanceMultiplier * consistencyMultiplier);

            // PASO 7: Limitar a rangos válidos del sistema
            finalElo = Math.max(100, Math.min(1200, finalElo)); // 100-1200 ELO válido

            // PASO 8: Determinar rango correspondiente
            Rank assignedRank = Rank.getRankByElo(finalElo);

            // PASO 9: Calcular MMR correspondiente para matchmaking equilibrado
            double finalMMR = convertEloToMMR(finalElo, metrics);

            // PASO 10: Actualizar PlayerData
            int oldElo = player.getElo();
            player.setElo(finalElo);
            player.setMmr(finalMMR);
            // PASO 11: Actualizar solo el ELO en la base de datos principal
            DatabaseManager.PlayerStatUpdate update = new DatabaseManager.PlayerStatUpdate(
                    player.getMinecraftUuid(), wonLastMatch, finalElo, finalMMR, player.getCurrentMatchKills(), player.getCurrentMatchDeaths());
            DatabaseManager.updatePlayerStats(java.util.Collections.singletonList(update));
            // Ensure persistence: directly set elo/mmr (in case batch updates run later)
            try {
                DatabaseManager.setPlayerEloAndMmr(player.getMinecraftUuid(), finalElo, finalMMR);
            } catch (Exception e) {
                logger.warning("Placement Direct ELO Persist Failed", "No se pudo persistir directamente elo/mmr: " + e.getMessage());
            }
            // PASO 12: Asignar rol en Discord usando el nombre del rango calculado por el ELO
            try {
                if (discordBot != null && player.getDiscordId() != null && !player.getDiscordId().isEmpty()) {
                    discordBot.assignRankRole(player.getDiscordId(), assignedRank);
                }
            } catch (Exception e) {
                logger.warning("Discord Role Assignment Failed", "No se pudo asignar el rol de Discord: " + e.getMessage());
            }
            // PASO 13: Logs detallados del resultado
            logger.success("Placement Final Assignment",
                    String.format("🎉 %s | ELO: %d→%d | Rango: %s | WR: %.1f%% | K/D: %.2f | Daño Avg: %.1f",
                            player.getMinecraftName(),
                            oldElo,
                            finalElo,
                            assignedRank.getDisplayName(),
                            metrics.winRate,
                            metrics.kdRatio,
                            metrics.avgDamage));

            // PASO 14: Log de análisis detallado
            logger.info("Placement Analysis",
                    String.format("📊 %s | Base: %d | Perf: x%.2f | Cons: x%.2f | MMR: %.1f",
                            player.getMinecraftName(),
                            baseElo,
                            performanceMultiplier,
                            consistencyMultiplier,
                            finalMMR));

        } catch (Exception e) {
            logger.systemError("MatchFinisher",
                    String.format("Error asignando placement final para %s: %s",
                            player.getMinecraftName(), e.getMessage()), e.toString());

            // Fallback en caso de error
            assignFallbackElo(player, logger);
        }
    }

    /**
     * Obtiene el historial de placement matches de un jugador desde la base de datos
     */
    private static List<PlacementMatchData> getPlayerPlacementHistory(String playerUuid) {
        List<PlacementMatchData> history = new ArrayList<>();

        try {
            // Obtener las 8 partidas de placement desde match_logs usando DatabaseManager
            List<DatabaseManager.PlacementMatchData> dbHistory = DatabaseManager.getPlayerPlacementMatches(playerUuid);

            // Convertir de DatabaseManager.PlacementMatchData a MatchFinisher.PlacementMatchData
            for (DatabaseManager.PlacementMatchData dbMatch : dbHistory) {
                history.add(new PlacementMatchData(dbMatch.won, dbMatch.kills, dbMatch.deaths, dbMatch.damage));
            }

        } catch (Exception e) {
            // Error obteniendo historial - usar lista vacía
        }

        return history;
    }

    /**
     * Calcula métricas de rendimiento durante placement matches
     */
    private static PlacementMetrics calculatePlayerPlacementMetrics(List<PlacementMatchData> matches) {
        int wins = 0;
        int totalKills = 0;
        int totalDeaths = 0;
        double totalDamage = 0.0;

        for (PlacementMatchData match : matches) {
            if (match.won) wins++;
            totalKills += match.kills;
            totalDeaths += match.deaths;
            totalDamage += match.damage;
        }

        double winRate = (double) wins / matches.size() * 100.0;
        double avgKills = (double) totalKills / matches.size();
        double avgDeaths = (double) totalDeaths / matches.size();
        double avgDamage = totalDamage / matches.size();
        double kdRatio = totalDeaths > 0 ? (double) totalKills / totalDeaths : totalKills;

        return new PlacementMetrics(winRate, avgKills, avgDeaths, avgDamage, kdRatio,
                calculateConsistency(matches), wins, matches.size());
    }

    /**
     * Calcula ELO base según win rate (sistema equilibrado)
     */
    private static int calculateBaseEloFromWinRate(double winRate) {
        if (winRate >= 87.5) {       // 7-8 wins - Rendimiento excepcional
            return 700; // ORO II
        } else if (winRate >= 75.0) { // 6 wins - Rendimiento sólido
            return 600; // ORO III
        } else if (winRate >= 62.5) { // 5 wins - Rendimiento bueno
            return 500; // PLATA II
        } else if (winRate >= 50.0) { // 4 wins - Rendimiento promedio
            return 400; // HIERRO I
        } else if (winRate >= 37.5) { // 3 wins - Rendimiento bajo
            return 300; // HIERRO II
        } else if (winRate >= 25.0) { // 2 wins - Rendimiento muy bajo
            return 250; // HIERRO III
        } else {                     // 0-1 wins - Rendimiento malo
            return 200; // COBRE I
        }
    }

    /**
     * Calcula multiplicador por rendimiento individual
     */
    private static double calculatePerformanceMultiplier(PlacementMetrics metrics) {
        double multiplier = 1.0;

        // Bonus por K/D alto
        if (metrics.kdRatio >= 2.0) {
            multiplier += 0.15; // +15%
        } else if (metrics.kdRatio >= 1.5) {
            multiplier += 0.10; // +10%
        } else if (metrics.kdRatio >= 1.0) {
            multiplier += 0.05; // +5%
        } else if (metrics.kdRatio < 0.5) {
            multiplier -= 0.10; // -10%
        }

        // Bonus por daño alto
        if (metrics.avgDamage >= 1000) {
            multiplier += 0.10; // +10%
        } else if (metrics.avgDamage >= 700) {
            multiplier += 0.05; // +5%
        } else if (metrics.avgDamage < 300) {
            multiplier -= 0.05; // -5%
        }

        return Math.max(0.7, Math.min(1.3, multiplier)); // Límites: 70%-130%
    }

    /**
     * Calcula multiplicador por consistencia
     */
    private static double calculateConsistencyMultiplier(PlacementMetrics metrics) {
        // Mayor consistencia = mejor multiplicador
        if (metrics.consistency >= 0.8) {
            return 1.05; // +5%
        } else if (metrics.consistency >= 0.6) {
            return 1.0; // Normal
        } else {
            return 0.95; // -5%
        }
    }

    /**
     * Convierte ELO a MMR para matchmaking equilibrado
     */
    private static double convertEloToMMR(int elo, PlacementMetrics metrics) {
        // Base MMR similar al ELO
        double baseMMR = elo * 0.8 + 200; // ELO 500 → MMR 600

        // Ajustar MMR según rendimiento para mejor matchmaking
        if (metrics.kdRatio > 1.5) {
            baseMMR += 50; // MMR más alto para mejores jugadores
        } else if (metrics.kdRatio < 0.8) {
            baseMMR -= 50; // MMR más bajo para jugadores que necesitan práctica
        }

        return Math.max(400, Math.min(1400, baseMMR));
    }

    /**
     * Calcula consistencia del jugador basada en variación de rendimiento
     */
    private static double calculateConsistency(List<PlacementMatchData> matches) {
        if (matches.size() < 2) return 1.0;

        // Calcular variación en damage (como métrica principal de consistencia)
        double[] damages = matches.stream().mapToDouble(m -> m.damage).toArray();
        double mean = Arrays.stream(damages).average().orElse(0.0);
        double variance = Arrays.stream(damages)
                .map(d -> Math.pow(d - mean, 2))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        // Convertir a score de consistencia (0-1, donde 1 = muy consistente)
        double coefficientOfVariation = mean > 0 ? stdDev / mean : 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - coefficientOfVariation));
    }

    /**
     * Asigna ELO de fallback en caso de errores
     */
    private static void assignFallbackElo(PlayerData player, DiscordLogger logger) {
        int fallbackElo = 400; // HIERRO I como base segura
        double fallbackMMR = 600;

        player.setElo(fallbackElo);
        player.setMmr(fallbackMMR);

        logger.warning("Placement Fallback Applied",
                String.format("⚠️ %s - ELO fallback asignado: %d | Rango: %s",
                        player.getMinecraftName(),
                        fallbackElo,
                        Rank.getRankByElo(fallbackElo).getDisplayName()));
    }

    /**
     * Clases de apoyo para datos de placement
     */
    private static class PlacementMatchData {
        final boolean won;
        final int kills;
        final int deaths;
        final double damage;

        PlacementMatchData(boolean won, int kills, int deaths, double damage) {
            this.won = won;
            this.kills = kills;
            this.deaths = deaths;
            this.damage = damage;
        }
    }

    private static class PlacementMetrics {
        final double winRate;
        final double avgKills;
        final double avgDeaths;
        final double avgDamage;
        final double kdRatio;
        final double consistency;
        final int wins;
        final int totalMatches;

        PlacementMetrics(double winRate, double avgKills, double avgDeaths, double avgDamage,
                         double kdRatio, double consistency, int wins, int totalMatches) {
            this.winRate = winRate;
            this.avgKills = avgKills;
            this.avgDeaths = avgDeaths;
            this.avgDamage = avgDamage;
            this.kdRatio = kdRatio;
            this.consistency = consistency;
            this.wins = wins;
            this.totalMatches = totalMatches;
        }
    }
    /**
     * NUEVO: Procesa resultados de un equipo considerando protecciones por abandono
     */
    private static void processTeamResults(List<PlayerData> teamPlayers, boolean won,
                                           Map<String, Boolean> playerProtections,
                                           Map<String, Boolean> abandonmentProcessed,
                                           ActiveMatch activeMatch, DiscordLogger logger,
                                           RankedMinecraft plugin) {

        for (PlayerData player : teamPlayers) {
            try {
                String playerUuid = player.getMinecraftUuid();
                boolean isProtected = playerProtections.getOrDefault(playerUuid, false);
                boolean wasProcessedForAbandonment = abandonmentProcessed.getOrDefault(playerUuid, false);

                // CRÍTICO: Si el jugador ya fue procesado por abandono, NO aplicar más penalizaciones
                if (wasProcessedForAbandonment) {
                    logger.info("Jugador Ya Procesado",
                            String.format("Jugador %s ya fue procesado por abandono - evitando doble penalización",
                                    player.getMinecraftUuid().substring(0, 8)));

                    // Solo notificar que no se procesará más
                    Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerUuid));
                    if (mcPlayer != null && mcPlayer.isOnline()) {
                        mcPlayer.sendMessage("§7⚠️ Ya fuiste penalizado por abandono - sin cambios adicionales");
                    }

                    // Continuar con siguiente jugador sin procesar ELO
                    continue;
                }

                if (isProtected && !won) {
                    // El jugador está protegido de la pérdida por abandono de compañero
                    logger.info("Pérdida Protegida",
                            String.format("Jugador %s protegido de pérdida por abandono - sin cambio de ELO",
                                    player.getMinecraftUuid().substring(0, 8)));

                    // Notificar al jugador
                    Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerUuid));
                    if (mcPlayer != null && mcPlayer.isOnline()) {
                        mcPlayer.sendMessage("§a✅ Protegido de pérdida de ELO por abandono de compañero");
                        mcPlayer.sendMessage("§7Esta derrota no afectará tu rango");
                    }

                    // Solo actualizar estado (sin cambios de ELO/MMR)
                    if (player.isInPlacement()) {
                        // Incrementar contador de placement pero sin aplicar resultado negativo
                        int newPlacementCount = player.getPlacementMatchesPlayed() + 1;
                        boolean stillInPlacement = newPlacementCount < PlayerData.getPlacementMatchesRequired();

                        player.setPlacementMatchesPlayed(newPlacementCount);
                        player.setInPlacement(stillInPlacement);

                        // Actualizar solo el contador de placement, no las wins/losses
                        DatabaseManager.updatePlayerPlacementData(playerUuid, stillInPlacement, newPlacementCount);
                    }

                    // Continuar con siguiente jugador sin procesar ELO
                    continue;
                }

                // AQUÍ ES DONDE SE PROCESARÍA NORMALMENTE EL ELO
                // Pero este código ya existe en updatePlayerStatistics(), no necesito duplicarlo aquí
                logger.debug("Procesamiento Normal",
                        String.format("Jugador %s procesado normalmente - sin protecciones ni abandono previo",
                                player.getMinecraftUuid().substring(0, 8)));

            } catch (Exception e) {
                logger.logError("Error procesando resultado de equipo para jugador " + player.getMinecraftUuid(), e);
            }
        }
    }
}