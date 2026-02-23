package org.fabricioyv.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Configuración de rendimiento para optimizar hit registration y PvP
 * Permite desactivar trackeo de estadísticas para maximizar performance
 *
 * @author FabricioYV
 */
public class PerformanceConfig {

    private static FileConfiguration config;
    private static Plugin plugin;

    // ========================================
    // CONFIGURACIONES DE TRACKEO/ESTADÍSTICAS
    // ========================================

    private static boolean statsTrackingEnabled = true;
    private static boolean killsTrackingEnabled = true;
    private static boolean damageTrackingEnabled = true;
    private static boolean arrowTrackingEnabled = true;
    private static boolean deathsTrackingEnabled = true;
    private static boolean matchLogsEnabled = true;
    private static boolean databaseSavingEnabled = true;

    // ========================================
    // OPTIMIZACIONES DE HIT REGISTRATION
    // ========================================

    private static boolean asyncStatsProcessing = true;
    private static boolean reduceDbOperations = true;
    private static boolean batchDatabaseWrites = true;
    private static int batchSize = 50;
    private static long batchFlushInterval = 5000; // 5 segundos

    // ========================================
    // CONFIGURACIONES ESPECÍFICAS DE PVP
    // ========================================

    private static boolean pvpOnlyTracking = false;
    private static boolean disableEnvironmentalTracking = true;
    private static boolean optimizeEventPriority = true;
    private static boolean cachePlayerMatches = true;

    public static void init(Plugin pluginInstance) {
        plugin = pluginInstance;
        config = pluginInstance.getConfig();
        loadConfiguration();
    }

    public static void loadConfiguration() {
        if (config == null) return;

        // Trackeo de estadísticas
        statsTrackingEnabled = config.getBoolean("performance.stats.enabled", true);
        killsTrackingEnabled = config.getBoolean("performance.stats.track-kills", true);
        damageTrackingEnabled = config.getBoolean("performance.stats.track-damage", true);
        arrowTrackingEnabled = config.getBoolean("performance.stats.track-arrows", true);
        deathsTrackingEnabled = config.getBoolean("performance.stats.track-deaths", true);
        matchLogsEnabled = config.getBoolean("performance.stats.match-logs", true);
        databaseSavingEnabled = config.getBoolean("performance.stats.database-saving", true);

        // Optimizaciones de hit registration
        asyncStatsProcessing = config.getBoolean("performance.optimization.async-stats", true);
        reduceDbOperations = config.getBoolean("performance.optimization.reduce-db-operations", true);
        batchDatabaseWrites = config.getBoolean("performance.optimization.batch-writes", true);
        batchSize = config.getInt("performance.optimization.batch-size", 50);
        batchFlushInterval = config.getLong("performance.optimization.batch-flush-interval", 5000);

        // Configuraciones PvP
        pvpOnlyTracking = config.getBoolean("performance.pvp.only-pvp-tracking", false);
        disableEnvironmentalTracking = config.getBoolean("performance.pvp.disable-environmental", true);
        optimizeEventPriority = config.getBoolean("performance.pvp.optimize-event-priority", true);
        cachePlayerMatches = config.getBoolean("performance.pvp.cache-player-matches", true);
    }

    // ========================================
    // GETTERS PARA CONFIGURACIONES
    // ========================================

    public static boolean isStatsTrackingEnabled() {
        return statsTrackingEnabled;
    }

    public static boolean isKillsTrackingEnabled() {
        return killsTrackingEnabled && statsTrackingEnabled;
    }

    public static boolean isDamageTrackingEnabled() {
        return damageTrackingEnabled && statsTrackingEnabled;
    }

    public static boolean isArrowTrackingEnabled() {
        return arrowTrackingEnabled && statsTrackingEnabled;
    }

    public static boolean isDeathsTrackingEnabled() {
        return deathsTrackingEnabled && statsTrackingEnabled;
    }

    public static boolean isMatchLogsEnabled() {
        return matchLogsEnabled && statsTrackingEnabled;
    }

    public static boolean isDatabaseSavingEnabled() {
        return databaseSavingEnabled;
    }

    public static boolean isAsyncStatsProcessing() {
        return asyncStatsProcessing;
    }

    public static boolean isReduceDbOperations() {
        return reduceDbOperations;
    }

    public static boolean isBatchDatabaseWrites() {
        return batchDatabaseWrites;
    }

    public static int getBatchSize() {
        return batchSize;
    }

    public static long getBatchFlushInterval() {
        return batchFlushInterval;
    }

    public static boolean isPvpOnlyTracking() {
        return pvpOnlyTracking;
    }

    public static boolean isDisableEnvironmentalTracking() {
        return disableEnvironmentalTracking;
    }

    public static boolean isOptimizeEventPriority() {
        return optimizeEventPriority;
    }

    public static boolean isCachePlayerMatches() {
        return cachePlayerMatches;
    }

    // ========================================
    // MÉTODOS DE UTILIDAD
    // ========================================

    /**
     * Verifica si cualquier tipo de trackeo está habilitado
     */
    public static boolean isAnyTrackingEnabled() {
        return statsTrackingEnabled && (
            killsTrackingEnabled ||
            damageTrackingEnabled ||
            arrowTrackingEnabled ||
            deathsTrackingEnabled
        );
    }

    /**
     * Modo ultra performance - deshabilita todo el trackeo
     */
    public static boolean isUltraPerformanceMode() {
        return !statsTrackingEnabled && !matchLogsEnabled && !databaseSavingEnabled;
    }

    /**
     * Recarga la configuración desde el archivo
     */
    public static void reload() {
        if (plugin != null) {
            plugin.reloadConfig();
            config = plugin.getConfig();
            loadConfiguration();
        }
    }
}
