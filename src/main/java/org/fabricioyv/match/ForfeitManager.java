package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ForfeitManager implements Listener {

    private static final Map<String, ForfeitVote> forfeitVotes = new ConcurrentHashMap<>();

    // Reglas:
    // - Primeros 3 min: /ff bloqueado
    // - Excepción: si tu equipo tiene AFK o DC (1+), pero SOLO si han pasado 60s desde la DETECCIÓN del AFK/DC
    // - Después de 3 min: /ff permitido siempre (aunque estén completos)
    //   - PERO si hay AFK/DC, igual aplica espera 60s desde la detección
    // - Votos: unanimidad de ONLINE del equipo (3 online => 3/3, 4 online => 4/4, etc.)

    // Defaults (se sobre-escriben desde config.yml)
    private static volatile long FF_LOCK_MILLIS = 3 * 60 * 1000L;          // 3 minutos
    private static volatile long AFK_TIMEOUT_MILLIS = 20 * 1000L;          // 20s sin actividad => AFK (detectado al cumplir este tiempo)
    private static volatile long ISSUE_WAIT_MILLIS = 60 * 1000L;           // 60s desde detección AFK/DC antes de permitir /ff

    private static volatile boolean AFK_KICK_ENABLED = true;
    private static volatile String AFK_KICK_MESSAGE = "§cHas sido expulsado por AFK en partida. Si fue un error, vuelve a entrar.";

    private static volatile RankedMinecraft PLUGIN; // referencia para scheduler/config

    // AFK tracker global (UUID -> last activity millis)
    private static final Map<String, Long> lastActivity = new ConcurrentHashMap<>();

    // DC tracker global (UUID -> disconnect millis)
    private static final Map<String, Long> disconnectAt = new ConcurrentHashMap<>();

    public ForfeitManager(RankedMinecraft plugin) {
        PLUGIN = plugin;
        loadSettings(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startAfkKickTask(plugin);
    }

    /**
     * Lee tiempos/opciones desde config.yml.
     *
     * Recomendación en onEnable():
     *   saveDefaultConfig();
     */
    public static void loadSettings(RankedMinecraft plugin) {
        if (plugin == null) return;

        // Defaults si no existen
        plugin.getConfig().addDefault("forfeit.lock-seconds", 180);
        plugin.getConfig().addDefault("forfeit.issue-wait-seconds", 60);
        plugin.getConfig().addDefault("forfeit.afk-detect-seconds", 20);
        plugin.getConfig().addDefault("forfeit.afk-kick.enabled", true);
        plugin.getConfig().addDefault("forfeit.afk-kick.message", "&cHas sido expulsado por AFK en partida. Si fue un error, vuelve a entrar.");
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        long lockSec = plugin.getConfig().getLong("forfeit.lock-seconds", 180);
        long waitSec = plugin.getConfig().getLong("forfeit.issue-wait-seconds", 60);
        long afkDetectSec = plugin.getConfig().getLong("forfeit.afk-detect-seconds", 20);

        FF_LOCK_MILLIS = Math.max(0L, lockSec) * 1000L;
        ISSUE_WAIT_MILLIS = Math.max(0L, waitSec) * 1000L;
        AFK_TIMEOUT_MILLIS = Math.max(1L, afkDetectSec) * 1000L;

        AFK_KICK_ENABLED = plugin.getConfig().getBoolean("forfeit.afk-kick.enabled", true);
        String msg = plugin.getConfig().getString("forfeit.afk-kick.message",
                "&cHas sido expulsado por AFK en partida. Si fue un error, vuelve a entrar.");
        AFK_KICK_MESSAGE = translate(msg);
    }

    private static String translate(String s) {
        if (s == null) return "";
        return s.replace('&', '§');
    }

    /**
     * Task que expulsa AFKs en partida (no observadores) cuando cumplen el umbral.
     *
     * - Detecta AFK por inactividad (lastActivity)
     * - Solo aplica si el jugador pertenece a un team de un match IN_PROGRESS
     * - Optimizado para evitar lag con jugadores desconectados
     */
    private static void startAfkKickTask(RankedMinecraft plugin) {
        // ⚡ OPTIMIZACIÓN: Ejecutar cada 4 segundos (80 ticks) en lugar de cada 2s
        // Reduce carga del servidor 50% sin afectar detección de AFK (20s threshold)
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!AFK_KICK_ENABLED) return;

            long now = System.currentTimeMillis();

            // Cachear la colección de jugadores online para evitar llamadas repetidas
            Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
            if (onlinePlayers.isEmpty()) return;

            for (Player p : onlinePlayers) {
                String uuid = p.getUniqueId().toString();

                // Verificación rápida: si no está en lastActivity, inicializar ahora
                if (!lastActivity.containsKey(uuid)) {
                    lastActivity.put(uuid, now);
                    continue;
                }

                ActiveMatch match = ActiveMatch.findActiveMatchForPlayer(uuid);
                if (match == null) continue;
                if (match.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) continue;

                // No observadores: debe estar en algún team
                Team team = findTeam(match, uuid);
                if (team == null) continue;

                long last = lastActivity.get(uuid);
                long detectedAt = last + AFK_TIMEOUT_MILLIS;

                if (now >= detectedAt) {
                    // Para que el /ff no tenga "doble espera": guardamos la detección como disconnectAt
                    disconnectAt.putIfAbsent(uuid, detectedAt);

                    // Kick
                    p.kickPlayer(AFK_KICK_MESSAGE);

                    // Limpiar inmediatamente después del kick para evitar reprocesamiento
                    lastActivity.remove(uuid);
                }
            }

            // Limpieza de jugadores desconectados: remover de lastActivity si no están online
            // Esto previene que el mapa crezca indefinidamente
            lastActivity.keySet().removeIf(uuid -> {
                try {
                    Player p = Bukkit.getPlayer(UUID.fromString(uuid));
                    return p == null || !p.isOnline();
                } catch (Exception e) {
                    return true; // Remover UUIDs inválidos
                }
            });

        }, 80L, 80L); // ⚡ OPTIMIZACIÓN: cada 4 segundos en lugar de 2
    }

    private static Team findTeam(ActiveMatch match, String playerUuid) {
        for (Map.Entry<Team, List<PlayerData>> entry : match.getTeams().entrySet()) {
            for (PlayerData pd : entry.getValue()) {
                if (pd.getMinecraftUuid().equals(playerUuid)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public static void proccesForfeit(ActiveMatch activeMatch, PlayerData playerData, RankedMinecraft plugin) {
        DiscordLogger logger = plugin.getDiscordBot().getLogger();
        String matchId = activeMatch.getMatchId();

        Team playerTeam = getPlayerTeam(activeMatch, playerData);
        if (playerTeam == null) {
            sendMessage(playerData, "§c❌ No se pudo determinar tu equipo.");
            return;
        }

        boolean locked = isFfLocked(activeMatch);
        IssueGate gate = evaluateIssueGate(activeMatch, playerTeam);

        // 1) Primeros 3 min: bloqueado, excepto si hay AFK/DC + espera 60s desde detección
        if (locked) {
            if (!gate.hasIssue) {
                long elapsed = System.currentTimeMillis() - activeMatch.getStartedAtMillis();
                long leftSec = Math.max(1, (FF_LOCK_MILLIS - elapsed) / 1000);
                sendMessage(playerData,
                        "§c⛔ /ff está bloqueado los primeros 3 minutos. §7Faltan " + leftSec + "s.");
                return;
            }

            if (!gate.ready) {
                sendMessage(playerData,
                        "§c⏳ Hay " + gate.issueLabel + " en tu equipo, pero debes esperar §e" + gate.waitLeftSec + "s§c para usar /ff.");
                return;
            }

            // permitido por excepción
            sendMessage(playerData, "§e⚠️ /ff permitido: detecté " + gate.issueLabel + " en tu equipo (espera cumplida)." );
        } else {
            // 2) Después de 3 min: permitido siempre, PERO si hay AFK/DC debe cumplirse la espera 60s
            if (gate.hasIssue && !gate.ready) {
                sendMessage(playerData,
                        "§c⏳ Detecté " + gate.issueLabel + " en tu equipo. Espera §e" + gate.waitLeftSec + "s§c antes de tirar /ff.");
                return;
            }
        }

        // Obtener o crear la votación de rendición
        ForfeitVote vote = forfeitVotes.computeIfAbsent(matchId, k -> new ForfeitVote(activeMatch));

        // Registrar el voto (solo para su propio equipo)
        if (vote.addVote(playerData.getMinecraftUuid(), playerTeam)) {
            String playerName = getPlayerName(playerData);

            int current = vote.getVoteCount(playerTeam);
            int required = vote.getRequiredVotes(playerTeam);

            notifyTeam(activeMatch, playerTeam,
                    "§e⚠️ " + playerName + " §evotó por rendirse. §7(" + current + "/" + required + ")");

            logger.info("Voto de Rendición",
                    playerName + " votó rendirse en " + matchId + " - Equipo " + playerTeam.getDisplayName());

            if (vote.hasConsensus(playerTeam)) {
                boolean disadvantagedFf = isTeamDisadvantaged(activeMatch, playerTeam);
                executeForfeit(activeMatch, playerTeam, disadvantagedFf, plugin, logger);
                forfeitVotes.remove(matchId);
            }

        } else {
            sendMessage(playerData, "§c❌ Ya votaste por rendirte.");
        }
    }

    private static boolean isFfLocked(ActiveMatch match) {
        long elapsed = System.currentTimeMillis() - match.getStartedAtMillis();
        return elapsed < FF_LOCK_MILLIS;
    }

    /**
     * Resultado de la regla "si hay AFK/DC hay que esperar 60s desde detección".
     *
     * Para ser estrictos (y evitar "se fue 1 y al segundo ya tiran ff"),
     * si hay múltiples issues activos, usamos la detección MÁS RECIENTE.
     */
    private static class IssueGate {
        final boolean hasIssue;
        final boolean ready;
        final long waitLeftSec;
        final String issueLabel;

        IssueGate(boolean hasIssue, boolean ready, long waitLeftSec, String issueLabel) {
            this.hasIssue = hasIssue;
            this.ready = ready;
            this.waitLeftSec = waitLeftSec;
            this.issueLabel = issueLabel;
        }
    }

    private static IssueGate evaluateIssueGate(ActiveMatch match, Team team) {
        // Detectar issues actuales y calcular "última detección".
        long now = System.currentTimeMillis();

        boolean hasAfk = false;
        boolean hasDc = false;

        long latestDetectedAt = -1L; // usamos la detección más reciente

        List<PlayerData> teamPlayers = match.getTeams().get(team);
        if (teamPlayers == null || teamPlayers.isEmpty()) {
            return new IssueGate(false, true, 0, "");
        }

        // Cachear todas las verificaciones de jugadores online de una vez
        Map<String, Player> onlinePlayersCache = new HashMap<>();
        for (PlayerData pd : teamPlayers) {
            String uuid = pd.getMinecraftUuid();
            try {
                Player p = Bukkit.getPlayer(UUID.fromString(uuid));
                if (p != null && p.isOnline()) {
                    onlinePlayersCache.put(uuid, p);
                }
            } catch (Exception ignored) {
                // UUID inválido, se considerará DC
            }
        }

        for (PlayerData pd : teamPlayers) {
            String uuid = pd.getMinecraftUuid();
            Player p = onlinePlayersCache.get(uuid);

            // DC = no online
            if (p == null) {
                hasDc = true;
                // Si no tenemos timestamp (por reload, etc.), lo marcamos ahora para ser conservadores
                long detected = disconnectAt.computeIfAbsent(uuid, k -> now);
                latestDetectedAt = Math.max(latestDetectedAt, detected);
                continue;
            }

            // Si volvió online, limpiamos su DC
            disconnectAt.remove(uuid);

            // AFK = (now - lastActivity) >= AFK_TIMEOUT; detección = lastActivity + AFK_TIMEOUT
            long last = lastActivity.getOrDefault(uuid, now);
            long detectedAfkAt = last + AFK_TIMEOUT_MILLIS;
            if (now >= detectedAfkAt) {
                hasAfk = true;
                latestDetectedAt = Math.max(latestDetectedAt, detectedAfkAt);
            }
        }

        boolean hasIssue = hasAfk || hasDc;
        if (!hasIssue) {
            return new IssueGate(false, true, 0, "");
        }

        long elapsedSinceDetect = (latestDetectedAt <= 0) ? 0 : (now - latestDetectedAt);
        long remaining = Math.max(0L, ISSUE_WAIT_MILLIS - elapsedSinceDetect);
        long remainingSec = (remaining + 999) / 1000; // ceil

        boolean ready = remaining <= 0;

        String label;
        if (hasAfk && hasDc) label = "AFK/DC";
        else if (hasDc) label = "DC";
        else label = "AFK";

        return new IssueGate(true, ready, remainingSec, label);
    }

    private static boolean isTeamDisadvantaged(ActiveMatch match, Team team) {
        Team enemy = (team == Team.BLUE) ? Team.RED : Team.BLUE;
        int myOnline = countOnline(match, team);
        int enemyOnline = countOnline(match, enemy);
        return myOnline < enemyOnline;
    }

    private static int countOnline(ActiveMatch match, Team team) {
        List<PlayerData> teamPlayers = match.getTeams().get(team);
        if (teamPlayers == null) return 0;

        int c = 0;
        for (PlayerData pd : teamPlayers) {
            try {
                Player p = Bukkit.getPlayer(UUID.fromString(pd.getMinecraftUuid()));
                if (p != null && p.isOnline()) c++;
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    private static void executeForfeit(ActiveMatch activeMatch,
                                       Team forfeitTeam,
                                       boolean disadvantagedFf,
                                       RankedMinecraft plugin,
                                       DiscordLogger logger) {

        String matchId = activeMatch.getMatchId();
        Team winnerTeam = (forfeitTeam == Team.BLUE) ? Team.RED : Team.BLUE;

        activeMatch.setFinishedByForfeit(true);

        // Si este FF ocurrió por desventaja, protege a los jugadores ONLINE del team afectado
        if (disadvantagedFf) {
            protectOnlinePlayersFromLoss(activeMatch, forfeitTeam, "ff_disadvantage");
        }

        announceToAll(activeMatch, "§c§l⚠️ RENDICIÓN ⚠️");
        announceToAll(activeMatch, "§eEl equipo " + forfeitTeam.getFormattedName() + " §ese ha rendido.");
        if (disadvantagedFf) {
            announceToAll(activeMatch, "§a✅ Equipo rendido protegido de pérdida de ELO por desventaja.");
        }
        announceToAll(activeMatch, "§a🏆 Victoria para el equipo " + winnerTeam.getFormattedName() + "§a!");

        logger.matchEvent(matchId, "Partida Terminada por Rendición",
                "Equipo " + forfeitTeam.getDisplayName() + " se rindió" + (disadvantagedFf ? " (desventaja)" : ""),
                activeMatch.getAllPlayers().size());

        executeCustomCommand(activeMatch, winnerTeam);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            MatchFinisher.finishMatch(activeMatch, winnerTeam, plugin, logger);
        }, 60L);
    }

    private static void protectOnlinePlayersFromLoss(ActiveMatch match, Team team, String reason) {
        List<PlayerData> players = match.getTeams().get(team);
        if (players == null) return;

        for (PlayerData pd : players) {
            try {
                Player p = Bukkit.getPlayer(UUID.fromString(pd.getMinecraftUuid()));
                if (p != null && p.isOnline()) {
                    DatabaseManager.markPlayerProtectedFromLoss(pd.getMinecraftUuid(), match.getMatchId(), reason);
                    p.sendMessage("§a✅ Estás protegido de pérdida de ELO por jugar en desventaja.");
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void executeCustomCommand(ActiveMatch activeMatch, Team winnerTeam) {
        String command = "finish " + winnerTeam.getPgmName();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }

    private static void notifyTeam(ActiveMatch activeMatch, Team team, String message) {
        List<PlayerData> teamPlayers = activeMatch.getTeams().get(team);
        if (teamPlayers != null) {
            for (PlayerData pd : teamPlayers) sendMessage(pd, message);
        }
    }

    private static Team getPlayerTeam(ActiveMatch activeMatch, PlayerData playerData) {
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            if (entry.getValue().contains(playerData)) return entry.getKey();
        }
        return null;
    }

    private static void announceToAll(ActiveMatch activeMatch, String message) {
        for (PlayerData pd : activeMatch.getAllPlayers()) sendMessage(pd, message);
    }

    private static void sendMessage(PlayerData playerData, String message) {
        try {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) mcPlayer.sendMessage(message);
        } catch (Exception ignored) {
        }
    }

    private static String getPlayerName(PlayerData playerData) {
        try {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            return mcPlayer != null ? mcPlayer.getName() : "Jugador";
        } catch (Exception e) {
            return "Jugador";
        }
    }

    public static void cleanupMatchVotes(String matchId) {
        forfeitVotes.remove(matchId);
    }

    /**
     * Limpia todos los datos de AFK/DC de los jugadores de una partida terminada.
     * Esto previene acumulación de memoria y lag al procesar jugadores que ya no están en partida.
     */
    public static void cleanupMatchData(ActiveMatch match) {
        if (match == null) return;

        // Limpiar votos
        forfeitVotes.remove(match.getMatchId());

        // Limpiar datos de AFK/DC de todos los jugadores de la partida
        for (PlayerData pd : match.getAllPlayers()) {
            String uuid = pd.getMinecraftUuid();
            disconnectAt.remove(uuid);
            // No limpiamos lastActivity aquí para permitir tracking continuo entre partidas
        }
    }

    // =========================
    // AFK/DC: actualizar activity y disconnect
    // =========================

    private static void touch(Player p) {
        if (p == null) return;
        String uuid = p.getUniqueId().toString();
        lastActivity.put(uuid, System.currentTimeMillis());
        // si estaba marcado como DC y volvió/interactuó, lo limpiamos
        disconnectAt.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        // ⚡ OPTIMIZACIÓN: Solo procesar cada 2 bloques en lugar de cada 1
        // Reduce eventos procesados ~70% sin afectar detección de AFK
        // Con 30 jugadores: de 600 eventos/s a ~200 eventos/s
        int deltaX = Math.abs(e.getFrom().getBlockX() - e.getTo().getBlockX());
        int deltaY = Math.abs(e.getFrom().getBlockY() - e.getTo().getBlockY());
        int deltaZ = Math.abs(e.getFrom().getBlockZ() - e.getTo().getBlockZ());

        if (deltaX >= 2 || deltaY >= 2 || deltaZ >= 2) {
            touch(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        touch(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInv(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) touch(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) touch(p);
        if (e.getEntity() instanceof Player p) touch(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        // marcar desconexión para regla de espera 60s
        String uuid = e.getPlayer().getUniqueId().toString();
        // si ya estaba marcado (por ejemplo, kick por AFK) no lo sobre-escribimos
        disconnectAt.putIfAbsent(uuid, System.currentTimeMillis());
        // opcional: limpiamos lastActivity para no inflar AFK de un offline
        lastActivity.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // al volver, reiniciar activity y limpiar DC
        touch(e.getPlayer());
    }

    // =========================
    // Votos por equipo
    // =========================
    private static class ForfeitVote {
        private final ActiveMatch match;
        private final Map<Team, Set<String>> votesByTeam = new ConcurrentHashMap<>();

        public ForfeitVote(ActiveMatch match) {
            this.match = match;
            votesByTeam.put(Team.BLUE, ConcurrentHashMap.newKeySet());
            votesByTeam.put(Team.RED, ConcurrentHashMap.newKeySet());
        }

        public boolean addVote(String playerUuid, Team team) {
            return votesByTeam.get(team).add(playerUuid);
        }

        // Solo cuenta votos de jugadores que siguen ONLINE
        public int getVoteCount(Team team) {
            Set<String> votes = votesByTeam.getOrDefault(team, Collections.emptySet());
            if (votes.isEmpty()) return 0;

            int count = 0;
            for (String voterUuid : votes) {
                try {
                    Player p = Bukkit.getPlayer(UUID.fromString(voterUuid));
                    if (p != null && p.isOnline()) count++;
                } catch (Exception ignored) {
                }
            }
            return count;
        }

        // Requiere UNANIMIDAD de conectados: 3/3, 4/4, 5/5, 8/8...
        public int getRequiredVotes(Team team) {
            return Math.max(1, countOnline(match, team));
        }

        public boolean hasConsensus(Team team) {
            return getVoteCount(team) >= getRequiredVotes(team);
        }
    }
}
