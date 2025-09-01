package org.fabricioyv.queue;

public enum QueueType {
    FIVE_VS_FIVE(10),
    EIGHT_VS_EIGHT(16);

    private final int requiredPlayers;

    QueueType(int requiredPlayers) {
        this.requiredPlayers = requiredPlayers;
    }

    public int getRequiredPlayers() {
        return requiredPlayers;
    }
}