package org.fabricioyv.config;

/**
 * Configuración para el sistema de estadísticas optimizado
 */
public class StatsOptimizationConfig {

    // Activar/desactivar el sistema optimizado
    public static final boolean USE_OPTIMIZED_STATS = true;

    // Configuración del sistema de batch
    public static final int BATCH_SIZE = 20;                    // Eventos por lote
    public static final int BATCH_INTERVAL_MS = 2000;           // Intervalo de procesamiento (2 segundos)
    public static final int MEMORY_CLEANUP_INTERVAL_SECONDS = 300; // Limpieza cada 5 minutos
    public static final int MAX_PENDING_EVENTS = 100;           // Máximo eventos pendientes

    // Configuración de rendimiento
    public static final int DATABASE_BATCH_SIZE = 50;           // Sub-lotes para BD
    public static final int EVENT_RETENTION_MINUTES = 5;        // Retener eventos 5 minutos
    public static final boolean ENABLE_PERFORMANCE_LOGGING = false; // Logs de rendimiento

    /**
     * Determina si usar el sistema optimizado o el legacy
     */
    public static boolean shouldUseOptimizedStats() {
        return USE_OPTIMIZED_STATS;
    }

    /**
     * Configuración dinámica basada en carga del servidor
     */
    public static int getDynamicBatchSize() {
        // Ajustar tamaño de lote basado en TPS del servidor
        double tps = getCurrentTPS();

        if (tps < 15.0) {
            return BATCH_SIZE * 2; // Lotes más grandes si hay lag
        } else if (tps > 19.0) {
            return Math.max(5, BATCH_SIZE / 2); // Lotes más pequeños si va bien
        }

        return BATCH_SIZE;
    }

    /**
     * Obtiene TPS aproximado del servidor
     */
    private static double getCurrentTPS() {
        try {
            // Método simple para estimar TPS
            return org.bukkit.Bukkit.getServer().getTPS()[0];
        } catch (Exception e) {
            return 20.0; // Valor por defecto
        }
    }
}
