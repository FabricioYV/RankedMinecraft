package org.fabricioyv.database;

import org.bukkit.Bukkit;
import org.fabricioyv.model.PlayerData;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MatchLogsManager {

    // Zona horaria de Lima, Perú
    private static final ZoneId LIMA_ZONE = ZoneId.of("America/Lima");

    /**
     * Clase para almacenar las estadísticas de un jugador en una partida
     */
    public static class PlayerMatchStats {
        private final String playerUuid;
        private final String playerName;
        private final String team;
        private int kills = 0;
        private int deaths = 0;
        private double damageDealt = 0.0;
        private double damageReceived = 0.0;
        private int arrowsShot = 0;
        private int arrowsHit = 0;
        private double arrowAccuracy = 0.0;
        private int oldElo;
        private int newElo;
        private int eloChange;
        private double oldMmr;
        private double newMmr;
        private double mmrChange;
        private boolean won;

        public PlayerMatchStats(String playerUuid, String playerName, String team) {
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.team = team;
        }

        // Getters y setters
        public String getPlayerUuid() { return playerUuid; }
        public String getPlayerName() { return playerName; }
        public String getTeam() { return team; }
        
        public int getKills() { return kills; }
        public void setKills(int kills) { this.kills = kills; }
        
        public int getDeaths() { return deaths; }
        public void setDeaths(int deaths) { this.deaths = deaths; }
        
        public double getDamageDealt() { return damageDealt; }
        public void setDamageDealt(double damageDealt) { this.damageDealt = damageDealt; }
        
        public double getDamageReceived() { return damageReceived; }
        public void setDamageReceived(double damageReceived) { this.damageReceived = damageReceived; }
        
        public int getArrowsShot() { return arrowsShot; }
        public void setArrowsShot(int arrowsShot) { 
            this.arrowsShot = arrowsShot;
            calculateArrowAccuracy();
        }
        
        public int getArrowsHit() { return arrowsHit; }
        public void setArrowsHit(int arrowsHit) { 
            this.arrowsHit = arrowsHit;
            calculateArrowAccuracy();
        }
        
        public double getArrowAccuracy() { return arrowAccuracy; }
        
        private void calculateArrowAccuracy() {
            if (arrowsShot > 0) {
                this.arrowAccuracy = ((double) arrowsHit / arrowsShot) * 100.0;
            } else {
                this.arrowAccuracy = 0.0;
            }
        }
        
        public int getOldElo() { return oldElo; }
        public void setOldElo(int oldElo) { this.oldElo = oldElo; }
        
        public int getNewElo() { return newElo; }
        public void setNewElo(int newElo) { 
            this.newElo = newElo;
            this.eloChange = newElo - oldElo;
        }
        
        public int getEloChange() { return eloChange; }
        
        public double getOldMmr() { return oldMmr; }
        public void setOldMmr(double oldMmr) { this.oldMmr = oldMmr; }
        
        public double getNewMmr() { return newMmr; }
        public void setNewMmr(double newMmr) { 
            this.newMmr = newMmr;
            this.mmrChange = newMmr - oldMmr;
        }
        
        public double getMmrChange() { return mmrChange; }
        
        public boolean isWon() { return won; }
        public void setWon(boolean won) { this.won = won; }

        public void addKill() { this.kills++; }
        public void addDeath() { this.deaths++; }
        public void addDamageDealt(double damage) { this.damageDealt += damage; }
        public void addDamageReceived(double damage) { this.damageReceived += damage; }
        public void addArrowShot() { 
            this.arrowsShot++; 
            calculateArrowAccuracy();
        }

        public void addArrowHit() {
            this.arrowsHit++;
            calculateArrowAccuracy();
        }
    }

    /**
     * Clase para almacenar la información completa de una partida
     */
    public static class MatchSummary {
        private final String matchId;
        private final String matchType;
        private final String mapName;
        private final String winnerTeam;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final long durationSeconds;
        private final Map<String, PlayerMatchStats> playerStats;

        public MatchSummary(String matchId, String matchType, String mapName, String winnerTeam,
                          LocalDateTime startTime, LocalDateTime endTime, Map<String, PlayerMatchStats> playerStats) {
            this.matchId = matchId;
            this.matchType = matchType;
            this.mapName = mapName;
            this.winnerTeam = winnerTeam;
            this.startTime = startTime;
            this.endTime = endTime;

            // Calcular duración considerando si la partida está en curso
            if (endTime != null && startTime != null) {
                this.durationSeconds = java.time.Duration.between(startTime, endTime).getSeconds();
            } else if ("TBD".equals(winnerTeam) && startTime != null) {
                // Partida en curso: calcular duración hasta ahora
                this.durationSeconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
            } else {
                this.durationSeconds = 0;
            }

            this.playerStats = playerStats != null ? playerStats : new java.util.HashMap<>();
        }

        // Constructor para partidas completadas con duración específica
        public MatchSummary(String matchId, String matchType, String mapName, String winnerTeam,
                          LocalDateTime startTime, LocalDateTime endTime, long durationSeconds,
                          Map<String, PlayerMatchStats> playerStats) {
            this.matchId = matchId;
            this.matchType = matchType;
            this.mapName = mapName;
            this.winnerTeam = winnerTeam;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationSeconds = durationSeconds;
            this.playerStats = playerStats != null ? playerStats : new java.util.HashMap<>();
        }

        // Getters
        public String getMatchId() { return matchId; }
        public String getMatchType() { return matchType; }
        public String getMapName() { return mapName; }
        public String getWinnerTeam() { return winnerTeam; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public long getDurationSeconds() { return durationSeconds; }
        public Map<String, PlayerMatchStats> getPlayerStats() { return playerStats; }

        // Método de utilidad para verificar si la partida está completada
        public boolean isCompleted() {
            return winnerTeam != null && !"TBD".equals(winnerTeam);
        }

        // Método de utilidad para verificar si la partida está en curso
        public boolean isOngoing() {
            return "TBD".equals(winnerTeam);
        }
    }

    /**
     * Guarda los datos completos de una partida en la base de datos match_logs
     */
    public static CompletableFuture<Boolean> saveMatchData(MatchSummary matchSummary) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DatabaseManager.getConnectionTo("match_logs")) {
                conn.setAutoCommit(false);
                
                try {
                    // Insertar información de la partida
                    saveMatchInfo(conn, matchSummary);
                    
                    // Insertar estadísticas de los jugadores
                    savePlayerStats(conn, matchSummary);
                    
                    conn.commit();
                    
                    Bukkit.getConsoleSender().sendMessage(
                        "§a✅ Datos de partida " + matchSummary.getMatchId() + " guardados correctamente en match_logs"
                    );
                    
                    return true;
                    
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
                
            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al guardar datos de partida " + matchSummary.getMatchId() + ": " + e.getMessage()
                );
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * Guarda la información general de la partida
     */
    private static void saveMatchInfo(Connection conn, MatchSummary matchSummary) throws SQLException {
        String insertMatchQuery = """
            INSERT INTO matches (match_id, match_type, map_name, winner_team, duration_seconds, start_time, end_time)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            winner_team = VALUES(winner_team),
            duration_seconds = VALUES(duration_seconds),
            end_time = VALUES(end_time)
        """;

        try (PreparedStatement stmt = conn.prepareStatement(insertMatchQuery)) {
            stmt.setString(1, matchSummary.getMatchId());
            stmt.setString(2, matchSummary.getMatchType());
            stmt.setString(3, matchSummary.getMapName());
            stmt.setString(4, matchSummary.getWinnerTeam());
            stmt.setLong(5, matchSummary.getDurationSeconds());
            stmt.setTimestamp(6, Timestamp.valueOf(matchSummary.getStartTime()));
            stmt.setTimestamp(7, Timestamp.valueOf(matchSummary.getEndTime()));
            
            stmt.executeUpdate();
        }
    }

    /**
     * Guarda las estadísticas de todos los jugadores de la partida
     */
    private static void savePlayerStats(Connection conn, MatchSummary matchSummary) throws SQLException {
        String insertPlayerStatsQuery = """
            INSERT INTO match_players (
                match_id, player_uuid, player_name, team, kills, deaths, damage_dealt, damage_received,
                arrows_shot, arrows_hit, arrow_accuracy, old_elo, new_elo, elo_change,
                old_mmr, new_mmr, mmr_change, won
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            kills = VALUES(kills),
            deaths = VALUES(deaths),
            damage_dealt = VALUES(damage_dealt),
            damage_received = VALUES(damage_received),
            arrows_shot = VALUES(arrows_shot),
            arrows_hit = VALUES(arrows_hit),
            arrow_accuracy = VALUES(arrow_accuracy),
            new_elo = VALUES(new_elo),
            elo_change = VALUES(elo_change),
            new_mmr = VALUES(new_mmr),
            mmr_change = VALUES(mmr_change),
            won = VALUES(won)
        """;

        try (PreparedStatement stmt = conn.prepareStatement(insertPlayerStatsQuery)) {
            for (PlayerMatchStats playerStats : matchSummary.getPlayerStats().values()) {
                stmt.setString(1, matchSummary.getMatchId());
                stmt.setString(2, playerStats.getPlayerUuid());
                stmt.setString(3, playerStats.getPlayerName());
                stmt.setString(4, playerStats.getTeam());
                stmt.setInt(5, playerStats.getKills());
                stmt.setInt(6, playerStats.getDeaths());
                stmt.setDouble(7, playerStats.getDamageDealt());
                stmt.setDouble(8, playerStats.getDamageReceived());
                stmt.setInt(9, playerStats.getArrowsShot());
                stmt.setInt(10, playerStats.getArrowsHit());
                stmt.setDouble(11, playerStats.getArrowAccuracy());
                stmt.setInt(12, playerStats.getOldElo());
                stmt.setInt(13, playerStats.getNewElo());
                stmt.setInt(14, playerStats.getEloChange());
                stmt.setDouble(15, playerStats.getOldMmr());
                stmt.setDouble(16, playerStats.getNewMmr());
                stmt.setDouble(17, playerStats.getMmrChange());
                stmt.setBoolean(18, playerStats.isWon());

                stmt.addBatch();
            }
            
            stmt.executeBatch();
        }
    }

    /**
     * Crea un registro inicial de partida en la base de datos cuando comienza
     * Versión sincronizada para evitar condiciones de carrera
     */
    public static CompletableFuture<Boolean> initializeMatch(String matchId, String matchType, String mapName) {
        return CompletableFuture.supplyAsync(() -> {
            String insertMatchQuery = """
                INSERT INTO matches (match_id, match_type, map_name, start_time, winner_team, duration_seconds, end_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                match_type = VALUES(match_type),
                map_name = VALUES(map_name),
                start_time = VALUES(start_time)
            """;

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(insertMatchQuery)) {

                // Usar tiempo de Lima para inicializar la partida
                LocalDateTime startTimeLima = getCurrentTimeInLima();
                Timestamp startTime = limaTimeToTimestamp(startTimeLima);

                stmt.setString(1, matchId);
                stmt.setString(2, matchType);
                stmt.setString(3, mapName);
                stmt.setTimestamp(4, startTime);
                stmt.setString(5, "TBD"); // Placeholder for winner_team since match hasn't finished
                stmt.setLong(6, 0); // Duration 0 for ongoing match
                stmt.setTimestamp(7, startTime); // Temporary end_time, will be updated when match finishes

                int rowsAffected = stmt.executeUpdate();

                // Forzar commit explícito para asegurar que la transacción se complete
                if (!conn.getAutoCommit()) {
                    conn.commit();
                }

                // Verificar que el registro se creó correctamente
                if (rowsAffected > 0) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§a✅ Partida " + matchId + " inicializada correctamente en base de datos (Hora Lima: " +
                        startTimeLima.toString() + ")"
                    );
                    return true;
                } else {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c⚠️ No se pudo confirmar la inicialización de partida " + matchId
                    );
                    return false;
                }

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al inicializar partida en base de datos: " + e.getMessage()
                );
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * Registra un evento específico de la partida
     * Verifica que el match exista antes de insertar el evento
     */
    public static CompletableFuture<Void> logMatchEvent(String matchId, String eventType, String playerUuid, String eventData) {
        return CompletableFuture.runAsync(() -> {
            // Reintentar hasta 3 veces si hay problemas de sincronización
            int maxRetries = 3;
            int retryDelay = 100; // milisegundos

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    String checkMatchQuery = "SELECT COUNT(*) FROM matches WHERE match_id = ?";
                    String insertEventQuery = """
                        INSERT INTO match_events (match_id, event_type, player_uuid, event_data, event_timestamp)
                        VALUES (?, ?, ?, ?, ?)
                    """;

                    try (Connection conn = DatabaseManager.getConnectionTo("match_logs")) {
                        // Verificar si el match existe
                        try (PreparedStatement checkStmt = conn.prepareStatement(checkMatchQuery)) {
                            checkStmt.setString(1, matchId);
                            ResultSet rs = checkStmt.executeQuery();

                            if (rs.next() && rs.getInt(1) == 0) {
                                if (attempt == maxRetries) {
                                    Bukkit.getConsoleSender().sendMessage(
                                        "§c⚠️ Partida " + matchId + " no existe después de " + maxRetries + " intentos"
                                    );
                                }
                                // Esperar un poco antes del siguiente intento
                                Thread.sleep(retryDelay);
                                continue;
                            }
                        }

                        // Insertar el evento
                        try (PreparedStatement stmt = conn.prepareStatement(insertEventQuery)) {
                            stmt.setString(1, matchId);
                            stmt.setString(2, eventType);
                            stmt.setString(3, playerUuid);
                            stmt.setString(4, eventData);
                            stmt.setTimestamp(5, new Timestamp(System.currentTimeMillis()));

                            stmt.executeUpdate();

                            // Éxito, salir del bucle de reintentos
                            return;
                        }
                    }

                } catch (SQLException e) {
                    if (e.getMessage().contains("foreign key constraint") && attempt < maxRetries) {
                        // Es un error de clave foránea, intentar de nuevo
                        try {
                            Thread.sleep(retryDelay * attempt); // Aumentar el delay con cada intento
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        continue;
                    } else {
                        // Error diferente o último intento fallido
                        Bukkit.getConsoleSender().sendMessage(
                            "§c❌ Error al registrar evento de partida (intento " + attempt + "/" + maxRetries + "): " + e.getMessage()
                        );
                        if (attempt == maxRetries) {
                            e.printStackTrace();
                        }
                        if (attempt < maxRetries) {
                            try {
                                Thread.sleep(retryDelay * attempt);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    /**
     * Obtiene una lista de las últimas partidas con información básica
     * Filtra solo partidas completadas por defecto
     */
    public static CompletableFuture<List<MatchBasicInfo>> getRecentMatches(int limit) {
        return getRecentMatches(limit, true);
    }

    /**
     * Obtiene una lista de las últimas partidas con información básica
     * @param limit número máximo de partidas a obtener
     * @param onlyCompleted si true, solo obtiene partidas completadas (winner_team != 'TBD')
     */
    public static CompletableFuture<List<MatchBasicInfo>> getRecentMatches(int limit, boolean onlyCompleted) {
        return CompletableFuture.supplyAsync(() -> {
            List<MatchBasicInfo> matches = new java.util.ArrayList<>();

            String query;
            if (onlyCompleted) {
                query = """
                    SELECT match_id, match_type, map_name, winner_team, start_time, end_time, duration_seconds
                    FROM matches 
                    WHERE winner_team != 'TBD' AND winner_team IS NOT NULL
                    ORDER BY start_time DESC 
                    LIMIT ?
                """;
            } else {
                query = """
                    SELECT match_id, match_type, map_name, winner_team, start_time, end_time, duration_seconds
                    FROM matches 
                    ORDER BY start_time DESC 
                    LIMIT ?
                """;
            }

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    // Calcular duración real para partidas en curso
                    long duration = rs.getLong("duration_seconds");
                    String winnerTeam = rs.getString("winner_team");
                    Timestamp endTime = rs.getTimestamp("end_time");
                    Timestamp startTime = rs.getTimestamp("start_time");

                    // Si la partida está en curso (TBD), calcular duración hasta ahora
                    if ("TBD".equals(winnerTeam) && startTime != null) {
                        duration = (System.currentTimeMillis() - startTime.getTime()) / 1000;
                        endTime = null; // No mostrar end_time para partidas en curso
                    }

                    MatchBasicInfo info = new MatchBasicInfo(
                        rs.getString("match_id"),
                        rs.getString("match_type"),
                        rs.getString("map_name"),
                        winnerTeam,
                        startTime.toLocalDateTime(),
                        endTime != null ? endTime.toLocalDateTime() : null,
                        duration
                    );
                    matches.add(info);
                }

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al obtener partidas recientes: " + e.getMessage()
                );
                e.printStackTrace();
            }

            return matches;
        });
    }

    /**
     * Obtiene los detalles completos de una partida específica
     */
    public static CompletableFuture<MatchSummary> getMatchDetails(String matchId) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = DatabaseManager.getConnectionTo("match_logs")) {

                // Obtener información básica de la partida
                MatchBasicInfo basicInfo = getMatchBasicInfo(conn, matchId);
                if (basicInfo == null) return null;

                // Obtener estadísticas de los jugadores
                Map<String, PlayerMatchStats> playerStats = getMatchPlayerStats(conn, matchId);

                return new MatchSummary(
                    basicInfo.getMatchId(),
                    basicInfo.getMatchType(),
                    basicInfo.getMapName(),
                    basicInfo.getWinnerTeam(),
                    basicInfo.getStartTime(),
                    basicInfo.getEndTime(),
                    playerStats
                );

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al obtener detalles de partida " + matchId + ": " + e.getMessage()
                );
                e.printStackTrace();
                return null;
            }
        });
    }

    /**
     * Obtiene información básica de una partida
     */
    private static MatchBasicInfo getMatchBasicInfo(Connection conn, String matchId) throws SQLException {
        String query = """
            SELECT match_id, match_type, map_name, winner_team, start_time, end_time, duration_seconds
            FROM matches 
            WHERE match_id = ?
        """;

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, matchId);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new MatchBasicInfo(
                    rs.getString("match_id"),
                    rs.getString("match_type"),
                    rs.getString("map_name"),
                    rs.getString("winner_team"),
                    rs.getTimestamp("start_time").toLocalDateTime(),
                    rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toLocalDateTime() : null,
                    rs.getLong("duration_seconds")
                );
            }
        }

        return null;
    }

    /**
     * Obtiene las estadísticas de todos los jugadores de una partida
     */
    private static Map<String, PlayerMatchStats> getMatchPlayerStats(Connection conn, String matchId) throws SQLException {
        Map<String, PlayerMatchStats> playerStats = new java.util.HashMap<>();
        
        String query = """
            SELECT player_uuid, player_name, team, kills, deaths, damage_dealt, damage_received,
                   arrows_shot, arrows_hit, arrow_accuracy, old_elo, new_elo, elo_change,
                   old_mmr, new_mmr, mmr_change, won
            FROM match_players 
            WHERE match_id = ?
        """;

        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, matchId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                PlayerMatchStats stats = new PlayerMatchStats(
                    rs.getString("player_uuid"),
                    rs.getString("player_name"),
                    rs.getString("team")
                );

                stats.setKills(rs.getInt("kills"));
                stats.setDeaths(rs.getInt("deaths"));
                stats.setDamageDealt(rs.getDouble("damage_dealt"));
                stats.setDamageReceived(rs.getDouble("damage_received"));
                stats.setArrowsShot(rs.getInt("arrows_shot"));
                stats.setArrowsHit(rs.getInt("arrows_hit"));
                stats.setOldElo(rs.getInt("old_elo"));
                stats.setNewElo(rs.getInt("new_elo"));
                stats.setOldMmr(rs.getDouble("old_mmr"));
                stats.setNewMmr(rs.getDouble("new_mmr"));
                stats.setWon(rs.getBoolean("won"));

                playerStats.put(stats.getPlayerUuid(), stats);
            }
        }
        
        return playerStats;
    }

    /**
     * Clase para información básica de partidas (para listas)
     */
    public static class MatchBasicInfo {
        private final String matchId;
        private final String matchType;
        private final String mapName;
        private final String winnerTeam;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;
        private final long durationSeconds;

        public MatchBasicInfo(String matchId, String matchType, String mapName, String winnerTeam,
                            LocalDateTime startTime, LocalDateTime endTime, long durationSeconds) {
            this.matchId = matchId;
            this.matchType = matchType;
            this.mapName = mapName;
            this.winnerTeam = winnerTeam;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationSeconds = durationSeconds;
        }

        // Getters
        public String getMatchId() { return matchId; }
        public String getMatchType() { return matchType; }
        public String getMapName() { return mapName; }
        public String getWinnerTeam() { return winnerTeam; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getEndTime() { return endTime; }
        public long getDurationSeconds() { return durationSeconds; }
    }

    /**
     * Obtiene las partidas recientes de un jugador específico
     */
    public static CompletableFuture<List<MatchSummary>> getPlayerRecentMatches(String playerUuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<MatchSummary> matches = new java.util.ArrayList<>();

            String query = """
                SELECT DISTINCT m.match_id, m.match_type, m.map_name, m.winner_team, 
                       m.start_time, m.end_time, m.duration_seconds
                FROM matches m
                INNER JOIN match_players mp ON m.match_id = mp.match_id
                WHERE mp.player_uuid = ?
                ORDER BY m.start_time DESC 
                LIMIT ?
            """;

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, playerUuid);
                stmt.setInt(2, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    String matchId = rs.getString("match_id");

                    // Obtener estadísticas de jugadores para esta partida
                    Map<String, PlayerMatchStats> playerStats = getMatchPlayerStats(conn, matchId);

                    MatchSummary summary = new MatchSummary(
                        matchId,
                        rs.getString("match_type"),
                        rs.getString("map_name"),
                        rs.getString("winner_team"),
                        rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time") != null ? rs.getTimestamp("end_time").toLocalDateTime() : null,
                        playerStats
                    );
                    matches.add(summary);
                }

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al obtener historial de jugador " + playerUuid + ": " + e.getMessage()
                );
                e.printStackTrace();
            }

            return matches;
        });
    }

    /**
     * Obtiene estadísticas completas de una partida específica (alias para getMatchDetails)
     */
    public static CompletableFuture<MatchSummary> getMatchStats(String matchId) {
        return getMatchDetails(matchId);
    }

    /**
     * Método de utilidad para verificar la integridad de los datos guardados
     * Compara los datos esperados con los datos realmente guardados en la base de datos
     */
    public static CompletableFuture<Boolean> validateMatchData(String matchId, MatchSummary expectedData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Obtener los datos guardados
                MatchSummary savedData = getMatchDetails(matchId).join();

                if (savedData == null) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c❌ Validación fallida: No se encontró la partida " + matchId + " en la base de datos"
                    );
                    return false;
                }

                boolean isValid = true;
                StringBuilder errors = new StringBuilder();

                // Verificar datos básicos de la partida
                if (!expectedData.getMatchType().equals(savedData.getMatchType())) {
                    errors.append("§c- Match type: esperado '").append(expectedData.getMatchType())
                           .append("', encontrado '").append(savedData.getMatchType()).append("'\n");
                    isValid = false;
                }

                if (!expectedData.getMapName().equals(savedData.getMapName())) {
                    errors.append("§c- Map name: esperado '").append(expectedData.getMapName())
                           .append("', encontrado '").append(savedData.getMapName()).append("'\n");
                    isValid = false;
                }

                if (!expectedData.getWinnerTeam().equals(savedData.getWinnerTeam())) {
                    errors.append("§c- Winner team: esperado '").append(expectedData.getWinnerTeam())
                           .append("', encontrado '").append(savedData.getWinnerTeam()).append("'\n");
                    isValid = false;
                }

                // Verificar estadísticas de jugadores
                Map<String, PlayerMatchStats> expectedStats = expectedData.getPlayerStats();
                Map<String, PlayerMatchStats> savedStats = savedData.getPlayerStats();

                if (expectedStats.size() != savedStats.size()) {
                    errors.append("§c- Player stats count: esperado ").append(expectedStats.size())
                           .append(", encontrado ").append(savedStats.size()).append("\n");
                    isValid = false;
                }

                // Verificar cada jugador
                for (Map.Entry<String, PlayerMatchStats> entry : expectedStats.entrySet()) {
                    String playerUuid = entry.getKey();
                    PlayerMatchStats expected = entry.getValue();
                    PlayerMatchStats saved = savedStats.get(playerUuid);

                    if (saved == null) {
                        errors.append("§c- Jugador ").append(expected.getPlayerName())
                               .append(" (").append(playerUuid).append(") no encontrado en base de datos\n");
                        isValid = false;
                        continue;
                    }

                    // Verificar estadísticas específicas del jugador
                    if (expected.getKills() != saved.getKills()) {
                        errors.append("§c- ").append(expected.getPlayerName()).append(" kills: esperado ")
                               .append(expected.getKills()).append(", encontrado ").append(saved.getKills()).append("\n");
                        isValid = false;
                    }

                    if (expected.getDeaths() != saved.getDeaths()) {
                        errors.append("§c- ").append(expected.getPlayerName()).append(" deaths: esperado ")
                               .append(expected.getDeaths()).append(", encontrado ").append(saved.getDeaths()).append("\n");
                        isValid = false;
                    }

                    if (Math.abs(expected.getDamageDealt() - saved.getDamageDealt()) > 0.01) {
                        errors.append("§c- ").append(expected.getPlayerName()).append(" damage dealt: esperado ")
                               .append(expected.getDamageDealt()).append(", encontrado ").append(saved.getDamageDealt()).append("\n");
                        isValid = false;
                    }

                    if (expected.getNewElo() != saved.getNewElo()) {
                        errors.append("§c- ").append(expected.getPlayerName()).append(" new ELO: esperado ")
                               .append(expected.getNewElo()).append(", encontrado ").append(saved.getNewElo()).append("\n");
                        isValid = false;
                    }

                    if (Math.abs(expected.getNewMmr() - saved.getNewMmr()) > 0.01) {
                        errors.append("§c- ").append(expected.getPlayerName()).append(" new MMR: esperado ")
                               .append(expected.getNewMmr()).append(", encontrado ").append(saved.getNewMmr()).append("\n");
                        isValid = false;
                    }
                }

                if (isValid) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§a✅ Validación exitosa: Todos los datos de la partida " + matchId + " se guardaron correctamente"
                    );
                } else {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c❌ Validación fallida para partida " + matchId + ":\n" + errors.toString()
                    );
                }

                return isValid;

            } catch (Exception e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error durante validación de partida " + matchId + ": " + e.getMessage()
                );
                e.printStackTrace();
                return false;
            }
        });
    }

    /**
     * Método de utilidad para verificar partidas incompletas o corruptas
     */
    public static CompletableFuture<List<String>> findIncompleteMatches() {
        return CompletableFuture.supplyAsync(() -> {
            List<String> incompleteMatches = new java.util.ArrayList<>();

            String query = """
                SELECT m.match_id, m.winner_team, 
                       COUNT(mp.player_uuid) as player_count,
                       COUNT(CASE WHEN mp.old_elo = 0 OR mp.new_elo = 0 THEN 1 END) as invalid_elo_count
                FROM matches m
                LEFT JOIN match_players mp ON m.match_id = mp.match_id
                GROUP BY m.match_id, m.winner_team
                HAVING winner_team = 'TBD' 
                    OR player_count = 0 
                    OR invalid_elo_count > 0
                ORDER BY m.start_time DESC
            """;

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    String matchId = rs.getString("match_id");
                    String winnerTeam = rs.getString("winner_team");
                    int playerCount = rs.getInt("player_count");
                    int invalidEloCount = rs.getInt("invalid_elo_count");

                    String reason = "";
                    if ("TBD".equals(winnerTeam)) {
                        reason = "partida en curso";
                    } else if (playerCount == 0) {
                        reason = "sin estadísticas de jugadores";
                    } else if (invalidEloCount > 0) {
                        reason = "datos de ELO inválidos";
                    }

                    incompleteMatches.add(matchId + " (" + reason + ")");
                }

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                    "§c❌ Error al buscar partidas incompletas: " + e.getMessage()
                );
                e.printStackTrace();
            }

            return incompleteMatches;
        });
    }

    /**
     * Obtiene la fecha y hora actual en la zona horaria de Lima, Perú
     */
    public static LocalDateTime getCurrentTimeInLima() {
        return ZonedDateTime.now(LIMA_ZONE).toLocalDateTime();
    }

    /**
     * Convierte un Timestamp de la base de datos a LocalDateTime en zona horaria de Lima
     */
    private static LocalDateTime timestampToLimaTime(Timestamp timestamp) {
        if (timestamp == null) return null;
        return timestamp.toInstant().atZone(LIMA_ZONE).toLocalDateTime();
    }

    /**
     * Convierte un LocalDateTime de Lima a Timestamp para la base de datos
     */
    public static Timestamp limaTimeToTimestamp(LocalDateTime limaTime) {
        ZonedDateTime limaZoned = limaTime.atZone(LIMA_ZONE);
        return Timestamp.valueOf(limaZoned.toLocalDateTime());
    }
}
