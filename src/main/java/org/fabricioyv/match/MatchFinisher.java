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
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.MMRCalculator;
import org.fabricioyv.rating.ProgressiveEloCalculator;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class MatchFinisher {

    public static void finishMatch(ActiveMatch activeMatch, Team winnerTeam, RankedMinecraft plugin, DiscordLogger logger){
        String matchId = activeMatch.getMatchId();
        long startTime = System.currentTimeMillis();

        logger.matchEvent(matchId, "Finalizando Partida",
                "Iniciando proceso de finalización", activeMatch.getAllPlayers().size());
        // EJECUTAR OPERACIONES PESADAS DE FORMA ASÍNCRONA
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 1. Calcular duración de la partida
                Duration matchDuration = Duration.between(activeMatch.getStartTime(), LocalDateTime.now());
                long durationSeconds = matchDuration.getSeconds();

                // 2. Actualizar estadísticas y ELO (ASÍNCRONO)
                Map<String, Integer> eloChanges = updatePlayerStatistics(activeMatch, winnerTeam, logger);

                // 3. Enviar mensaje de finalización a Discord (ASÍNCRONO)
                sendDiscordResults(activeMatch, winnerTeam, eloChanges, durationSeconds, logger);

                // OPERACIONES SÍNCRONAS EN EL MAIN THREAD
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    // 4. Notificar jugadores en Minecraft (SÍNCRONO - UI)
                    notifyPlayersInMinecraft(activeMatch, winnerTeam, eloChanges);

                    // 5. Mover jugadores de Discord (SÍNCRONO - API Discord)
                    movePlayersToWaitingRoom(activeMatch, plugin, logger);

                    // 6. Limpiar canales y recursos (RETRASADO)
                    cleanupMatchResources(activeMatch, plugin, logger);

                    // 7. Finalizar estado de partida
                    finalizeMatchState(activeMatch, logger);
                },20L); // Esperar un tick

            } catch (Exception e) {
                logger.systemError("MatchFinisher", "Error crítico finalizando partida " + matchId, e.getMessage());

                // Cleanup en main thread si hay error
                Bukkit.getScheduler().runTask(plugin, () -> {
                    emergencyCleanup(activeMatch, plugin, logger);
                });
            }
        });
    }
    /**
     * Actualiza estadísticas de todos los jugadores y calcula cambios de ELO y MMR
     */
    private static Map<String, Integer> updatePlayerStatistics(ActiveMatch activeMatch,
                                                               Team winnerTeam, DiscordLogger logger) {
        Map<String, Integer> eloChanges = new HashMap<>();
        Map<String, ProgressiveEloCalculator.EloChange> detailedChanges = new HashMap<>(); // Nuevo
        List<DatabaseManager.PlayerStatUpdate> batchUpdates = new ArrayList<>();
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

        // Procesar cada equipo
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean won = team == winnerTeam;
            double teamAvgMMR = team == Team.BLUE ? blueTeamAvgMMR : redTeamAvgMMR;
            double opponentAvgMMR = team == Team.BLUE ? redTeamAvgMMR : blueTeamAvgMMR;

            for (PlayerData player : players) {
                try {
                    // 1. Calcular cambio de ELO (visible para el jugador)
                    // Calcular cambio de ELO con modificadores de tipo de partida
                    ProgressiveEloCalculator.EloChange eloChange =
                            ProgressiveEloCalculator.calculateEloChange(
                                    player.getElo(), opponentAvgMMR, won, matchType);

                    // 2. Calcular cambio de MMR (interno para balanceo)
                    MMRCalculator.MMRChange mmrChange =
                            MMRCalculator.calculateMMRChange(player, won, teamAvgMMR, opponentAvgMMR);

                    eloChanges.put(player.getMinecraftUuid(), eloChange.getEloChange());
                    detailedChanges.put(player.getMinecraftUuid(), eloChange); // Guardar cambios detallados

                    // 3. Actualizar en base de datos (ELO, MMR y estadísticas)
                    // PREPARAR PARA BATCH UPDATE - ASEGURANDO KILLS Y DEATHS
                    int matchKills = player.getCurrentMatchKills();
                    int matchDeaths = player.getCurrentMatchDeaths();

                    batchUpdates.add(new DatabaseManager.PlayerStatUpdate(
                            player.getMinecraftUuid(),
                            won,
                            eloChange.getNewElo(),
                            mmrChange.getNewMMR(),
                            matchKills,    // KILLS de esta partida
                            matchDeaths    // DEATHS de esta partida
                    ));

                    // 4. Actualizar objeto en memoria
                    player.setElo(eloChange.getNewElo());
                    player.setMmr(mmrChange.getNewMMR());
                    player.setInMatch(false);
                    player.setCurrentMatchId(null);

                    // 5. Log detallado de cambios
                    String playerName = getPlayerName(player);
                    logger.info("Player Stats Updated",
                            String.format("%s | %s | %s | Result: %s",
                                    playerName,
                                    eloChange.getChangeMessage(),
                                    mmrChange.getDetailedMessage(),
                                    won ? "VICTORIA" : "DERROTA"));

                    // 6. Resetear estadísticas de partida para próxima partida
                    player.resetMatchStats();

                } catch (Exception e) {
                    logger.systemError("MatchFinisher",
                            "Error updating player statistics: " + player.getMinecraftUuid(),
                            e.getMessage());
                }
            }
        }
        // EJECUTAR BATCH UPDATE UNA SOLA VEZ
        if (!batchUpdates.isEmpty()) {
            DatabaseManager.updatePlayerStats(batchUpdates);
            logger.success("Database Updated",
                    "Actualizadas estadísticas de " + batchUpdates.size() + " jugadores en batch");
        }
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

                    // Mensaje personalizado con cambio de ELO
                    Integer eloChange = eloChanges.get(playerData.getMinecraftUuid());
                    if (eloChange != null) {
                        String eloMessage = eloChange > 0 ?
                                "§a📈 +" + eloChange + " ELO!" :
                                "§c📉 " + eloChange + " ELO";
                        mcPlayer.sendMessage(eloMessage);
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
        ForfeitManager.cleanupMatchVotes(activeMatch.getMatchId());
        // Finalizar estado global
        MatchState.endMatch();

        logger.success("Estado Finalizado",
                "Estado global de partida finalizado - Sistema listo para nuevas colas");
    }

    /**
     * Cancela una partida sin procesar estadísticas
     */
    public static void cancelMatch(ActiveMatch activeMatch, String reason,
                                   RankedMinecraft plugin, DiscordLogger logger) {

        logger.matchEvent(activeMatch.getMatchId(), "Partida Cancelada", reason,
                activeMatch.getAllPlayers().size());

        // Notificar jugadores
        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage("§c❌ Partida cancelada: " + reason);
                }

                // Limpiar estado del jugador
                playerData.setInMatch(false);
                playerData.setCurrentMatchId(null);
            }
        }

        // Mover jugadores al canal de espera
        movePlayersToWaitingRoom(activeMatch, plugin, logger);

        // Limpiar recursos
        cleanupMatchResources(activeMatch, plugin, logger);

        // Finalizar estado
        finalizeMatchState(activeMatch, logger);
    }

    /**
     * Limpieza de emergencia en caso de errores críticos
     */
    private static void emergencyCleanup(ActiveMatch activeMatch,
                                         RankedMinecraft plugin, DiscordLogger logger) {

        logger.error("Limpieza de Emergencia",
                "Ejecutando limpieza de emergencia para partida " + activeMatch.getMatchId());

        // Limpiar estado de jugadores
        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                playerData.setInMatch(false);
                playerData.setCurrentMatchId(null);
            }
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
     * Limpia el estado de los jugadores sin cambiar ELO/MMR
     */
    private static void cleanPlayerStatesForDraw(ActiveMatch activeMatch) {
        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData player : teamPlayers) {
                // Solo limpiar estado de partida, NO tocar ELO/MMR
                player.setInMatch(false);
                player.setCurrentMatchId(null);
                player.resetMatchStats();
            }
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
     * Notifica a los jugadores en Minecraft sobre el empate
     */
    private static void notifyPlayersAboutDraw(ActiveMatch activeMatch) {
        String[] messages = {
                "§6§l=== PARTIDA EMPATADA ===",
                "§e⚖️ La partida terminó en empate",
                "§a✓ No hay cambios de ELO para ningún jugador",
                "§e📍 Mapa: §f" + activeMatch.getSelectedMap(),
                "§6§l======================"
        };

        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    for (String message : messages) {
                        mcPlayer.sendMessage(message);
                    }
                }
            }
        }
    }
}
