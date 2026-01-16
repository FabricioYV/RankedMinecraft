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

    // Estadísticas generales
    private final int wins;
    private final int losses;
    private final int gamesPlayed;
    private final int totalKills;
    private final int totalDeaths;

    // Sistema de partidas de prueba (placement matches)
    private boolean isInPlacement;
    private int placementMatchesPlayed;
    private static final int PLACEMENT_MATCHES_REQUIRED = 8; // 8 partidas de prueba


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
        this.wins = wins;
        this.losses = losses;
        this.gamesPlayed = gamesPlayed;
        this.totalKills = totalKills;
        this.totalDeaths = totalDeaths;

        // Inicializar sistema de placement
        this.isInPlacement = gamesPlayed < PLACEMENT_MATCHES_REQUIRED;
        this.placementMatchesPlayed = Math.min(gamesPlayed, PLACEMENT_MATCHES_REQUIRED);
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


    public inWt getWins() { return wins; }
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

    /**
     * Obtiene el UUID como objeto UUID (para compatibilidad con rejoin system)
     */
    public java.util.UUID getUuid() {
        return java.util.UUID.fromString(minecraftUuid);
    }

    /**
     * Obtiene el nombre de Minecraft del jugador
     */
    public String getMinecraftName() {
        try {
            org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(getUuid());
            if (player != null) {
                return player.getName();
            }

            // Si el jugador no está online, intentar obtener el nombre desde el UUID
            org.bukkit.OfflinePlayer offlinePlayer = org.bukkit.Bukkit.getOfflinePlayer(getUuid());
            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                return offlinePlayer.getName();
            }

            // Fallback: usar los primeros 8 caracteres del UUID
            return "Player_" + minecraftUuid.substring(0, 8);
        } catch (Exception e) {
            // Fallback en caso de error
            return "Unknown_Player";
        }
    }

    @Override
    public int hashCode() {
        return minecraftUuid.hashCode();
    }

    /**
     * Métodos para el sistema de placement matches
     */
    public boolean isInPlacement() {
        return isInPlacement;
    }

    public void setInPlacement(boolean inPlacement) {
        this.isInPlacement = inPlacement;
    }

    public int getPlacementMatchesPlayed() {
        return placementMatchesPlayed;
    }

    public void setPlacementMatchesPlayed(int placementMatchesPlayed) {
        this.placementMatchesPlayed = placementMatchesPlayed;
    }

    public void incrementPlacementMatches() {
        this.placementMatchesPlayed++;
        // Si completó todas las partidas de placement, salir del sistema
        if (this.placementMatchesPlayed >= PLACEMENT_MATCHES_REQUIRED) {
            this.isInPlacement = false;
        }
    }

    public static int getPlacementMatchesRequired() {
        return PLACEMENT_MATCHES_REQUIRED;
    }
    
    public void setPlacementData(boolean isInPlacement, int placementMatchesPlayed) {
        this.isInPlacement = isInPlacement;
        this.placementMatchesPlayed = placementMatchesPlayed;
    }
}