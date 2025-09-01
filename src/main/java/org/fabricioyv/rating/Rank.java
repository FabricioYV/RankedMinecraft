package org.fabricioyv.rating;

public enum Rank {
    COBRE_3("Cobre III", "§c", 0, 399),
    COBRE_2("Cobre II", "§c", 400, 499),
    COBRE_1("Cobre I", "§c", 500, 599),

    HIERRO_3("Hierro III", "§7", 600, 699),
    HIERRO_2("Hierro II", "§7", 700, 799),
    HIERRO_1("Hierro I", "§7", 800, 899),

    ORO_3("Oro III", "§6", 900, 999),
    ORO_2("Oro II", "§6", 1000, 1099),
    ORO_1("Oro I", "§6", 1100, 1199),

    DIAMANTE_3("Diamante III", "§b", 1200, 1299),
    DIAMANTE_2("Diamante II", "§b", 1300, 1399),
    DIAMANTE_1("Diamante I", "§b", 1400, 1499),

    ESMERALDA("Esmeralda", "§a", 1500, Integer.MAX_VALUE);

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