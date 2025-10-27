package org.fabricioyv.rating;

/**
 * Enumeración de rangos usados por el sistema de ELO
 *
 * Created by FabricioYV
 * @author FabricioYV
 */
public enum Rank {
    COBRE_3("Cobre III", "\u00a7c", 0, 99),
    COBRE_2("Cobre II", "\u00a7c", 100, 199),
    COBRE_1("Cobre I", "\u00a7c", 200, 299),

    HIERRO_3("Hierro III", "\u00a77", 300, 399),
    HIERRO_2("Hierro II", "\u00a77", 400, 499),
    HIERRO_1("Hierro I", "\u00a77", 500, 599),

    ORO_3("Oro III", "\u00a76", 600, 699),
    ORO_2("Oro II", "\u00a76", 700, 799),
    ORO_1("Oro I", "\u00a76", 800, 899),

    DIAMANTE_3("Diamante III", "\u00a7b", 900, 999),
    DIAMANTE_2("Diamante II", "\u00a7b", 1000, 1099),
    DIAMANTE_1("Diamante I", "\u00a7b", 1100, 1199),

    ESMERALDA("Esmeralda", "\u00a7a", 1200, Integer.MAX_VALUE),

    // Rango especial para jugadores en placement matches
    PLACEMENT("En Evaluación", "\u00a7e", -1, -1);

    private final String displayName;
    private final String colorCode;
    private final int minElo;
    private final int maxElo;

    Rank(String displayName, String colorCode, int minElo, int maxElo) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.minElo = minElo;
        this.maxElo = maxElo;
    }

    public String getDisplayName() { return displayName; }
    public String getColorCode() { return colorCode; }
    public int getMinElo() { return minElo; }
    public int getMaxElo() { return maxElo; }

    public String getFormattedName() {
        return colorCode + displayName + "§r";
    }

    public static Rank getRankByElo(int elo) {
        for (Rank rank : values()) {
            if (elo >= rank.minElo && elo <= rank.maxElo) {
                return rank;
            }
        }
        return COBRE_3; // Fallback
    }

    public boolean isPromotionPossible() {
        return this != ESMERALDA;
    }

    public Rank getNextRank() {
        Rank[] ranks = values();
        int currentIndex = this.ordinal();
        return currentIndex < ranks.length - 1 ? ranks[currentIndex + 1] : this;
    }

    public Rank getPreviousRank() {
        Rank[] ranks = values();
        int currentIndex = this.ordinal();
        return currentIndex > 0 ? ranks[currentIndex - 1] : this;
    }
}