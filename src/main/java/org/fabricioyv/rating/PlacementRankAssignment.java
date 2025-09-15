package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;

/**
 * Sistema de evaluación final para placement matches
 * CORREGIDO: Asigna rangos basados en la clase Rank existente del sistema
 */
public class PlacementRankAssignment {

    // Usar los rangos reales del sistema existente (basado en ELO)
    // El sistema trabaja con ELO, no MMR separado

    /**
     * Analiza el rendimiento durante placement matches y asigna ELO final
     */
    public static PlacementResult calculateFinalPlacement(PlayerData player, PlacementMatchHistory history) {
        if (player.getPlacementMatchesPlayed() < PlayerData.getPlacementMatchesRequired()) {
            throw new IllegalStateException("El jugador no ha completado todas las placement matches");
        }

        // Calcular métricas de rendimiento
        PlacementMetrics metrics = calculatePlacementMetrics(history);

        // Determinar ELO base según win rate
        double baseElo = calculateBaseEloFromWinRate(metrics.winRate);

        // Aplicar modificadores por rendimiento individual
        double performanceMultiplier = calculatePerformanceMultiplier(metrics);

        // Aplicar modificadores por consistencia
        double consistencyMultiplier = calculateConsistencyMultiplier(metrics);

        // Calcular ELO final
        double finalElo = baseElo * performanceMultiplier * consistencyMultiplier;

        // Limitar a rangos válidos según el sistema existente
        finalElo = Math.max(0, Math.min(1300, finalElo)); // 0 = COBRE III, 1300+ = ESMERALDA

        // Determinar rango usando la clase Rank existente
        Rank assignedRank = Rank.getRankByElo((int) finalElo);

        // También actualizar MMR para el sistema de matchmaking
        double finalMMR = convertEloToMMR(finalElo);

        return new PlacementResult(finalElo, finalMMR, assignedRank, metrics,
                                 generatePlacementSummary(metrics, finalElo, assignedRank));
    }

    /**
     * Convierte ELO a MMR para el sistema de matchmaking
     */
    private static double convertEloToMMR(double elo) {
        // Conversión para mantener compatibilidad con el sistema de matchmaking
        // ELO rango 0-1200+ → MMR rango 800-1400+
        return 800 + (elo / 1200.0) * 600;
    }

    /**
     * Calcula ELO base según win rate (ajustado para ser más equilibrado y justo)
     */
    private static double calculateBaseEloFromWinRate(double winRate) {
        // SISTEMA MÁS EQUILIBRADO: Rango más comprimido y justo
        if (winRate >= 87.5) {       // 7-8 wins - Rendimiento excepcional
            return 700; // ORO II (en lugar de DIAMANTE II)
        } else if (winRate >= 75.0) { // 6 wins - Rendimiento sólido
            return 600; // ORO III (en lugar de ORO I)
        } else if (winRate >= 62.5) { // 5 wins - Rendimiento bueno
            return 500; // HIERRO II (en lugar de ORO III)
        } else if (winRate >= 50.0) { // 4 wins - Rendimiento promedio
            return 400; // HIERRO II (en lugar de HIERRO II)
        } else if (winRate >= 37.5) { // 3 wins - Rendimiento bajo
            return 300; // HIERRO III (en lugar de HIERRO III)
        } else if (winRate >= 25.0) { // 2 wins - Rendimiento muy bajo
            return 250; // COBRE I/HIERRO III (en lugar de COBRE I)
        } else {                     // 0-1 wins - Rendimiento malo
            return 200; // COBRE I (en lugar de COBRE II)
        }
    }

    /**
     * Calcula métricas de rendimiento durante placement
     */
    private static PlacementMetrics calculatePlacementMetrics(PlacementMatchHistory history) {
        double totalKills = history.matches.stream().mapToDouble(m -> m.kills).sum();
        double totalDeaths = history.matches.stream().mapToDouble(m -> m.deaths).sum();
        double totalDamage = history.matches.stream().mapToDouble(m -> m.damage).sum();
        long wins = history.matches.stream().mapToLong(m -> m.won ? 1 : 0).sum();

        double winRate = (double) wins / history.matches.size() * 100.0;
        double avgKills = totalKills / history.matches.size();
        double avgDeaths = totalDeaths / history.matches.size();
        double avgDamage = totalDamage / history.matches.size();
        double kd = totalDeaths > 0 ? totalKills / totalDeaths : totalKills;

        // Calcular consistencia (menos variación = más consistente)
        double damageVariance = calculateVariance(history.matches.stream().mapToDouble(m -> m.damage).toArray());
        double killVariance = calculateVariance(history.matches.stream().mapToDouble(m -> m.kills).toArray());

        // MVP performances (partidas destacadas)
        long mvpMatches = history.matches.stream()
                .mapToLong(m -> (m.kills >= 6 && m.damage >= 2500) ? 1 : 0).sum();

        return new PlacementMetrics(winRate, avgKills, avgDeaths, avgDamage, kd,
                                  damageVariance, killVariance, mvpMatches, history.matches.size());
    }

    /**
     * Multiplica según rendimiento individual (REDUCIDO para más equilibrio)
     */
    private static double calculatePerformanceMultiplier(PlacementMetrics metrics) {
        double multiplier = 1.0;

        // Modificadores REDUCIDOS por K/D
        if (metrics.kd >= 2.5) {
            multiplier += 0.08; // +8% (antes +15%)
        } else if (metrics.kd >= 2.0) {
            multiplier += 0.06; // +6% (antes +10%)
        } else if (metrics.kd >= 1.5) {
            multiplier += 0.03; // +3% (antes +5%)
        } else if (metrics.kd < 0.8) {
            multiplier -= 0.06; // -6% (antes -10%)
        } else if (metrics.kd < 1.0) {
            multiplier -= 0.03; // -3% (antes -5%)
        }

        // Modificadores REDUCIDOS por damage promedio
        if (metrics.avgDamage >= 2500) {
            multiplier += 0.07; // +7% (antes +12%)
        } else if (metrics.avgDamage >= 2000) {
            multiplier += 0.05; // +5% (antes +8%)
        } else if (metrics.avgDamage >= 1500) {
            multiplier += 0.02; // +2% (antes +4%)
        } else if (metrics.avgDamage < 1000) {
            multiplier -= 0.05; // -5% (antes -8%)
        } else if (metrics.avgDamage < 1200) {
            multiplier -= 0.02; // -2% (antes -4%)
        }

        // Modificadores REDUCIDOS por performances MVP
        if (metrics.mvpMatches >= 4) {
            multiplier += 0.10; // +10% (antes +20%)
        } else if (metrics.mvpMatches >= 2) {
            multiplier += 0.05; // +5% (antes +10%)
        } else if (metrics.mvpMatches >= 1) {
            multiplier += 0.02; // +2% (antes +5%)
        }

        // RANGO MÁS COMPRIMIDO: 80% a 125% (antes 70% a 150%)
        return Math.max(0.8, Math.min(1.25, multiplier));
    }

    /**
     * Modificador por consistencia (REDUCIDO para más equilibrio)
     */
    private static double calculateConsistencyMultiplier(PlacementMetrics metrics) {
        double multiplier = 1.0;

        // Normalizar varianzas (valores más bajos = más consistente)
        double normalizedDamageVar = Math.min(1.0, metrics.damageVariance / 1000000);
        double normalizedKillVar = Math.min(1.0, metrics.killVariance / 10);

        // Recompensar consistencia (REDUCIDO)
        if (normalizedDamageVar < 0.3 && normalizedKillVar < 0.3) {
            multiplier += 0.04; // +4% (antes +8%)
        } else if (normalizedDamageVar < 0.5 && normalizedKillVar < 0.5) {
            multiplier += 0.02; // +2% (antes +4%)
        } else if (normalizedDamageVar > 0.8 || normalizedKillVar > 0.8) {
            multiplier -= 0.03; // -3% (antes -5%)
        }

        // RANGO MÁS COMPRIMIDO: 95% a 110% (antes 90% a 115%)
        return Math.max(0.95, Math.min(1.10, multiplier));
    }

    /**
     * Genera un resumen detallado del placement
     */
    private static String generatePlacementSummary(PlacementMetrics metrics, double finalElo, Rank rank) {
        StringBuilder summary = new StringBuilder();

        summary.append("**🎯 PLACEMENT COMPLETADO**\n");
        summary.append(String.format("**Rango Asignado:** %s (%.0f ELO)\n\n",
                      rank.getFormattedName(), finalElo));

        summary.append("**📊 Rendimiento General:**\n");
        summary.append(String.format("• Victorias: %.0f/8 (%.1f%%)\n",
                      metrics.totalMatches * metrics.winRate / 100, metrics.winRate));
        summary.append(String.format("• K/D Promedio: %.2f\n", metrics.kd));
        summary.append(String.format("• Damage Promedio: %.0f\n", metrics.avgDamage));
        summary.append(String.format("• Partidas MVP: %d/8\n", metrics.mvpMatches));

        summary.append("\n**⭐ Destacados:**\n");
        if (metrics.winRate >= 75) {
            summary.append("• 🏆 Excelente tasa de victorias\n");
        }
        if (metrics.kd >= 2.0) {
            summary.append("• ⚔️ K/D excepcional\n");
        }
        if (metrics.avgDamage >= 2200) {
            summary.append("• 💥 Alto damage por partida\n");
        }
        if (metrics.mvpMatches >= 3) {
            summary.append("• 🌟 Múltiples performances MVP\n");
        }

        // Información del próximo rango
        if (rank.isPromotionPossible()) {
            Rank nextRank = rank.getNextRank();
            summary.append(String.format("\n**🎯 Próximo Objetivo:** %s (%d ELO)\n",
                          nextRank.getFormattedName(), nextRank.getMinElo()));
        }

        return summary.toString();
    }

    /**
     * Calcula varianza de un array de valores
     */
    private static double calculateVariance(double[] values) {
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        return java.util.Arrays.stream(values)
                .map(x -> Math.pow(x - mean, 2))
                .average().orElse(0);
    }

    // Clases de datos para almacenar resultados
    public static class PlacementResult {
        public final double finalElo;
        public final double finalMMR;
        public final Rank assignedRank;
        public final PlacementMetrics metrics;
        public final String summary;

        public PlacementResult(double finalElo, double finalMMR, Rank assignedRank,
                             PlacementMetrics metrics, String summary) {
            this.finalElo = finalElo;
            this.finalMMR = finalMMR;
            this.assignedRank = assignedRank;
            this.metrics = metrics;
            this.summary = summary;
        }
    }

    public static class PlacementMetrics {
        public final double winRate;
        public final double avgKills;
        public final double avgDeaths;
        public final double avgDamage;
        public final double kd;
        public final double damageVariance;
        public final double killVariance;
        public final long mvpMatches;
        public final int totalMatches;

        public PlacementMetrics(double winRate, double avgKills, double avgDeaths, double avgDamage,
                              double kd, double damageVariance, double killVariance, long mvpMatches, int totalMatches) {
            this.winRate = winRate;
            this.avgKills = avgKills;
            this.avgDeaths = avgDeaths;
            this.avgDamage = avgDamage;
            this.kd = kd;
            this.damageVariance = damageVariance;
            this.killVariance = killVariance;
            this.mvpMatches = mvpMatches;
            this.totalMatches = totalMatches;
        }
    }

    public static class PlacementMatchHistory {
        public final java.util.List<MatchRecord> matches;

        public PlacementMatchHistory(java.util.List<MatchRecord> matches) {
            this.matches = matches;
        }
    }

    public static class MatchRecord {
        public final boolean won;
        public final int kills;
        public final int deaths;
        public final double damage;
        public final String matchId;

        public MatchRecord(boolean won, int kills, int deaths, double damage, String matchId) {
            this.won = won;
            this.kills = kills;
            this.deaths = deaths;
            this.damage = damage;
            this.matchId = matchId;
        }
    }
}
