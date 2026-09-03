package org.fabricioyv.queue;

public enum QueueType {

    TWO_VS_TWO(4, 2, "2v2"),
    FIVE_VS_FIVE(10, 5, "5v5"),
    EIGHT_VS_EIGHT(16, 8, "8v8");

    private final int requiredPlayers; // total players in match
    private final int teamSize;
    private final String displayName;

    QueueType(int requiredPlayers, int teamSize, String displayName) {
        this.requiredPlayers = requiredPlayers;
        this.teamSize = teamSize;
        this.displayName = displayName;
    }

    public int getRequiredPlayers() {
        return requiredPlayers;
    }

    public int getTeamSize() {
        return teamSize;
    }

    public int getTotalPlayers() {
        return requiredPlayers;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * MatchManager/MapManager:
     * - 4  -> 2v2
     * - 10 -> 5v5
     * - 16 -> 8v8
     */
    public static QueueType fromPlayersCount(int players) {
        if (players == 4) return TWO_VS_TWO;
        if (players == 10) return FIVE_VS_FIVE;
        if (players == 16) return EIGHT_VS_EIGHT;
        return null;
    }
}