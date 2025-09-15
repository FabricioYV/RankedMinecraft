package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.database.PlacementHistoryManager;

/**
 * Calculador de MMR especializado para partidas de prueba (placement matches)
 * NUEVO: Durante placement, cambios mínimos de MMR. Al final se asigna rango definitivo.
 */
public class PlacementMMRCalculator {

    // Cambios MÍNIMOS durante placement (solo para tracking)
    private static final double PLACEMENT_WIN_POINTS = 15.0;   // Victoria: +15 MMR
    private static final double PLACEMENT_LOSS_POINTS = -10.0; // Derrota: -10 MMR
    private static final double PLACEMENT_PERFORMANCE_BONUS = 10.0; // Bonus por buen rendimiento

    /**
     * Calcula el cambio de MMR para una partida de placement
     * CAMBIO: Solo cambios pequeños, al final se asigna rango definitivo
     */
    public static MMRCalculator.MMRChange calculatePlacementMMRChange(PlayerData player, boolean won,
                                                                     double teamAvgMMR, double opponentAvgMMR) {
        double oldMMR = player.getMmr();
        StringBuilder breakdown = new StringBuilder();

        int currentMatch = player.getPlacementMatchesPlayed() + 1;
        breakdown.append("PLACEMENT (").append(currentMatch)
                .append("/").append(PlayerData.getPlacementMatchesRequired()).append(")");

        // Cambios pequeños durante placement
        double mmrChange;
        if (won) {
            mmrChange = PLACEMENT_WIN_POINTS;
            breakdown.append(" WIN:+").append(String.format("%.0f", PLACEMENT_WIN_POINTS));
        } else {
            mmrChange = PLACEMENT_LOSS_POINTS;
            breakdown.append(" LOSS:").append(String.format("%.0f", PLACEMENT_LOSS_POINTS));
        }

        // Pequeño bonus por buen rendimiento
        double performanceBonus = calculateMinorPerformanceBonus(player);
        if (performanceBonus > 0) {
            mmrChange += performanceBonus;
            breakdown.append(" PERF:+").append(String.format("%.0f", performanceBonus));
        }

        // Estadísticas de la partida
        breakdown.append(" | Stats: DMG:").append(String.format("%.0f", player.getCurrentMatchDamage()))
                .append(" K:").append(player.getCurrentMatchKills())
                .append(" D:").append(player.getCurrentMatchDeaths());

        // Guardar estadísticas para evaluación final
        PlacementHistoryManager.savePlacementMatch(
            player.getMinecraftUuid(),
            player.getCurrentMatchId(),
            won,
            player.getCurrentMatchKills(),
            player.getCurrentMatchDeaths(),
            player.getCurrentMatchDamage()
        );

        // Verificar si completó las placement matches
        if (currentMatch >= PlayerData.getPlacementMatchesRequired()) {
            breakdown.append("\n🎯 **EVALUANDO PLACEMENT FINAL...**");

            // Obtener historial y calcular rango final
            PlacementRankAssignment.PlacementMatchHistory history =
                PlacementHistoryManager.getPlacementHistory(player.getMinecraftUuid());

            PlacementRankAssignment.PlacementResult result =
                PlacementRankAssignment.calculateFinalPlacement(player, history);

            // Asignar MMR final (ignorar cambios previos)
            double newMMR = result.finalMMR;

            breakdown = new StringBuilder(result.summary);

            return new MMRCalculator.MMRChange(oldMMR, newMMR, breakdown.toString());
        }

        // Durante placement: cambios mínimos
        double newMMR = Math.max(800, Math.min(1200, oldMMR + mmrChange)); // Rango limitado temporalmente

        return new MMRCalculator.MMRChange(oldMMR, newMMR, breakdown.toString());
    }

    /**
     * Bonus menor por rendimiento durante placement (solo para feedback)
     */
    private static double calculateMinorPerformanceBonus(PlayerData player) {
        double bonus = 0;

        // Bonus menor por buen rendimiento
        if (player.getCurrentMatchKills() >= 6 && player.getCurrentMatchDamage() >= 2000) {
            bonus = PLACEMENT_PERFORMANCE_BONUS; // +10 puntos
        } else if (player.getCurrentMatchKills() >= 4 && player.getCurrentMatchDamage() >= 1500) {
            bonus = PLACEMENT_PERFORMANCE_BONUS * 0.5; // +5 puntos
        }

        return bonus;
    }
}
