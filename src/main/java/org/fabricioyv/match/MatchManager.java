package org.fabricioyv.match;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.database.MatchLogsIntegration;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class MatchManager {

    private static final RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);

    private static final String PREFIX = "§8[§6Keke Ranked§8]§r ";

    // Reintentos para esperar que JDA termine de cargar guilds (útil en 2v2)
    private static final int MAX_GUILD_RESOLVE_ATTEMPTS = 10; // ~10s si reintentas cada 20 ticks
    private static final long GUILD_RESOLVE_RETRY_TICKS = 20L;

    /**
     * Inicia una partida con los jugadores especificados
     */
    public static void startMatch(List<PlayerData> players) {
        if (players == null || players.isEmpty()) return;
        startMatchInternal(players, 0);
    }

    private static void startMatchInternal(List<PlayerData> players, int attempt) {
        try {
            if (plugin.getDiscordBot() == null) {
                Bukkit.getLogger().warning("[MatchManager] DiscordBot es null, no se puede iniciar match.");
                MatchState.endMatch();
                return;
            }

            DiscordLogger logger = plugin.getDiscordBot().getLogger();
            JDA jda = plugin.getDiscordBot().getJda();

            if (jda == null) {
                if (attempt < MAX_GUILD_RESOLVE_ATTEMPTS) {
                    if (logger != null) {
                        logger.warning("MatchManager",
                                "JDA aún es null. Reintentando resolve de guild (" + (attempt + 1) + "/" + MAX_GUILD_RESOLVE_ATTEMPTS + ")");
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() {
                            startMatchInternal(players, attempt + 1);
                        }
                    }, GUILD_RESOLVE_RETRY_TICKS);
                    return;
                }

                if (logger != null) logger.error("MatchManager", "JDA es null, no se puede iniciar la partida.");
                MatchState.endMatch();
                return;
            }

            Guild guild = resolveGuild(jda, players);

            if (guild == null) {
                if (attempt < MAX_GUILD_RESOLVE_ATTEMPTS) {
                    if (logger != null) {
                        logger.warning("MatchManager",
                                "No se pudo resolver Guild (guilds=" + jda.getGuilds().size() + "). Reintentando (" +
                                        (attempt + 1) + "/" + MAX_GUILD_RESOLVE_ATTEMPTS + ")");
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() {
                            startMatchInternal(players, attempt + 1);
                        }
                    }, GUILD_RESOLVE_RETRY_TICKS);
                    return;
                }

                if (logger != null) logger.error("MatchManager", "No se encontró Guild para iniciar la partida (falló tras reintentos).");
                MatchState.endMatch();
                return;
            }

            // ---- Ya tenemos guild válido ----
            String matchId = generateMatchId();
            String matchType = detectMatchType(players.size());

            logger.matchEvent(matchId, "Match preparando",
                    "Preparando partida " + matchType + " (guild=" + guild.getId() + ")", players.size());

            ActiveMatch activeMatch = new ActiveMatch(matchId, players, jda, guild, plugin, logger);

            // Fase 1: validar conexiones MC
            if (!validatePlayersConnection(players, logger)) {
                logger.warning("Match cancelado", "Algunos jugadores se desconectaron en la preparación");
                cancelMatch(activeMatch, "Uno o más jugadores se desconectaron durante la preparación");
                return;
            }

            // Fase 2: balanceo
            activeMatch.balanceTeams();

            // Fase 3: votación de mapas
            startMapVoting(activeMatch, logger);

        } catch (Exception e) {
            try {
                if (plugin.getDiscordBot() != null && plugin.getDiscordBot().getLogger() != null) {
                    plugin.getDiscordBot().getLogger().systemError(
                            "MatchManager",
                            "Error crítico iniciando partida",
                            e.getMessage()
                    );
                }
            } catch (Exception ignored) {}
            e.printStackTrace();
            MatchState.endMatch();
        }
    }

    /**
     * Resuelve el guild de forma robusta:
     * 1) Si hay guild-id en config -> lo usa
     * 2) Busca un guild donde exista al menos 1 miembro de la lista
     * 3) Fallback: primer guild si existe
     */
    private static Guild resolveGuild(JDA jda, List<PlayerData> players) {
        try {
            // 1) Por config (cambia la key si usas otra)
            String configuredGuildId = null;
            try {
                configuredGuildId = plugin.getConfig().getString("discord.guild-id");
            } catch (Exception ignored) {}

            if (configuredGuildId != null && !configuredGuildId.trim().isEmpty()) {
                Guild g = jda.getGuildById(configuredGuildId.trim());
                if (g != null) return g;
            }

            // 2) Buscar por miembros
            if (players != null && !players.isEmpty()) {
                for (Guild g : jda.getGuilds()) {
                    for (PlayerData p : players) {
                        if (p == null) continue;
                        String did = p.getDiscordId();
                        if (did == null || did.isEmpty()) continue;

                        try {
                            if (g.getMemberById(did) != null) {
                                return g;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            // 3) Fallback
            return jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);

        } catch (Exception e) {
            return null;
        }
    }

    private static String generateMatchId() {
        return "MATCH_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    /**
     * 4 -> 2v2 | 10 -> 5v5 | 16 -> 8v8
     */
    private static String detectMatchType(int playerCount) {
        if (playerCount == 4) return "2v2";
        if (playerCount == 10) return "5v5";
        if (playerCount == 16) return "8v8";
        int perTeam = Math.max(1, playerCount / 2);
        return perTeam + "v" + perTeam;
    }

    private static boolean validatePlayersConnection(List<PlayerData> players, DiscordLogger logger) {
        int connectedCount = 0;

        for (PlayerData playerData : players) {
            try {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    connectedCount++;
                } else {
                    logger.warning("Jugador desconectado",
                            "UUID:" + safeShort(playerData.getMinecraftUuid()) + " no está online");
                }
            } catch (Exception e) {
                logger.warning("Validación", "Error validando jugador: " + e.getMessage());
            }
        }

        logger.info("Validación", connectedCount + "/" + players.size() + " conectados");
        return connectedCount == players.size();
    }

    private static void startMapVoting(ActiveMatch activeMatch, DiscordLogger logger) {
        activeMatch.setStatus(ActiveMatch.MatchStatus.MAP_VOTING);

        MapVoting mapVoting = new MapVoting(
                plugin,
                logger,
                activeMatch.getAllPlayers(),
                activeMatch.getMatchType()
        );

        activeMatch.setMapVoting(mapVoting);

        mapVoting.startVoting(selectedMap -> {
            activeMatch.setSelectedMap(selectedMap);
            onMapSelected(activeMatch, selectedMap, logger);
        });
    }

    private static void onMapSelected(ActiveMatch activeMatch, String selectedMap, DiscordLogger logger) {
        logger.matchEvent(activeMatch.getMatchId(), "Mapa seleccionado",
                "Mapa: " + selectedMap, activeMatch.getAllPlayers().size());

        announceToPlayers(activeMatch.getAllPlayers(), PREFIX + "§7Mapa: §f" + selectedMap);

        cycleMap(selectedMap, activeMatch, logger);
    }

    private static void cycleMap(String mapName, ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sn " + mapName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cycle 1");
            });

            logger.info("Mapa", "Ciclando a: " + mapName);

            Bukkit.getScheduler().runTaskLater(plugin, () -> startPickPhaseOrBalance(activeMatch, logger), 120L);

        } catch (Exception e) {
            logger.logError("Error ciclando mapa: " + mapName, e);
            cancelMatch(activeMatch, "Error ciclando mapa");
        }
    }

    /**
     * En 2v2 NO usamos CaptainPickSystem.
     */
    private static void startPickPhaseOrBalance(ActiveMatch activeMatch, DiscordLogger logger) {
        int count = activeMatch.getAllPlayers() == null ? 0 : activeMatch.getAllPlayers().size();

        if (count == 4 || "2v2".equalsIgnoreCase(activeMatch.getMatchType())) {
            logger.info("Picks", "Modo 2v2: omitiendo fase de picks");
            continueWithNormalFlow(activeMatch, logger);
            return;
        }

        try {
            CaptainPickSystem.startPickPhase(activeMatch, logger);
        } catch (Exception e) {
            logger.logError("Picks fallaron, usando balanceo automático", e);
            activeMatch.balanceTeams();
            continueWithNormalFlow(activeMatch, logger);
        }
    }

    private static void continueWithNormalFlow(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            if (activeMatch.getBlueTeamChannel() == null && activeMatch.getRedTeamChannel() == null) {
                logger.info("Discord", "Creando canales de equipo");
                activeMatch.createTeamChannels();
            }

            activeMatch.assignPlayersInMinecraft();
            startFinalCountdown(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error en flujo normal", e);
            cancelMatch(activeMatch, "Error técnico en el flujo de la partida");
        }
    }

    private static void startFinalCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Countdown", "Iniciando countdown final para " + activeMatch.getMatchId());

        final int totalSeconds = 30;

        announceToPlayers(activeMatch.getAllPlayers(),
                PREFIX + "§7Equipos listos. Iniciando en §f" + totalSeconds + "§7s...");

        new BukkitRunnable() {
            int countdown = totalSeconds;

            @Override
            public void run() {
                if (countdown <= 0) {
                    if (!validatePlayersConnection(activeMatch.getAllPlayers(), logger)) {
                        cancelMatch(activeMatch, "Se desconectó un jugador antes del inicio");
                        cancel();
                        return;
                    }

                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 120");
                    startOfficialMatch(activeMatch, logger);
                    cancel();
                    return;
                }

                if (countdown == 20 || countdown == 10 || countdown <= 5) {
                    announceToPlayers(activeMatch.getAllPlayers(),
                            PREFIX + "§7Iniciando en §f" + countdown + "§7s...");
                }

                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private static void startOfficialMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        activeMatch.setStatus(ActiveMatch.MatchStatus.IN_PROGRESS);

        try {
            MatchLogsIntegration.startMatchTracking(
                    activeMatch.getMatchId(),
                    activeMatch.getTeams(),
                    activeMatch.getMatchType(),
                    activeMatch.getSelectedMap()
            );
        } catch (Exception e) {
            logger.warning("Logs", "No se pudo iniciar tracking: " + e.getMessage());
        }

        try {
            MatchLogsIntegration.logMatchStart(
                    activeMatch.getMatchId(),
                    activeMatch.getSelectedMap(),
                    activeMatch.getAllPlayers().size()
            );
        } catch (Exception ignored) {}

        announceToPlayers(activeMatch.getAllPlayers(),
                PREFIX + "§aPartida iniciada §8• §7Mapa: §f" + activeMatch.getSelectedMap());

        logger.matchEvent(activeMatch.getMatchId(), "Match iniciado",
                "Modo " + activeMatch.getMatchType() + " en " + activeMatch.getSelectedMap(),
                activeMatch.getAllPlayers().size());

        updatePlayersMatchStatus(activeMatch.getAllPlayers(), activeMatch.getMatchId(), true);
    }

    private static void cancelMatch(ActiveMatch activeMatch, String reason) {
        DiscordLogger logger = plugin.getDiscordBot().getLogger();

        activeMatch.setStatus(ActiveMatch.MatchStatus.CANCELLED);

        logger.matchEvent(activeMatch.getMatchId(), "Match cancelado",
                reason, activeMatch.getAllPlayers().size());

        announceToPlayers(activeMatch.getAllPlayers(),
                PREFIX + "§cPartida cancelada §8• §7" + reason);

        updatePlayersMatchStatus(activeMatch.getAllPlayers(), null, false);

        if (activeMatch.getMapVoting() != null) {
            try { activeMatch.getMapVoting().cancelVoting(); } catch (Exception ignored) {}
        }

        activeMatch.cleanup();
        ReadySystem.clearMatchVotes(activeMatch.getMatchId());
    }

    private static void announceToPlayers(List<PlayerData> players, String message) {
        for (PlayerData playerData : players) {
            try {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(message);
                }
            } catch (Exception ignored) {}
        }
    }

    private static void updatePlayersMatchStatus(List<PlayerData> players, String matchId, boolean inMatch) {
        for (PlayerData player : players) {
            player.setInMatch(inMatch);
            player.setCurrentMatchId(matchId);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (PlayerData player : players) {
                try {
                    DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), inMatch, matchId);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error actualizando BD para " +
                            safeShort(player.getMinecraftUuid()) + ": " + e.getMessage());
                }
            }
        });
    }

    private static String safeShort(String uuid) {
        if (uuid == null) return "null";
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }
}