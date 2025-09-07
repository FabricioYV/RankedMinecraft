package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Listener para capturar estadísticas de jugadores durante las partidas
 * OPTIMIZADO PARA RENDIMIENTO - Sin operaciones pesadas en main thread
 */
public class MatchStatsListener implements Listener {

    // Mapa para almacenar estadísticas temporales de jugadores durante las partidas
    private static final Map<String, Map<UUID, MatchLogsManager.PlayerMatchStats>> activeMatchStats = new ConcurrentHashMap<>();

    // Mapa para rastrear flechas disparadas por jugadores
    private static final Map<UUID, UUID> arrowOwners = new ConcurrentHashMap<>();

    // OPTIMIZACIÓN: Cache directo para lookup de partidas por jugador (evita iteración)
    private static final Map<UUID, String> playerToMatchCache = new ConcurrentHashMap<>();

    // OPTIMIZACIÓN: Cola para procesar eventos de forma asíncrona
    private static final LinkedBlockingQueue<DamageEvent> damageEventQueue = new LinkedBlockingQueue<>();

    // Clase para almacenar eventos de daño para procesamiento asíncrono
    private static class DamageEvent {
        final UUID attackerUuid;
        final UUID victimUuid;
        final double damage;
        final boolean isArrowHit;
        final long timestamp;

        DamageEvent(UUID attackerUuid, UUID victimUuid, double damage, boolean isArrowHit) {
            this.attackerUuid = attackerUuid;
            this.victimUuid = victimUuid;
            this.damage = damage;
            this.isArrowHit = isArrowHit;
            this.timestamp = System.currentTimeMillis();
        }
    }

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

                // OPTIMIZACIÓN: Actualizar cache directo
                playerToMatchCache.put(playerUuid, matchId);
            }
        }

        activeMatchStats.put(matchId, matchStats);

        // Log del evento de inicio de partida (asíncrono)
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("RankedMinecraft"), () -> {
            MatchLogsManager.logMatchEvent(matchId, "MATCH_START", null,
                "Partida iniciada con " + playerTeams.size() + " jugadores");
        });
    }

    /**
     * Obtiene las estadísticas finales de una partida y las limpia de memoria
     */
    public static Map<UUID, MatchLogsManager.PlayerMatchStats> finalizeMatchStats(String matchId) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> stats = activeMatchStats.remove(matchId);

        // OPTIMIZACIÓN: Limpiar cache de jugadores de esta partida
        if (stats != null) {
            for (UUID playerUuid : stats.keySet()) {
                playerToMatchCache.remove(playerUuid);
            }

            // Log del evento de finalización de partida (asíncrono)
            Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("RankedMinecraft"), () -> {
                MatchLogsManager.logMatchEvent(matchId, "MATCH_END", null,
                    "Partida finalizada con " + stats.size() + " jugadores");
            });
        }

        return stats;
    }

    /**
     * Obtiene las estadísticas actuales de un jugador en una partida específica
     */
    public static MatchLogsManager.PlayerMatchStats getPlayerStats(String matchId, UUID playerUuid) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        return matchStats != null ? matchStats.get(playerUuid) : null;
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

        // Log del evento (asíncrono)
        String eventData = killerUuid != null ?
            "Killed by " + killerUuid.toString() : "Death (no killer)";
        Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("RankedMinecraft"), () -> {
            MatchLogsManager.logMatchEvent(matchId, "PLAYER_DEATH", playerUuid.toString(), eventData);
        });
    }

    /**
     * OPTIMIZADO: Registra daño infligido por un jugador a otro
     * Ahora usa prioridad MONITOR y procesamiento asíncrono
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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

        // OPTIMIZACIÓN: Usar cache directo en lugar de iteración
        String matchId = playerToMatchCache.get(attacker.getUniqueId());
        if (matchId == null) return;

        // OPTIMIZACIÓN: Verificar que ambos jugadores están en la misma partida
        if (!matchId.equals(playerToMatchCache.get(victim.getUniqueId()))) return;

        // OPTIMIZACIÓN: Encolar evento para procesamiento asíncrono
        double damage = event.getFinalDamage();
        DamageEvent damageEvent = new DamageEvent(
            attacker.getUniqueId(),
            victim.getUniqueId(),
            damage,
            isArrowHit
        );

        // Procesar inmediatamente en memoria (muy rápido) y logs asíncronos
        processDamageEventSync(matchId, damageEvent);
    }

    /**
     * OPTIMIZACIÓN: Procesa el evento de daño de forma síncrona para estadísticas inmediatas
     * Solo operaciones en memoria, sin I/O
     */
    private void processDamageEventSync(String matchId, DamageEvent event) {
        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        // Registrar daño infligido al atacante
        MatchLogsManager.PlayerMatchStats attackerStats = matchStats.get(event.attackerUuid);
        if (attackerStats != null) {
            attackerStats.addDamageDealt(event.damage);

            // Si es un hit de flecha, registrar el hit
            if (event.isArrowHit) {
                attackerStats.addArrowHit();
            }
        }

        // Registrar daño recibido a la víctima
        MatchLogsManager.PlayerMatchStats victimStats = matchStats.get(event.victimUuid);
        if (victimStats != null) {
            victimStats.addDamageReceived(event.damage);
        }
    }

    /**
     * OPTIMIZADO: Registra cuando un jugador dispara una flecha
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        // OPTIMIZACIÓN: Usar cache directo
        String matchId = playerToMatchCache.get(player.getUniqueId());
        if (matchId == null) return;

        Map<UUID, MatchLogsManager.PlayerMatchStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        // Registrar flecha disparada (operación en memoria)
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

        // OPTIMIZACIÓN: Usar cache directo
        String matchId = playerToMatchCache.get(playerUuid);
        if (matchId != null) {
            // Remover del cache
            playerToMatchCache.remove(playerUuid);

            // Log asíncrono
            Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("RankedMinecraft"), () -> {
                MatchLogsManager.logMatchEvent(matchId, "PLAYER_DISCONNECT",
                    playerUuid.toString(), "Player disconnected during match");
            });
        }
    }

    /**
     * Registrar cuando un jugador se reconecta a una partida
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();

        // OPTIMIZACIÓN: Usar cache directo
        String matchId = playerToMatchCache.get(playerUuid);
        if (matchId != null) {
            // Log asíncrono
            Bukkit.getScheduler().runTaskAsynchronously(Bukkit.getPluginManager().getPlugin("RankedMinecraft"), () -> {
                MatchLogsManager.logMatchEvent(matchId, "PLAYER_RECONNECT",
                    playerUuid.toString(), "Player reconnected to match");
            });
        }
    }

    /**
     * OPTIMIZACIÓN: Encuentra en qué partida está un jugador usando cache directo
     * O(1) en lugar de O(n)
     */
    private String findPlayerMatch(UUID playerUuid) {
        return playerToMatchCache.get(playerUuid);
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
}
