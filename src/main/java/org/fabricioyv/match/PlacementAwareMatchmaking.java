package org.fabricioyv.match;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.MMRCalculator;
import java.util.*;

/**
 * Sistema de matchmaking mejorado que considera partidas de prueba (placement matches)
 */
public class PlacementAwareMatchmaking {
    
    // Configuración de placement matchmaking
    private static final double PLACEMENT_MMR_VARIANCE = 200.0; // Mayor varianza permitida para placement
    private static final double REGULAR_MMR_VARIANCE = 150.0; // Varianza normal
    private static final int MIN_PLACEMENT_PLAYERS = 2; // Mínimo de jugadores en placement por equipo
    private static final int MAX_PLACEMENT_PLAYERS = 4; // Máximo de jugadores en placement por equipo
    
    /**
     * Balancea equipos considerando el estado de placement de los jugadores
     */
    public static BalancedTeams balanceTeamsWithPlacement(List<PlayerData> players) {
        if (players.size() != 10) {
            throw new IllegalArgumentException("El matchmaking requiere exactamente 10 jugadores");
        }
        
        // Separar jugadores por estado de placement
        List<PlayerData> placementPlayers = new ArrayList<>();
        List<PlayerData> regularPlayers = new ArrayList<>();
        
        for (PlayerData player : players) {
            if (player.isInPlacement()) {
                placementPlayers.add(player);
            } else {
                regularPlayers.add(player);
            }
        }
        
        // Ordenar por MMR
        placementPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));
        regularPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));
        
        // Calcular la mejor distribución de equipos
        return findBestTeamBalance(placementPlayers, regularPlayers);
    }
    
    /**
     * Encuentra el mejor balance de equipos
     */
    private static BalancedTeams findBestTeamBalance(List<PlayerData> placementPlayers, 
                                                    List<PlayerData> regularPlayers) {
        BalancedTeams bestBalance = null;
        double bestScore = Double.MAX_VALUE;
        
        // Probar diferentes distribuciones de jugadores en placement
        int totalPlacement = placementPlayers.size();
        int totalRegular = regularPlayers.size();
        
        // Determinar distribución óptima de placement players
        for (int team1Placement = Math.max(0, totalPlacement - MAX_PLACEMENT_PLAYERS); 
             team1Placement <= Math.min(totalPlacement, MAX_PLACEMENT_PLAYERS); 
             team1Placement++) {
            
            int team2Placement = totalPlacement - team1Placement;
            
            // Validar que la distribución sea válida
            if (team2Placement > MAX_PLACEMENT_PLAYERS || 
                team1Placement + (5 - team2Placement) > 5 ||
                team2Placement + (5 - team1Placement) > 5) {
                continue;
            }
            
            int team1Regular = 5 - team1Placement;
            int team2Regular = 5 - team2Placement;
            
            if (team1Regular > totalRegular || team2Regular > totalRegular) {
                continue;
            }
            
            // Crear equipos con esta distribución
            BalancedTeams candidate = createTeamsWithDistribution(
                placementPlayers, regularPlayers, 
                team1Placement, team1Regular
            );
            
            if (candidate != null) {
                double score = calculateBalanceScore(candidate);
                if (score < bestScore) {
                    bestScore = score;
                    bestBalance = candidate;
                }
            }
        }
        
        // Si no se encontró una distribución válida, usar balanceado simple
        if (bestBalance == null) {
            bestBalance = createSimpleBalance(placementPlayers, regularPlayers);
        }
        
        return bestBalance;
    }
    
    /**
     * Crea equipos con una distribución específica
     */
    private static BalancedTeams createTeamsWithDistribution(List<PlayerData> placementPlayers,
                                                           List<PlayerData> regularPlayers,
                                                           int team1Placement, int team1Regular) {
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();
        
        // Distribuir placement players alternadamente para equilibrar
        for (int i = 0; i < placementPlayers.size(); i++) {
            if (i % 2 == 0 && team1.size() < team1Placement) {
                team1.add(placementPlayers.get(i));
            } else if (team2.size() < placementPlayers.size() - team1Placement) {
                team2.add(placementPlayers.get(i));
            } else {
                team1.add(placementPlayers.get(i));
            }
        }
        
        // Distribuir regular players para balancear MMR
        List<PlayerData> remainingRegular = new ArrayList<>(regularPlayers);
        
        // Primero llenar team1
        while (team1.size() < 5 && !remainingRegular.isEmpty()) {
            PlayerData bestPick = findBestPlayerForTeam(team1, remainingRegular, team1Regular);
            if (bestPick != null) {
                team1.add(bestPick);
                remainingRegular.remove(bestPick);
            } else {
                break;
            }
        }
        
        // Luego llenar team2
        while (team2.size() < 5 && !remainingRegular.isEmpty()) {
            team2.add(remainingRegular.remove(0));
        }
        
        // Verificar que ambos equipos tengan 5 jugadores
        if (team1.size() != 5 || team2.size() != 5) {
            return null;
        }
        
        return new BalancedTeams(team1, team2);
    }
    
    /**
     * Encuentra el mejor jugador regular para agregar a un equipo
     */
    private static PlayerData findBestPlayerForTeam(List<PlayerData> currentTeam, 
                                                   List<PlayerData> candidates, 
                                                   int maxRegularPlayers) {
        PlayerData bestCandidate = null;
        double bestBalance = Double.MAX_VALUE;
        
        double currentTeamMMR = MMRCalculator.calculateAverageMMR(currentTeam);
        
        for (PlayerData candidate : candidates) {
            if (!candidate.isInPlacement()) { // Solo jugadores regulares
                List<PlayerData> testTeam = new ArrayList<>(currentTeam);
                testTeam.add(candidate);
                double newMMR = MMRCalculator.calculateAverageMMR(testTeam);
                
                // Preferir balance cercano a 1000 MMR promedio
                double balance = Math.abs(newMMR - 1000.0);
                
                if (balance < bestBalance) {
                    bestBalance = balance;
                    bestCandidate = candidate;
                }
            }
        }
        
        return bestCandidate;
    }
    
    /**
     * Crea un balance simple cuando no se puede aplicar estrategia avanzada
     */
    private static BalancedTeams createSimpleBalance(List<PlayerData> placementPlayers,
                                                   List<PlayerData> regularPlayers) {
        List<PlayerData> allPlayers = new ArrayList<>();
        allPlayers.addAll(placementPlayers);
        allPlayers.addAll(regularPlayers);
        
        // Ordenar por MMR
        allPlayers.sort((p1, p2) -> Double.compare(p2.getMmr(), p1.getMmr()));
        
        List<PlayerData> team1 = new ArrayList<>();
        List<PlayerData> team2 = new ArrayList<>();
        
        // Distribución alternada (snake draft)
        for (int i = 0; i < allPlayers.size(); i++) {
            if (i % 4 < 2) {
                team1.add(allPlayers.get(i));
            } else {
                team2.add(allPlayers.get(i));
            }
        }
        
        return new BalancedTeams(team1, team2);
    }
    
    /**
     * Calcula un score de balance para los equipos (menor es mejor)
     */
    private static double calculateBalanceScore(BalancedTeams teams) {
        double team1MMR = MMRCalculator.calculateAverageMMR(teams.team1);
        double team2MMR = MMRCalculator.calculateAverageMMR(teams.team2);
        
        // Diferencia de MMR entre equipos
        double mmrDifference = Math.abs(team1MMR - team2MMR);
        
        // Penalizar distribuciones desiguales de placement players
        int team1Placement = (int) teams.team1.stream().mapToInt(p -> p.isInPlacement() ? 1 : 0).sum();
        int team2Placement = (int) teams.team2.stream().mapToInt(p -> p.isInPlacement() ? 1 : 0).sum();
        double placementImbalance = Math.abs(team1Placement - team2Placement) * 50;
        
        // Score final
        return mmrDifference + placementImbalance;
    }
    
    /**
     * Genera mensaje descriptivo del matchmaking
     */
    public static String generateMatchmakingReport(BalancedTeams teams) {
        StringBuilder report = new StringBuilder();
        
        double team1MMR = MMRCalculator.calculateAverageMMR(teams.team1);
        double team2MMR = MMRCalculator.calculateAverageMMR(teams.team2);
        
        int team1Placement = (int) teams.team1.stream().mapToInt(p -> p.isInPlacement() ? 1 : 0).sum();
        int team2Placement = (int) teams.team2.stream().mapToInt(p -> p.isInPlacement() ? 1 : 0).sum();
        
        report.append("**MATCHMAKING REPORT**\n");
        report.append(String.format("🔵 **Equipo 1**: %.0f MMR promedio (%d en placement)\n", team1MMR, team1Placement));
        report.append(String.format("🔴 **Equipo 2**: %.0f MMR promedio (%d en placement)\n", team2MMR, team2Placement));
        report.append(String.format("⚖️ **Diferencia**: %.0f MMR\n", Math.abs(team1MMR - team2MMR)));
        
        if (team1Placement > 0 || team2Placement > 0) {
            report.append("\n📊 **Jugadores en Placement Matches activos**");
        }
        
        return report.toString();
    }
    
    /**
     * Clase para contener los equipos balanceados
     */
    public static class BalancedTeams {
        public final List<PlayerData> team1;
        public final List<PlayerData> team2;
        
        public BalancedTeams(List<PlayerData> team1, List<PlayerData> team2) {
            this.team1 = new ArrayList<>(team1);
            this.team2 = new ArrayList<>(team2);
        }
    }
}
