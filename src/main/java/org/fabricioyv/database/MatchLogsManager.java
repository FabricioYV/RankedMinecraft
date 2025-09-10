package org.fabricioyv.database;

import org.bukkit.Bukkit;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        public String getPlayerUuid() {
            return playerUuid;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getTeam() {
            return team;
        }

        public int getKills() {
            return kills;
        }

        public void setKills(int kills) {
            this.kills = kills;
        }

        public int getDeaths() {
            return deaths;
        }

        public void setDeaths(int deaths) {
            this.deaths = deaths;
        }

        public double getDamageDealt() {
            return damageDealt;
        }

        public void setDamageDealt(double damageDealt) {
            this.damageDealt = damageDealt;
        }

        public double getDamageReceived() {
            return damageReceived;
        }

        public void setDamageReceived(double damageReceived) {
            this.damageReceived = damageReceived;
        }

        public int getArrowsShot() {
            return arrowsShot;
        }

        public void setArrowsShot(int arrowsShot) {
            this.arrowsShot = arrowsShot;
            calculateArrowAccuracy();
        }

        public int getArrowsHit() {
            return arrowsHit;
        }

        public void setArrowsHit(int arrowsHit) {
            this.arrowsHit = arrowsHit;
            calculateArrowAccuracy();
        }

        public double getArrowAccuracy() {
            return arrowAccuracy;
        }

        private void calculateArrowAccuracy() {
            if (arrowsShot > 0) {
                this.arrowAccuracy = ((double) arrowsHit / arrowsShot) * 100.0;
            } else {
                this.arrowAccuracy = 0.0;
            }
        }

        public int getOldElo() {
            return oldElo;
        }

        public void setOldElo(int oldElo) {
            this.oldElo = oldElo;
        }

        public int getNewElo() {
            return newElo;
        }

        public void setNewElo(int newElo) {
            this.newElo = newElo;
            this.eloChange = newElo - oldElo;
        }

        public int getEloChange() {
            return eloChange;
        }

        public double getOldMmr() {
            return oldMmr;
        }

        public void setOldMmr(double oldMmr) {
            this.oldMmr = oldMmr;
        }

        public double getNewMmr() {
            return newMmr;
        }

        public void setNewMmr(double newMmr) {
            this.newMmr = newMmr;
            this.mmrChange = newMmr - oldMmr;
        }

        public double getMmrChange() {
            return mmrChange;
        }

        public boolean isWon() {
            return won;
        }

        public void setWon(boolean won) {
            this.won = won;
        }

        public void addKill() {
            this.kills++;
        }

        public void addDeath() {
            this.deaths++;
        }

        public void addDamageDealt(double damage) {
            this.damageDealt += damage;
        }

        public void addDamageReceived(double damage) {
            this.damageReceived += damage;
        }

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
        public String getMatchId() {
            return matchId;
        }

        public String getMatchType() {
            return matchType;
        }

        public String getMapName() {
            return mapName;
        }

        public String getWinnerTeam() {
            return winnerTeam;
        }

        public LocalDateTime getStartTime() {
            return startTime;
        }

        public LocalDateTime getEndTime() {
            return endTime;
        }

        public long getDurationSeconds() {
            return durationSeconds;
        }

        public Map<String, PlayerMatchStats> getPlayerStats() {
            return playerStats;
        }

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
     * Obtiene la hora actual en la zona horaria de Lima
     */
    private static LocalDateTime getCurrentTimeInLima() {
        return LocalDateTime.now(LIMA_ZONE);
    }

    /**
     * Convierte LocalDateTime de Lima a Timestamp para la base de datos
     */
    private static Timestamp limaTimeToTimestamp(LocalDateTime limaTime) {
        return Timestamp.valueOf(limaTime);
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
                // Log error instead of printStackTrace
                Bukkit.getLogger().severe("Error guardando partida: " + e.getMessage());
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
                // Log error instead of printStackTrace
                Bukkit.getLogger().severe("Error inicializando partida: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * Registra un evento específico de la partida
     * Verifica que el match exista antes de insertar el evento
     */
    public static void logMatchEvent(String matchId, String eventType, String playerUuid, String eventData) {
        CompletableFuture.runAsync(() -> {
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
                            Thread.sleep((long) retryDelay * attempt); // Fix casting issue
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        // Error diferente o último intento fallido
                        Bukkit.getConsoleSender().sendMessage(
                                "§c❌ Error al registrar evento de partida (intento " + attempt + "/" + maxRetries + "): " + e.getMessage()
                        );
                        if (attempt == maxRetries) {
                            Bukkit.getLogger().severe("Error registrando evento: " + e.getMessage());
                        }
                        if (attempt < maxRetries) {
                            try {
                                Thread.sleep((long) retryDelay * attempt); // Fix casting issue
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
     * Procesa eventos en lotes para optimizar rendimiento de base de datos
     * NUEVO: Sistema optimizado que reduce lag significativamente
     */
    public static void processBatchEvents(List<PendingEvent> events) {
        if (events.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            try (Connection conn = DatabaseManager.getConnectionTo("match_logs")) {
                conn.setAutoCommit(false);

                String insertEventQuery = """
                    INSERT INTO match_events (match_id, event_type, player_uuid, event_data, event_timestamp)
                    VALUES (?, ?, ?, ?, ?)
                """;

                try (PreparedStatement stmt = conn.prepareStatement(insertEventQuery)) {
                    int batchCount = 0;

                    for (PendingEvent event : events) {
                        try {
                            stmt.setString(1, event.matchId);
                            stmt.setString(2, event.eventType);
                            stmt.setString(3, event.playerUuid);
                            stmt.setString(4, event.eventData);
                            stmt.setTimestamp(5, new Timestamp(event.timestamp));

                            stmt.addBatch();
                            batchCount++;

                            // Ejecutar en sub-lotes de 50 para evitar timeouts
                            if (batchCount % 50 == 0) {
                                stmt.executeBatch();
                                stmt.clearBatch();
                            }

                        } catch (Exception e) {
                            Bukkit.getConsoleSender().sendMessage(
                                    "§c[BatchProcessor] Error procesando evento individual: " + e.getMessage()
                            );
                        }
                    }

                    // Ejecutar el lote restante
                    if (batchCount % 50 != 0) {
                        stmt.executeBatch();
                    }

                    conn.commit();

                    // Log de éxito (solo para lotes grandes)
                    if (events.size() > 10) {
                        Bukkit.getConsoleSender().sendMessage(
                                "§a[BatchProcessor] ✅ Procesados " + events.size() + " eventos en lote"
                        );
                    }

                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }

            } catch (SQLException e) {
                Bukkit.getConsoleSender().sendMessage(
                        "§c[BatchProcessor] ❌ Error procesando lote de " + events.size() + " eventos: " + e.getMessage()
                );
            }
        });
    }

    /**
     * Clase para eventos pendientes de procesamiento en lotes
     */
    public static class PendingEvent {
        public final String matchId;
        public final String eventType;
        public final String playerUuid;
        public final String eventData;
        public final long timestamp;

        public PendingEvent(String matchId, String eventType, String playerUuid, String eventData) {
            this.matchId = matchId;
            this.eventType = eventType;
            this.playerUuid = playerUuid;
            this.eventData = eventData;
            this.timestamp = System.currentTimeMillis();
        }
    }












    /**
     * Obtiene las partidas más recientes (para todos los jugadores)
     */
    public static CompletableFuture<List<MatchSummary>> getRecentMatches(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<MatchSummary> matches = new ArrayList<>();

            String query = """
                SELECT DISTINCT m.match_id, m.match_type, m.map_name, m.winner_team, 
                       m.start_time, m.end_time, m.duration_seconds
                FROM matches m 
                WHERE m.winner_team != 'TBD'
                ORDER BY m.start_time DESC 
                LIMIT ?
            """;

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setInt(1, limit);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    MatchSummary summary = new MatchSummary(
                            rs.getString("match_id"),
                            rs.getString("match_type"),
                            rs.getString("map_name"),
                            rs.getString("winner_team"),
                            rs.getTimestamp("start_time").toLocalDateTime(),
                            rs.getTimestamp("end_time").toLocalDateTime(),
                            rs.getLong("duration_seconds"),
                            new HashMap<>() // Empty player stats for recent matches list
                    );
                    matches.add(summary);
                }

            } catch (SQLException e) {
                Bukkit.getLogger().severe("Error obteniendo partidas recientes: " + e.getMessage());
            }

            return matches;
        });
    }
}

