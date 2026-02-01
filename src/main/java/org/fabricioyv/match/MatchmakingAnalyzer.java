package org.fabricioyv.match;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.MMRCalculator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulador y analizador avanzado de matchmaking
 * Herramientas para testing y optimización del sistema de balanceo
 */
public class MatchmakingAnalyzer {

    /**
     * Simula múltiples configuraciones de matchmaking para encontrar la óptima
     */
    public static MatchmakingSimulationResult simulateMatchmaking(List<PlayerData> players, int iterations) {
        if (players.size() != 10) {
            throw new IllegalArgumentException("Se requieren exactamente 10 jugadores para la simulación");
        }

        List<BalanceResult> results = new ArrayList<>();
        double bestBalance = Double.MAX_VALUE;
        PlacementAwareMatchmaking.BalancedTeams bestTeams = null;

        // Simular múltiples configuraciones
        for (int i = 0; i < iterations; i++) {
            // Mezclar jugadores para diferentes configuraciones
            List<PlayerData> shuffledPlayers = new ArrayList<>(players);
            Collections.shuffle(shuffledPlayers);

            try {
                PlacementAwareMatchmaking.BalancedTeams teams =
                        PlacementAwareMatchmaking.balanceTeamsWithPlacement(shuffledPlayers);

                if (teams != null) {
                    double mmrDifference = teams.getMMRDifference();
                    boolean isAcceptable = PlacementAwareMatchmaking.isMatchmakingAcceptable(teams);

                    results.add(new BalanceResult(mmrDifference, isAcceptable, i));

                    if (mmrDifference < bestBalance) {
                        bestBalance = mmrDifference;
                        bestTeams = teams;
                    }
                }
            } catch (Exception e) {
                // Ignorar configuraciones que fallen
            }
        }

        return new MatchmakingSimulationResult(results, bestTeams, bestBalance);
    }

    /**
     * Analiza la distribución de MMR en un pool de jugadores
     */
    public static PlayerPoolAnalysis analyzePlayerPool(List<PlayerData> players) {
        if (players.isEmpty()) {
            return new PlayerPoolAnalysis(0, 0, 0, 0, 0, 0, 0, 0);
        }

        // Estadísticas básicas
        int totalPlayers = players.size();
        int placementPlayers = (int) players.stream().filter(PlayerData::isInPlacement).count();
        int regularPlayers = totalPlayers - placementPlayers;

        // Estadísticas de MMR
        double avgMMR = players.stream().mapToDouble(PlayerData::getMmr).average().orElse(0);
        double minMMR = players.stream().mapToDouble(PlayerData::getMmr).min().orElse(0);
        double maxMMR = players.stream().mapToDouble(PlayerData::getMmr).max().orElse(0);

        // Calcular desviación estándar de MMR
        double variance = players.stream()
                .mapToDouble(p -> Math.pow(p.getMmr() - avgMMR, 2))
                .average().orElse(0);
        double stdDeviation = Math.sqrt(variance);

        // Experiencia promedio
        double avgGamesPlayed = players.stream()
                .mapToDouble(PlayerData::getGamesPlayed)
                .average().orElse(0);

        return new PlayerPoolAnalysis(
                totalPlayers, placementPlayers, regularPlayers,
                avgMMR, minMMR, maxMMR, stdDeviation, avgGamesPlayed
        );
    }

    /**
     * Predice la calidad del matchmaking antes de ejecutarlo
     */
    public static MatchmakingPrediction predictMatchmakingQuality(List<PlayerData> players) {
        if (players.size() != 10) {
            return new MatchmakingPrediction(MatchmakingPrediction.Quality.IMPOSSIBLE,
                    "Se requieren exactamente 10 jugadores", 0.0);
        }

        PlayerPoolAnalysis analysis = analyzePlayerPool(players);

        // Factor 1: Varianza de MMR
        double mmrVarianceFactor = analysis.mmrStdDeviation / 200.0; // Normalizar

        // Factor 2: Proportion de placement players
        double placementFactor = Math.abs(5.0 - analysis.placementPlayers) / 5.0;

        // Factor 3: Rango de MMR
        double mmrRangeFactor = (analysis.maxMMR - analysis.minMMR) / 1000.0;

        // Factor 4: Experiencia desbalanceada
        double experienceFactor = analysis.avgGamesPlayed < 10 ? 0.3 : 0.0;

        // Calcular score de dificultad (0-1, menor es mejor)
        double difficultyScore = (mmrVarianceFactor + placementFactor + mmrRangeFactor + experienceFactor) / 4.0;

        // Determinar calidad esperada
        MatchmakingPrediction.Quality quality;
        String reason;

        if (difficultyScore <= 0.2) {
            quality = MatchmakingPrediction.Quality.EXCELLENT;
            reason = "Pool muy equilibrado, matchmaking óptimo esperado";
        } else if (difficultyScore <= 0.4) {
            quality = MatchmakingPrediction.Quality.GOOD;
            reason = "Pool bien balanceado, buen matchmaking esperado";
        } else if (difficultyScore <= 0.6) {
            quality = MatchmakingPrediction.Quality.ACCEPTABLE;
            reason = "Pool moderadamente desbalanceado, matchmaking aceptable";
        } else if (difficultyScore <= 0.8) {
            quality = MatchmakingPrediction.Quality.POOR;
            reason = "Pool muy desbalanceado, matchmaking problemático";
        } else {
            quality = MatchmakingPrediction.Quality.TERRIBLE;
            reason = "Pool extremadamente desbalanceado, matchmaking muy malo";
        }

        return new MatchmakingPrediction(quality, reason, difficultyScore);
    }

    /**
     * Genera reporte completo de análisis de matchmaking
     */
    public static String generateAnalysisReport(List<PlayerData> players) {
        StringBuilder report = new StringBuilder();

        PlayerPoolAnalysis poolAnalysis = analyzePlayerPool(players);
        MatchmakingPrediction prediction = predictMatchmakingQuality(players);

        report.append("🔬 **ANÁLISIS AVANZADO DE MATCHMAKING**\n\n");

        // Estadísticas del pool
        report.append("📊 **Pool de Jugadores:**\n");
        report.append(String.format("• Total: %d jugadores\n", poolAnalysis.totalPlayers));
        report.append(String.format("• En Placement: %d | Regulares: %d\n",
                poolAnalysis.placementPlayers, poolAnalysis.regularPlayers));
        report.append(String.format("• MMR Promedio: %.0f\n", poolAnalysis.avgMMR));
        report.append(String.format("• Rango MMR: %.0f - %.0f\n", poolAnalysis.minMMR, poolAnalysis.maxMMR));
        report.append(String.format("• Desviación Estándar: %.0f\n", poolAnalysis.mmrStdDeviation));
        report.append(String.format("• Experiencia Promedio: %.1f partidas\n", poolAnalysis.avgGamesPlayed));

        // Predicción de calidad
        report.append("\n🎯 **Predicción de Calidad:**\n");
        report.append(String.format("• Calidad Esperada: %s\n", prediction.quality.getDisplayName()));
        report.append(String.format("• Razón: %s\n", prediction.reason));
        report.append(String.format("• Score de Dificultad: %.2f/1.00\n", prediction.difficultyScore));

        // Recomendaciones
        report.append("\n💡 **Recomendaciones:**\n");
        if (poolAnalysis.mmrStdDeviation > 300) {
            report.append("• ⚠️ Alta varianza de MMR - considerar esperar más jugadores\n");
        }
        if (Math.abs(poolAnalysis.placementPlayers - 5) > 2) {
            report.append("• ⚠️ Distribución desigual de placement players\n");
        }
        if (poolAnalysis.avgGamesPlayed < 5) {
            report.append("• ⚠️ Muchos jugadores novatos - mayor volatilidad esperada\n");
        }
        if (prediction.quality.ordinal() >= MatchmakingPrediction.Quality.POOR.ordinal()) {
            report.append("• ❌ Recomendado: Esperar mejor pool de jugadores\n");
        } else {
            report.append("• ✅ Pool aceptable para matchmaking\n");
        }

        return report.toString();
    }

    // Clases de datos para resultados

    public static class MatchmakingSimulationResult {
        public final List<BalanceResult> results;
        public final PlacementAwareMatchmaking.BalancedTeams bestTeams;
        public final double bestBalance;
        public final double avgBalance;
        public final int acceptableResults;

        public MatchmakingSimulationResult(List<BalanceResult> results,
                                           PlacementAwareMatchmaking.BalancedTeams bestTeams,
                                           double bestBalance) {
            this.results = results;
            this.bestTeams = bestTeams;
            this.bestBalance = bestBalance;
            this.avgBalance = results.stream().mapToDouble(r -> r.mmrDifference).average().orElse(0);
            this.acceptableResults = (int) results.stream().filter(r -> r.isAcceptable).count();
        }
    }

    public static class BalanceResult {
        public final double mmrDifference;
        public final boolean isAcceptable;
        public final int iteration;

        public BalanceResult(double mmrDifference, boolean isAcceptable, int iteration) {
            this.mmrDifference = mmrDifference;
            this.isAcceptable = isAcceptable;
            this.iteration = iteration;
        }
    }

    public static class PlayerPoolAnalysis {
        public final int totalPlayers;
        public final int placementPlayers;
        public final int regularPlayers;
        public final double avgMMR;
        public final double minMMR;
        public final double maxMMR;
        public final double mmrStdDeviation;
        public final double avgGamesPlayed;

        public PlayerPoolAnalysis(int totalPlayers, int placementPlayers, int regularPlayers,
                                  double avgMMR, double minMMR, double maxMMR,
                                  double mmrStdDeviation, double avgGamesPlayed) {
            this.totalPlayers = totalPlayers;
            this.placementPlayers = placementPlayers;
            this.regularPlayers = regularPlayers;
            this.avgMMR = avgMMR;
            this.minMMR = minMMR;
            this.maxMMR = maxMMR;
            this.mmrStdDeviation = mmrStdDeviation;
            this.avgGamesPlayed = avgGamesPlayed;
        }
    }

    public static class MatchmakingPrediction {
        public final Quality quality;
        public final String reason;
        public final double difficultyScore;

        public MatchmakingPrediction(Quality quality, String reason, double difficultyScore) {
            this.quality = quality;
            this.reason = reason;
            this.difficultyScore = difficultyScore;
        }

        public enum Quality {
            EXCELLENT("Excelente"),
            GOOD("Bueno"),
            ACCEPTABLE("Aceptable"),
            POOR("Malo"),
            TERRIBLE("Terrible"),
            IMPOSSIBLE("Imposible");

            private final String displayName;

            Quality(String displayName) {
                this.displayName = displayName;
            }

            public String getDisplayName() {
                return displayName;
            }
        }
    }
}