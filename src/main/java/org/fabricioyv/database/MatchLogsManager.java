package org.fabricioyv.database;

import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;
import org.bukkit.Bukkit;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Manejador especializado para guardar logs detallados de matches
 * NO envía mensajes a Discord, solo almacena datos en la base de datos
 */
public class MatchLogsManager {

    /**
     * Guarda un match completo con todas las estadísticas detalladas en la base de datos
     */
    public static boolean saveMatchLog(String matchId, String matchType, String mapName,
                                       Team winnerTeam, Map<Team, List<PlayerData>> teams,
                                       long durationSeconds, LocalDateTime startTime, LocalDateTime endTime) {

        if (!DatabaseManager.isDatabaseConnected("match_logs")) {
            System.err.println("⚠️ Base de datos de match logs no está disponible");
            return false;
        }

        Connection conn = null;
        try {
            conn = DatabaseManager.getConnectionTo("match_logs");
            conn.setAutoCommit(false);

            // 1. Insertar información básica del match
            String insertMatchQuery = """
                INSERT INTO matches (match_id, match_type, map_name, winner_team, duration_seconds, start_time, end_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement stmt = conn.prepareStatement(insertMatchQuery)) {
                stmt.setString(1, matchId);
                stmt.setString(2, matchType);
                stmt.setString(3, mapName);
                stmt.setString(4, winnerTeam.name());
                stmt.setLong(5, durationSeconds);
                stmt.setTimestamp(6, Timestamp.valueOf(startTime));
                stmt.setTimestamp(7, Timestamp.valueOf(endTime));
                stmt.executeUpdate();
            }

            // 2. Insertar estadísticas detalladas de cada jugador
            String insertPlayerQuery = """
                INSERT INTO match_players (match_id, player_uuid, player_name, team, kills, deaths, 
                                         damage_dealt, arrows_shot, arrows_hit, arrow_accuracy,
                                         old_elo, new_elo, elo_change, old_mmr, new_mmr, mmr_change, won)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            try (PreparedStatement stmt = conn.prepareStatement(insertPlayerQuery)) {
                for (Map.Entry<Team, List<PlayerData>> teamEntry : teams.entrySet()) {
                    Team team = teamEntry.getKey();
                    boolean won = team == winnerTeam;

                    for (PlayerData player : teamEntry.getValue()) {
                        String uuid = player.getMinecraftUuid();
                        String playerName = getPlayerName(uuid);

                        // Obtener estadísticas actuales del match
                        int kills = player.getCurrentMatchKills();
                        int deaths = player.getCurrentMatchDeaths();
                        double damageDealt = player.getCurrentMatchDamage();
                        int arrowsShot = player.getCurrentMatchArrowsShot();
                        int arrowsHit = player.getCurrentMatchArrowsHit();
                        double arrowAccuracy = player.getCurrentMatchArrowAccuracy();

                        // Para ELO y MMR necesitamos calcular los valores anteriores
                        // Asumiendo que los valores actuales ya fueron actualizados
                        int currentElo = player.getElo();
                        double currentMMR = player.getMmr();

                        // Estimación de valores anteriores (esto podría mejorarse pasando los valores reales)
                        int oldElo = currentElo; // Por ahora, usaremos el actual
                        double oldMMR = currentMMR; // Por ahora, usaremos el actual
                        int eloChange = 0; // Por ahora, 0
                        double mmrChange = 0.0; // Por ahora, 0

                        stmt.setString(1, matchId);
                        stmt.setString(2, uuid);
                        stmt.setString(3, playerName);
                        stmt.setString(4, team.name());
                        stmt.setInt(5, kills);
                        stmt.setInt(6, deaths);
                        stmt.setDouble(7, damageDealt);
                        stmt.setInt(8, arrowsShot);
                        stmt.setInt(9, arrowsHit);
                        stmt.setDouble(10, arrowAccuracy);
                        stmt.setInt(11, oldElo);
                        stmt.setInt(12, currentElo);
                        stmt.setInt(13, eloChange);
                        stmt.setDouble(14, oldMMR);
                        stmt.setDouble(15, currentMMR);
                        stmt.setDouble(16, mmrChange);
                        stmt.setBoolean(17, won);

                        stmt.addBatch();
                    }
                }
                stmt.executeBatch();
            }

            conn.commit();
            System.out.println("✅ Match log guardado exitosamente: " + matchId);

            // Resetear estadísticas de match de todos los jugadores
            resetPlayersMatchStats(teams);

            return true;

        } catch (SQLException e) {
            System.err.println("❌ Error guardando match log: " + e.getMessage());
            e.printStackTrace();

            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    System.err.println("❌ Error en rollback: " + rollbackEx.getMessage());
                }
            }
            return false;

        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                    conn.close();
                } catch (SQLException e) {
                    System.err.println("❌ Error cerrando conexión: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Versión extendida que acepta cambios de ELO y MMR específicos
     * OPTIMIZADO: Usa prepared statements y transacciones más eficientes
     */
    public static boolean saveMatchLogWithChanges(String matchId, String matchType, String mapName,
                                                  Team winnerTeam, Map<Team, List<PlayerData>> teams,
                                                  Map<String, Integer> eloChanges, Map<String, Double> mmrChanges,
                                                  Map<String, Integer> oldElos, Map<String, Double> oldMMRs,
                                                  long durationSeconds, LocalDateTime startTime, LocalDateTime endTime) {

        if (!DatabaseManager.isDatabaseConnected("match_logs")) {
            System.err.println("⚠️ Base de datos de match logs no está disponible");
            return false;
        }

        Connection conn = null;
        PreparedStatement matchStmt = null;
        PreparedStatement playersStmt = null;

        try {
            conn = DatabaseManager.getConnectionTo("match_logs");
            conn.setAutoCommit(false);

            // 1. Preparar statement para match principal
            String insertMatchQuery = """
                INSERT INTO matches (match_id, match_type, map_name, winner_team, duration_seconds, start_time, end_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

            matchStmt = conn.prepareStatement(insertMatchQuery);
            matchStmt.setString(1, matchId);
            matchStmt.setString(2, matchType);
            matchStmt.setString(3, mapName);
            matchStmt.setString(4, winnerTeam.name());
            matchStmt.setLong(5, durationSeconds);
            matchStmt.setTimestamp(6, Timestamp.valueOf(startTime));
            matchStmt.setTimestamp(7, Timestamp.valueOf(endTime));
            matchStmt.executeUpdate();

            // 2. Preparar statement para jugadores (batch optimizado)
            String insertPlayerQuery = """
                INSERT INTO match_players (match_id, player_uuid, player_name, team, kills, deaths, 
                                         damage_dealt, arrows_shot, arrows_hit, arrow_accuracy,
                                         old_elo, new_elo, elo_change, old_mmr, new_mmr, mmr_change, won)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

            playersStmt = conn.prepareStatement(insertPlayerQuery);

            // 3. Procesar todos los jugadores en batch
            int batchCount = 0;
            for (Map.Entry<Team, List<PlayerData>> teamEntry : teams.entrySet()) {
                Team team = teamEntry.getKey();
                boolean won = team == winnerTeam;

                for (PlayerData player : teamEntry.getValue()) {
                    String uuid = player.getMinecraftUuid();
                    String playerName = getPlayerName(uuid);

                    // Estadísticas del match (IMPORTANTE: obtener ANTES del reset)
                    int kills = player.getCurrentMatchKills();
                    int deaths = player.getCurrentMatchDeaths();
                    double damageDealt = player.getCurrentMatchDamage();
                    int arrowsShot = player.getCurrentMatchArrowsShot();
                    int arrowsHit = player.getCurrentMatchArrowsHit();
                    double arrowAccuracy = player.getCurrentMatchArrowAccuracy();

                    // Cambios de ELO y MMR
                    int oldElo = oldElos.getOrDefault(uuid, player.getElo());
                    int newElo = oldElo + eloChanges.getOrDefault(uuid, 0);
                    int eloChange = eloChanges.getOrDefault(uuid, 0);

                    double oldMMR = oldMMRs.getOrDefault(uuid, player.getMmr());
                    double newMMR = oldMMR + mmrChanges.getOrDefault(uuid, 0.0);
                    double mmrChange = mmrChanges.getOrDefault(uuid, 0.0);

                    // Setear parámetros
                    playersStmt.setString(1, matchId);
                    playersStmt.setString(2, uuid);
                    playersStmt.setString(3, playerName);
                    playersStmt.setString(4, team.name());
                    playersStmt.setInt(5, kills);
                    playersStmt.setInt(6, deaths);
                    playersStmt.setDouble(7, damageDealt);
                    playersStmt.setInt(8, arrowsShot);
                    playersStmt.setInt(9, arrowsHit);
                    playersStmt.setDouble(10, arrowAccuracy);
                    playersStmt.setInt(11, oldElo);
                    playersStmt.setInt(12, newElo);
                    playersStmt.setInt(13, eloChange);
                    playersStmt.setDouble(14, oldMMR);
                    playersStmt.setDouble(15, newMMR);
                    playersStmt.setDouble(16, mmrChange);
                    playersStmt.setBoolean(17, won);

                    playersStmt.addBatch();
                    batchCount++;

                    // Ejecutar batch cada 100 registros para optimización
                    if (batchCount % 100 == 0) {
                        playersStmt.executeBatch();
                        playersStmt.clearBatch();
                    }
                }
            }

            // Ejecutar batch restante
            if (batchCount % 100 != 0) {
                playersStmt.executeBatch();
            }

            // 4. Commit de toda la transacción
            conn.commit();
            System.out.println("✅ Match log con cambios guardado exitosamente: " + matchId + " (" + batchCount + " jugadores)");

            // NO resetear estadísticas aquí - se hace desde MatchFinisher después del guardado

            return true;

        } catch (SQLException e) {
            System.err.println("❌ Error guardando match log con cambios: " + e.getMessage());
            e.printStackTrace();

            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    System.err.println("❌ Error en rollback: " + rollbackEx.getMessage());
                }
            }
            return false;

        } finally {
            // Cerrar resources de forma segura
            try {
                if (playersStmt != null) playersStmt.close();
                if (matchStmt != null) matchStmt.close();
                if (conn != null) {
                    conn.setAutoCommit(true);
                    conn.close();
                }
            } catch (SQLException e) {
                System.err.println("❌ Error cerrando conexión: " + e.getMessage());
            }
        }
    }

    /**
     * Guarda un evento específico del match
     */
    public static void saveMatchEvent(String matchId, String eventType, String playerUuid,
                                     String eventData, LocalDateTime eventTimestamp) {
        if (!DatabaseManager.isDatabaseConnected("match_logs")) {
            return;
        }

        String query = """
            INSERT INTO match_events (match_id, event_type, player_uuid, event_data, event_timestamp)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, matchId);
            stmt.setString(2, eventType);
            stmt.setString(3, playerUuid);
            stmt.setString(4, eventData);
            stmt.setTimestamp(5, Timestamp.valueOf(eventTimestamp));

            stmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("❌ Error guardando evento de match: " + e.getMessage());
        }
    }

    /**
     * Obtiene el nombre del jugador por UUID
     */
    private static String getPlayerName(String uuid) {
        try {
            org.bukkit.entity.Player mcPlayer = Bukkit.getPlayer(java.util.UUID.fromString(uuid));
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }
        } catch (Exception e) {
            // Si no se puede obtener el nombre, usar una parte del UUID
        }
        return "Player_" + uuid.substring(0, 8);
    }

    /**
     * Resetea las estadísticas de match de todos los jugadores
     */
    private static void resetPlayersMatchStats(Map<Team, List<PlayerData>> teams) {
        for (List<PlayerData> teamPlayers : teams.values()) {
            for (PlayerData player : teamPlayers) {
                player.resetMatchStats();
            }
        }
    }

    // Métodos de consulta para uso futuro con comandos de Discord

    /**
     * Obtiene estadísticas básicas de un match específico
     */
    public static MatchSummary getMatchSummary(String matchId) {
        if (!DatabaseManager.isDatabaseConnected("match_logs")) {
            return null;
        }

        String query = """
            SELECT m.*, COUNT(mp.id) as player_count
            FROM matches m
            LEFT JOIN match_players mp ON m.match_id = mp.match_id
            WHERE m.match_id = ?
            GROUP BY m.id
            """;

        try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, matchId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new MatchSummary(
                        rs.getString("match_id"),
                        rs.getString("match_type"),
                        rs.getString("map_name"),
                        rs.getString("winner_team"),
                        rs.getLong("duration_seconds"),
                        rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time").toLocalDateTime(),
                        rs.getInt("player_count"),
                        null, // Placeholder for mvpPlayer
                        null, // Placeholder for topKillsPlayer
                        null, // Placeholder for topDamagePlayer
                        null  // Placeholder for topArrowAccuracyPlayer
                    );
                }
            }

        } catch (SQLException e) {
            System.err.println("❌ Error obteniendo resumen de match: " + e.getMessage());
        }

        return null;
    }

    /**
     * Obtiene los últimos matches registrados
     */
    public static java.util.List<MatchSummary> getRecentMatches(int limit) {
        if (!DatabaseManager.isDatabaseConnected("match_logs")) {
            return java.util.Collections.emptyList();
        }

        String query = """
            SELECT m.*, COUNT(mp.id) as player_count
            FROM matches m
            LEFT JOIN match_players mp ON m.match_id = mp.match_id
            GROUP BY m.id
            ORDER BY m.start_time DESC
            LIMIT ?
            """;

        java.util.List<MatchSummary> matches = new java.util.ArrayList<>();

        try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    matches.add(new MatchSummary(
                        rs.getString("match_id"),
                        rs.getString("match_type"),
                        rs.getString("map_name"),
                        rs.getString("winner_team"),
                        rs.getLong("duration_seconds"),
                        rs.getTimestamp("start_time").toLocalDateTime(),
                        rs.getTimestamp("end_time").toLocalDateTime(),
                        rs.getInt("player_count"),
                        rs.getString("mvp_player"),
                        rs.getString("top_kills_player"),
                        rs.getString("top_damage_player"),
                        rs.getString("top_arrow_accuracy_player")
                    ));
                }
            }

        } catch (SQLException e) {
            System.err.println("❌ Error obteniendo matches recientes: " + e.getMessage());
        }

        return matches;
    }

    // Clases de datos para resultados
    public static class MatchSummary {
        public final String matchId;
        public final String matchType;
        public final String mapName;
        public final String winnerTeam;
        public final long durationSeconds;
        public final LocalDateTime startTime;
        public final LocalDateTime endTime;
        public final int playerCount;

        public final String mvpPlayer;
        public final String topKillsPlayer;
        public final String topDamagePlayer;
        public final String topArrowAccuracyPlayer;

        public MatchSummary(String matchId, String matchType, String mapName, String winnerTeam,
                           long durationSeconds, LocalDateTime startTime, LocalDateTime endTime, int playerCount,
                           String mvpPlayer, String topKillsPlayer, String topDamagePlayer, String topArrowAccuracyPlayer) {
            this.matchId = matchId;
            this.matchType = matchType;
            this.mapName = mapName;
            this.winnerTeam = winnerTeam;
            this.durationSeconds = durationSeconds;
            this.startTime = startTime;
            this.endTime = endTime;
            this.playerCount = playerCount;
            this.mvpPlayer = mvpPlayer;
            this.topKillsPlayer = topKillsPlayer;
            this.topDamagePlayer = topDamagePlayer;
            this.topArrowAccuracyPlayer = topArrowAccuracyPlayer;
        }

        public static MatchSummary getMatchDetails(String matchId) {
            if (!DatabaseManager.isDatabaseConnected("match_logs")) {
                return null;
            }

            String query = """
                SELECT m.match_id, m.match_type, m.map_name, m.winner_team, m.duration_seconds, 
                       m.start_time, m.end_time, COUNT(mp.id) as player_count,
                       MAX(mp.mvp_score) as mvp_player, 
                       MAX(mp.kills) as top_kills_player, 
                       MAX(mp.damage_dealt) as top_damage_player, 
                       MAX(mp.arrow_accuracy) as top_arrow_accuracy_player
                FROM matches m
                LEFT JOIN match_players mp ON m.match_id = mp.match_id
                WHERE m.match_id = ?
                GROUP BY m.match_id
            """;

            try (Connection conn = DatabaseManager.getConnectionTo("match_logs");
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, matchId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new MatchSummary(
                                rs.getString("match_id"),
                                rs.getString("match_type"),
                                rs.getString("map_name"),
                                rs.getString("winner_team"),
                                rs.getLong("duration_seconds"),
                                rs.getTimestamp("start_time").toLocalDateTime(),
                                rs.getTimestamp("end_time").toLocalDateTime(),
                                rs.getInt("player_count"),
                                rs.getString("mvp_player"),
                                rs.getString("top_kills_player"),
                                rs.getString("top_damage_player"),
                                rs.getString("top_arrow_accuracy_player")
                        );
                    }
                }
            } catch (SQLException e) {
                System.err.println("❌ Error retrieving match details: " + e.getMessage());
            }

            return null;
        }
    }
}
