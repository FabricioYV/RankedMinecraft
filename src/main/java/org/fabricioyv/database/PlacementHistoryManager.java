package org.fabricioyv.database;

import org.fabricioyv.rating.PlacementRankAssignment;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Maneja el almacenamiento y recuperación del historial de placement matches
 */
public class PlacementHistoryManager {

    /**
     * Guarda el resultado de una placement match
     */
    public static void savePlacementMatch(String playerUuid, String matchId, boolean won,
                                          int kills, int deaths, double damage) {
        String query = """
            INSERT INTO placement_match_history 
            (player_uuid, match_id, won, kills, deaths, damage, created_at) 
            VALUES (?, ?, ?, ?, ?, ?, NOW())
        """;

        try (Connection conn = DatabaseManager.getConnectionTo("ranked");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);
            stmt.setBoolean(3, won);
            stmt.setInt(4, kills);
            stmt.setInt(5, deaths);
            stmt.setDouble(6, damage);

            stmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("❌ Error guardando placement match: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Obtiene el historial completo de placement matches de un jugador
     */
    public static PlacementRankAssignment.PlacementMatchHistory getPlacementHistory(String playerUuid) {
        String query = """
            SELECT match_id, won, kills, deaths, damage 
            FROM placement_match_history 
            WHERE player_uuid = ? 
            ORDER BY created_at ASC
        """;

        List<PlacementRankAssignment.MatchRecord> matches = new ArrayList<>();

        try (Connection conn = DatabaseManager.getConnectionTo("ranked");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    matches.add(new PlacementRankAssignment.MatchRecord(
                            rs.getBoolean("won"),
                            rs.getInt("kills"),
                            rs.getInt("deaths"),
                            rs.getDouble("damage"),
                            rs.getString("match_id")
                    ));
                }
            }

        } catch (SQLException e) {
            System.err.println("❌ Error obteniendo historial de placement: " + e.getMessage());
            e.printStackTrace();
        }

        return new PlacementRankAssignment.PlacementMatchHistory(matches);
    }

    /**
     * Limpia el historial de placement de un jugador (útil para reset)
     */
    public static void clearPlacementHistory(String playerUuid) {
        String query = "DELETE FROM placement_match_history WHERE player_uuid = ?";

        try (Connection conn = DatabaseManager.getConnectionTo("ranked");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("❌ Error limpiando historial de placement: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Cuenta cuántas placement matches ha registrado un jugador
     */
    public static int getPlacementMatchCount(String playerUuid) {
        String query = "SELECT COUNT(*) FROM placement_match_history WHERE player_uuid = ?";

        try (Connection conn = DatabaseManager.getConnectionTo("ranked");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

        } catch (SQLException e) {
            System.err.println("❌ Error contando placement matches: " + e.getMessage());
            e.printStackTrace();
        }

        return 0;
    }
}