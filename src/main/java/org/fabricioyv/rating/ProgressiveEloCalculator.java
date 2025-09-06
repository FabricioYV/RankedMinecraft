package org.fabricioyv.rating;

public class ProgressiveEloCalculator {

    // ELO inicial para nuevos jugadores
    public static final int STARTING_ELO = 500; // Cobre I
    public enum MatchType {
        RANKED_5V5("5v5", 1.0, 1.0),      // Modificadores normales
        RANKED_8V8("8v8", 1.25, 0.85);    // +25% ganancia, -15% pérdida

        private final String displayName;
        private final double winMultiplier;
        private final double lossMultiplier;

        MatchType(String displayName, double winMultiplier, double lossMultiplier) {
            this.displayName = displayName;
            this.winMultiplier = winMultiplier;
            this.lossMultiplier = lossMultiplier;
        }

        public double getWinMultiplier() { return winMultiplier; }
        public double getLossMultiplier() { return lossMultiplier; }
        public String getDisplayName() { return displayName; }
    }
    /**
     * Calcula los cambios de ELO con sistema progresivo
     */
    public static EloChange calculateEloChange(int playerElo, double opponentAvgElo,
                                               boolean won, MatchType matchType) {
        Rank currentRank = Rank.getRankByElo(playerElo);

        // Calcular cambio base
        int baseChange = calculateBaseChange(playerElo, opponentAvgElo, won);

        // Aplicar modificadores de rango
        int rankModifiedChange = applyRankModifiers(baseChange, currentRank, won);

        // Aplicar modificadores de tipo de partida
        double multiplier = won ? matchType.getWinMultiplier() : matchType.getLossMultiplier();
        int finalChange = (int) Math.round(rankModifiedChange * multiplier);

        int newElo = Math.max(0, playerElo + finalChange);
        Rank newRank = Rank.getRankByElo(newElo);

        boolean promoted = newRank.ordinal() > currentRank.ordinal();
        boolean demoted = newRank.ordinal() < currentRank.ordinal();

        return new EloChange(finalChange, newElo, currentRank, newRank, promoted, demoted);
    }

    private static int calculateBaseChange(int playerElo, double opponentAvgElo, boolean won) {
        double eloDifference = opponentAvgElo - playerElo;

        // Fórmula ELO estándar
        double expectedScore = 1.0 / (1.0 + Math.pow(10, eloDifference / 400.0));
        double actualScore = won ? 1.0 : 0.0;

        // Factor K más alto para cambios significativos
        int kFactor = getKFactor(playerElo);

        int baseChange = (int) Math.round(kFactor * (actualScore - expectedScore));

        // Ajustar pérdidas mínimas según el rango
        if (!won) {
            Rank currentRank = Rank.getRankByElo(playerElo);
            int minLoss = getMinimumLoss(currentRank);

            // Asegurar que la pérdida sea al menos el mínimo para el rango
            if (baseChange > minLoss) {
                baseChange = minLoss;
            }
        }

        return baseChange;
    }


    private static int getKFactor(int elo) {
        if (elo < 600) return 45;      // Cobre: cambios más rápidos para salir
        if (elo < 900) return 40;      // Hierro: cambios altos
        if (elo < 1200) return 35;     // Oro: cambios moderados-altos
        if (elo < 1500) return 30;     // Diamante: cambios moderados
        return 25;                     // Esmeralda: cambios menores pero significativos
    }
    private static int getMinimumLoss(Rank rank) {
        return switch (rank) {
            case COBRE_3, COBRE_2, COBRE_1 -> -5;          // Mínimo -5 en cobre
            case HIERRO_3, HIERRO_2, HIERRO_1 -> -10;      // Mínimo -10 en hierro
            case ORO_3, ORO_2, ORO_1 -> -18;               // Mínimo -18 en oro
            case DIAMANTE_3, DIAMANTE_2, DIAMANTE_1 -> -25; // Mínimo -25 en diamante
            case ESMERALDA -> -30;                          // Mínimo -30 en esmeralda
        };
    }


    private static int applyRankModifiers(int baseChange, Rank currentRank, boolean won) {
        double multiplier = 1.0;

        switch (currentRank) {
            case COBRE_3:
            case COBRE_2:
            case COBRE_1:
                multiplier = won ? 1.2 : 1.2;  // Pérdidas mínimas en cobre
                break;

            case HIERRO_3:
            case HIERRO_2:
            case HIERRO_1:
                multiplier = won ? 1.0 : 1.5;  // Pérdidas moderadas
                break;

            case ORO_3:
            case ORO_2:
            case ORO_1:
                multiplier = won ? 0.9 : 2.3;  // Pérdidas significativas
                break;

            case DIAMANTE_3:
            case DIAMANTE_2:
            case DIAMANTE_1:
                multiplier = won ? 0.8 : 4.0;  // Pérdidas severas (40-50 puntos)
                break;

            case ESMERALDA:
                multiplier = won ? 0.7 : 5;  // Pérdidas extremas (50-60 puntos)
                break;
        }

        return (int) Math.round(baseChange * multiplier);
    }

    public static class EloChange {
        private final int eloChange;
        private final int newElo;
        private final Rank oldRank;
        private final Rank newRank;
        private final boolean promoted;
        private final boolean demoted;

        public EloChange(int eloChange, int newElo, Rank oldRank, Rank newRank,
                         boolean promoted, boolean demoted) {
            this.eloChange = eloChange;
            this.newElo = newElo;
            this.oldRank = oldRank;
            this.newRank = newRank;
            this.promoted = promoted;
            this.demoted = demoted;
        }

        // Getters
        public int getEloChange() { return eloChange; }
        public int getNewElo() { return newElo; }
        public Rank getOldRank() { return oldRank; }
        public Rank getNewRank() { return newRank; }
        public boolean isPromoted() { return promoted; }
        public boolean isDemoted() { return demoted; }

        public String getChangeMessage() {
            StringBuilder msg = new StringBuilder();

            if (promoted) {
                msg.append("§a🎉 ¡PROMOCIÓN! ");
                msg.append(oldRank.getFormattedName()).append(" → ").append(newRank.getFormattedName());
            } else if (demoted) {
                msg.append("§c📉 Degradación: ");
                msg.append(oldRank.getFormattedName()).append(" → ").append(newRank.getFormattedName());
            } else {
                msg.append("Rango: ").append(newRank.getFormattedName());
            }

            msg.append(" (").append(eloChange > 0 ? "+" : "").append(eloChange).append(" ELO)");
            return msg.toString();
        }
    }
}