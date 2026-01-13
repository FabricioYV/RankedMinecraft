package org.fabricioyv.match;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.MMRCalculator;
import org.fabricioyv.database.PlacementHistoryManager;
import org.fabricioyv.rating.PlacementRankAssignment;
import java.util.*;

/**
 * Sistema de matchmaking avanzado que mejora el balance considerando 
 * rendimiento estimado en placement matches y jugadores establecidos
 */
public class SmartPlacementMatchmaking {

    // Configuración para balance mejorado
    private static final double MAX_TEAM_MMR_DIFFERENCE = 100.0; // Diferencia máxima entre equipos
    private static final double PLACEMENT_PREDICTION_WEIGHT = 0.6; // Peso del MMR predicho vs base

    /**
     * Balancea equipos usando predicción de MMR para jugadores en placement
     */
    public static BalancedTeams balanceTeamsWithPrediction(List<PlayerData> players) {
        if (players.size() != 10) {
            throw new IllegalArgumentException("El matchmaking requiere exactamente 10 jugadores");
        }

        // Calcular MMR predicho para cada jugador
        List<PlayerWithPredictedMMR> playersWithMMR = new ArrayList<>();
        for (PlayerData player : players) {
            double predictedMMR = calculatePredictedMMR(player);
            playersWithMMR.add(new PlayerWithPredictedMMR(player, predictedMMR));
        }

        // Ordenar por MMR predicho
        playersWithMMR.sort((p1, p2) -> Double.compare(p2.predictedMMR, p1.predictedMMR));

        // Encontrar el mejor balance posible
        return findOptimalBalance(playersWithMMR);
    }

    /**
     * Calcula MMR predicho basado en historial de placement o MMR establecido
     */
    private static double calculatePredictedMMR(PlayerData player) {
        if (!player.isInPlacement()) {
            // Jugador establecido: usar MMR real
            return player.getMmr();
        }

        // Jugador en placement: estimar MMR basado en partidas jugadas
        if (player.getPlacementMatchesPlayed() == 0) {
            // Sin historial: MMR base conservador
            return 1000.0;
        }

        // Obtener historial y hacer predicción
        PlacementRankAssignment.PlacementMatchHistory history =
                PlacementHistoryManager.getPlacementHistory(player.getMinecraftUuid());

        if (history.matches.isEmpty()) {
            return 1000.0;
        }

        // Calcular métricas parciales
        double wins = history.matches.stream().mapToDouble(m -> m.won ? 1 : 0).sum();
        double winRate = wins / history.matches.size() * 100.0;
        double avgKills = history.matches.stream().mapToDouble(m -> m.kills).average().orElse(0);
        double avgDeaths = history.matches.stream().mapToDouble(m -> m.deaths).average().orElse(1);
        double avgDamage = history.matches.stream().mapToDouble(m -> m.damage).average().orElse(0);
        double kd = avgKills / Math.max(1, avgDeaths);

        // Predicción de MMR basada en rendimiento parcial
        double predictedMMR = calculatePartialPrediction(winRate, kd, avgDamage, history.matches.size());

        // Mezclar con MMR base para suavizar
        double currentMMR = player.getMmr();
        return (predictedMMR * PLACEMENT_PREDICTION_WEIGHT) + (currentMMR * (1 - PLACEMENT_PREDICTION_WEIGHT));
    }

    /**
     * Predicción de MMR basada en estadísticas parciales
     */
    private static double calculatePartialPrediction(double winRate, double kd, double avgDamage, int matchesPlayed) {
        // Base según win rate
        double baseMmr;
        if (winRate >= 80) baseMmr = 1500;
        else if (winRate >= 66) baseMmr = 1300;
        else if (winRate >= 50) baseMmr = 1100;
        else if (winRate >= 33) baseMmr = 950;
        else baseMmr = 800;

        // Modificadores por rendimiento
        double modifier = 1.0;

        // K/D impact
        if (kd >= 2.0) modifier += 0.15;
        else if (kd >= 1.5) modifier += 0.08;
        else if (kd >= 1.2) modifier += 0.04;
        else if (kd < 0.8) modifier -= 0.10;
        else if (kd < 1.0) modifier -= 0.05;

        // Damage impact
        if (avgDamage >= 2200) modifier += 0.12;
        else if (avgDamage >= 1800) modifier += 0.06;
        else if (avgDamage >= 1400) modifier += 0.03;
        else if (avgDamage < 1000) modifier -= 0.08;
        else if (avgDamage < 1200) modifier -= 0.04;

        // Confianza basada en partidas jugadas (más partidas = más confiable)
        double confidence = Math.min(1.0, matchesPlayed / 5.0); // 100% confianza a las 5 partidas

        double predictedMMR = baseMmr * modifier;

        // Si poca confianza, tender hacia MMR conservador
        if (confidence < 0.5) {
            predictedMMR = (predictedMMR * confidence) + (1000.0 * (1 - confidence));
        }

        return Math.max(700, Math.min(1800, predictedMMR));
    }

    /**
     * Encuentra el balance óptimo usando algoritmo mejorado
     */
    private static BalancedTeams findOptimalBalance(List<PlayerWithPredictedMMR> players) {
        BalancedTeams bestBalance = null;
        double bestScore = Double.MAX_VALUE;

        // Probar múltiples combinaciones usando diferentes estrategias
        List<BalancedTeams> candidates = new ArrayList<>();

        // Estrategia 1: Snake draft (1-2-2-1-1-2-2-1)
        candidates.add(createSnakeDraft(players));

        // Estrategia 2: Distribuir top players alternadamente
        candidates.add(createAlternatingTop(players));

        // Estrategia 3: Balance por suma total
        candidates.add(createSumBalance(players));

        // Evaluar cada candidato
        for (BalancedTeams candidate : candidates) {
            double score = evaluateBalance(candidate);
            if (score < bestScore) {
                bestScore = score;
                bestBalance = candidate;
            }
        }

        return bestBalance != null ? bestBalance : createSnakeDraft(players);
    }

    /**
     * Snake draft: 1-2-2-1-1-2-2-1
     */
    private static BalancedTeams createSnakeDraft(List<PlayerWithPredictedMMR> players) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        int[] pattern = {1, 2, 2, 1, 1, 2, 2, 1}; // 1=team1, 2=team2

        for (int i = 0; i < 10; i++) {
            if (pattern[i % 8] == 1) {
                team1.add(players.get(i).player);
            } else {
                team2.add(players.get(i).player);
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Distribución alternada de top players
     */
    private static BalancedTeams createAlternatingTop(List<PlayerWithPredictedMMR> players) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            if (i % 2 == 0) {
                team1.add(players.get(i).player);
            } else {
                team2.add(players.get(i).player);
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Balance por suma total de MMR
     */
    private static BalancedTeams createSumBalance(List<PlayerWithPredictedMMR> players) {
        List<PlayerWithPredictedMMR> remaining = new ArrayList<>(players);
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();
        double team1MMR = 0, team2MMR = 0;

        // Agregar jugadores uno por uno al equipo con menor MMR total
        while (!remaining.isEmpty()) {
            PlayerWithPredictedMMR player = remaining.remove(0);

            if (team1.size() == 5) {
                team2.add(player.player);
                team2MMR += player.predictedMMR;
            } else if (team2.size() == 5) {
                team1.add(player.player);
                team1MMR += player.predictedMMR;
            } else if (team1MMR <= team2MMR) {
                team1.add(player.player);
                team1MMR += player.predictedMMR;
            } else {
                team2.add(player.player);
                team2MMR += player.predictedMMR;
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Evalúa qué tan balanceado está un equipo (menor score = mejor)
     */
    private static double evaluateBalance(BalancedTeams teams) {
        double team1MMR = calculateTeamPredictedMMR(teams.team1);
        double team2MMR = calculateTeamPredictedMMR(teams.team2);

        // Diferencia absoluta de MMR (factor principal)
        double mmrDifference = Math.abs(team1MMR - team2MMR);

        // Penalizar distribución desigual de placement players
        long team1Placement = teams.team1.stream().mapToLong(p -> p.isInPlacement() ? 1 : 0).sum();
        long team2Placement = teams.team2.stream().mapToLong(p -> p.isInPlacement() ? 1 : 0).sum();
        double placementImbalance = Math.abs(team1Placement - team2Placement) * 25;

        // Penalizar equipos con MMR muy alto o muy bajo
        double avgMMR = (team1MMR + team2MMR) / 2;
        double extremePenalty = 0;
        if (avgMMR < 900 || avgMMR > 1400) {
            extremePenalty = Math.abs(avgMMR - 1150) * 0.1;
        }

        return mmrDifference + placementImbalance + extremePenalty;
    }

    /**
     * Calcula MMR predicho promedio de un equipo
     */
    private static double calculateTeamPredictedMMR(List<PlayerData> team) {
        return team.stream()
                .mapToDouble(SmartPlacementMatchmaking::calculatePredictedMMR)
                .average()
                .orElse(1000.0);
    }

    /**
     * Genera reporte detallado del matchmaking
     */
    public static String generateSmartMatchmakingReport(BalancedTeams teams) {
        StringBuilder report = new StringBuilder();

        double team1MMR = calculateTeamPredictedMMR(teams.team1);
        double team2MMR = calculateTeamPredictedMMR(teams.team2);
        double difference = Math.abs(team1MMR - team2MMR);

        long team1Placement = teams.team1.stream().mapToLong(p -> p.isInPlacement() ? 1 : 0).sum();
        long team2Placement = teams.team2.stream().mapToLong(p -> p.isInPlacement() ? 1 : 0).sum();

        report.append("**🎯 SMART MATCHMAKING REPORT**\n");
        report.append(String.format("🔵 **Equipo 1**: %.0f MMR predicho (%d en placement)\n", team1MMR, team1Placement));
        report.append(String.format("🔴 **Equipo 2**: %.0f MMR predicho (%d en placement)\n", team2MMR, team2Placement));
        report.append(String.format("⚖️ **Diferencia**: %.0f MMR\n", difference));

        // Calidad del balance
        String quality;
        if (difference <= 30) quality = "🟢 **Excelente**";
        else if (difference <= 60) quality = "🟡 **Bueno**";
        else if (difference <= 100) quality = "🟠 **Aceptable**";
        else quality = "🔴 **Desbalanceado**";

        report.append(String.format("📊 **Calidad**: %s\n", quality));

        if (team1Placement > 0 || team2Placement > 0) {
            report.append("\n💡 **MMR predicho** basado en rendimiento en placement matches");
        }

        return report.toString();
    }

    // Clases auxiliares
    private static class PlayerWithPredictedMMR {
        final PlayerData player;
        final double predictedMMR;

        PlayerWithPredictedMMR(PlayerData player, double predictedMMR) {
            this.player = player;
            this.predictedMMR = predictedMMR;
        }
    }

    public static class BalancedTeams {
        public final List<PlayerData> team1;
        public final List<PlayerData> team2;

        public BalancedTeams(List<PlayerData> team1, List<PlayerData> team2) {
            this.team1 = new ArrayList<>(team1);
            this.team2 = new ArrayList<>(team2);
        }
    }
}