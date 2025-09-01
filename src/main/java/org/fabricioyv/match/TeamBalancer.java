package org.fabricioyv.match;

import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.model.PlayerData;

import java.util.*;
import java.util.stream.Collectors;

public class TeamBalancer {

    // Pesos para cada factor en el cálculo de skill rating
    private static final double ELO_WEIGHT = 0.40;
    private static final double MMR_WEIGHT = 0.30;
    private static final double WINRATE_WEIGHT = 0.20;
    private static final double EXPERIENCE_WEIGHT = 0.10;

    // Configuraciones de balanceo
    private static final int MAX_BALANCE_ATTEMPTS = 1000;
    private static final double ACCEPTABLE_DIFFERENCE = 50.0; // Diferencia máxima aceptable

    public static class PlayerSkillData {
        private final PlayerData player;
        private final double skillRating;
        private final double winRate;
        private final int totalGames;

        public PlayerSkillData(PlayerData player, double skillRating, double winRate, int totalGames) {
            this.player = player;
            this.skillRating = skillRating;
            this.winRate = winRate;
            this.totalGames = totalGames;
        }

        // Getters
        public PlayerData getPlayer() { return player; }
        public double getSkillRating() { return skillRating; }
        public double getWinRate() { return winRate; }
        public int getTotalGames() { return totalGames; }
    }

    public static class BalanceResult {
        private final List<PlayerData> team1;
        private final List<PlayerData> team2;
        private final double team1Average;
        private final double team2Average;
        private final double difference;
        private final String balanceDetails;

        public BalanceResult(List<PlayerData> team1, List<PlayerData> team2,
                             double team1Average, double team2Average, String balanceDetails) {
            this.team1 = team1;
            this.team2 = team2;
            this.team1Average = team1Average;
            this.team2Average = team2Average;
            this.difference = Math.abs(team1Average - team2Average);
            this.balanceDetails = balanceDetails;
        }

        // Getters
        public List<PlayerData> getTeam1() { return team1; }
        public List<PlayerData> getTeam2() { return team2; }
        public double getTeam1Average() { return team1Average; }
        public double getTeam2Average() { return team2Average; }
        public double getDifference() { return difference; }
        public String getBalanceDetails() { return balanceDetails; }
        public boolean isBalanced() { return difference <= ACCEPTABLE_DIFFERENCE; }
    }

    /**
     * Calcula el skill rating de un jugador basado en múltiples factores
     */
    public static PlayerSkillData calculatePlayerSkill(PlayerData player) {
        // Obtener estadísticas de la base de datos
     //   DatabaseManager.PlayerStats stats = DatabaseManager.getPlayerStats(player.getMinecraftUuid());



        // Calcular componentes del skill rating
        double eloComponent = player.getElo() * ELO_WEIGHT;
        double mmrComponent = player.getMmr() * MMR_WEIGHT;
        double winRateComponent = player.getWinRate() * WINRATE_WEIGHT * 10;


        // Factor de experiencia (más partidas = más confiable el rating)
        double experienceFactor = Math.min(1.0, player.getGamesPlayed() / 20.0);
        double experienceComponent = experienceFactor * EXPERIENCE_WEIGHT * 100;

        double skillRating = eloComponent + mmrComponent + winRateComponent + experienceComponent;

        return new PlayerSkillData(player, skillRating, player.getWinRate(), player.getGamesPlayed());
    }

    /**
     * Balancea equipos usando algoritmo inteligente
     */
    public static BalanceResult balanceTeams(List<PlayerData> players) {
        if (players.size() % 2 != 0) {
            throw new IllegalArgumentException("Número impar de jugadores: " + players.size());
        }

        // Calcular skill rating para cada jugador
        List<PlayerSkillData> skillData = players.stream()
                .map(TeamBalancer::calculatePlayerSkill)
                .collect(Collectors.toList());

        // Intentar balanceo con diferentes estrategias
        BalanceResult bestResult = null;

        // Estrategia 1: Balanceo por skill rating total
        BalanceResult skillResult = balanceBySkillRating(skillData);
        if (bestResult == null || skillResult.getDifference() < bestResult.getDifference()) {
            bestResult = skillResult;
        }

        // Estrategia 2: Balanceo híbrido (ELO + MMR)
        BalanceResult hybridResult = balanceHybrid(skillData);
        if (hybridResult.getDifference() < bestResult.getDifference()) {
            bestResult = hybridResult;
        }

        // Estrategia 3: Optimización por iteraciones si no está balanceado
        if (!bestResult.isBalanced()) {
            BalanceResult optimizedResult = optimizeBalance(skillData);
            if (optimizedResult.getDifference() < bestResult.getDifference()) {
                bestResult = optimizedResult;
            }
        }

        return bestResult;
    }

    /**
     * Balanceo basado en skill rating calculado
     */
    private static BalanceResult balanceBySkillRating(List<PlayerSkillData> skillData) {
        // Ordenar por skill rating descendente
        skillData.sort((a, b) -> Double.compare(b.getSkillRating(), a.getSkillRating()));

        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();
        double team1Skill = 0.0;
        double team2Skill = 0.0;

        // Snake draft inteligente
        boolean addToTeam1 = true;
        for (PlayerSkillData data : skillData) {
            if (addToTeam1) {
                team1.add(data.getPlayer());
                team1Skill += data.getSkillRating();
            } else {
                team2.add(data.getPlayer());
                team2Skill += data.getSkillRating();
            }

            // Alternar equipos, pero considerar balance actual
            if (team1.size() == team2.size()) {
                addToTeam1 = team1Skill > team2Skill;
            } else {
                addToTeam1 = team1.size() < team2.size();
            }
        }

        double team1Avg = team1Skill / team1.size();
        double team2Avg = team2Skill / team2.size();

        String details = String.format("Balanceo por Skill Rating - Equipo 1: %.1f, Equipo 2: %.1f",
                team1Avg, team2Avg);

        return new BalanceResult(team1, team2, team1Avg, team2Avg, details);
    }

    /**
     * Balanceo híbrido considerando ELO y MMR por separado
     */
    private static BalanceResult balanceHybrid(List<PlayerSkillData> skillData) {
        // Ordenar por ELO primero, luego por MMR
        skillData.sort((a, b) -> {
            int eloCompare = Integer.compare(b.getPlayer().getElo(), a.getPlayer().getElo());
            if (eloCompare != 0) return eloCompare;
            return Double.compare(b.getPlayer().getMmr(), a.getPlayer().getMmr());
        });

        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // Distribuir alternando, pero ajustando por MMR
        for (int i = 0; i < skillData.size(); i++) {
            PlayerSkillData data = skillData.get(i);

            double team1AvgMmr = team1.stream().mapToDouble(p -> p.getMmr()).average().orElse(0);
            double team2AvgMmr = team2.stream().mapToDouble(p -> p.getMmr()).average().orElse(0);

            if (team1.size() == team2.size()) {
                // Agregar al equipo con menor MMR promedio
                if (team1AvgMmr <= team2AvgMmr) {
                    team1.add(data.getPlayer());
                } else {
                    team2.add(data.getPlayer());
                }
            } else if (team1.size() < team2.size()) {
                team1.add(data.getPlayer());
            } else {
                team2.add(data.getPlayer());
            }
        }

        double team1Skill = team1.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);
        double team2Skill = team2.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);

        String details = String.format("Balanceo Híbrido - Equipo 1: %.1f, Equipo 2: %.1f",
                team1Skill, team2Skill);

        return new BalanceResult(team1, team2, team1Skill, team2Skill, details);
    }

    /**
     * Optimización por iteraciones para encontrar el mejor balance
     */
    private static BalanceResult optimizeBalance(List<PlayerSkillData> skillData) {
        List<PlayerData> players = skillData.stream()
                .map(PlayerSkillData::getPlayer)
                .collect(Collectors.toList());

        BalanceResult bestResult = null;
        Random random = new Random();

        for (int attempt = 0; attempt < MAX_BALANCE_ATTEMPTS; attempt++) {
            Collections.shuffle(players, random);

            List<PlayerData> team1 = new ArrayList<>(players.subList(0, players.size() / 2));
            List<PlayerData> team2 = new ArrayList<>(players.subList(players.size() / 2, players.size()));

            double team1Skill = team1.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);
            double team2Skill = team2.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);

            String details = String.format("Optimización (Intento %d) - Equipo 1: %.1f, Equipo 2: %.1f",
                    attempt + 1, team1Skill, team2Skill);

            BalanceResult result = new BalanceResult(team1, team2, team1Skill, team2Skill, details);

            if (bestResult == null || result.getDifference() < bestResult.getDifference()) {
                bestResult = result;

                // Si encontramos un balance aceptable, usar ese
                if (result.isBalanced()) {
                    break;
                }
            }
        }

        return bestResult;
    }

    /**
     * Genera un reporte detallado del balance
     */
    public static String generateBalanceReport(BalanceResult result) {
        StringBuilder report = new StringBuilder();

        report.append("=== REPORTE DE BALANCE ===\n");
        report.append(String.format("Diferencia: %.2f (", result.getDifference()));
        report.append(result.isBalanced() ? "BALANCEADO" : "DESBALANCEADO").append(")\n");
        report.append(result.getBalanceDetails()).append("\n\n");

        report.append("EQUIPO 1 (Promedio: ").append(String.format("%.1f", result.getTeam1Average())).append("):\n");
        for (PlayerData player : result.getTeam1()) {
            PlayerSkillData skill = calculatePlayerSkill(player);
            report.append(String.format("• %s - ELO: %d, MMR: %.1f, Skill: %.1f\n",
                    getPlayerName(player), player.getElo(), player.getMmr(), skill.getSkillRating()));
        }

        report.append("\nEQUIPO 2 (Promedio: ").append(String.format("%.1f", result.getTeam2Average())).append("):\n");
        for (PlayerData player : result.getTeam2()) {
            PlayerSkillData skill = calculatePlayerSkill(player);
            report.append(String.format("• %s - ELO: %d, MMR: %.1f, Skill: %.1f\n",
                    getPlayerName(player), player.getElo(), player.getMmr(), skill.getSkillRating()));
        }

        return report.toString();
    }

    private static String getPlayerName(PlayerData player) {
        try {
            org.bukkit.entity.Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                    java.util.UUID.fromString(player.getMinecraftUuid())
            );
            return mcPlayer != null ? mcPlayer.getName() : "UUID:" + player.getMinecraftUuid().substring(0, 8);
        } catch (Exception e) {
            return "UUID:" + player.getMinecraftUuid().substring(0, 8);
        }
    }
}