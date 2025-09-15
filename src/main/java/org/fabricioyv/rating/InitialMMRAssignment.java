package org.fabricioyv.rating;

import java.util.Random;

/**
 * Sistema de asignación de MMR inicial para jugadores nuevos
 * Distribuye los MMR iniciales para evitar matchmaking aleatorio
 */
public class InitialMMRAssignment {

    // Rango de MMR inicial más conservador
    private static final double BASE_MMR = 950.0;           // MMR base más bajo
    private static final double MMR_VARIANCE = 100.0;       // Variación de ±100 MMR
    private static final double MIN_INITIAL_MMR = 850.0;    // Mínimo inicial
    private static final double MAX_INITIAL_MMR = 1050.0;   // Máximo inicial

    private static final Random random = new Random();

    /**
     * Calcula MMR inicial para un jugador nuevo usando distribución inteligente
     */
    public static double calculateInitialMMR(String playerUuid) {
        // Usar el UUID del jugador como semilla para consistencia
        // Mismo jugador = mismo MMR inicial si se re-registra
        long seed = playerUuid.hashCode();
        Random playerRandom = new Random(seed);

        // Distribución normal centrada en BASE_MMR
        double variance = (playerRandom.nextGaussian() * MMR_VARIANCE * 0.3); // 30% de la varianza
        double initialMMR = BASE_MMR + variance;

        // Aplicar límites
        initialMMR = Math.max(MIN_INITIAL_MMR, Math.min(MAX_INITIAL_MMR, initialMMR));

        return Math.round(initialMMR);
    }

    /**
     * Versión alternativa que considera información adicional del jugador
     */
    public static double calculateInitialMMRWithInfo(String playerUuid, String minecraftUsername) {
        // MMR base
        double initialMMR = calculateInitialMMR(playerUuid);

        // Pequeños ajustes basados en nombre (opcional, para más variación)
        int nameHash = Math.abs(minecraftUsername.toLowerCase().hashCode());
        double nameVariance = (nameHash % 41) - 20; // Variación de -20 a +20

        initialMMR += nameVariance;

        // Aplicar límites finales
        return Math.max(MIN_INITIAL_MMR, Math.min(MAX_INITIAL_MMR, initialMMR));
    }

    /**
     * Obtiene estadísticas de la distribución de MMR inicial
     */
    public static InitialMMRStats getInitialMMRStats() {
        return new InitialMMRStats(
            BASE_MMR,
            MIN_INITIAL_MMR,
            MAX_INITIAL_MMR,
            MMR_VARIANCE
        );
    }

    /**
     * Clase para estadísticas del sistema de MMR inicial
     */
    public static class InitialMMRStats {
        public final double baseMmr;
        public final double minMmr;
        public final double maxMmr;
        public final double variance;

        public InitialMMRStats(double baseMmr, double minMmr, double maxMmr, double variance) {
            this.baseMmr = baseMmr;
            this.minMmr = minMmr;
            this.maxMmr = maxMmr;
            this.variance = variance;
        }

        @Override
        public String toString() {
            return String.format("MMR Inicial: %.0f±%.0f (rango: %.0f-%.0f)",
                               baseMmr, variance, minMmr, maxMmr);
        }
    }
}
