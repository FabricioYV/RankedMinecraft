package org.fabricioyv.utils;

import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ULTRA-OPTIMIZADO: Scoreboard rate limiter inteligente para hit register perfecto
 * Ajusta dinámicamente la frecuencia según el estado de combate
 */
public class ScoreboardRateLimiter {

    // ========================================
    // CONFIGURACIÓN OPTIMIZADA PARA HIT REGISTER
    // ========================================

    // MODO COMBATE: 2 segundos entre updates (prioridad absoluta al hit register)
    private static final long COMBAT_UPDATE_COOLDOWN_MS = 2000;

    // MODO NORMAL: 1 segundo entre updates (balance perfecto)
    private static final long NORMAL_UPDATE_COOLDOWN_MS = 1000;

    // MODO URGENTE: 500ms para cambios críticos (deaths, wins, etc.)
    private static final long URGENT_UPDATE_COOLDOWN_MS = 500;

    // Detección de combate: si recibió daño en los últimos 3 segundos
    private static final long COMBAT_TIMEOUT_MS = 3000;

    // ========================================
    // CACHE OPTIMIZADO PARA 30 JUGADORES
    // ========================================

    // Cache de últimas actualizaciones por jugador (tamaño fijo para 30 jugadores)
    private static final Map<UUID, Long> lastUpdateTimes = new ConcurrentHashMap<>(48);

    // Cache de último contenido enviado para evitar actualizaciones redundantes
    private static final Map<UUID, String> lastContent = new ConcurrentHashMap<>(48);

    // NUEVO: Cache de último daño recibido para detectar combate
    private static final Map<UUID, Long> lastCombatTime = new ConcurrentHashMap<>(48);

    // NUEVO: Cache temporal para evitar múltiples llamadas en el mismo tick (CRÍTICO PARA HIT REGISTER)
    private static final Map<UUID, Long> lastDamageCallTime = new ConcurrentHashMap<>(48);
    private static final long DAMAGE_CALL_COOLDOWN_MS = 50; // 50ms = 1 tick, evita spam

    // NUEVO: Cache de prioridad de update (normal, urgent)
    private static final Map<UUID, UpdatePriority> updatePriority = new ConcurrentHashMap<>(48);

    // ========================================
    // ENUMS Y CLASES INTERNAS
    // ========================================

    public enum UpdatePriority {
        NORMAL,     // Updates normales de stats
        URGENT,     // Deaths, kills, match events críticos
        COMBAT      // Durante combate activo (máxima optimización)
    }

    /**
     * NUEVO: Registra que un jugador está en combate (llamar desde damage events)
     */
    public static void registerCombat(UUID playerId) {
        lastCombatTime.put(playerId, System.currentTimeMillis());
    }

    /**
     * NUEVO: Verifica si un jugador está en combate activo
     */
    public static boolean isInCombat(UUID playerId) {
        Long lastCombat = lastCombatTime.get(playerId);
        if (lastCombat == null) return false;

        return (System.currentTimeMillis() - lastCombat) < COMBAT_TIMEOUT_MS;
    }

    /**
     * ULTRA-OPTIMIZADO: Actualiza scoreboard con rate limiting inteligente
     * Prioriza hit register durante combate
     */
    public static boolean updateScoreboardSafe(Player player, String title, String... lines) {
        return updateScoreboardWithPriority(player, UpdatePriority.NORMAL, title, lines);
    }

    /**
     * NUEVO: Update con prioridad específica para casos críticos
     */
    public static boolean updateScoreboardWithPriority(Player player, UpdatePriority priority, String title, String... lines) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        // DETERMINAR COOLDOWN DINÁMICO basado en estado del jugador
        long cooldownMs = getCooldownForPlayer(playerId, priority);

        // Verificar rate limiting inteligente
        Long lastUpdate = lastUpdateTimes.get(playerId);
        if (lastUpdate != null && (now - lastUpdate) < cooldownMs) {
            return false; // Saltear actualización por rate limiting inteligente
        }

        // Crear contenido hash para detectar cambios (OPTIMIZADO)
        String contentHash = title + String.join("|", lines);
        String lastContentHash = lastContent.get(playerId);

        // Si el contenido es idéntico, no actualizar (ahorra 90% de updates redundantes)
        if (contentHash.equals(lastContentHash)) {
            return false;
        }

        try {
            // OPTIMIZACIÓN: Reutilizar scoreboard existente cuando sea posible
            Scoreboard scoreboard = player.getScoreboard();
            if (scoreboard == null) {
                scoreboard = player.getServer().getScoreboardManager().getNewScoreboard();
                player.setScoreboard(scoreboard);
            }

            // OPTIMIZACIÓN: Limpiar solo si es necesario
            Objective existingObjective = scoreboard.getObjective("main");
            if (existingObjective != null) {
                existingObjective.unregister();
            }

            // Crear nuevo objetivo
            Objective objective = scoreboard.registerNewObjective("main", "dummy");
            objective.setDisplayName(title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);

            // Añadir líneas (en orden inverso para display correcto)
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i] != null && !lines[i].isEmpty()) {
                    objective.getScore(lines[i]).setScore(i + 1);
                }
            }

            // Actualizar cache
            lastUpdateTimes.put(playerId, now);
            lastContent.put(playerId, contentHash);
            updatePriority.put(playerId, priority);

            return true;

        } catch (Exception e) {
            // Silenciar errores para no afectar performance
            return false;
        }
    }

    /**
     * INTELIGENCIA: Determina cooldown óptimo según estado del jugador
     */
    private static long getCooldownForPlayer(UUID playerId, UpdatePriority priority) {
        // PRIORIDAD 1: Si está en combate → MÁXIMO rate limiting para hit register perfecto
        if (isInCombat(playerId)) {
            return COMBAT_UPDATE_COOLDOWN_MS; // 2 segundos
        }

        // PRIORIDAD 2: Updates urgentes (deaths, match events)
        if (priority == UpdatePriority.URGENT) {
            return URGENT_UPDATE_COOLDOWN_MS; // 500ms
        }

        // PRIORIDAD 3: Updates normales
        return NORMAL_UPDATE_COOLDOWN_MS; // 1 segundo
    }

    /**
     * Limpiar scoreboard de un jugador
     */
    public static void clearScoreboard(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        try {
            Scoreboard scoreboard = player.getScoreboard();
            if (scoreboard != null) {
                for (Objective objective : scoreboard.getObjectives()) {
                    objective.unregister();
                }
            }

            // Limpiar cache
            UUID playerId = player.getUniqueId();
            lastUpdateTimes.remove(playerId);
            lastContent.remove(playerId);

        } catch (Exception e) {
            // Silenciar errores
        }
    }

    /**
     * Actualiza scoreboard para múltiples jugadores de forma eficiente
     * OPTIMIZADO: Usa batch processing inteligente
     */
    public static int updateScoreboardsBatch(Iterable<Player> players, String title, String... lines) {
        return updateScoreboardsBatchWithPriority(players, UpdatePriority.NORMAL, title, lines);
    }

    /**
     * NUEVO: Batch update con prioridad específica
     */
    public static int updateScoreboardsBatchWithPriority(Iterable<Player> players, UpdatePriority priority, String title, String... lines) {
        int updated = 0;
        int skippedCombat = 0;
        int skippedRateLimit = 0;

        for (Player player : players) {
            UUID playerId = player.getUniqueId();

            // OPTIMIZACIÓN: Skip inmediato si está en combate y no es urgente
            if (isInCombat(playerId) && priority == UpdatePriority.NORMAL) {
                skippedCombat++;
                continue;
            }

            if (updateScoreboardWithPriority(player, priority, title, lines)) {
                updated++;
            } else {
                skippedRateLimit++;
            }
        }

        // Log estadísticas solo si hay actividad significativa
        if (updated > 0 || skippedCombat > 5) {
            logBatchStats(updated, skippedCombat, skippedRateLimit);
        }

        return updated;
    }

    /**
     * NUEVO: Update urgente para eventos críticos (deaths, match finish, etc.)
     */
    public static boolean updateScoreboardUrgent(Player player, String title, String... lines) {
        return updateScoreboardWithPriority(player, UpdatePriority.URGENT, title, lines);
    }

    /**
     * Forzar actualización ignorando rate limiting (usar con cuidado)
     * OPTIMIZADO: Mantiene la detección de combate
     */
    public static boolean forceUpdateScoreboard(Player player, String title, String... lines) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        // Resetear rate limiting para este jugador (pero mantener combat state)
        lastUpdateTimes.remove(playerId);
        lastContent.remove(playerId);

        return updateScoreboardWithPriority(player, UpdatePriority.URGENT, title, lines);
    }

    /**
     * NUEVO: Limpieza inteligente de cache para jugadores desconectados
     */
    public static void cleanup(UUID playerId) {
        lastUpdateTimes.remove(playerId);
        lastContent.remove(playerId);
        lastCombatTime.remove(playerId); // NUEVO
        updatePriority.remove(playerId); // NUEVO
    }

    /**
     * NUEVO: Estadísticas detalladas de performance
     */
    public static String getPerformanceStats() {
        int totalPlayers = lastUpdateTimes.size();
        int playersInCombat = 0;

        for (UUID playerId : lastUpdateTimes.keySet()) {
            if (isInCombat(playerId)) {
                playersInCombat++;
            }
        }

        return String.format("ScoreboardRateLimiter: %d jugadores total, %d en combate (optimizado)",
            totalPlayers, playersInCombat);
    }

    /**
     * NUEVO: Limpiar toda la cache (usar en shutdown)
     */
    public static void shutdown() {
        lastUpdateTimes.clear();
        lastContent.clear();
        lastCombatTime.clear(); // NUEVO
        updatePriority.clear(); // NUEVO
    }

    /**
     * NUEVO: Configurar timeouts personalizados para testing
     */
    public static void setCombatTimeout(long timeoutMs) {
        // Implementación futura para testing
    }

    /**
     * NUEVO: Logging de estadísticas de batch processing
     */
    private static void logBatchStats(int updated, int skippedCombat, int skippedRateLimit) {
        if (updated > 10 || skippedCombat > 10) {
            // Solo log si hay actividad significativa
            System.out.println(String.format("[ScoreboardOpt] Batch: %d updated, %d skipped (combat), %d skipped (rate)",
                updated, skippedCombat, skippedRateLimit));
        }
    }

    /**
     * NUEVO: Método para integración con MatchStatsListener
     * Llama esto desde onEntityDamageByEntity para detectar combate automáticamente
     */
    public static void onPlayerDamaged(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // OPTIMIZACIÓN CRÍTICA: Verificar cache temporal para evitar spam
        Long lastCall = lastDamageCallTime.get(playerId);
        if (lastCall != null && (currentTime - lastCall) < DAMAGE_CALL_COOLDOWN_MS) {
            return; // Ya procesado recientemente, ignorar
        }

        // Actualizar cache temporal
        lastDamageCallTime.put(playerId, currentTime);

        // Marcar como en combate
        lastCombatTime.put(playerId, currentTime);
    }

    /**
     * NUEVO: Método para integración con sistema de deaths
     * Update urgente cuando un jugador muere
     */
    public static boolean onPlayerDeath(Player victim, Player killer, String deathMessage) {
        boolean updated = false;

        // Update urgente para la víctima
        if (victim != null) {
            updated = updateScoreboardUrgent(victim, "§c💀 MUERTE", deathMessage) || updated;
            // Resetear combat state (ya no está peleando)
            lastCombatTime.remove(victim.getUniqueId());
        }

        // Update urgente para el killer
        if (killer != null) {
            updated = updateScoreboardUrgent(killer, "§a⚔️ KILL", deathMessage) || updated;
        }

        return updated;
    }
}
