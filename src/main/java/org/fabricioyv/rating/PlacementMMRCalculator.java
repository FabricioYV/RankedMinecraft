package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;
import org.fabricioyv.database.PlacementHistoryManager;

/**
 * Calculador de MMR especializado para partidas de prueba (placement matches)
 * ACTUALIZADO: Usa el nuevo sistema de análisis avanzado para evaluación final más precisa
 */
public class PlacementMMRCalculator {

    // Cambios MÍNIMOS durante placement (solo para tracking)
    private static final double PLACEMENT_WIN_POINTS = 12.0;   // Victoria: +12 MMR (reducido)
    private static final double PLACEMENT_LOSS_POINTS = -8.0;  // Derrota: -8 MMR (reducido)
    private static final double PLACEMENT_PERFORMANCE_BONUS = 8.0; // Bonus por buen rendimiento (reducido)

    /**
     * Calcula el cambio de MMR para una partida de placement
     * NUEVO: Usa análisis avanzado al finalizar las 8 partidas
     */
    public static MMRCalculator.MMRChange calculatePlacementMMRChange(PlayerData player, boolean won,
                                                                     double teamAvgMMR, double opponentAvgMMR) {
        double oldMMR = player.getMmr();
        StringBuilder breakdown = new StringBuilder();

        int currentMatch = player.getPlacementMatchesPlayed() + 1;
        breakdown.append("PLACEMENT (").append(currentMatch)
                .append("/").append(PlayerData.getPlacementMatchesRequired()).append(")");

        // Cambios mínimos durante placement
        double mmrChange;
        if (won) {
            mmrChange = PLACEMENT_WIN_POINTS;
            breakdown.append(" WIN:+").append(String.format("%.0f", PLACEMENT_WIN_POINTS));
        } else {
            mmrChange = PLACEMENT_LOSS_POINTS;
            breakdown.append(" LOSS:").append(String.format("%.0f", PLACEMENT_LOSS_POINTS));
        }

        // Pequeño bonus por rendimiento
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
            breakdown.append("\n🔍 **INICIANDO ANÁLISIS AVANZADO DE PLACEMENT...**");

            try {
                // NUEVO: Usar el analizador avanzado para evaluación final
                AdvancedPlacementAnalyzer.PlacementAnalysisResult analysis =
                    AdvancedPlacementAnalyzer.analyzeCompleteHistory(player.getMinecraftUuid());

                // Asignar ELO y MMR finales basados en el análisis completo
                double newMMR = analysis.finalMMR;

                // Crear breakdown detallado con el reporte del análisis
                breakdown = new StringBuilder();
                breakdown.append("**🎯 PLACEMENT FINALIZADO CON ANÁLISIS AVANZADO**\n\n");
                breakdown.append(analysis.analysisReport);
                breakdown.append(String.format("\n**📊 Resultado Final:**\n"));
                breakdown.append(String.format("• MMR Inicial: %.0f\n", oldMMR));
                breakdown.append(String.format("• MMR Final: %.0f\n", newMMR));
                breakdown.append(String.format("• Cambio Total: %+.0f\n", newMMR - oldMMR));
                breakdown.append("\n*Tu ranking ha sido calibrado basado en un análisis profundo de tus 8 partidas de placement.*");

                return new MMRCalculator.MMRChange(oldMMR, newMMR, breakdown.toString());

            } catch (Exception e) {
                // Fallback al sistema anterior si hay algún error
                System.err.println("❌ Error en análisis avanzado, usando sistema de fallback: " + e.getMessage());

                PlacementRankAssignment.PlacementMatchHistory history =
                    PlacementHistoryManager.getPlacementHistory(player.getMinecraftUuid());

                PlacementRankAssignment.PlacementResult result =
                    PlacementRankAssignment.calculateFinalPlacement(player, history);

                double newMMR = result.finalMMR;
                breakdown = new StringBuilder(result.summary);

                return new MMRCalculator.MMRChange(oldMMR, newMMR, breakdown.toString());
            }
        }

        // Durante placement: cambios mínimos y temporales
        double newMMR = Math.max(850, Math.min(1150, oldMMR + mmrChange)); // Rango más limitado

        return new MMRCalculator.MMRChange(oldMMR, newMMR, breakdown.toString());
    }

    /**
     * Bonus menor por rendimiento durante placement (reducido para más equilibrio)
     */
    private static double calculateMinorPerformanceBonus(PlayerData player) {
        double bonus = 0;

        // Bonus más conservador
        if (player.getCurrentMatchKills() >= 7 && player.getCurrentMatchDamage() >= 2500) {
            bonus = PLACEMENT_PERFORMANCE_BONUS; // +8 puntos (antes +10)
        } else if (player.getCurrentMatchKills() >= 5 && player.getCurrentMatchDamage() >= 1800) {
            bonus = PLACEMENT_PERFORMANCE_BONUS * 0.5; // +4 puntos (antes +5)
        } else if (player.getCurrentMatchKills() >= 3 && player.getCurrentMatchDamage() >= 1200) {
            bonus = PLACEMENT_PERFORMANCE_BONUS * 0.25; // +2 puntos (nuevo)
        }

        return bonus;
    }
}
