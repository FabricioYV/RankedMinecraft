package org.fabricioyv.match;

public enum Team {
    BLUE("Azul", "§9", "blue"),
    RED("Rojo", "§c", "red");

    private final String displayName;
    private final String colorCode;
    private final String pgmName;

    Team(String displayName, String colorCode, String pgmName) {
        this.displayName = displayName;
        this.colorCode = colorCode;
        this.pgmName = pgmName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColorCode() {
        return colorCode;
    }

    public String getPgmName() {
        return pgmName;
    }

    public String getFormattedName() {
        return colorCode + displayName + "§r";
    }
    public String getDiscordFormattedName() {
        return switch (this) {
            case BLUE -> "🔵 Azul";
            case RED -> "🔴 Rojo";
        };
    }
}