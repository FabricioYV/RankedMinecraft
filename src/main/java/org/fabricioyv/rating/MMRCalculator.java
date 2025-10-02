package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;

public class MMRCalculator {

    // Factores rebalanceados priorizando daño
    private static final double DAMAGE_WEIGHT = 0.035; // Incrementado significativamente
    private static final double KILL_WEIGHT = 5.0; // Reducido - kills pueden ser robadas
    private static final double DEATH_WEIGHT = -10.0; // Mantenido alto - deaths importan
    private static final double WIN_WEIGHT = 18.0;
    private static final double LOSS_WEIGHT = -12.0;
    private static final double DAMAGE_RATIO_BONUS = 15.0; // Nuevo: bonus por daño/muerte

    // Límites para cambios extremos de MMR
    private static final double MAX_MMR_CHANGE = 85.0;
    private static final double MIN_MMR_CHANGE = -60.0;

    // CORREGIDO: MMR mínimo más alto para evitar trampa de MMR bajo
    private static final double MIN_MMR_ALLOWED = 600.0; // Mínimo MMR permitido (no 100)
    // MAX_MMR no necesario, el sistema es abierto hacia arriba

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

        public double getNewMMR() { return newMMR; }

        public String getDetailedMessage() {
            return String.format("MMR: %.1f → %.1f (%+.1f) | %s",
                    oldMMR, newMMR, change, performanceBreakdown);
        }
    }

    /**
     * Sistema MMR mejorado centrado en daño para PvP 5v5
     * Detecta automáticamente si el jugador está en placement matches
     */
    public static MMRChange calculateMMRChange(PlayerData player, boolean won,
                                               double teamAvgMMR, double opponentAvgMMR) {
        
        // Si el jugador está en placement matches, usar el calculador especializado
        if (player.isInPlacement()) {
            MMRChange placementResult = PlacementMMRCalculator.calculatePlacementMMRChange(
                player, won, teamAvgMMR, opponentAvgMMR);
            
            return placementResult;
        }
        
        // Código normal para jugadores post-placement
        double oldMMR = player.getMmr();
        double mmrChange = 0.0;
        StringBuilder breakdown = new StringBuilder();

        // Factor de experiencia para ajustar volatilidad
        double experienceFactor = getExperienceFactor(player);

        // 1. Resultado base con probabilidad esperada
        double mmrDifference = opponentAvgMMR - teamAvgMMR;
        double expectedWinChance = 1.0 / (1.0 + Math.pow(10, -mmrDifference / 400.0));

        double resultBonus;
        if (won) {
            resultBonus = WIN_WEIGHT * (1.5 - expectedWinChance) * experienceFactor;
            breakdown.append("Victoria (").append(String.format("%+.1f", resultBonus)).append(")");
        } else {
            resultBonus = LOSS_WEIGHT * (0.5 + expectedWinChance) * experienceFactor;
            breakdown.append("Derrota (").append(String.format("%.1f", resultBonus)).append(")");
        }
        mmrChange += resultBonus;

        // 2. DAÑO - Factor principal del rendimiento
        double damageScore = calculateDamageScore(player, opponentAvgMMR, experienceFactor);
        mmrChange += damageScore;

        if (Math.abs(damageScore) > 3.0) {
            breakdown.append(", Daño (").append(String.format("%+.1f", damageScore)).append(")");
        }

        // 3. Ratio Daño por Muerte (más importante que K/D)
        double damagePerDeath = player.getCurrentMatchDeaths() > 0 ?
                player.getCurrentMatchDamage() / player.getCurrentMatchDeaths() :
                player.getCurrentMatchDamage();

        double damageRatioBonus = calculateDamageRatioBonus(damagePerDeath, mmrDifference);
        mmrChange += damageRatioBonus;

        // 4. Contribución de kills (peso menor, anti-kill-stealing)
        double killScore = calculateKillScore(player, mmrDifference);
        mmrChange += killScore;

        // 5. Penalización por deaths
        double deathPenalty = player.getCurrentMatchDeaths() * DEATH_WEIGHT * experienceFactor;
        mmrChange += deathPenalty;

        // 6. Detección y corrección de kill stealing
        if (isLikelyKillStealing(player)) {
            mmrChange *= 0.8; // Reducir ganancia si parece kill stealing
            breakdown.append(" [KS]");
        }

        // Estadísticas en breakdown
        breakdown.append(" | DMG:").append(String.format("%.0f", player.getCurrentMatchDamage()))
                .append(" K:").append(player.getCurrentMatchKills())
                .append(" D:").append(player.getCurrentMatchDeaths())
                .append(" DPD:").append(String.format("%.0f", damagePerDeath));

        // Limitar cambios extremos
        mmrChange = Math.max(MIN_MMR_CHANGE, Math.min(MAX_MMR_CHANGE, mmrChange));

        double newMMR = Math.max(MIN_MMR_ALLOWED, oldMMR + mmrChange);

        return new MMRChange(oldMMR, newMMR, breakdown.toString());
    }

    /**
     * Calcula score de daño contextualizado por nivel de oponentes
     */
    private static double calculateDamageScore(PlayerData player, double opponentAvgMMR, double experienceFactor) {
        double actualDamage = player.getCurrentMatchDamage();

        // Daño esperado basado en MMR del jugador vs oponentes
        double skillDifference = (player.getMmr() - opponentAvgMMR) / 200.0;
        double expectedDamage = 1000.0 + (skillDifference * 400.0); // Base 1000, ajustado por skill

        // Score principal por daño total
        double damageScore = actualDamage * DAMAGE_WEIGHT * experienceFactor;

        // Bonus/penalización por superar/no alcanzar expectativas
        double performanceVsExpected = (actualDamage - expectedDamage) / 150.0;
        damageScore += performanceVsExpected * 3.0;

        return Math.max(-25.0, Math.min(40.0, damageScore));
    }

    /**
     * Bonus por ratio Daño/Muerte - más relevante que K/D
     */
    private static double calculateDamageRatioBonus(double damagePerDeath, double mmrDifference) {
        double baseBonus = 0.0;

        if (damagePerDeath >= 1800) { // Excelente eficiencia
            baseBonus = DAMAGE_RATIO_BONUS * 1.3;
        } else if (damagePerDeath >= 1200) { // Buena eficiencia
            baseBonus = DAMAGE_RATIO_BONUS * 0.8;
        } else if (damagePerDeath >= 800) { // Promedio
            baseBonus = DAMAGE_RATIO_BONUS * 0.3;
        } else if (damagePerDeath <= 300) { // Muy malo
            baseBonus = -DAMAGE_RATIO_BONUS;
        }

        // Ajustar según dificultad del oponente
        double difficultyMultiplier = 1.0 + (mmrDifference / 1200.0);
        return baseBonus * Math.max(0.6, Math.min(1.4, difficultyMultiplier));
    }

    /**
     * Score de kills anti-kill-stealing
     */
    private static double calculateKillScore(PlayerData player, double mmrDifference) {
        double kills = player.getCurrentMatchKills();
        double damage = player.getCurrentMatchDamage();

        // Damage por kill - detectar calidad de kills
        double damagePerKill = kills > 0 ? damage / kills : 0;

        double killScore = kills * KILL_WEIGHT;

        // Penalizar kills con poco daño (posible kill stealing)
        if (damagePerKill < 150 && kills > 3) {
            killScore *= 0.6; // Reducir valor de kills de baja calidad
        } else if (damagePerKill > 300) {
            killScore *= 1.2; // Bonus por kills de alta calidad
        }

        return Math.max(-20.0, Math.min(25.0, killScore));
    }

    /**
     * Detecta posible kill stealing
     */
    private static boolean isLikelyKillStealing(PlayerData player) {
        double kills = player.getCurrentMatchKills();
        double damage = player.getCurrentMatchDamage();

        if (kills <= 2) return false; // Pocas kills, no evaluar

        double damagePerKill = damage / kills;

        // Kill stealing probable si tiene muchas kills pero poco daño por kill
        return damagePerKill < 120 && kills >= 5;
    }

    /**
     * Factor de experiencia para ajustar volatilidad del MMR
     */
    private static double getExperienceFactor(PlayerData player) {
        int gamesPlayed = player.getGamesPlayed();

        if (gamesPlayed < 5) return 1.6; // Novatos: cambios grandes
        if (gamesPlayed < 15) return 1.3;
        if (gamesPlayed < 40) return 1.1;
        if (gamesPlayed < 80) return 1.0;
        return 0.85; // Veteranos: cambios menores
    }

    /**
     * Calcula MMR promedio de un equipo
     */
    public static double calculateAverageMMR(java.util.List<PlayerData> players) {
        if (players == null || players.isEmpty()) {
            return 1000.0;
        }

        return players.stream()
                .mapToDouble(PlayerData::getMmr)
                .average()
                .orElse(1000.0);
    }
}