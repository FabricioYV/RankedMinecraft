package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;

public class MMRCalculator {

    // Factores de peso para el cálculo de MMR
    private static final double KILL_WEIGHT = 12.0;
    private static final double DEATH_WEIGHT = -8.0;
    private static final double DAMAGE_WEIGHT = 0.008; // Por cada punto de daño
    private static final double WIN_WEIGHT = 20.0;
    private static final double LOSS_WEIGHT = -15.0;
    private static final double KD_RATIO_BONUS = 8.0;

    // Límites para cambios extremos de MMR
    private static final double MAX_MMR_CHANGE = 85.0;
    private static final double MIN_MMR_CHANGE = -60.0;

    public static class MMRChange {
        private final double oldMMR;
        private final double newMMR;
        private final double change;
        private final String performanceBreakdown;

        public MMRChange(double oldMMR, double newMMR, String performanceBreakdown) {
            this.oldMMR = oldMMR;
            this.newMMR = newMMR;
            this.change = newMMR - oldMMR;
            this.performanceBreakdown = performanceBreakdown;
        }

        public double getOldMMR() { return oldMMR; }
        public double getNewMMR() { return newMMR; }
        public double getChange() { return change; }
        public String getPerformanceBreakdown() { return performanceBreakdown; }

        public String getChangeMessage() {
            return String.format("MMR: %.1f → %.1f (%+.1f)",
                    oldMMR, newMMR, change);
        }

        public String getDetailedMessage() {
            return String.format("MMR: %.1f → %.1f (%+.1f) | %s",
                    oldMMR, newMMR, change, performanceBreakdown);
        }
    }

    /**
     * Calcula el cambio de MMR basado en rendimiento individual y resultado
     */
    public static MMRChange calculateMMRChange(PlayerData player, boolean won,
                                               double teamAvgMMR, double opponentAvgMMR) {
        double oldMMR = player.getMmr();
        double mmrChange = 0.0;
        StringBuilder breakdown = new StringBuilder();

        // 1. Resultado base de la partida
        if (won) {
            mmrChange += WIN_WEIGHT;
            breakdown.append("Victoria (+").append(WIN_WEIGHT).append(")");
        } else {
            mmrChange += LOSS_WEIGHT;
            breakdown.append("Derrota (").append(LOSS_WEIGHT).append(")");
        }

        // 2. Ajuste por diferencia de MMR entre equipos
        double mmrDifference = opponentAvgMMR - teamAvgMMR;
        double difficultyBonus = mmrDifference * 0.12; // 12% de la diferencia
        mmrChange += difficultyBonus;

        if (Math.abs(difficultyBonus) > 1.5) {
            breakdown.append(", Dif.MMR (").append(String.format("%+.1f", difficultyBonus)).append(")");
        }

        // 3. Rendimiento individual - Kills
        double killBonus = player.getCurrentMatchKills() * KILL_WEIGHT;
        mmrChange += killBonus;

        // 4. Rendimiento individual - Deaths
        double deathPenalty = player.getCurrentMatchDeaths() * DEATH_WEIGHT;
        mmrChange += deathPenalty;

        // 5. Rendimiento individual - Damage
        double damageBonus = player.getCurrentMatchDamage() * DAMAGE_WEIGHT;
        mmrChange += damageBonus;

        // 6. Bonus por K/D ratio excepcional
        double kdRatio = player.getCurrentMatchDeaths() > 0 ?
                (double) player.getCurrentMatchKills() / player.getCurrentMatchDeaths() :
                player.getCurrentMatchKills();

        double kdBonus = 0.0;
        if (kdRatio >= 3.0) {
            kdBonus = KD_RATIO_BONUS * 1.5; // Bonus grande por K/D excelente
        } else if (kdRatio >= 2.0) {
            kdBonus = KD_RATIO_BONUS; // Bonus estándar
        } else if (kdRatio <= 0.3) {
            kdBonus = -KD_RATIO_BONUS; // Penalización por K/D muy bajo
        }

        mmrChange += kdBonus;

        // Agregar estadísticas al breakdown
        breakdown.append(" | K:").append(player.getCurrentMatchKills())
                .append(" D:").append(player.getCurrentMatchDeaths())
                .append(" KD:").append(String.format("%.2f", kdRatio))
                .append(" DMG:").append(String.format("%.0f", player.getCurrentMatchDamage()));

        // Limitar cambios extremos
        mmrChange = Math.max(MIN_MMR_CHANGE, Math.min(MAX_MMR_CHANGE, mmrChange));

        double newMMR = Math.max(100, oldMMR + mmrChange); // MMR mínimo de 100

        return new MMRChange(oldMMR, newMMR, breakdown.toString());
    }

    /**
     * Calcula MMR promedio de un equipo
     */
    public static double calculateAverageMMR(java.util.List<PlayerData> players) {
        if (players == null || players.isEmpty()) {
            return 1000.0; // MMR base por defecto
        }

        return players.stream()
                .mapToDouble(PlayerData::getMmr)
                .average()
                .orElse(1000.0);
    }

    /**
     * Calcula una puntuación de rendimiento normalizada (0-10)
     */
    public static double calculatePerformanceScore(PlayerData player) {
        int kills = player.getCurrentMatchKills();
        int deaths = player.getCurrentMatchDeaths();
        double damage = player.getCurrentMatchDamage();

        // Puntuación base por K/D
        double kdScore = deaths > 0 ? (double) kills / deaths : kills;
        kdScore = Math.min(5.0, kdScore); // Máximo 5 puntos por K/D

        // Puntuación por daño (normalizada)
        double damageScore = Math.min(3.0, damage / 1000.0); // Máximo 3 puntos por daño

        // Puntuación por kills
        double killScore = Math.min(2.0, kills * 0.4); // Máximo 2 puntos por kills

        return kdScore + damageScore + killScore;
    }
}