package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistema de detección y castigo por abandono de partidas
 */
public class AbandonmentDetectionSystem implements Listener {

    private final RankedMinecraft plugin;
    private final DiscordLogger logger;

    // Mapa para rastrear desconexiones temporales vs abandonos reales
    private final Map<String, DisconnectionTracker> disconnectedPlayers = new ConcurrentHashMap<>();

    // Configuración del sistema
    private static final long RECONNECTION_GRACE_PERIOD = 120L; // CAMBIADO: 2 minutos en segundos

    // Configuración escalonada de castigos
    private static final Map<Integer, AbandonmentPenalty> PENALTY_TIERS = Map.of(
        1, new AbandonmentPenalty(60, 30, "Primer abandono"),
        2, new AbandonmentPenalty(120, 50, "Segundo abandono"),
        3, new AbandonmentPenalty(240, 75, "Tercer abandono"),
        4, new AbandonmentPenalty(480, 100, "Cuarto abandono"),
        5, new AbandonmentPenalty(960, 125, "Quinto abandono"),
        6, new AbandonmentPenalty(1440, 150, "Sexto abandono - Última advertencia"),
        7, new AbandonmentPenalty(-1, 200, "BANEO PERMANENTE") // -1 indica baneo permanente
    );

    public AbandonmentDetectionSystem(RankedMinecraft plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;

        // Registrar el listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        logger.info("Sistema de Abandono", "Sistema de detección de abandono inicializado");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerUuid = player.getUniqueId().toString();

        // Verificar si el jugador está en una partida activa
        ActiveMatch activeMatch = findActiveMatchForPlayer(playerUuid);

        if (activeMatch == null) {
            return; // No está en partida, ignorar
        }

        // Solo procesar si la partida está en progreso
        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
            return;
        }

        logger.warning("Desconexión Durante Partida",
            String.format("Jugador %s se desconectó durante partida %s",
                player.getName(), activeMatch.getMatchId()));

        // Iniciar rastreo de desconexión
        startDisconnectionTracking(playerUuid, activeMatch, player.getName());
    }

    /**
     * Inicia el rastreo de una desconexión para determinar si es abandono
     */
    private void startDisconnectionTracking(String playerUuid, ActiveMatch activeMatch, String playerName) {
        DisconnectionTracker tracker = new DisconnectionTracker(
            playerUuid, activeMatch.getMatchId(), LocalDateTime.now(), playerName
        );

        disconnectedPlayers.put(playerUuid, tracker);

        // Anunciar desconexión temporal al equipo
        announceTemporaryDisconnection(activeMatch, playerUuid, playerName);

        // Programar verificación de abandono después del período de gracia
        new BukkitRunnable() {
            @Override
            public void run() {
                checkForAbandonment(playerUuid, tracker);
            }
        }.runTaskLater(plugin, RECONNECTION_GRACE_PERIOD * 20L); // Convertir a ticks
    }

    /**
     * Verifica si una desconexión se convierte en abandono
     */
    private void checkForAbandonment(String playerUuid, DisconnectionTracker tracker) {
        // Verificar si el jugador se reconectó
        DisconnectionTracker currentTracker = disconnectedPlayers.get(playerUuid);
        if (currentTracker == null || !currentTracker.equals(tracker)) {
            // El jugador se reconectó o el tracker fue reemplazado
            return;
        }

        // Verificar si la partida aún está activa
        ActiveMatch activeMatch = ActiveMatch.getActiveMatch(tracker.matchId);
        if (activeMatch == null || activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
            // La partida terminó, limpiar tracker
            disconnectedPlayers.remove(playerUuid);
            return;
        }

        // Es un abandono confirmado
        processAbandonment(playerUuid, tracker, activeMatch);
    }

    /**
     * Procesa un abandono confirmado
     */
    private void processAbandonment(String playerUuid, DisconnectionTracker tracker, ActiveMatch activeMatch) {
        logger.error("Abandono Confirmado",
            String.format("Jugador %s abandonó partida %s tras %d minutos",
                tracker.playerName, tracker.matchId,
                ChronoUnit.MINUTES.between(tracker.disconnectTime, LocalDateTime.now())));

        try {
            // Obtener datos del jugador
            PlayerData playerData = DatabaseManager.getPlayerData(playerUuid);
            if (playerData == null) {
                logger.warning("Datos No Encontrados", "No se encontraron datos para jugador abandono: " + playerUuid);
                return;
            }

            // Calcular castigo basado en historial
            int abandonmentCount = DatabaseManager.getPlayerAbandonmentCount(playerUuid);
            AbandonmentPenalty penalty = calculatePenalty(abandonmentCount + 1);

            // Aplicar castigo
            applyAbandonmentPenalty(playerData, penalty);

            // Registrar abandono en base de datos
            DatabaseManager.recordAbandonment(playerUuid, activeMatch.getMatchId(),
                penalty.eloPenalty, penalty.cooldownMinutes);

            // Anunciar abandono al resto del equipo
            announceAbandonment(activeMatch, playerData, penalty);

            // Proteger a compañeros de equipo
            protectTeammates(activeMatch, playerData);

            // Limpiar tracker
            disconnectedPlayers.remove(playerUuid);

        } catch (Exception e) {
            logger.logError("Error procesando abandono", e);
        }
    }

    /**
     * Aplica la penalización por abandono
     */
    private void applyAbandonmentPenalty(PlayerData playerData, AbandonmentPenalty penalty) {
        // Verificar si es baneo permanente
        if (penalty.cooldownMinutes == -1) {
            // BANEO PERMANENTE
            applyPermanentBan(playerData, penalty);
            return;
        }

        // Aplicar pérdida de ELO
        int newElo = Math.max(0, playerData.getElo() - penalty.eloPenalty);
        playerData.setElo(newElo);

        // Aplicar cooldown de queue
        long cooldownEndTime = System.currentTimeMillis() + ((long) penalty.cooldownMinutes * 60 * 1000);

        // NUEVO: Aplicar pérdidas dobles (cuenta como 2 derrotas)
        applyDoubleLosses(playerData);

        // Actualizar MEMORIA inmediatamente (instantáneo)
        playerData.setInMatch(false);
        playerData.setCurrentMatchId(null);

        // Actualizar BD de forma ASÍNCRONA (no bloquea servidor)
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
            String.format("Jugador %s castigado: -%d ELO, %d min cooldown, +2 derrotas (BD actualizándose en segundo plano)",
                playerData.getMinecraftUuid().substring(0, 8),
                penalty.eloPenalty, penalty.cooldownMinutes));
    }

    /**
     * NUEVO: Aplica baneo permanente al jugador
     */
    private void applyPermanentBan(PlayerData playerData, AbandonmentPenalty penalty) {
        // Aplicar ELO penalty final
        int newElo = Math.max(0, playerData.getElo() - penalty.eloPenalty);
        playerData.setElo(newElo);

        // Baneo permanente = cooldown hasta el año 2099
        long permanentBanTime = 4102444800000L; // 1 de enero de 2100

        // Aplicar pérdidas dobles
        applyDoubleLosses(playerData);

        // Actualizar MEMORIA inmediatamente
        playerData.setInMatch(false);
        playerData.setCurrentMatchId(null);

        // Actualizar BD de forma ASÍNCRONA
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

        // Notificar al jugador si está conectado (operación rápida)
        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
        if (mcPlayer != null && mcPlayer.isOnline()) {
            mcPlayer.sendMessage("§4§l❌ HAS SIDO BANEADO PERMANENTEMENTE DEL SISTEMA RANKED");
            mcPlayer.sendMessage("§c Razón: Abandono excesivo de partidas (7+ abandonos)");
            mcPlayer.sendMessage("§7 Contacta a un administrador para apelar el baneo");
        }

        logger.error("BANEO PERMANENTE APLICADO",
            String.format("Jugador %s BANEADO PERMANENTEMENTE por abandono excesivo (%d abandonos)",
                playerData.getMinecraftUuid().substring(0, 8), 7));

        // Log crítico en Discord
        logger.matchEvent("PERMANENT_BAN", "Baneo Permanente",
            String.format("Jugador baneado permanentemente por %d abandonos. ELO final: %d",
                7, newElo), 1);
    }

    /**
     * NUEVO: Aplica pérdidas dobles al jugador (cuenta como 2 derrotas)
     */
    private void applyDoubleLosses(PlayerData playerData) {
        try {
            // Aplicar 2 derrotas adicionales al historial del jugador
            DatabaseManager.addDoubleLossesToPlayer(playerData.getMinecraftUuid());

            logger.info("Pérdidas Dobles Aplicadas",
                String.format("Jugador %s recibió 2 derrotas adicionales por abandono",
                    playerData.getMinecraftUuid().substring(0, 8)));

        } catch (Exception e) {
            logger.logError("Error aplicando pérdidas dobles", e);
        }
    }

    /**
     * Protege a los compañeros de equipo del jugador que abandonó
     */
    private void protectTeammates(ActiveMatch activeMatch, PlayerData abandoner) {
        Team abandonerTeam = findPlayerTeam(activeMatch, abandoner);
        if (abandonerTeam == null) {
            return;
        }

        List<PlayerData> teammates = activeMatch.getTeams().get(abandonerTeam);
        if (teammates == null) {
            return;
        }

        // Marcar a compañeros como protegidos por abandono
        for (PlayerData teammate : teammates) {
            if (!teammate.equals(abandoner)) {
                DatabaseManager.markPlayerProtectedFromLoss(teammate.getMinecraftUuid(),
                    activeMatch.getMatchId(), "teammate_abandonment");

                // Notificar protección
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

    /**
     * Anuncia la desconexión temporal al equipo
     */
    private void announceTemporaryDisconnection(ActiveMatch activeMatch, String playerUuid, String playerName) {
        Team playerTeam = findPlayerTeam(activeMatch, playerUuid);
        if (playerTeam == null) return;

        List<PlayerData> teammates = activeMatch.getTeams().get(playerTeam);
        if (teammates == null) return;

        String message = String.format("§e⚠️ %s se desconectó. Tiene §c1:30 minutos §epara reconectarse o será marcado como abandono", playerName);

        for (PlayerData teammate : teammates) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(teammate.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(message);
            }
        }
    }

    /**
     * Anuncia el abandono confirmado
     */
    private void announceAbandonment(ActiveMatch activeMatch, PlayerData abandoner, AbandonmentPenalty penalty) {
        String message = String.format("§c❌ Jugador abandonó la partida y fue penalizado con -%d ELO", penalty.eloPenalty);

        for (PlayerData player : activeMatch.getAllPlayers()) {
            if (!player.equals(abandoner)) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(message);
                }
            }
        }

        // Log en Discord
        logger.matchEvent(activeMatch.getMatchId(), "Jugador Abandonó",
            String.format("Jugador penalizado con %d ELO y %d minutos de cooldown por %s",
                penalty.eloPenalty, penalty.cooldownMinutes, penalty.description),
            activeMatch.getAllPlayers().size());
    }

    /**
     * Se llama cuando un jugador se reconecta
     */
    public void onPlayerReconnect(String playerUuid) {
        DisconnectionTracker tracker = disconnectedPlayers.remove(playerUuid);
        if (tracker != null) {
            long minutesDisconnected = ChronoUnit.MINUTES.between(tracker.disconnectTime, LocalDateTime.now());

            logger.info("Reconexión Exitosa",
                String.format("Jugador %s se reconectó tras %d minutos - abandono cancelado",
                    tracker.playerName, minutesDisconnected));

            // Anunciar reconexión al equipo
            ActiveMatch activeMatch = ActiveMatch.getActiveMatch(tracker.matchId);
            if (activeMatch != null) {
                announceReconnection(activeMatch, playerUuid, tracker.playerName);
            }
        }
    }

    /**
     * Anuncia la reconexión exitosa
     */
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

    // Métodos auxiliares
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

    /**
     * Clase para rastrear desconexiones
     */
    private static class DisconnectionTracker {
        final String playerUuid;
        final String matchId;
        final LocalDateTime disconnectTime;
        final String playerName;

        DisconnectionTracker(String playerUuid, String matchId, LocalDateTime disconnectTime, String playerName) {
            this.playerUuid = playerUuid;
            this.matchId = matchId;
            this.disconnectTime = disconnectTime;
            this.playerName = playerName;
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

    /**
     * Clase para definir penalizaciones por abandono
     */
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

