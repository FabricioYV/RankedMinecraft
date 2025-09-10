package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Listener optimizado para capturar estadísticas con sistema de batch para evitar lag
 */
public class OptimizedMatchStatsListener implements Listener {

    // Sistema de estadísticas en memoria optimizado
    private static final Map<String, Map<UUID, OptimizedPlayerStats>> activeMatchStats = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> arrowOwners = new ConcurrentHashMap<>();
    
    // Sistema de batch para eventos de base de datos
    private static final List<MatchLogsManager.PendingEvent> pendingEvents = Collections.synchronizedList(new ArrayList<>());
    private static final ScheduledExecutorService batchProcessor = Executors.newSingleThreadScheduledExecutor();
    
    // Configuración optimizada
    private static final int BATCH_SIZE = 20;           // Procesar eventos en lotes de 20
    private static final int BATCH_INTERVAL_MS = 2000;  // Procesar cada 2 segundos
    private static final int MEMORY_CLEANUP_INTERVAL = 300; // Limpiar memoria cada 5 minutos
    
    static {
        // Inicializar procesador de batch
        startBatchProcessor();
    }

    /**
     * Clase optimizada para estadísticas de jugador usando contadores atómicos
     */
    private static class OptimizedPlayerStats {
        private final String playerUuid;
        private final String playerName;
        private final String team;
        
        // Contadores atómicos para operaciones thread-safe sin locks
        private final AtomicInteger kills = new AtomicInteger(0);
        private final AtomicInteger deaths = new AtomicInteger(0);
        private final AtomicInteger arrowsShot = new AtomicInteger(0);
        private final AtomicInteger arrowsHit = new AtomicInteger(0);
        
        // Variables de daño (usar synchronized solo cuando sea necesario)
        private volatile double damageDealt = 0.0;
        private volatile double damageReceived = 0.0;
        
        // Variables de ELO/MMR
        private volatile int oldElo, newElo;
        private volatile double oldMmr, newMmr;
        private volatile boolean won;

        public OptimizedPlayerStats(String playerUuid, String playerName, String team) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.team = team;
        }

        // Métodos optimizados con operaciones atómicas
        public void addKill() { kills.incrementAndGet(); }
        public void addDeath() { deaths.incrementAndGet(); }
        public void addArrowShot() { arrowsShot.incrementAndGet(); }
        public void addArrowHit() { arrowsHit.incrementAndGet(); }
        
        public synchronized void addDamageDealt(double damage) { 
            this.damageDealt += damage; 
        }
        
        public synchronized void addDamageReceived(double damage) { 
            this.damageReceived += damage; 
        }

        // Conversión a formato legacy para compatibilidad
        public MatchLogsManager.PlayerMatchStats toLegacyStats() {
            MatchLogsManager.PlayerMatchStats legacy = new MatchLogsManager.PlayerMatchStats(
                playerUuid, playerName, team
            );
            
            legacy.setKills(kills.get());
            legacy.setDeaths(deaths.get());
            legacy.setDamageDealt(damageDealt);
            legacy.setDamageReceived(damageReceived);
            legacy.setArrowsShot(arrowsShot.get());
            legacy.setArrowsHit(arrowsHit.get());
            legacy.setOldElo(oldElo);
            legacy.setNewElo(newElo);
            legacy.setOldMmr(oldMmr);
            legacy.setNewMmr(newMmr);
            legacy.setWon(won);
            
            return legacy;
        }

        // Setters para ELO/MMR
        public void setEloData(int oldElo, int newElo) {
            this.oldElo = oldElo;
            this.newElo = newElo;
        }
        
        public void setMmrData(double oldMmr, double newMmr) {
            this.oldMmr = oldMmr;
            this.newMmr = newMmr;
        }
        
        public void setWon(boolean won) { this.won = won; }
    }

    /**
     * Inicia el procesador de batch en segundo plano
     */
    private static void startBatchProcessor() {
        batchProcessor.scheduleAtFixedRate(() -> {
            try {
                processPendingEventsBatch();
            } catch (Exception e) {
                Bukkit.getConsoleSender().sendMessage("§c[OptimizedStats] Error procesando batch: " + e.getMessage());
            }
        }, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Limpieza de memoria periódica
        batchProcessor.scheduleAtFixedRate(() -> {
            try {
                cleanupMemory();
            } catch (Exception e) {
                Bukkit.getConsoleSender().sendMessage("§c[OptimizedStats] Error en limpieza: " + e.getMessage());
            }
        }, MEMORY_CLEANUP_INTERVAL, MEMORY_CLEANUP_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * Procesa eventos pendientes en lotes para reducir carga de BD
     */
    private static void processPendingEventsBatch() {
        if (pendingEvents.isEmpty()) return;

        List<MatchLogsManager.PendingEvent> batch = new ArrayList<>();
        synchronized (pendingEvents) {
            int batchSize = Math.min(BATCH_SIZE, pendingEvents.size());
            for (int i = 0; i < batchSize; i++) {
                batch.add(pendingEvents.remove(0));
            }
        }

        if (!batch.isEmpty()) {
            // Procesar batch de forma asíncrona
            MatchLogsManager.processBatchEvents(batch);
        }
    }

    /**
     * Limpia memoria de flechas y estadísticas obsoletas
     */
    private static void cleanupMemory() {
        // Limpiar flechas huérfanas periódicamente
        arrowOwners.entrySet().removeIf(entry -> Math.random() < 0.1); // Limpiar 10% aleatoriamente
    }

    /**
     * Inicializa estadísticas optimizadas para una nueva partida
     */
    public static void initializeMatchStats(String matchId, Map<String, String> playerTeams) {
        Map<UUID, OptimizedPlayerStats> matchStats = new ConcurrentHashMap<>();

        for (Map.Entry<String, String> entry : playerTeams.entrySet()) {
            UUID playerUuid = UUID.fromString(entry.getKey());
            String team = entry.getValue();
            Player player = Bukkit.getPlayer(playerUuid);

            if (player != null) {
                OptimizedPlayerStats stats = new OptimizedPlayerStats(
                    playerUuid.toString(),
                    player.getName(),
                    team
                );
                matchStats.put(playerUuid, stats);
            }
        }

        activeMatchStats.put(matchId, matchStats);

        // Agregar evento de inicio (sin bloquear el thread principal)
        addPendingEvent(matchId, "MATCH_START", null, 
            "Partida iniciada con " + playerTeams.size() + " jugadores");
    }

    /**
     * Registra muerte de jugador de forma optimizada
     */
    public static void recordPlayerDeath(String matchId, UUID playerUuid, UUID killerUuid) {
        Map<UUID, OptimizedPlayerStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        // Actualizar estadísticas en memoria (operación rápida)
        OptimizedPlayerStats victimStats = matchStats.get(playerUuid);
        if (victimStats != null) {
            victimStats.addDeath();
        }

        if (killerUuid != null) {
            OptimizedPlayerStats killerStats = matchStats.get(killerUuid);
            if (killerStats != null) {
                killerStats.addKill();
            }
        }

        // Agregar evento a batch (sin bloquear)
        String eventData = killerUuid != null ?
            "Killed by " + killerUuid : "Death (no killer)";
        addPendingEvent(matchId, "PLAYER_DEATH", playerUuid.toString(), eventData);
    }



    /**
     * Agrega evento a la cola de procesamiento (operación no bloqueante)
     */
    private static void addPendingEvent(String matchId, String eventType, String playerUuid, String eventData) {
        pendingEvents.add(new MatchLogsManager.PendingEvent(matchId, eventType, playerUuid, eventData));

        // Si hay demasiados eventos pendientes, procesar inmediatamente
        if (pendingEvents.size() > BATCH_SIZE * 2) {
            batchProcessor.execute(OptimizedMatchStatsListener::processPendingEventsBatch);
        }
    }

    // ========================================
    // EVENT HANDLERS OPTIMIZADOS
    // ========================================

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;
        boolean isArrowHit = false;

        if (event.getDamager() instanceof Player) {
            attacker = (Player) event.getDamager();
        } else if (event.getDamager() instanceof Arrow arrow) {
            if (arrow.getShooter() instanceof Player) {
                attacker = (Player) arrow.getShooter();
                isArrowHit = true;
            }
        }

        if (attacker == null || attacker.equals(victim)) return;

        String matchId = findPlayerMatch(attacker.getUniqueId());
        if (matchId == null) return;

        Map<UUID, OptimizedPlayerStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        double damage = event.getFinalDamage();

        // Actualizar estadísticas (operaciones optimizadas)
        OptimizedPlayerStats attackerStats = matchStats.get(attacker.getUniqueId());
        if (attackerStats != null) {
            attackerStats.addDamageDealt(damage);
            if (isArrowHit) {
                attackerStats.addArrowHit();
            }
        }

        OptimizedPlayerStats victimStats = matchStats.get(victim.getUniqueId());
        if (victimStats != null) {
            victimStats.addDamageReceived(damage);
        }
    }

    @EventHandler
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getProjectile() instanceof Arrow arrow)) return;

        String matchId = findPlayerMatch(player.getUniqueId());
        if (matchId == null) return;

        Map<UUID, OptimizedPlayerStats> matchStats = activeMatchStats.get(matchId);
        if (matchStats == null) return;

        OptimizedPlayerStats playerStats = matchStats.get(player.getUniqueId());
        if (playerStats != null) {
            playerStats.addArrowShot();
        }

        arrowOwners.put(arrow.getUniqueId(), player.getUniqueId());
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        arrowOwners.remove(arrow.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        arrowOwners.values().removeIf(uuid -> uuid.equals(playerUuid));

        String matchId = findPlayerMatch(playerUuid);
        if (matchId != null) {
            addPendingEvent(matchId, "PLAYER_DISCONNECT",
                playerUuid.toString(), "Player disconnected during match");
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID playerUuid = event.getPlayer().getUniqueId();
        String matchId = findPlayerMatch(playerUuid);
        if (matchId != null) {
            addPendingEvent(matchId, "PLAYER_RECONNECT",
                playerUuid.toString(), "Player reconnected to match");
        }
    }

    /**
     * Encuentra partida de jugador (operación optimizada)
     */
    private String findPlayerMatch(UUID playerUuid) {
        for (Map.Entry<String, Map<UUID, OptimizedPlayerStats>> entry : activeMatchStats.entrySet()) {
            if (entry.getValue().containsKey(playerUuid)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Cierra el sistema de batch cuando el plugin se desactiva
     */
    public static void shutdown() {
        try {
            // Procesar eventos pendientes antes del cierre
            processPendingEventsBatch();
            
            batchProcessor.shutdown();
            if (!batchProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
                batchProcessor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchProcessor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
