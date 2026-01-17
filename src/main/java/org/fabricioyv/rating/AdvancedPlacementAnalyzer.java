package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.database.DatabaseManager;

import java.util.List;

/**
 * NUEVO: Analizador avanzado de placement matches
 * Evalúa patrones profundos en el historial para asignar rangos más precisos
 * Soluciona el problema de desequilibrio cuando hay 8 jugadores experimentados vs 2 nuevos
 */
public class AdvancedPlacementAnalyzer {

    /**
     * Analiza el historial completo de placement_match_history y calcula un rango inicial más preciso
     */
    public static PlacementAnalysisResult analyzeCompleteHistory(String playerUuid) {
        // Obtener historial completo de la base de datos
        List<DatabaseManager.PlacementMatchData> history = DatabaseManager.getPlayerPlacementMatches(playerUuid);

        if (history.size() < 8) {
            throw new IllegalStateException("Jugador no ha completado placement matches");
        }

        // Análisis multifacético del rendimiento
        MatchProgressionAnalysis progression = analyzeMatchProgression(history);
        PerformanceConsistency consistency = analyzePerformanceConsistency(history);
        SkillIndicators skillLevel = analyzeSkillIndicators(history);
        PressureResponse pressureHandling = analyzePressureResponse(history);

        // Calcular puntuación final basada en múltiples factores
        double finalScore = calculateComprehensiveScore(progression, consistency, skillLevel, pressureHandling);

        // Asignar ELO y MMR basado en la puntuación final
        int finalElo = calculateFinalElo(finalScore);
        double finalMMR = calculateFinalMMR(finalElo);

        // Determinar rango
        Rank assignedRank = Rank.getRankByElo(finalElo);

        // Generar reporte detallado
        String analysisReport = generateDetailedReport(progression, consistency, skillLevel, pressureHandling, finalScore, assignedRank);

        return new PlacementAnalysisResult(finalElo, finalMMR, assignedRank, analysisReport, finalScore);
    }

    /**
     * Analiza la progresión del jugador a lo largo de las 8 partidas
     */
    private static MatchProgressionAnalysis analyzeMatchProgression(List<DatabaseManager.PlacementMatchData> history) {
        double[] performanceScores = new double[8];
        int wins = 0;
        boolean showedImprovement = false;
        boolean consistentPerformance = true;

        for (int i = 0; i < history.size(); i++) {
            DatabaseManager.PlacementMatchData match = history.get(i);

            // Calcular score de rendimiento por partida (0-100)
            double kd = match.deaths > 0 ? (double) match.kills / match.deaths : match.kills;
            double damageScore = Math.min(100, match.damage / 30.0); // Normalizar damage
            double kdScore = Math.min(100, kd * 25); // Normalizar K/D
            double winBonus = match.won ? 30 : 0;

            performanceScores[i] = (damageScore * 0.4) + (kdScore * 0.4) + (winBonus * 0.2);

            if (match.won) wins++;
        }

        // Detectar tendencias
        double firstHalfAvg = (performanceScores[0] + performanceScores[1] + performanceScores[2] + performanceScores[3]) / 4;
        double secondHalfAvg = (performanceScores[4] + performanceScores[5] + performanceScores[6] + performanceScores[7]) / 4;

        showedImprovement = secondHalfAvg > firstHalfAvg + 5; // Mejora significativa

        // Calcular consistencia (menos variación = más consistente)
        double variance = calculateVariance(performanceScores);
        consistentPerformance = variance < 400; // Threshold de consistencia

        double winRate = (double) wins / 8 * 100;

        return new MatchProgressionAnalysis(performanceScores, winRate, showedImprovement, consistentPerformance, firstHalfAvg, secondHalfAvg);
    }

    /**
     * Analiza la consistencia del rendimiento
     */
    private static PerformanceConsistency analyzePerformanceConsistency(List<DatabaseManager.PlacementMatchData> history) {
        double[] kdRatios = new double[8];
        double[] damageValues = new double[8];
        int clutchWins = 0; // Victorias con buen rendimiento individual
        int badLosses = 0;  // Derrotas con mal rendimiento

        for (int i = 0; i < history.size(); i++) {
            DatabaseManager.PlacementMatchData match = history.get(i);
            kdRatios[i] = match.deaths > 0 ? (double) match.kills / match.deaths : match.kills;
            damageValues[i] = match.damage;

            // Detectar clutch wins y bad losses
            if (match.won && match.kills >= 4 && match.damage >= 1500) {
                clutchWins++;
            }
            if (!match.won && match.kills <= 2 && match.damage < 1000) {
                badLosses++;
            }
        }

        double kdConsistency = 100 - (calculateVariance(kdRatios) * 10); // Invertir varianza
        double damageConsistency = 100 - (calculateVariance(damageValues) / 10000); // Normalizar

        kdConsistency = Math.max(0, Math.min(100, kdConsistency));
        damageConsistency = Math.max(0, Math.min(100, damageConsistency));

        return new PerformanceConsistency(kdConsistency, damageConsistency, clutchWins, badLosses);
    }

    /**
     * Analiza indicadores de nivel de habilidad
     */
    private static SkillIndicators analyzeSkillIndicators(List<DatabaseManager.PlacementMatchData> history) {
        double totalKills = history.stream().mapToDouble(m -> m.kills).sum();
        double totalDeaths = history.stream().mapToDouble(m -> m.deaths).sum();
        double totalDamage = history.stream().mapToDouble(m -> m.damage).sum();

        double overallKD = totalDeaths > 0 ? totalKills / totalDeaths : totalKills;
        double avgDamage = totalDamage / 8;

        // Contar partidas destacadas (MVP-level performance)
        long mvpMatches = history.stream()
                .mapToLong(m -> (m.kills >= 6 && m.damage >= 2500) ? 1 : 0)
                .sum();

        // Contar partidas sólidas (buen rendimiento general)
        long solidMatches = history.stream()
                .mapToLong(m -> (m.kills >= 3 && m.damage >= 1200 && (m.deaths == 0 || (double)m.kills/m.deaths >= 1.0)) ? 1 : 0)
                .sum();

        // Determinar skill tier basado en métricas
        SkillTier skillTier;
        if (overallKD >= 2.0 && avgDamage >= 2200 && mvpMatches >= 3) {
            skillTier = SkillTier.HIGH_SKILL;
        } else if (overallKD >= 1.5 && avgDamage >= 1800 && solidMatches >= 5) {
            skillTier = SkillTier.GOOD_SKILL;
        } else if (overallKD >= 1.0 && avgDamage >= 1400 && solidMatches >= 3) {
            skillTier = SkillTier.AVERAGE_SKILL;
        } else if (overallKD >= 0.7 && avgDamage >= 1000) {
            skillTier = SkillTier.DEVELOPING_SKILL;
        } else {
            skillTier = SkillTier.LEARNING_SKILL;
        }

        return new SkillIndicators(overallKD, avgDamage, mvpMatches, solidMatches, skillTier);
    }

    /**
     * Analiza cómo responde el jugador bajo presión (partidas importantes)
     */
    private static PressureResponse analyzePressureResponse(List<DatabaseManager.PlacementMatchData> history) {
        // Las últimas 3 partidas son consideradas "bajo presión" (cuando el placement está en juego)
        double[] earlyMatches = new double[5]; // Partidas 1-5
        double[] pressureMatches = new double[3]; // Partidas 6-8

        for (int i = 0; i < 5; i++) {
            DatabaseManager.PlacementMatchData match = history.get(i);
            double performance = calculateMatchPerformance(match);
            earlyMatches[i] = performance;
        }

        for (int i = 0; i < 3; i++) {
            DatabaseManager.PlacementMatchData match = history.get(i + 5);
            double performance = calculateMatchPerformance(match);
            pressureMatches[i] = performance;
        }

        double earlyAvg = java.util.Arrays.stream(earlyMatches).average().orElse(0);
        double pressureAvg = java.util.Arrays.stream(pressureMatches).average().orElse(0);

        boolean performsUnderPressure = pressureAvg >= earlyAvg * 0.9; // Mantiene al menos 90% del rendimiento

        return new PressureResponse(earlyAvg, pressureAvg, performsUnderPressure);
    }

    /**
     * Calcula una puntuación comprehensiva final (0-1000)
     */
    private static double calculateComprehensiveScore(MatchProgressionAnalysis progression,
                                                      PerformanceConsistency consistency,
                                                      SkillIndicators skillLevel,
                                                      PressureResponse pressure) {
        double score = 0;

        // Peso por win rate (40% del score)
        score += (progression.winRate / 100.0) * 400;

        // Peso por skill level (30% del score)
        double skillScore = switch (skillLevel.skillTier) {
            case HIGH_SKILL -> 300;
            case GOOD_SKILL -> 230;
            case AVERAGE_SKILL -> 150;
            case DEVELOPING_SKILL -> 90;
            case LEARNING_SKILL -> 50;
        };
        score += skillScore;

        // Peso por consistencia (20% del score)
        double consistencyScore = ((consistency.kdConsistency + consistency.damageConsistency) / 2) * 2;
        score += consistencyScore;

        // Peso por respuesta a presión (10% del score)
        score += pressure.performsUnderPressure ? 100 : 50;

        // Bonificaciones adicionales
        if (progression.showedImprovement) score += 30;
        if (consistency.clutchWins >= 3) score += 25;
        if (skillLevel.mvpMatches >= 4) score += 35;

        // Penalizaciones
        if (consistency.badLosses >= 3) score -= 40;

        return Math.max(0, Math.min(1000, score));
    }

    /**
     * Convierte la puntuación final a ELO (más conservador)
     */
    private static int calculateFinalElo(double score) {
        // Mapeo conservador: Score 0-1000 → ELO 150-600
        // Esto evita que nuevos jugadores empiecen muy alto
        return (int) (150 + (score / 1000.0) * 450);
    }

    /**
     * Convierte ELO a MMR
     */
    private static double calculateFinalMMR(int elo) {
        return 800 + (elo / 600.0) * 400; // ELO 150-600 → MMR 900-1200
    }

    /**
     * Calcula el rendimiento de una partida individual
     */
    private static double calculateMatchPerformance(DatabaseManager.PlacementMatchData match) {
        double kd = match.deaths > 0 ? (double) match.kills / match.deaths : match.kills;
        double kdScore = Math.min(50, kd * 12.5);
        double damageScore = Math.min(40, match.damage / 50.0);
        double winBonus = match.won ? 10 : 0;
        return kdScore + damageScore + winBonus;
    }

    /**
     * Genera un reporte detallado del análisis
     */
    private static String generateDetailedReport(MatchProgressionAnalysis progression,
                                                 PerformanceConsistency consistency,
                                                 SkillIndicators skillLevel,
                                                 PressureResponse pressure,
                                                 double finalScore,
                                                 Rank assignedRank) {
        StringBuilder report = new StringBuilder();

        report.append("**🔍 ANÁLISIS AVANZADO DE PLACEMENT**\n");
        report.append(String.format("**Rango Final:** %s (Puntuación: %.0f/1000)\n\n",
                assignedRank.getFormattedName(), finalScore));

        report.append("**📈 Progresión:**\n");
        report.append(String.format("• Win Rate: %.1f%% (%d/8 victorias)\n", progression.winRate, (int)(progression.winRate * 8 / 100)));
        report.append(String.format("• Mejora detectada: %s\n", progression.showedImprovement ? "✅ Sí" : "❌ No"));
        report.append(String.format("• Rendimiento consistente: %s\n", progression.consistentPerformance ? "✅ Sí" : "❌ No"));

        report.append("\n**🎯 Nivel de Habilidad:**\n");
        report.append(String.format("• Tier: %s\n", skillLevel.skillTier.getDisplayName()));
        report.append(String.format("• K/D General: %.2f\n", skillLevel.overallKD));
        report.append(String.format("• Damage Promedio: %.0f\n", skillLevel.avgDamage));
        report.append(String.format("• Partidas MVP: %d/8\n", skillLevel.mvpMatches));

        report.append("\n**⚡ Consistencia:**\n");
        report.append(String.format("• Consistencia K/D: %.0f%%\n", consistency.kdConsistency));
        report.append(String.format("• Consistencia Damage: %.0f%%\n", consistency.damageConsistency));
        report.append(String.format("• Clutch Wins: %d\n", consistency.clutchWins));

        report.append("\n**🔥 Bajo Presión:**\n");
        report.append(String.format("• Mantiene rendimiento: %s\n", pressure.performsUnderPressure ? "✅ Sí" : "❌ No"));

        return report.toString();
    }

    private static double calculateVariance(double[] values) {
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        return java.util.Arrays.stream(values)
                .map(x -> Math.pow(x - mean, 2))
                .average().orElse(0);
    }

    // Clases de datos para el análisis
    public static class PlacementAnalysisResult {
        public final int finalElo;
        public final double finalMMR;
        public final Rank assignedRank;
        public final String analysisReport;
        public final double score;

        public PlacementAnalysisResult(int finalElo, double finalMMR, Rank assignedRank, String analysisReport, double score) {
            this.finalElo = finalElo;
            this.finalMMR = finalMMR;
            this.assignedRank = assignedRank;
            this.analysisReport = analysisReport;
            this.score = score;
        }
    }

    public static class MatchProgressionAnalysis {
        public final double[] performanceScores;
        public final double winRate;
        public final boolean showedImprovement;
        public final boolean consistentPerformance;
        public final double firstHalfAvg;
        public final double secondHalfAvg;

        public MatchProgressionAnalysis(double[] performanceScores, double winRate, boolean showedImprovement,
                                        boolean consistentPerformance, double firstHalfAvg, double secondHalfAvg) {
            this.performanceScores = performanceScores;
            this.winRate = winRate;
            this.showedImprovement = showedImprovement;
            this.consistentPerformance = consistentPerformance;
            this.firstHalfAvg = firstHalfAvg;
            this.secondHalfAvg = secondHalfAvg;
        }
    }

    public static class PerformanceConsistency {
        public final double kdConsistency;
        public final double damageConsistency;
        public final int clutchWins;
        public final int badLosses;

        public PerformanceConsistency(double kdConsistency, double damageConsistency, int clutchWins, int badLosses) {
            this.kdConsistency = kdConsistency;
            this.damageConsistency = damageConsistency;
            this.clutchWins = clutchWins;
            this.badLosses = badLosses;
        }
    }

    public static class SkillIndicators {
        public final double overallKD;
        public final double avgDamage;
        public final long mvpMatches;
        public final long solidMatches;
        public final SkillTier skillTier;

        public SkillIndicators(double overallKD, double avgDamage, long mvpMatches, long solidMatches, SkillTier skillTier) {
            this.overallKD = overallKD;
            this.avgDamage = avgDamage;
            this.mvpMatches = mvpMatches;
            this.solidMatches = solidMatches;
            this.skillTier = skillTier;
        }
    }

    public static class PressureResponse {
        public final double earlyAvg;
        public final double pressureAvg;
        public final boolean performsUnderPressure;

        public PressureResponse(double earlyAvg, double pressureAvg, boolean performsUnderPressure) {
            this.earlyAvg = earlyAvg;
            this.pressureAvg = pressureAvg;
            this.performsUnderPressure = performsUnderPressure;
        }
    }

    public enum SkillTier {
        HIGH_SKILL("Alto nivel"),
        GOOD_SKILL("Buen nivel"),
        AVERAGE_SKILL("Nivel promedio"),
        DEVELOPING_SKILL("En desarrollo"),
        LEARNING_SKILL("Aprendiendo");

        private final String displayName;

        SkillTier(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}