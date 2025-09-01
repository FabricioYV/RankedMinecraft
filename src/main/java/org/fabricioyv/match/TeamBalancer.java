package org.fabricioyv.match;

import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.model.PlayerData;

import java.util.*;
import java.util.stream.Collectors;

public class TeamBalancer {

    // Pesos rebalanceados priorizando capacidad de daño
    private static final double MMR_WEIGHT = 0.45; // Incrementado
    private static final double ELO_WEIGHT = 0.25; // Reducido
    private static final double DAMAGE_POTENTIAL_WEIGHT = 0.20; // Nuevo: basado en daño histórico
    private static final double EXPERIENCE_WEIGHT = 0.10;

    // Configuraciones de balanceo
    private static final int MAX_BALANCE_ATTEMPTS = 1500;
    private static final double ACCEPTABLE_DIFFERENCE = 40.0; // Más estricto para 5v5

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

        public List<PlayerData> getTeam1() { return team1; }
        public List<PlayerData> getTeam2() { return team2; }
        public double getTeam1Average() { return team1Average; }
        public double getTeam2Average() { return team2Average; }
        public double getDifference() { return difference; }
        public String getBalanceDetails() { return balanceDetails; }
        public boolean isBalanced() { return difference <= ACCEPTABLE_DIFFERENCE; }
    }

    /**
     * Calcula skill rating mejorado priorizando capacidad de daño
     */
    public static PlayerSkillData calculatePlayerSkill(PlayerData player) {
        // Componente MMR (factor principal)
        double mmrComponent = player.getMmr() * MMR_WEIGHT;

        // Componente ELO
        double eloComponent = player.getElo() * ELO_WEIGHT;

        // Potencial de daño basado en estadísticas históricas
        double damagePotential = calculateDamagePotential(player);
        double damageComponent = damagePotential * DAMAGE_POTENTIAL_WEIGHT;

        // Factor de experiencia (confiabilidad del rating)
        double experienceFactor = Math.min(1.0, player.getGamesPlayed() / 25.0);
        double experienceComponent = experienceFactor * EXPERIENCE_WEIGHT * 100;

        double skillRating = mmrComponent + eloComponent + damageComponent + experienceComponent;

        return new PlayerSkillData(player, skillRating, player.getWinRate(), player.getGamesPlayed());
    }

    /**
     * Calcula el potencial de daño del jugador basado en estadísticas
     */
    private static double calculateDamagePotential(PlayerData player) {
        if (player.getGamesPlayed() == 0) return 800.0; // Valor base para nuevos jugadores

        // Estimación de daño promedio basado en K/D y MMR
        double avgKD = player.getTotalDeaths() > 0 ?
                (double) player.getTotalKills() / player.getTotalDeaths() :
                player.getTotalKills();

        // Modelo de daño basado en eficiencia y MMR
        double estimatedAvgDamage = (avgKD * 200) + (player.getMmr() * 0.8);

        // Ajustar por experiencia (más confiable con más partidas)
        double reliabilityFactor = Math.min(1.0, player.getGamesPlayed() / 20.0);

        return estimatedAvgDamage * reliabilityFactor + (800.0 * (1.0 - reliabilityFactor));
    }

    /**
     * Balanceo optimizado para PvP 5v5 competitivo
     */
    public static BalanceResult balanceTeams(List<PlayerData> players) {
        if (players.size() != 10) {
            throw new IllegalArgumentException("Se requieren exactamente 10 jugadores para 5v5, recibidos: " + players.size());
        }

        // Calcular skill data para cada jugador
        List<PlayerSkillData> skillData = players.stream()
                .map(TeamBalancer::calculatePlayerSkill)
                .collect(Collectors.toList());

        BalanceResult bestResult = null;

        // Estrategia 1: Balanceo por skill rating optimizado
        BalanceResult skillResult = balanceByEnhancedSkillRating(skillData);
        bestResult = skillResult;

        // Estrategia 2: Balanceo considerando roles (top players distribuidos)
        BalanceResult roleResult = balanceByRoleDistribution(skillData);
        if (roleResult.getDifference() < bestResult.getDifference()) {
            bestResult = roleResult;
        }

        // Estrategia 3: Optimización iterativa si no está balanceado
        if (!bestResult.isBalanced()) {
            BalanceResult optimizedResult = optimizeBalance(skillData);
            if (optimizedResult.getDifference() < bestResult.getDifference()) {
                bestResult = optimizedResult;
            }
        }

        return bestResult;
    }

    /**
     * Balanceo mejorado por skill rating con distribución inteligente
     */
    private static BalanceResult balanceByEnhancedSkillRating(List<PlayerSkillData> skillData) {
        // Ordenar por skill rating descendente
        skillData.sort((a, b) -> Double.compare(b.getSkillRating(), a.getSkillRating()));

        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();
        double team1Skill = 0.0;
        double team2Skill = 0.0;

        // Distribuir jugadores top alternadamente, luego optimizar el resto
        for (int i = 0; i < skillData.size(); i++) {
            PlayerSkillData data = skillData.get(i);

            if (team1.size() == team2.size()) {
                // Si están igualados en cantidad, elegir el equipo con menor skill total
                if (team1Skill <= team2Skill) {
                    team1.add(data.getPlayer());
                    team1Skill += data.getSkillRating();
                } else {
                    team2.add(data.getPlayer());
                    team2Skill += data.getSkillRating();
                }
            } else if (team1.size() < team2.size()) {
                team1.add(data.getPlayer());
                team1Skill += data.getSkillRating();
            } else {
                team2.add(data.getPlayer());
                team2Skill += data.getSkillRating();
            }
        }

        double team1Avg = team1Skill / team1.size();
        double team2Avg = team2Skill / team2.size();

        String details = String.format("Enhanced Skill Balance - T1: %.1f, T2: %.1f", team1Avg, team2Avg);
        return new BalanceResult(team1, team2, team1Avg, team2Avg, details);
    }

    /**
     * Balanceo por distribución de roles (asegurar que cada equipo tenga carry potential)
     */
    private static BalanceResult balanceByRoleDistribution(List<PlayerSkillData> skillData) {
        // Ordenar por skill rating
        skillData.sort((a, b) -> Double.compare(b.getSkillRating(), a.getSkillRating()));

        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // Primero distribuir los top 4 jugadores (2 por equipo - potential carries)
        team1.add(skillData.get(0).getPlayer()); // Mejor jugador a team1
        team2.add(skillData.get(1).getPlayer()); // Segundo mejor a team2
        team2.add(skillData.get(2).getPlayer()); // Tercero a team2 (compensar)
        team1.add(skillData.get(3).getPlayer()); // Cuarto a team1

        // Distribuir el resto balanceando skill total
        for (int i = 4; i < skillData.size(); i++) {
            PlayerSkillData data = skillData.get(i);

            double team1CurrentSkill = team1.stream()
                    .mapToDouble(p -> calculatePlayerSkill(p).getSkillRating())
                    .sum();
            double team2CurrentSkill = team2.stream()
                    .mapToDouble(p -> calculatePlayerSkill(p).getSkillRating())
                    .sum();

            if (team1.size() == team2.size()) {
                if (team1CurrentSkill <= team2CurrentSkill) {
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

        double team1Avg = team1.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);
        double team2Avg = team2.stream().mapToDouble(p -> calculatePlayerSkill(p).getSkillRating()).average().orElse(0);

        String details = String.format("Role Distribution - T1: %.1f, T2: %.1f", team1Avg, team2Avg);
        return new BalanceResult(team1, team2, team1Avg, team2Avg, details);
    }

    /**
     * Optimización iterativa intensiva para 5v5
     */
    private static BalanceResult optimizeBalance(List<PlayerSkillData> skillData) {
        List<PlayerData> players = skillData.stream()
                .map(PlayerSkillData::getPlayer)
                .collect(Collectors.toList());

        BalanceResult bestResult = null;
        Random random = new Random();

        for (int attempt = 0; attempt < MAX_BALANCE_ATTEMPTS; attempt++) {
            Collections.shuffle(players, random);

            List<PlayerData> team1 = new ArrayList<>(players.subList(0, 5));
            List<PlayerData> team2 = new ArrayList<>(players.subList(5, 10));

            double team1Skill = team1.stream()
                    .mapToDouble(p -> calculatePlayerSkill(p).getSkillRating())
                    .average().orElse(0);
            double team2Skill = team2.stream()
                    .mapToDouble(p -> calculatePlayerSkill(p).getSkillRating())
                    .average().orElse(0);

            String details = String.format("Optimización (Int %d) - T1: %.1f, T2: %.1f",
                    attempt + 1, team1Skill, team2Skill);

            BalanceResult result = new BalanceResult(team1, team2, team1Skill, team2Skill, details);

            if (bestResult == null || result.getDifference() < bestResult.getDifference()) {
                bestResult = result;

                if (result.isBalanced()) {
                    break;
                }
            }
        }

        return bestResult;
    }

    /**
     * Genera reporte detallado del balance para 5v5
     */
    public static String generateBalanceReport(BalanceResult result) {
        StringBuilder report = new StringBuilder();

        report.append("=== BALANCE REPORT 5v5 RANKED ===\n");
        report.append(String.format("Diferencia: %.2f (", result.getDifference()));
        report.append(result.isBalanced() ? "✅ BALANCEADO" : "⚠️ DESBALANCEADO").append(")\n");
        report.append(result.getBalanceDetails()).append("\n\n");

        report.append("🔴 EQUIPO 1 (Avg: ").append(String.format("%.1f", result.getTeam1Average())).append("):\n");
        result.getTeam1().forEach(player -> {
            PlayerSkillData skill = calculatePlayerSkill(player);
            report.append(String.format("  • %s - MMR: %.0f | ELO: %d | Skill: %.0f | DMG Est: %.0f\n",
                    getPlayerName(player), player.getMmr(), player.getElo(),
                    skill.getSkillRating(), calculateDamagePotential(player)));
        });

        report.append("\n🔵 EQUIPO 2 (Avg: ").append(String.format("%.1f", result.getTeam2Average())).append("):\n");
        result.getTeam2().forEach(player -> {
            PlayerSkillData skill = calculatePlayerSkill(player);
            report.append(String.format("  • %s - MMR: %.0f | ELO: %d | Skill: %.0f | DMG Est: %.0f\n",
                    getPlayerName(player), player.getMmr(), player.getElo(),
                    skill.getSkillRating(), calculateDamagePotential(player)));
        });

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