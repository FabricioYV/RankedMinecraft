package org.fabricioyv.listeners;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.fabricioyv.database.MatchLogsManager;
import org.fabricioyv.match.ActiveMatch;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listener para capturar estadísticas de jugadores durante las partidas
 */
public class MatchStatsListener implements Listener {

    // Mapa para almacenar estadísticas temporales de jugadores durante las partidas
    private static final Map<String, Map<UUID, MatchLogsManager.PlayerMatchStats>> activeMatchStats = new ConcurrentHashMap<>();

    // Mapa para rastrear flechas disparadas por jugadores
    private static final Map<UUID, UUID> arrowOwners = new ConcurrentHashMap<>();

    /**
     * Inicializa las estadísticas para una nueva partida
     */
    public static void initializeMatchStats(String matchId, Map<String, String> playerTeams) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = new ConcurrentHashMap<>();

        for (Map.Entry<String, String> entry : playerTeams.entrySet()) {
            UUID playerUuid = UUID.fromString(entry.getKey());
            String team = entry.getValue();
            Player player = org.bukkit.Bukkit.getPlayer(playerUuid);

            if (player != null) {
                MatchLogsManager.PlayerMatchStats stats = new MatchLogsManager.PlayerMatchStats(
                    playerUuid.toString(),
                    player.getName(),
                    team
                );
                matchStats.put(playerUuid, stats);
            }
        }

        activeMatchStats.put(matchId, matchStats);

        // Log del evento de inicio de partida
        MatchLogsManager.logMatchEvent(matchId, "MATCH_START", null,
            "Partida iniciada con " + playerTeams.size() + " jugadores");
    }

    /**
     * Obtiene las estadísticas finales de una partida y las limpia de memoria
     */
    public static Map<UUID, MatchLogsManager.PlayerMatchStats> finalizeMatchStats(String matchId) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> stats = activeMatchStats.remove(matchId);

        // Log del evento de finalización de partida
        if (stats != null) {
            MatchLogsManager.logMatchEvent(matchId, "MATCH_END", null,
                "Partida finalizada con " + stats.size() + " jugadores");
        }

        return stats;
    }


    /**
     * Registra una muerte de jugador
     */
    public static void recordPlayerDeath(String matchId, UUID playerUuid, UUID killerUuid) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        // Registrar muerte
        MatchLogsManager.PlayerMatchStats victimStats = matchStats.get(playerUuid);
        if (victimStats != null) {
            victimStats.addDeath();
        }

        // Registrar kill si hay killer
        if (killerUuid != null) {
            MatchLogsManager.PlayerMatchStats killerStats = matchStats.get(killerUuid);
            if (killerStats != null) {
                killerStats.addKill();
            }
        }

        // Log del evento
        String eventData = killerUuid != null ?
            "Killed by " + killerUuid.toString() : "Death (no killer)";
        MatchLogsManager.logMatchEvent(matchId, "PLAYER_DEATH", playerUuid.toString(), eventData);
    }

    /**
     * Registra daño infligido por un jugador a otro
     */
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        boolean isArrowHit = false;

        // Determinar el atacante
        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Arrow arrow) {
            if (arrow.getShooter() instanceof Player) {
                attacker = (Player) arrow.getShooter();
                isArrowHit = true;
            }
        }

        if (attacker == null || attacker.equals(victim)) return;

        // Buscar en qué partida están los jugadores
        String matchId = findPlayerMatch(attacker.getUniqueId());
        if (matchId == null) return;

        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        double damage = event.getFinalDamage();

        // Registrar daño infligido al atacante
        MatchLogsManager.PlayerMatchStats attackerStats = matchStats.get(attacker.getUniqueId());
        if (attackerStats != null) {
            attackerStats.addDamageDealt(damage);

            // Si es un hit de flecha, registrar el hit
            if (isArrowHit) {
                attackerStats.addArrowHit();
            }
        }

        // Registrar daño recibido a la víctima
        MatchLogsManager.PlayerMatchStats victimStats = matchStats.get(victim.getUniqueId());
        if (victimStats != null) {
            victimStats.addDamageReceived(damage);
        }
    }

    /**
     * Registra cuando un jugador dispara una flecha
     */
    @EventHandler
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        String matchId = findPlayerMatch(player.getUniqueId());
        if (matchId == null) return;

        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        // Registrar flecha disparada
        MatchLogsManager.PlayerMatchStats playerStats = matchStats.get(player.getUniqueId());
        if (playerStats != null) {
            playerStats.addArrowShot();
        }

        // Asociar la flecha con el jugador para tracking de hits
        arrowOwners.put(arrow.getUniqueId(), player.getUniqueId());
    }

    /**
     * Registra cuando una flecha impacta (compatible con Bukkit 1.8)
     */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;

        // En Bukkit 1.8, ProjectileHitEvent no tiene getHitEntity()
        // Solo removemos la flecha del tracking, el hit se detecta en EntityDamageByEntityEvent
        arrowOwners.remove(arrow.getUniqueId());
    }

    /**
     * Limpiar referencias cuando un jugador se desconecta
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();

        // Remover flechas del jugador
        arrowOwners.values().removeIf(uuid -> uuid.equals(playerUuid));

        // Log del evento si el jugador está en una partida
        String matchId = findPlayerMatch(playerUuid);
        if (matchId != null) {
            MatchLogsManager.logMatchEvent(matchId, "PLAYER_DISCONNECT",
                playerUuid.toString(), "Player disconnected during match");
        }
    }

    /**
     * Registrar cuando un jugador se reconecta a una partida
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();

        // Log del evento si el jugador está en una partida
        String matchId = findPlayerMatch(playerUuid);
        if (matchId != null) {
            MatchLogsManager.logMatchEvent(matchId, "PLAYER_RECONNECT",
                playerUuid.toString(), "Player reconnected to match");
        }
    }

    /**
     * Encuentra en qué partida está un jugador
     */
    private String findPlayerMatch(UUID playerUuid) {
        for (Map.Entry<String, Map<UUID, MatchLogsManager.PlayerMatchStats>> entry : activeMatchStats.entrySet()) {
            if (entry.getValue().containsKey(playerUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Establece los ELO/MMR iniciales y finales para un jugador en una partida
     */
    public static void setPlayerRatingChanges(String matchId, UUID playerUuid,
                                            int oldElo, int newElo, double oldMmr, double newMmr) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        MatchLogsManager.PlayerMatchStats playerStats = matchStats.get(playerUuid);
        if (playerStats != null) {
            playerStats.setOldElo(oldElo);
            playerStats.setNewElo(newElo);
            playerStats.setOldMmr(oldMmr);
            playerStats.setNewMmr(newMmr);
        }
    }

    /**
     * Marca si un jugador ganó o perdió la partida
     */
    public static void setPlayerMatchResult(String matchId, UUID playerUuid, boolean won) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        MatchLogsManager.PlayerMatchStats playerStats = matchStats.get(playerUuid);
        if (playerStats != null) {
            playerStats.setWon(won);
        }
    }

}
