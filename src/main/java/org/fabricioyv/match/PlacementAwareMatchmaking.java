package org.fabricioyv.match;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.MMRCalculator;
import java.util.*;

/**
 * Sistema de matchmaking optimizado para balanceo entre placement y jugadores regulares
 * ENFOQUE: Distribución inteligente que equilibra experiencia y MMR
 */
public class PlacementAwareMatchmaking {

    // Configuración optimizada para balance placement/regular
    private static final double MAX_MMR_DIFFERENCE = 120.0; // Reducido para mejor balance
    private static final double PLACEMENT_MMR_TOLERANCE = 180.0; // Tolerancia para placement players
    private static final double PLACEMENT_WEIGHT_FACTOR = 1.5; // Factor de peso para placement players
    private static final double EXPERIENCE_BALANCE_WEIGHT = 25.0; // Peso para balance de experiencia

    /**
     * Balancea equipos con enfoque especial en distribución placement/regular
     * ALGORITMO MEJORADO: Prioriza balance de experiencia y MMR
     */
    public static BalancedTeams balanceTeamsWithPlacement(List<PlayerData> players) {
        if (players.size() != 10) {
            throw new IllegalArgumentException("El matchmaking requiere exactamente 10 jugadores");
        }

        // Separar jugadores por tipo
        List<PlayerData> placementPlayers = new ArrayList<>();
        List<PlayerData> regularPlayers = new ArrayList<>();

        for (PlayerData player : players) {
            if (player.isInPlacement()) {
                placementPlayers.add(player);
            } else {
                regularPlayers.add(player);
            }
        }

        // Ordenar por MMR (mayor a menor)
        placementPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));
        regularPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));

        // Usar algoritmo optimizado específico para placement/regular balance
        return createOptimalPlacementBalance(placementPlayers, regularPlayers);
    }

    /**
     * Crea balance óptimo considerando placement vs regular players
     * NUEVO ALGORITMO: Enfoque en equidad de experiencia y MMR
     */
    private static BalancedTeams createOptimalPlacementBalance(List<PlayerData> placementPlayers,
                                                               List<PlayerData> regularPlayers) {
        int totalPlacement = placementPlayers.size();
        int totalRegular = regularPlayers.size();

        // Caso especial: Solo placement players o solo regulares
        if (totalPlacement == 0) {
            return balanceRegularPlayersOnly(regularPlayers);
        }
        if (totalRegular == 0) {
            return balancePlacementPlayersOnly(placementPlayers);
        }

        // Distribución ideal: dividir placement players lo más equilibradamente posible
        int team1Placement = totalPlacement / 2;
        int team2Placement = totalPlacement - team1Placement;

        // Ajustar si hay diferencia par/impar
        if (Math.abs(team1Placement - team2Placement) > 1) {
            // Redistribuir para mejor balance
            if (team1Placement > team2Placement) {
                team2Placement++;
                team1Placement--;
            }
        }

        // Crear equipos con distribución optimizada
        return createBalancedTeamsWithDistribution(placementPlayers, regularPlayers,
                team1Placement, team2Placement);
    }

    /**
     * Crea equipos balanceados con distribución específica de placement players
     * ALGORITMO CORE: Distribución inteligente por pares MMR
     */
    private static BalancedTeams createBalancedTeamsWithDistribution(List<PlayerData> placementPlayers,
                                                                     List<PlayerData> regularPlayers,
                                                                     int team1Placement,
                                                                     int team2Placement) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // FASE 1: Distribuir placement players con balance de MMR
        distributePlacementPlayers(placementPlayers, team1, team2, team1Placement, team2Placement);

        // FASE 2: Completar equipos con jugadores regulares balanceando MMR
        distributeRegularPlayers(regularPlayers, team1, team2);

        // Verificar equipos completos
        if (team1.size() != 5 || team2.size() != 5) {
            // Fallback: distribución simple
            return createSimpleFallback(placementPlayers, regularPlayers);
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Distribuye placement players de forma inteligente
     * ESTRATEGIA: Alternar por MMR para equilibrar skill
     */
    private static void distributePlacementPlayers(List<PlayerData> placementPlayers,
                                                   List<PlayerData> team1, List<PlayerData> team2,
                                                   int team1Placement, int team2Placement) {

        // Distribución por pares para balance de MMR
        for (int i = 0; i < placementPlayers.size(); i++) {
            PlayerData player = placementPlayers.get(i);

            // Lógica de distribución: alternar, pero priorizando balance de MMR
            if (team1.size() < team1Placement && team2.size() < team2Placement) {
                // Ambos equipos pueden recibir placement players
                double team1AvgMMR = team1.isEmpty() ? 0 : MMRCalculator.calculateAverageMMR(team1);
                double team2AvgMMR = team2.isEmpty() ? 0 : MMRCalculator.calculateAverageMMR(team2);

                // Agregar al equipo con menor MMR promedio (o alternar si están iguales)
                if (team1AvgMMR <= team2AvgMMR || (team1AvgMMR == team2AvgMMR && i % 2 == 0)) {
                    team1.add(player);
                } else {
                    team2.add(player);
                }
            } else if (team1.size() < team1Placement) {
                team1.add(player);
            } else if (team2.size() < team2Placement) {
                team2.add(player);
            }
        }
    }

    /**
     * Distribuye jugadores regulares para completar y balancear equipos
     * ESTRATEGIA: Priorizar balance de MMR total
     */
    private static void distributeRegularPlayers(List<PlayerData> regularPlayers,
                                                 List<PlayerData> team1, List<PlayerData> team2) {

        List<PlayerData> regularCopy = new ArrayList<>(regularPlayers);

        while ((team1.size() < 5 || team2.size() < 5) && !regularCopy.isEmpty()) {
            PlayerData nextPlayer = regularCopy.remove(0);

            // Determinar a qué equipo agregar
            if (team1.size() >= 5) {
                team2.add(nextPlayer);
            } else if (team2.size() >= 5) {
                team1.add(nextPlayer);
            } else {
                // Ambos equipos necesitan jugadores - elegir por balance de MMR
                double team1MMR = MMRCalculator.calculateAverageMMR(team1);
                double team2MMR = MMRCalculator.calculateAverageMMR(team2);

                // Simulación: ver qué equipo quedaría mejor balanceado
                double team1NewMMR = calculateProjectedMMR(team1, nextPlayer);
                double team2NewMMR = calculateProjectedMMR(team2, nextPlayer);

                double team1Balance = Math.abs(team1NewMMR - team2MMR);
                double team2Balance = Math.abs(team1MMR - team2NewMMR);

                // Elegir la opción que produzca mejor balance
                if (team1Balance <= team2Balance) {
                    team1.add(nextPlayer);
                } else {
                    team2.add(nextPlayer);
                }
            }
        }
    }

    /**
     * Calcula MMR proyectado si se agrega un jugador al equipo
     */
    private static double calculateProjectedMMR(List<PlayerData> team, PlayerData newPlayer) {
        List<PlayerData> tempTeam = new ArrayList<>(team);
        tempTeam.add(newPlayer);
        return MMRCalculator.calculateAverageMMR(tempTeam);
    }

    /**
     * Balance para equipos con solo jugadores regulares
     */
    private static BalancedTeams balanceRegularPlayersOnly(List<PlayerData> regularPlayers) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // Distribución zigzag optimizada para jugadores regulares
        for (int i = 0; i < regularPlayers.size(); i++) {
            if (i % 4 < 2) {
                team1.add(regularPlayers.get(i));
            } else {
                team2.add(regularPlayers.get(i));
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Balance para equipos con solo placement players
     */
    private static BalancedTeams balancePlacementPlayersOnly(List<PlayerData> placementPlayers) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // Distribución alternada simple para placement players
        for (int i = 0; i < placementPlayers.size(); i++) {
            if (i % 2 == 0) {
                team1.add(placementPlayers.get(i));
            } else {
                team2.add(placementPlayers.get(i));
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Fallback simple si el algoritmo principal falla
     */
    private static BalancedTeams createSimpleFallback(List<PlayerData> placementPlayers,
                                                      List<PlayerData> regularPlayers) {
        List<PlayerData> allPlayers = new ArrayList<>();
        allPlayers.addAll(placementPlayers);
        allPlayers.addAll(regularPlayers);

        // Ordenar por MMR
        allPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));

        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();

        // Distribución 1-2-2-1 pattern
        for (int i = 0; i < 10; i++) {
            if (i % 4 < 2) {
                team1.add(allPlayers.get(i));
            } else {
                team2.add(allPlayers.get(i));
            }
        }

        return new BalancedTeams(team1, team2);
    }

    /**
     * Calcula score de balance optimizado para placement/regular
     * ENFOQUE: Mayor peso a distribución de experiencia
     */
    private static double calculatePlacementAwareBalanceScore(BalancedTeams teams) {
        double team1MMR = MMRCalculator.calculateAverageMMR(teams.team1);
        double team2MMR = MMRCalculator.calculateAverageMMR(teams.team2);

        // Factor 1: Diferencia de MMR (peso alto)
        double mmrDifference = Math.abs(team1MMR - team2MMR);
        double mmrScore = mmrDifference * 2.0;

        // Factor 2: Balance de placement players (peso muy alto)
        int team1Placement = (int) teams.team1.stream().filter(PlayerData::isInPlacement).count();
        int team2Placement = (int) teams.team2.stream().filter(PlayerData::isInPlacement).count();
        double placementImbalance = Math.abs(team1Placement - team2Placement) * 40.0; // Aumentado

        // Factor 3: Balance de experiencia (nuevo enfoque)
        double experienceScore = calculateEnhancedExperienceBalance(teams) * EXPERIENCE_BALANCE_WEIGHT;

        // Factor 4: Distribución de skill dentro de cada equipo
        double skillDistributionScore = calculateSkillDistribution(teams) * 15.0;

        return mmrScore + placementImbalance + experienceScore + skillDistributionScore;
    }

    /**
     * Calcula balance de experiencia mejorado
     */
    private static double calculateEnhancedExperienceBalance(BalancedTeams teams) {
        // Experiencia promedio por equipo
        double team1AvgGames = teams.team1.stream().mapToInt(PlayerData::getGamesPlayed).average().orElse(0);
        double team2AvgGames = teams.team2.stream().mapToInt(PlayerData::getGamesPlayed).average().orElse(0);

        // Diferencia de experiencia
        double experienceDiff = Math.abs(team1AvgGames - team2AvgGames);

        // Factor adicional: distribución de placement vs regular
        long team1PlacementCount = teams.team1.stream().filter(PlayerData::isInPlacement).count();
        long team2PlacementCount = teams.team2.stream().filter(PlayerData::isInPlacement).count();

        double placementBalance = Math.abs(team1PlacementCount - team2PlacementCount);

        return experienceDiff + (placementBalance * 5.0); // Penalizar desbalance de placement más
    }

    /**
     * Calcula distribución de skill dentro de cada equipo
     */
    private static double calculateSkillDistribution(BalancedTeams teams) {
        double team1Variance = calculateTeamMMRVariance(teams.team1);
        double team2Variance = calculateTeamMMRVariance(teams.team2);

        // Penalizar equipos con varianza muy alta (un carry + 4 noobs)
        return Math.abs(team1Variance - team2Variance) / 100.0;
    }

    /**
     * Calcula varianza de MMR dentro de un equipo
     */
    private static double calculateTeamMMRVariance(List<PlayerData> team) {
        double avgMMR = MMRCalculator.calculateAverageMMR(team);
        return team.stream()
                .mapToDouble(p -> Math.pow(p.getMmr() - avgMMR, 2))
                .average().orElse(0);
    }

    /**
     * Genera reporte enfocado en balance placement/regular
     */
    public static String generateMatchmakingReport(BalancedTeams teams) {
        StringBuilder report = new StringBuilder();

        double team1MMR = MMRCalculator.calculateAverageMMR(teams.team1);
        double team2MMR = MMRCalculator.calculateAverageMMR(teams.team2);
        double mmrDifference = Math.abs(team1MMR - team2MMR);

        int team1Placement = (int) teams.team1.stream().filter(PlayerData::isInPlacement).count();
        int team2Placement = (int) teams.team2.stream().filter(PlayerData::isInPlacement).count();

        double team1AvgGames = teams.team1.stream().mapToInt(PlayerData::getGamesPlayed).average().orElse(0);
        double team2AvgGames = teams.team2.stream().mapToInt(PlayerData::getGamesPlayed).average().orElse(0);

        report.append("⚖️ **BALANCE PLACEMENT/REGULAR OPTIMIZADO**\n");
        report.append(String.format("🔵 **Equipo 1**: %.0f MMR | %d placement | %.1f partidas\n",
                team1MMR, team1Placement, team1AvgGames));
        report.append(String.format("🔴 **Equipo 2**: %.0f MMR | %d placement | %.1f partidas\n",
                team2MMR, team2Placement, team2AvgGames));
        report.append(String.format("📊 **Diferencia MMR**: %.0f", mmrDifference));

        // Evaluación de calidad específica para placement/regular
        if (mmrDifference <= 50 && Math.abs(team1Placement - team2Placement) <= 1) {
            report.append(" ✅ (Balance Excelente)");
        } else if (mmrDifference <= 100 && Math.abs(team1Placement - team2Placement) <= 1) {
            report.append(" ✅ (Balance Bueno)");
        } else if (mmrDifference <= 150) {
            report.append(" ⚠️ (Balance Aceptable)");
        } else {
            report.append(" ❌ (Desbalanceado)");
        }

        // Información específica de placement balance
        int placementDiff = Math.abs(team1Placement - team2Placement);
        if (placementDiff == 0) {
            report.append("\n🎯 **Placement Balance**: Perfecto (igual distribución)");
        } else if (placementDiff == 1) {
            report.append("\n🎯 **Placement Balance**: Bueno (diferencia mínima)");
        } else {
            report.append("\n⚠️ **Placement Balance**: Desigual (diferencia: " + placementDiff + ")");
        }

        return report.toString();
    }

    /**
     * Validación optimizada para balance placement/regular
     */
    public static boolean isMatchmakingAcceptable(BalancedTeams teams) {
        double team1MMR = MMRCalculator.calculateAverageMMR(teams.team1);
        double team2MMR = MMRCalculator.calculateAverageMMR(teams.team2);
        double mmrDifference = Math.abs(team1MMR - team2MMR);

        int team1Placement = (int) teams.team1.stream().filter(PlayerData::isInPlacement).count();
        int team2Placement = (int) teams.team2.stream().filter(PlayerData::isInPlacement).count();
        int placementDifference = Math.abs(team1Placement - team2Placement);

        // Criterios más estrictos para placement balance
        boolean mmrAcceptable = mmrDifference <= MAX_MMR_DIFFERENCE;
        boolean placementAcceptable = placementDifference <= 2; // Máximo 2 de diferencia

        // Si hay muchos placement players, ser más tolerante con MMR
        if (team1Placement + team2Placement >= 6) {
            mmrAcceptable = mmrDifference <= PLACEMENT_MMR_TOLERANCE;
        }

        return mmrAcceptable && placementAcceptable;
    }

    /**
     * Clase para equipos balanceados (sin cambios)
     */
    public static class BalancedTeams {
        public final List<PlayerData> team1;
        public final List<PlayerData> team2;

        public BalancedTeams(List<PlayerData> team1, List<PlayerData> team2) {
            this.team1 = new ArrayList<>(team1);
            this.team2 = new ArrayList<>(team2);
        }

        public double getMMRDifference() {
            double team1MMR = MMRCalculator.calculateAverageMMR(team1);
            double team2MMR = MMRCalculator.calculateAverageMMR(team2);
            return Math.abs(team1MMR - team2MMR);
        }

        public boolean isBalanced() {
            return getMMRDifference() <= MAX_MMR_DIFFERENCE;
        }

        /**
         * NUEVO: Verifica balance específico de placement players
         */
        public boolean isPlacementBalanced() {
            int team1Placement = (int) team1.stream().filter(PlayerData::isInPlacement).count();
            int team2Placement = (int) team2.stream().filter(PlayerData::isInPlacement).count();
            return Math.abs(team1Placement - team2Placement) <= 1;
        }

        /**
         * NUEVO: Obtiene score de balance general
         */
        public double getBalanceScore() {
            return calculatePlacementAwareBalanceScore(this);
        }
    }
}