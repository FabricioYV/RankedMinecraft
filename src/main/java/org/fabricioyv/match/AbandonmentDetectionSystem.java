package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.rating.ProgressiveEloCalculator;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AbandonmentDetectionSystem implements Listener {

    private final RankedMinecraft plugin;
    private final DiscordLogger logger;

    private final Map<String, DisconnectionTracker> disconnectedPlayers = new ConcurrentHashMap<>();

    // "Doble loss" = contar como 2 derrotas (no 2 adicionales)
    private static final int DOUBLE_LOSS_COUNT = 2;

    // Defaults (configurable en config.yml)
    private static volatile long RECONNECTION_GRACE_PERIOD_SECONDS = 120L;
    private static final long EARLY_FF_WINDOW_MILLIS = 3 * 60 * 1000L;

    private static final Map<Integer, AbandonmentPenalty> PENALTY_TIERS = Map.of(
            1, new AbandonmentPenalty(60, 30, "Primer abandono"),
            2, new AbandonmentPenalty(120, 50, "Segundo abandono"),
            3, new AbandonmentPenalty(240, 75, "Tercer abandono"),
            4, new AbandonmentPenalty(480, 100, "Cuarto abandono"),
            5, new AbandonmentPenalty(960, 125, "Quinto abandono"),
            6, new AbandonmentPenalty(1440, 150, "Sexto abandono - Última advertencia"),
            7, new AbandonmentPenalty(-1, 200, "BANEO PERMANENTE")
    );

    public AbandonmentDetectionSystem(RankedMinecraft plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;

        loadSettings(plugin);

        Bukkit.getPluginManager().registerEvents(this, plugin);
        logger.info("Sistema de Abandono", "Sistema de detección de abandono inicializado");
    }

    /**
     * Config:
     * abandonment.reconnect-grace-seconds: tiempo para que el jugador vuelva antes de ser marcado como abandono.
     */
    private static void loadSettings(RankedMinecraft plugin) {
        if (plugin == null) return;

        plugin.getConfig().addDefault("abandonment.reconnect-grace-seconds", 120);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        long grace = plugin.getConfig().getLong("abandonment.reconnect-grace-seconds", 120);
        RECONNECTION_GRACE_PERIOD_SECONDS = Math.max(5L, grace);
    }



    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerUuid = player.getUniqueId().toString();

        ActiveMatch activeMatch = findActiveMatchForPlayer(playerUuid);
        if (activeMatch == null) return;

        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) return;

        boolean earlyDisconnect = isEarlyWindow(activeMatch);

        logger.warning("Desconexión Durante Partida",
                String.format("Jugador %s se desconectó durante partida %s (early=%s)",
                        player.getName(), activeMatch.getMatchId(), earlyDisconnect));

        if (earlyDisconnect) {
            Team team = findPlayerTeam(activeMatch, playerUuid);
            if (team != null) {
                activeMatch.markEarlyDisconnect(team, playerUuid);
            }
        }

        startDisconnectionTracking(playerUuid, activeMatch, player.getName(), earlyDisconnect);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerUuid = event.getPlayer().getUniqueId().toString();
        onPlayerReconnect(playerUuid);
    }

    private boolean isEarlyWindow(ActiveMatch match) {
        long elapsed = System.currentTimeMillis() - match.getStartedAtMillis();
        return elapsed < EARLY_FF_WINDOW_MILLIS;
    }

    private void startDisconnectionTracking(String playerUuid, ActiveMatch activeMatch, String playerName, boolean earlyDisconnect) {
        DisconnectionTracker tracker = new DisconnectionTracker(
                playerUuid,
                activeMatch.getMatchId(),
                LocalDateTime.now(),
                playerName,
                earlyDisconnect
        );

        disconnectedPlayers.put(playerUuid, tracker);

        announceTemporaryDisconnection(activeMatch, playerUuid, playerName);

        new BukkitRunnable() {
            @Override
            public void run() {
                checkForAbandonment(playerUuid, tracker);
            }
        }.runTaskLater(plugin, RECONNECTION_GRACE_PERIOD_SECONDS * 20L);
    }

    private void checkForAbandonment(String playerUuid, DisconnectionTracker tracker) {
        DisconnectionTracker currentTracker = disconnectedPlayers.get(playerUuid);
        if (currentTracker == null || !currentTracker.equals(tracker)) {
            return;
        }

        ActiveMatch activeMatch = ActiveMatch.getActiveMatch(tracker.matchId);

        // CORRECCIÓN: Si la partida fue cancelada (ej. por dogeo de otro) lo ignoramos.
        // Pero si la partida terminó (FINISHED) o sigue en progreso, SÍ aplicamos el castigo
        // porque el jugador nunca se reconectó a tiempo.
        if (activeMatch == null || activeMatch.getStatus() == ActiveMatch.MatchStatus.CANCELLED || activeMatch.getStatus() == ActiveMatch.MatchStatus.PREPARING) {
            disconnectedPlayers.remove(playerUuid);
            return;
        }

        processAbandonment(playerUuid, tracker, activeMatch);
    }

    private void processAbandonment(String playerUuid, DisconnectionTracker tracker, ActiveMatch activeMatch) {
        // Idempotencia defensiva: si ya fue procesado (por reintentos / tareas duplicadas), no aplicar dos veces.
        try {
            if (isAbandonmentAlreadyProcessed(activeMatch.getMatchId(), playerUuid)) {
                disconnectedPlayers.remove(playerUuid);
                return;
            }
        } catch (Exception ignored) {}

        logger.error("Abandono Confirmado",
                String.format("Jugador %s abandonó partida %s tras %d min (early=%s)",
                        tracker.playerName, tracker.matchId,
                        ChronoUnit.MINUTES.between(tracker.disconnectTime, LocalDateTime.now()),
                        tracker.earlyDisconnect));

        try {
            PlayerData playerData = DatabaseManager.getPlayerData(playerUuid);
            if (playerData == null) {
                logger.warning("Datos No Encontrados", "No se encontraron datos para jugador abandono: " + playerUuid);
                disconnectedPlayers.remove(playerUuid);
                return;
            }

            int abandonmentCount = DatabaseManager.getPlayerAbandonmentCount(playerUuid);
            AbandonmentPenalty penalty = calculatePenalty(abandonmentCount + 1);

            // ✅ Punto C: calcular el ELO real aplicado (doble loss según tu cálculo normal)
            int appliedEloPenalty = computeDoubleLossEloPenalty(playerData, activeMatch, penalty.eloPenalty);

            // ✅ Aplicar con el ELO real (no el tier fijo)
            applyAbandonmentPenalty(playerData, penalty, activeMatch, appliedEloPenalty);

            // ✅ Guardar el ELO real aplicado en el historial
            DatabaseManager.recordAbandonment(playerUuid, activeMatch.getMatchId(),
                    appliedEloPenalty, penalty.cooldownMinutes);

            // Best-effort: marcar este jugador como "abandonmentProcessed" para que MatchFinisher lo SKIP.
            // (si DatabaseManager.recordAbandonment ya lo hace, esto es redundante e inocuo)
            markAbandonmentProcessedBestEffort(playerUuid, activeMatch.getMatchId());

            // ✅ Anunciar con el ELO real aplicado
            announceAbandonment(activeMatch, playerData, appliedEloPenalty, penalty);

            protectTeammates(activeMatch, playerData);

            disconnectedPlayers.remove(playerUuid);

        } catch (Exception e) {
            logger.logError("Error procesando abandono", e);
        }
    }

    private int computeDoubleLossEloPenalty(PlayerData offender, ActiveMatch activeMatch, int fallbackTierPenalty) {
        // Abandono: simular “dos derrotas” => pérdida normal * 2.
        // Fallback: tier * 2 si no se puede calcular.
        try {
            if (offender == null) return Math.max(0, fallbackTierPenalty * 2);

            int oldElo = offender.getElo();
            int opponentAvgMMR = (int) Math.round(offender.getMmr());

            ProgressiveEloCalculator.MatchType mt;
            String raw = null;
            try { raw = (activeMatch != null) ? activeMatch.getMatchType() : null; } catch (Exception ignored) {}

            mt = ProgressiveEloCalculator.MatchType.fromKey(raw);
            if (mt == null) mt = ProgressiveEloCalculator.MatchType.RANKED_5V5;

            // Si hay equipos, promedio MMR del rival
            try {
                if (activeMatch != null && activeMatch.getTeams() != null) {
                    Team offenderTeam = findPlayerTeam(activeMatch, offender);
                    if (offenderTeam != null) {
                        Team opponentTeam = (offenderTeam == Team.BLUE) ? Team.RED : Team.BLUE;
                        List<PlayerData> opponents = activeMatch.getTeams().get(opponentTeam);

                        if (opponents != null && !opponents.isEmpty()) {
                            double sum = 0.0;
                            int count = 0;
                            for (PlayerData p : opponents) {
                                if (p == null) continue;
                                try {
                                    sum += p.getMmr();
                                    count++;
                                } catch (Exception ignored) {}
                            }
                            if (count > 0) opponentAvgMMR = (int) Math.round(sum / count);
                        }
                    }
                }
            } catch (Exception ignored) {}

            ProgressiveEloCalculator.EloChange change =
                    ProgressiveEloCalculator.calculateEloChange(oldElo, opponentAvgMMR, false, mt);

            int baseLoss = Math.abs(change.getEloChange());
            if (baseLoss <= 0) baseLoss = Math.max(1, fallbackTierPenalty);

            return Math.max(0, baseLoss * 2);
        } catch (Exception ignored) {
            return Math.max(0, fallbackTierPenalty * 2);
        }
    }

    private void applyAbandonmentPenalty(PlayerData playerData, AbandonmentPenalty penalty, ActiveMatch activeMatch, int appliedEloPenalty) {
        if (penalty.cooldownMinutes == -1) {
            applyPermanentBan(playerData, penalty, activeMatch);
            return;
        }

        int newElo = Math.max(0, playerData.getElo() - Math.max(0, appliedEloPenalty));
        playerData.setElo(newElo);

        long cooldownEndTime = System.currentTimeMillis() + (penalty.cooldownMinutes * 60 * 1000);

        // ✅ SIEMPRE: doble loss
        applyDoubleLosses(playerData);

        List<PlayerData> allPlayers = activeMatch != null ? activeMatch.getAllPlayers() : Collections.emptyList();

        playerData.setInMatch(false);
        playerData.setLastQueueType(QueueManager.getQueueTypeFromSize(allPlayers.size()));
        playerData.setCurrentMatchId(null);

        Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
            try {
                DatabaseManager.updatePlayerElo(playerData.getMinecraftUuid(), newElo);
                DatabaseManager.setPlayerCooldown(playerData.getMinecraftUuid(), cooldownEndTime);
                DatabaseManager.updatePlayerMatchStatus(playerData.getMinecraftUuid(), false, null);
            } catch (Exception e) {
                logger.warning("Async DB Update Failed",
                        "Error actualizando BD para castigo: " + e.getMessage());
            }
        });

        logger.info("Castigo Aplicado",
                String.format("Jugador %s castigado: -%d ELO, %d min cooldown, +2 derrotas",
                        playerData.getMinecraftUuid().substring(0, 8),
                        penalty.eloPenalty,
                        penalty.cooldownMinutes));
    }

    private void applyPermanentBan(PlayerData playerData, AbandonmentPenalty penalty, ActiveMatch activeMatch) {
        int newElo = Math.max(0, playerData.getElo() - penalty.eloPenalty);
        playerData.setElo(newElo);

        long permanentBanTime = 4102444800000L;

        applyDoubleLosses(playerData);

        List<PlayerData> allPlayers = activeMatch != null ? activeMatch.getAllPlayers() : Collections.emptyList();

        playerData.setInMatch(false);
        playerData.setLastQueueType(QueueManager.getQueueTypeFromSize(allPlayers.size()));
        playerData.setCurrentMatchId(null);

        Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
            try {
                DatabaseManager.updatePlayerElo(playerData.getMinecraftUuid(), newElo);
                DatabaseManager.setPlayerCooldown(playerData.getMinecraftUuid(), permanentBanTime);
                DatabaseManager.setPermanentBan(playerData.getMinecraftUuid(), true);
                DatabaseManager.updatePlayerMatchStatus(playerData.getMinecraftUuid(), false, null);
            } catch (Exception e) {
                logger.warning("Async DB Update Failed",
                        "Error actualizando BD para baneo permanente: " + e.getMessage());
            }
        });

        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
        if (mcPlayer != null && mcPlayer.isOnline()) {
            mcPlayer.sendMessage("§4§l❌ HAS SIDO BANEADO PERMANENTEMENTE DEL SISTEMA RANKED");
            mcPlayer.sendMessage("§c Razón: Abandono excesivo de partidas (7+ abandonos)");
            mcPlayer.sendMessage("§7 Contacta a un administrador para apelar el baneo");
        }

        logger.error("BANEO PERMANENTE APLICADO",
                String.format("Jugador %s BANEADO PERMANENTEMENTE por abandono excesivo",
                        playerData.getMinecraftUuid().substring(0, 8)));

        logger.matchEvent("PERMANENT_BAN", "Baneo Permanente",
                String.format("Jugador baneado permanentemente. ELO final: %d", newElo), 1);
    }

    private void applyDoubleLosses(PlayerData playerData) {
        try {
            DatabaseManager.addDoubleLossesToPlayer(playerData.getMinecraftUuid());

            logger.info("Pérdidas Dobles Aplicadas",
                    String.format("Jugador %s recibió %d derrotas por abandono (doble loss)",
                            playerData.getMinecraftUuid().substring(0, 8),
                            DOUBLE_LOSS_COUNT));

        } catch (Exception e) {
            logger.logError("Error aplicando pérdidas dobles", e);
        }
    }

    /**
     * Devuelve true si el jugador ya fue marcado como abandono procesado en este match.
     * Esto evita doble castigo y evita que MatchFinisher procese ELO/WL normal.
     */
    private boolean isAbandonmentAlreadyProcessed(String matchId, String playerUuid) {
        if (matchId == null || playerUuid == null) return false;
        try {
            DatabaseManager.MatchProtectionSnapshot snap = DatabaseManager.getMatchProtectionSnapshot(matchId);
            return snap != null && snap.abandonmentProcessed != null && snap.abandonmentProcessed.contains(playerUuid);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Compatibilidad: intenta marcar "abandonmentProcessed" sin depender de un método exacto.
     * Si no existe, no rompe compile ni runtime.
     */
    private void markAbandonmentProcessedBestEffort(String playerUuid, String matchId) {
        if (playerUuid == null || matchId == null) return;

        // Si ya está marcado, no hacemos nada.
        try {
            if (isAbandonmentAlreadyProcessed(matchId, playerUuid)) return;
        } catch (Exception ignored) {}

        // Posibles nombres de método en DatabaseManager (según evoluciones del proyecto)
        String[] candidates = new String[] {
                "markAbandonmentProcessed",
                "markPlayerAbandonmentProcessed",
                "markAbandonmentAsProcessed",
                "markPlayerProcessedAbandonment",
                "setAbandonmentProcessed"
        };

        for (String name : candidates) {
            try {
                java.lang.reflect.Method m = DatabaseManager.class.getMethod(name, String.class, String.class);
                m.invoke(null, playerUuid, matchId);
                return;
            } catch (Exception ignored) {}
        }
    }

    private void protectTeammates(ActiveMatch activeMatch, PlayerData abandoner) {
        Team abandonerTeam = findPlayerTeam(activeMatch, abandoner);
        if (abandonerTeam == null) return;

        List<PlayerData> teammates = activeMatch.getTeams().get(abandonerTeam);
        if (teammates == null) return;

        for (PlayerData teammate : teammates) {
            if (!teammate.equals(abandoner)) {
                DatabaseManager.markPlayerProtectedFromLoss(teammate.getMinecraftUuid(),
                        activeMatch.getMatchId(), "teammate_abandonment");

                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(teammate.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage("§a✅ Estás protegido de pérdida de ELO debido al abandono de tu compañero");
                    mcPlayer.sendMessage("§7Si pierdes esta partida, no perderás ELO ni contará como derrota");
                }
            }
        }

        logger.info("Compañeros Protegidos",
                String.format("Protegidos %d compañeros del abandono en partida %s",
                        teammates.size() - 1, activeMatch.getMatchId()));
    }

    private void announceTemporaryDisconnection(ActiveMatch activeMatch, String playerUuid, String playerName) {
        Team playerTeam = findPlayerTeam(activeMatch, playerUuid);
        if (playerTeam == null) return;

        List<PlayerData> teammates = activeMatch.getTeams().get(playerTeam);
        if (teammates == null) return;

        long seconds = RECONNECTION_GRACE_PERIOD_SECONDS;
        long mm = seconds / 60;
        long ss = seconds % 60;
        String time = String.format("%d:%02d", mm, ss);

        String message = String.format("§e⚠️ %s se desconectó. Tiene §c%s §epara reconectarse o será marcado como abandono", playerName, time);

        for (PlayerData teammate : teammates) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(teammate.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(message);
            }
        }
    }

    private void announceAbandonment(ActiveMatch activeMatch, PlayerData abandoner, int appliedEloPenalty, AbandonmentPenalty penalty) {
        String message = String.format("§c❌ Jugador abandonó la partida y fue penalizado con -%d ELO (doble loss)", Math.max(0, appliedEloPenalty));

        for (PlayerData player : activeMatch.getAllPlayers()) {
            if (!player.equals(abandoner)) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(message);
                }
            }
        }

        logger.matchEvent(activeMatch.getMatchId(), "Jugador Abandonó",
                String.format("Jugador penalizado con %d ELO y %d min cooldown por %s",
                        penalty.eloPenalty, penalty.cooldownMinutes, penalty.description),
                activeMatch.getAllPlayers().size());
    }

    public void onPlayerReconnect(String playerUuid) {
        DisconnectionTracker tracker = disconnectedPlayers.remove(playerUuid);
        if (tracker == null) return;

        long minutesDisconnected = ChronoUnit.MINUTES.between(tracker.disconnectTime, LocalDateTime.now());

        logger.info("Reconexión Exitosa",
                String.format("Jugador %s se reconectó tras %d min - abandono cancelado",
                        tracker.playerName, minutesDisconnected));

        ActiveMatch activeMatch = ActiveMatch.getActiveMatch(tracker.matchId);
        if (activeMatch != null) {
            announceReconnection(activeMatch, playerUuid, tracker.playerName);

        }
    }

    private void announceReconnection(ActiveMatch activeMatch, String playerUuid, String playerName) {
        Team playerTeam = findPlayerTeam(activeMatch, playerUuid);
        if (playerTeam == null) return;

        List<PlayerData> teammates = activeMatch.getTeams().get(playerTeam);
        if (teammates == null) return;

        String message = String.format("§a✅ %s se reconectó exitosamente", playerName);

        for (PlayerData teammate : teammates) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(teammate.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(message);
            }
        }
    }

    private ActiveMatch findActiveMatchForPlayer(String playerUuid) {
        return ActiveMatch.findActiveMatchForPlayer(playerUuid);
    }

    private Team findPlayerTeam(ActiveMatch activeMatch, String playerUuid) {
        for (Map.Entry<Team, List<PlayerData>> entry : activeMatch.getTeams().entrySet()) {
            for (PlayerData player : entry.getValue()) {
                if (player.getMinecraftUuid().equals(playerUuid)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private Team findPlayerTeam(ActiveMatch activeMatch, PlayerData targetPlayer) {
        return findPlayerTeam(activeMatch, targetPlayer.getMinecraftUuid());
    }

    private AbandonmentPenalty calculatePenalty(int abandonmentCount) {
        return PENALTY_TIERS.getOrDefault(Math.min(abandonmentCount, 7), PENALTY_TIERS.get(7));
    }

    private static class DisconnectionTracker {
        final String playerUuid;
        final String matchId;
        final LocalDateTime disconnectTime;
        final String playerName;
        final boolean earlyDisconnect;

        DisconnectionTracker(String playerUuid, String matchId, LocalDateTime disconnectTime, String playerName, boolean earlyDisconnect) {
            this.playerUuid = playerUuid;
            this.matchId = matchId;
            this.disconnectTime = disconnectTime;
            this.playerName = playerName;
            this.earlyDisconnect = earlyDisconnect;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            DisconnectionTracker that = (DisconnectionTracker) obj;
            return Objects.equals(playerUuid, that.playerUuid) &&
                    Objects.equals(matchId, that.matchId) &&
                    Objects.equals(disconnectTime, that.disconnectTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerUuid, matchId, disconnectTime);
        }
    }

    private static class AbandonmentPenalty {
        final int cooldownMinutes;
        final int eloPenalty;
        final String description;

        AbandonmentPenalty(int cooldownMinutes, int eloPenalty, String description) {
            this.cooldownMinutes = cooldownMinutes;
            this.eloPenalty = eloPenalty;
            this.description = description;
        }
    }
}