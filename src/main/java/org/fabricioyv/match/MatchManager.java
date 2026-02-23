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
import org.fabricioyv.queue.QueueManager;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class MatchManager {

    private static final RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
    private static final String PREFIX = "§8[§6Keke Ranked§8]§r ";

    private static final int MAX_GUILD_RESOLVE_ATTEMPTS = 10;
    private static final long GUILD_RESOLVE_RETRY_TICKS = 20L;

    /**
     * Flujo NUEVO:
     * - 5v5/8v8: Proponer capitanes -> RR -> Picks -> Map Selection -> cycle -> flujo normal
     * - 2v2: balance -> Map Selection -> cycle -> flujo normal
     */
    public static void startMatch(List<PlayerData> players) {
        if (players == null || players.isEmpty()) return;

        if (!MatchState.startMatch()) {
            try {
                if (plugin.getDiscordBot() != null && plugin.getDiscordBot().getLogger() != null) {
                    plugin.getDiscordBot().getLogger().warning("MatchManager", "Ya hay una partida activa. Ignorando startMatch().");
                }
            } catch (Exception ignored) {}
            return;
        }

        startMatchInternal(players, 0);
    }

    private static void startMatchInternal(final List<PlayerData> players, final int attempt) {
        ActiveMatch activeMatch = null;

        try {
            if (plugin.getDiscordBot() == null) {
                Bukkit.getLogger().warning("[MatchManager] DiscordBot es null, no se puede iniciar match.");
                MatchState.endMatch();
                return;
            }

            final DiscordLogger logger = plugin.getDiscordBot().getLogger();
            JDA jda = plugin.getDiscordBot().getJda();

            if (jda == null) {
                if (attempt < MAX_GUILD_RESOLVE_ATTEMPTS) {
                    if (logger != null) {
                        logger.warning("MatchManager", "JDA aún es null. Reintentando resolve de guild (" + (attempt + 1) + "/" + MAX_GUILD_RESOLVE_ATTEMPTS + ")");
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
                        int guilds = 0;
                        try { guilds = jda.getGuilds().size(); } catch (Exception ignored) {}
                        logger.warning("MatchManager", "No se pudo resolver Guild (guilds=" + guilds + "). Reintentando (" + (attempt + 1) + "/" + MAX_GUILD_RESOLVE_ATTEMPTS + ")");
                    }
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() {
                            startMatchInternal(players, attempt + 1);
                        }
                    }, GUILD_RESOLVE_RETRY_TICKS);
                    return;
                }

                if (logger != null) logger.error("MatchManager", "No se encontró guild. Cancelando match.");
                MatchState.endMatch();
                return;
            }

            String matchId = generateMatchId();

            if (logger != null) {
                logger.matchEvent(matchId, "Match Iniciado", "Preparando partida (players=" + players.size() + ")", players.size());
            }

            activeMatch = new ActiveMatch(matchId, players, jda, guild, plugin, logger);

            if (!validatePlayersConnection(players, logger)) {
                if (logger != null) logger.warning("Match cancelado", "Algunos jugadores se desconectaron en la preparación");
                cancelMatch(activeMatch, "Uno o más jugadores se desconectaron durante la preparación");
                return;
            }

            int count = activeMatch.getAllPlayers() == null ? 0 : activeMatch.getAllPlayers().size();

            if (count == 4 || "2v2".equalsIgnoreCase(activeMatch.getMatchType())) {
                activeMatch.balanceTeams();
                beginMapSelection(activeMatch, logger);
                return;
            }

            // 5v5 / 8v8: Picks (incluye RR). Al terminar picks, CaptainPickSystem llama beginMapSelection().
            activeMatch.setStatus(ActiveMatch.MatchStatus.PREPARING);
            CaptainPickSystem.startPickPhase(activeMatch, logger);

        } catch (Exception e) {
            try {
                if (plugin.getDiscordBot() != null && plugin.getDiscordBot().getLogger() != null) {
                    plugin.getDiscordBot().getLogger().systemError("MatchManager", "Error crítico iniciando partida", e.getMessage());
                }
            } catch (Exception ignored) {}

            e.printStackTrace();

            if (activeMatch != null) {
                cancelMatch(activeMatch, "Error crítico iniciando partida");
            } else {
                MatchState.endMatch();
            }
        }
    }

    private static String generateMatchId() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        return "match-" + LocalDateTime.now().format(formatter);
    }

    private static Guild resolveGuild(JDA jda, List<PlayerData> players) {
        // 1) Intentar por guildId (si existe)
        if (players != null) {
            for (PlayerData pd : players) {
                if (pd == null) continue;

                String gid = null;
                try {
                    // No obligamos la existencia del método en compile-time (por seguridad)
                    Method m = pd.getClass().getMethod("getGuildId");
                    Object o = m.invoke(pd);
                    if (o != null) gid = String.valueOf(o).trim();
                } catch (Exception ignored) {}

                if (gid != null && !gid.isEmpty()) {
                    try {
                        Guild g = jda.getGuildById(gid);
                        if (g != null) return g;
                    } catch (Exception ignored) {}
                }
            }
        }

        // 2) Fallback: primer guild disponible (sin null-check redundante)
        try {
            List<Guild> guilds = jda.getGuilds();
            return guilds.isEmpty() ? null : guilds.get(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean validatePlayersConnection(List<PlayerData> players, DiscordLogger logger) {
        int connectedCount = 0;

        if (players == null) return false;

        for (PlayerData playerData : players) {
            try {
                if (playerData == null) continue;
                UUID u = parseUuid(playerData.getMinecraftUuid());
                if (u == null) continue;

                Player player = Bukkit.getPlayer(u);
                if (player != null && player.isOnline()) connectedCount++;
            } catch (Exception ignored) {}
        }

        if (logger != null) {
            try { logger.info("Validación", connectedCount + "/" + players.size() + " conectados"); } catch (Exception ignored) {}
        }

        return connectedCount == players.size();
    }

    /** Se llama DESPUÉS de picks (o directo en 2v2). */
    public static void beginMapSelection(ActiveMatch activeMatch, DiscordLogger logger) {
        startMapVoting(activeMatch, logger);
    }

    /** Se llama DESPUÉS de cycle map para seguir el flujo normal. */
    public static void continueNormalFlow(ActiveMatch activeMatch, DiscordLogger logger) {
        continueWithNormalFlow(activeMatch, logger);
    }

    private static void startMapVoting(final ActiveMatch activeMatch, final DiscordLogger logger) {
        String mode = MapManager.getSelectionMode();

        if ("RANDOM".equalsIgnoreCase(mode)) {
            String map = MapManager.getRandomMap(MapManager.normalizeMatchTypePublic(activeMatch.getMatchType()));
            onMapSelected(map, activeMatch, logger);
            return;
        }

        if ("VETO".equalsIgnoreCase(mode)) {
            activeMatch.setStatus(ActiveMatch.MatchStatus.MAP_VOTING);

            MapVeto veto = new MapVeto(plugin, logger, activeMatch);
            activeMatch.setMapVeto(veto);

            veto.startVeto(new MapVoting.VotingCompleteCallback() {
                @Override
                public void onVotingComplete(String selectedMap) {
                    onMapSelected(selectedMap, activeMatch, logger);
                }
            });
            return;
        }

        // Default: VOTING
        activeMatch.setStatus(ActiveMatch.MatchStatus.MAP_VOTING);

        MapVoting mapVoting = new MapVoting(plugin, logger, activeMatch.getAllPlayers(), activeMatch.getMatchType());
        activeMatch.setMapVoting(mapVoting);

        mapVoting.startVoting(new MapVoting.VotingCompleteCallback() {
            @Override
            public void onVotingComplete(String selectedMap) {
                onMapSelected(selectedMap, activeMatch, logger);
            }
        });
    }

    private static void onMapSelected(String mapName, ActiveMatch activeMatch, DiscordLogger logger) {
        if (mapName == null || mapName.trim().isEmpty()) {
            if (logger != null) logger.warning("Mapa", "Mapa inválido, usando random");
            mapName = MapManager.getRandomMap(MapManager.normalizeMatchTypePublic(activeMatch.getMatchType()));
        }

        activeMatch.setSelectedMap(mapName);

        if (logger != null) {
            logger.matchEvent(activeMatch.getMatchId(), "Mapa Seleccionado", "Se seleccionó: " + mapName, activeMatch.getAllPlayers().size());
        }

        cycleMap(mapName, activeMatch, logger);
    }

    private static void cycleMap(final String mapName, final ActiveMatch activeMatch, final DiscordLogger logger) {
        try {
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sn " + mapName);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cycle 1");
                }
            });

            if (logger != null) logger.info("Mapa", "Ciclando a: " + mapName);

            int delaySec = getIntStaticFromMapManager("getMapCycleDelaySeconds", 6);
            long delayTicks = Math.max(1, delaySec) * 20L;

            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    continueWithNormalFlow(activeMatch, logger);
                }
            }, delayTicks);

        } catch (Exception e) {
            if (logger != null) logger.logError("Error ciclando mapa: " + mapName, e);
            cancelMatch(activeMatch, "Error ciclando mapa");
        }
    }

    private static void continueWithNormalFlow(final ActiveMatch activeMatch, final DiscordLogger logger) {
        try {
            if (activeMatch.getBlueTeamChannel() == null && activeMatch.getRedTeamChannel() == null) {
                if (logger != null) logger.info("Discord", "Creando canales de equipo");
                activeMatch.createTeamChannels();
            }

            activeMatch.assignPlayersInMinecraft(); // <--- Aquí se les mueve a su canal de Discord

            // [NUEVO] Limpiamos la sesión de picks, lo que borrará el canal temporal de voz "🎯 Picks"
            CaptainPickSystem.cleanupSession(activeMatch.getMatchId());

            startFinalCountdown(activeMatch, logger);

        } catch (Exception e) {
            if (logger != null) logger.logError("Error en flujo normal", e);
            cancelMatch(activeMatch, "Error técnico en el flujo de la partida");
        }
    }

    private static void startFinalCountdown(final ActiveMatch activeMatch, final DiscordLogger logger) {
        int totalSeconds = getIntStaticFromMapManager("getFinalCountdownDurationSeconds", 30);
        if (totalSeconds <= 0) totalSeconds = 30;

        final int totalSecondsFinal = totalSeconds;

        if (logger != null) logger.info("Countdown", "Iniciando countdown final (" + totalSecondsFinal + "s) para " + activeMatch.getMatchId());

        announceToPlayers(activeMatch.getAllPlayers(),
                PREFIX + "§7Equipos listos. Iniciando en §f" + totalSecondsFinal + "§7s...");

        new BukkitRunnable() {
            int countdown = totalSecondsFinal;

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

        // VETO inteligente: registrar como "reciente" solo cuando el match inicia oficial
        try {
            String type = MapManager.normalizeMatchTypePublic(activeMatch.getMatchType());
            String map = activeMatch.getSelectedMap();

            if (map != null && !map.trim().isEmpty()) {
                List<String> recent = MapManager.getRecentMaps(type);
                String last = (recent == null || recent.isEmpty()) ? null : recent.get(recent.size() - 1);
                if (last == null || !last.equalsIgnoreCase(map)) {
                    MapManager.recordMapUsage(type, map);
                }
            }
        } catch (Exception ignored) {}

        try {
            MatchLogsIntegration.startMatchTracking(
                    activeMatch.getMatchId(),
                    activeMatch.getTeams(),
                    activeMatch.getMatchType(),
                    activeMatch.getSelectedMap()
            );
        } catch (Exception e) {
            if (logger != null) logger.warning("Logs", "No se pudo iniciar tracking: " + e.getMessage());
        }

        try {
            MatchLogsIntegration.logMatchStart(
                    activeMatch.getMatchId(),
                    activeMatch.getSelectedMap(),
                    activeMatch.getAllPlayers().size()
            );
        } catch (Exception ignored) {}

        if (logger != null) {
            logger.matchEvent(activeMatch.getMatchId(), "Partida Oficial Iniciada",
                    "MatchType: " + activeMatch.getMatchType() + " en " + activeMatch.getSelectedMap(),
                    activeMatch.getAllPlayers().size());
        }

        updatePlayersMatchStatus(activeMatch.getAllPlayers(), activeMatch.getMatchId(), true);
    }

    private static void cancelMatch(ActiveMatch activeMatch, String reason) {
        DiscordLogger logger = null;
        try { logger = plugin.getDiscordBot().getLogger(); } catch (Exception ignored) {}

        activeMatch.setStatus(ActiveMatch.MatchStatus.CANCELLED);

        if (logger != null) {
            logger.matchEvent(activeMatch.getMatchId(), "Match cancelado", reason, activeMatch.getAllPlayers().size());
        }

        announceToPlayers(activeMatch.getAllPlayers(),
                PREFIX + "§cPartida cancelada §8- §7" + reason);

        updatePlayersMatchStatus(activeMatch.getAllPlayers(), null, false);

        if (activeMatch.getMapVoting() != null) {
            try { activeMatch.getMapVoting().cancelVoting(); } catch (Exception ignored) {}
        }
        if (activeMatch.getMapVeto() != null) {
            try { activeMatch.getMapVeto().cancelVeto(); } catch (Exception ignored) {}
        }

        // [NUEVO] Asegurarnos de limpiar el canal de picks si se cancela la partida en esta fase
        CaptainPickSystem.cleanupSession(activeMatch.getMatchId());

        try { activeMatch.cleanup(); } catch (Exception ignored) {}
        try { ReadySystem.clearMatchVotes(activeMatch.getMatchId()); } catch (Exception ignored) {}

        MatchState.endMatch();
    }

    private static void announceToPlayers(List<PlayerData> players, String message) {
        if (players == null) return;

        for (PlayerData playerData : players) {
            try {
                UUID u = parseUuid(playerData.getMinecraftUuid());
                if (u == null) continue;
                Player mcPlayer = Bukkit.getPlayer(u);
                if (mcPlayer != null && mcPlayer.isOnline()) mcPlayer.sendMessage(message);
            } catch (Exception ignored) {}
        }
    }

    private static void updatePlayersMatchStatus(final List<PlayerData> players, final String matchId, final boolean inMatch) {
        if (players == null) return;

        for (PlayerData player : players) {
            try {
                player.setInMatch(inMatch);
                player.setLastQueueType(QueueManager.getQueueTypeFromSize(players.size()));
                player.setCurrentMatchId(matchId);
            } catch (Exception ignored) {}
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                for (PlayerData player : players) {
                    try {
                        DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), inMatch, matchId);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error actualizando BD para " + safeShort(player.getMinecraftUuid()) + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    private static String safeShort(String uuid) {
        if (uuid == null) return "null";
        return uuid.length() <= 8 ? uuid : uuid.substring(0, 8);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}

        if (s.length() == 32) {
            String dashed = s.substring(0, 8) + "-" +
                    s.substring(8, 12) + "-" +
                    s.substring(12, 16) + "-" +
                    s.substring(16, 20) + "-" +
                    s.substring(20);
            try { return UUID.fromString(dashed); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private static int getIntStaticFromMapManager(String methodName, int fallback) {
        try {
            Method m = MapManager.class.getMethod(methodName);
            Object o = m.invoke(null);
            if (o instanceof Number) return ((Number) o).intValue();
        } catch (Exception ignored) {}
        return fallback;
    }
    /**
     * Método para iniciar la partida desde el QueueManager.
     * NO verifica MatchState.startMatch() porque el QueueManager ya reservó el estado
     * antes de iniciar el conteo regresivo.
     */
    public static void startMatchFromQueue(List<PlayerData> players) {
        if (players == null || players.isEmpty()) return;

        // Si QueueManager YA reservó (matchActive=true), no re-intentamos startMatch().
        // Si NO está reservado por alguna razón, lo intentamos reservar aquí.
        if (!MatchState.isMatchActive()) {
            if (!MatchState.startMatch()) {
                try {
                    if (plugin.getDiscordBot() != null && plugin.getDiscordBot().getLogger() != null) {
                        plugin.getDiscordBot().getLogger().warning("MatchManager",
                                "Ya hay una partida activa. Ignorando startMatchFromQueue().");
                    }
                } catch (Exception ignored) {}
                return;
            }
        }

        startMatchInternal(players, 0);
    }
}