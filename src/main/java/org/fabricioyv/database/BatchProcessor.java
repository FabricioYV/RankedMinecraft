package org.fabricioyv.database;

import org.bukkit.Bukkit;
import org.fabricioyv.RankedMinecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Procesador de lotes para operaciones de base de datos
 * Evita bloquear el main thread agrupando operaciones
 */
public class BatchProcessor {

    // Cola thread-safe para operaciones pendientes
    private static final BlockingQueue<DatabaseOperation> operationQueue = new LinkedBlockingQueue<>();

    // Configuración de batch processing
    private static final int BATCH_SIZE = 25; // Procesar hasta 25 operaciones por lote
    private static final int BATCH_TIMEOUT_MS = 1500; // Procesar cada 1.5 segundos máximo

    // Worker thread para procesar batches
    private static final ExecutorService batchWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DB-BatchProcessor");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); // Prioridad ligeramente menor
        return t;
    });

    // Control de shutdown
    private static final AtomicBoolean isShutdown = new AtomicBoolean(false);

    // Estadísticas de rendimiento
    private static volatile long totalOperationsProcessed = 0;
    private static volatile long totalBatchesProcessed = 0;
    private static volatile long lastBatchTime = 0;

    static {
        startBatchProcessor();
    }

    /**
     * Operación de base de datos genérica
     */
    public interface DatabaseOperation {
        void execute() throws Exception;
        String getOperationType();
        long getTimestamp();
    }

    /**
     * Operación para actualizar estadísticas de jugador
     */
    public static class PlayerStatsUpdate implements DatabaseOperation {
        private final DatabaseManager.PlayerStatUpdate update;
        private final long timestamp;

        public PlayerStatsUpdate(DatabaseManager.PlayerStatUpdate update) {
            this.update = update;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public void execute() throws Exception {
            // Esta operación se agregará a un batch más grande
            // La ejecución real se maneja en processBatch()
        }

        @Override
        public String getOperationType() {
            return "PLAYER_STATS_UPDATE";
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        public DatabaseManager.PlayerStatUpdate getUpdate() {
            return update;
        }
    }

    /**
     * Operación para actualizar estado de partida
     */
    public static class MatchStatusUpdate implements DatabaseOperation {
        private final String minecraftUuid;
        private final boolean isInMatch;
        private final String currentMatchId;
        private final long timestamp;

        public MatchStatusUpdate(String minecraftUuid, boolean isInMatch, String currentMatchId) {
            this.minecraftUuid = minecraftUuid;
            this.isInMatch = isInMatch;
            this.currentMatchId = currentMatchId;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public void execute() throws Exception {
            DatabaseManager.updatePlayerMatchStatus(minecraftUuid, isInMatch, currentMatchId);
        }

        @Override
        public String getOperationType() {
            return "MATCH_STATUS_UPDATE";
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Inicia el procesador de batches
     */
    private static void startBatchProcessor() {
        batchWorker.submit(() -> {
            while (!isShutdown.get()) {
                try {
                    processBatch();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Bukkit.getLogger().warning("Error en batch processor: " + e.getMessage());
                    // Continuar procesando en caso de error
                }
            }
        });
    }

    /**
     * Procesa un lote de operaciones
     */
    private static void processBatch() throws InterruptedException {
        List<DatabaseOperation> batch = new ArrayList<>();

        // Esperar por al menos una operación (bloquea hasta que llegue)
        DatabaseOperation firstOp = operationQueue.take();
        batch.add(firstOp);

        // Recoger operaciones adicionales hasta el límite o timeout
        long batchStartTime = System.currentTimeMillis();

        while (batch.size() < BATCH_SIZE &&
                (System.currentTimeMillis() - batchStartTime) < BATCH_TIMEOUT_MS) {

            DatabaseOperation op = operationQueue.poll(100, TimeUnit.MILLISECONDS);
            if (op != null) {
                batch.add(op);
            }
        }

        // Procesar el batch agrupando por tipo
        try {
            executeBatch(batch);
            totalOperationsProcessed += batch.size();
            totalBatchesProcessed++;
            lastBatchTime = System.currentTimeMillis();

            // Log de rendimiento cada 50 batches
            if (totalBatchesProcessed % 50 == 0) {
                Bukkit.getLogger().info(String.format(
                        "§a📊 BatchProcessor Stats: %d batches, %d ops total, avg %.1f ops/batch",
                        totalBatchesProcessed, totalOperationsProcessed,
                        (double) totalOperationsProcessed / totalBatchesProcessed
                ));
            }

        } catch (Exception e) {
            Bukkit.getLogger().severe("Error ejecutando batch de " + batch.size() + " operaciones: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ejecuta un batch agrupando operaciones similares
     */
    private static void executeBatch(List<DatabaseOperation> batch) {
        // Agrupar operaciones por tipo para máxima eficiencia
        List<DatabaseManager.PlayerStatUpdate> playerUpdates = new ArrayList<>();
        List<DatabaseOperation> otherOperations = new ArrayList<>();

        for (DatabaseOperation op : batch) {
            if (op instanceof PlayerStatsUpdate) {
                playerUpdates.add(((PlayerStatsUpdate) op).getUpdate());
            } else {
                otherOperations.add(op);
            }
        }

        // Ejecutar updates de jugadores en batch (más eficiente)
        if (!playerUpdates.isEmpty()) {
            try {
                DatabaseManager.updatePlayerStats(playerUpdates);
            } catch (Exception e) {
                Bukkit.getLogger().warning("Error en batch update de players: " + e.getMessage());
            }
        }

        // Ejecutar otras operaciones individualmente
        for (DatabaseOperation op : otherOperations) {
            try {
                op.execute();
            } catch (Exception e) {
                Bukkit.getLogger().warning("Error ejecutando operación " + op.getOperationType() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Encola una operación para procesamiento asíncrono
     * NUNCA bloquea el main thread
     */
    public static boolean enqueueOperation(DatabaseOperation operation) {
        if (isShutdown.get()) {
            return false;
        }

        try {
            // offer() no bloquea, retorna false si la cola está llena
            boolean added = operationQueue.offer(operation);

            if (!added) {
                Bukkit.getLogger().warning("⚠️ Cola de batch processor llena, descartando operación " + operation.getOperationType());
            }

            return added;
        } catch (Exception e) {
            Bukkit.getLogger().warning("Error encolando operación: " + e.getMessage());
            return false;
        }
    }

    /**
     * Métodos de conveniencia para operaciones comunes
     */
    public static boolean enqueuePlayerStatsUpdate(DatabaseManager.PlayerStatUpdate update) {
        return enqueueOperation(new PlayerStatsUpdate(update));
    }

    public static boolean enqueueMatchStatusUpdate(String uuid, boolean inMatch, String matchId) {
        return enqueueOperation(new MatchStatusUpdate(uuid, inMatch, matchId));
    }

    /**
     * Forzar procesamiento inmediato de operaciones pendientes
     */
    public static void flushPendingOperations() {
        Bukkit.getLogger().info("🔄 Forzando flush de operaciones pendientes...");

        // Dar tiempo al worker para procesar operaciones pendientes
        try {
            Thread.sleep(BATCH_TIMEOUT_MS + 500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Obtener estadísticas de rendimiento
     */
    public static String getPerformanceStats() {
        int pendingOps = operationQueue.size();
        double avgOpsPerBatch = totalBatchesProcessed > 0 ?
                (double) totalOperationsProcessed / totalBatchesProcessed : 0;

        return String.format(
                "BatchProcessor: %d ops procesadas, %d batches, %.1f ops/batch promedio, %d pendientes",
                totalOperationsProcessed, totalBatchesProcessed, avgOpsPerBatch, pendingOps
        );
    }

    /**
     * Shutdown limpio del batch processor
     */
    public static void shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            Bukkit.getLogger().info("🛑 Cerrando BatchProcessor...");

            // Procesar operaciones pendientes
            flushPendingOperations();

            // Cerrar worker thread
            batchWorker.shutdown();

            try {
                if (!batchWorker.awaitTermination(5, TimeUnit.SECONDS)) {
                    batchWorker.shutdownNow();
                    Bukkit.getLogger().warning("BatchProcessor forzadamente cerrado");
                } else {
                    Bukkit.getLogger().info("✅ BatchProcessor cerrado correctamente");
                }
            } catch (InterruptedException e) {
                batchWorker.shutdownNow();
                Thread.currentThread().interrupt();
            }

            Bukkit.getLogger().info("📊 " + getPerformanceStats());
        }
    }
}