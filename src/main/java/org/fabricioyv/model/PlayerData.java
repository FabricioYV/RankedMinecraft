package org.fabricioyv.model;

public class PlayerData {
    private final String minecraftUuid;
    private final String discordId;
    private int elo;
    private double mmr;
    private boolean isInMatch;
    private String currentMatchId;

    private int currentMatchKills;
    private int currentMatchDeaths;
    private double currentMatchDamage;

    // Estadísticas de flechas por partida
    private int currentMatchArrowsShot;
    private int currentMatchArrowsHit;

    // Estadísticas generales
    private int wins;
    private int losses;
    private int gamesPlayed;
    private int totalKills;
    private int totalDeaths;


    public PlayerData(String minecraftUuid, String discordId, int elo, boolean isInMatch, String currentMatchId ,double mmr,int wins ,int losses,int gamesPlayed,int totalKills,int totalDeaths ) {
        this.minecraftUuid = minecraftUuid;
        this.discordId = discordId;
        this.elo = elo;
        this.isInMatch = isInMatch;
        this.mmr = mmr;
        this.currentMatchId = currentMatchId;
        this.currentMatchKills = 0;
        this.currentMatchDeaths = 0;
        this.currentMatchDamage = 0.0;
        this.currentMatchArrowsShot = 0;
        this.currentMatchArrowsHit = 0;
        this.wins = wins;
        this.losses = losses;
        this.gamesPlayed = gamesPlayed;
        this.totalKills = totalKills;
        this.totalDeaths = totalDeaths;
    }

    // Getters y setters
    public String getMinecraftUuid() { return minecraftUuid; }
    public String getDiscordId() { return discordId; }
    public int getElo() { return elo; }
    public double getMmr() { return mmr; }
    public void setMmr(double mmr) { this.mmr = mmr; }
    public boolean isInMatch() { return isInMatch; }
    public void setInMatch(boolean inMatch) { this.isInMatch = inMatch; }
    public String getCurrentMatchId() { return currentMatchId; }
    public void setCurrentMatchId(String currentMatchId) { this.currentMatchId = currentMatchId; }

    public int getCurrentMatchKills() { return currentMatchKills; }
    public void setCurrentMatchKills(int kills) { this.currentMatchKills = kills; }
    public void addKill() { this.currentMatchKills++; }

    public int getCurrentMatchDeaths() { return currentMatchDeaths; }
    public void setCurrentMatchDeaths(int deaths) { this.currentMatchDeaths = deaths; }
    public void addDeath() { this.currentMatchDeaths++; }

    public double getCurrentMatchDamage() { return currentMatchDamage; }
    public void setCurrentMatchDamage(double damage) { this.currentMatchDamage = damage; }
    public void addDamage(double damage) { this.currentMatchDamage += damage; }

    // Métodos para estadísticas de flechas
    public int getCurrentMatchArrowsShot() { return currentMatchArrowsShot; }
    public int getCurrentMatchArrowsHit() { return currentMatchArrowsHit; }
    public void addArrowShot() { this.currentMatchArrowsShot++; }
    public void addArrowHit() { this.currentMatchArrowsHit++; }

    public double getCurrentMatchArrowAccuracy() {
        return currentMatchArrowsShot > 0 ? (double) currentMatchArrowsHit / currentMatchArrowsShot * 100.0 : 0.0;
    }

    // Resetear estadísticas de partida
    public void resetMatchStats() {
        this.currentMatchKills = 0;
        this.currentMatchDeaths = 0;
        this.currentMatchDamage = 0.0;
        this.currentMatchArrowsShot = 0;
        this.currentMatchArrowsHit = 0;
    }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getGamesPlayed() { return gamesPlayed; }
    public int getTotalKills() { return totalKills; }
    public int getTotalDeaths() { return totalDeaths; }

    public double getWinRate() {
        return gamesPlayed > 0 ? (double) wins / gamesPlayed * 100.0 : 50.0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PlayerData that = (PlayerData) obj;
        return minecraftUuid.equals(that.minecraftUuid);
    }
    public void setElo(int elo) {
        this.elo = elo;
    }

    @Override
    public int hashCode() {
        return minecraftUuid.hashCode();
    }

}