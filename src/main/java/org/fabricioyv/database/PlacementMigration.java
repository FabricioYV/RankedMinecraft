package org.fabricioyv.database;

import java.sql.*;

/**
 * Migración para agregar campos de placement matches a la base de datos
 */
public class PlacementMigration {

    /**
     * Ejecuta la migración para agregar campos de placement matches
     */
    public static void executePlacementMigration() {
        try (Connection conn = DatabaseManager.getConnectionTo("ranked")) {

            // (Opcional pero útil) ver qué BD está usando realmente esta conexión
            logCurrentDatabase(conn);

            // 1) Asegurar estructura (columnas / índices / tablas)
            MigrationResult result = addPlacementFields(conn);

            // 2) Inicializar datos SOLO si realmente se agregaron columnas (primera vez)
            if (result.addedAnyPlacementColumns) {
                initializePlacementData(conn, true);
            } else {
                // Si ya existía todo, solo arregla casos raros (nulos) sin tocar lo demás
                initializePlacementData(conn, false);
            }

            System.out.println("✅ Migración de placement matches completada exitosamente");

        } catch (SQLException e) {
            System.err.println("❌ Error ejecutando migración de placement matches: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Agrega los campos de placement matches a la tabla
     * (SIN usar IF NOT EXISTS en DDL, porque no siempre existe según versión)
     */
    private static MigrationResult addPlacementFields(Connection conn) throws SQLException {
        boolean addedColumns = false;

        // 1) Columnas en ranked_players
        if (!columnExists(conn, "ranked_players", "is_in_placement")) {
            exec(conn, "ALTER TABLE ranked_players ADD COLUMN is_in_placement TINYINT(1) NOT NULL DEFAULT 1");
            System.out.println("✅ Columna agregada: ranked_players.is_in_placement");
            addedColumns = true;
        } else {
            System.out.println("ℹ️ Ya existe: ranked_players.is_in_placement");
        }

        if (!columnExists(conn, "ranked_players", "placement_matches_played")) {
            exec(conn, "ALTER TABLE ranked_players ADD COLUMN placement_matches_played INT NOT NULL DEFAULT 0");
            System.out.println("✅ Columna agregada: ranked_players.placement_matches_played");
            addedColumns = true;
        } else {
            System.out.println("ℹ️ Ya existe: ranked_players.placement_matches_played");
        }

        // 2) Índice (CREATE INDEX IF NOT EXISTS NO es estándar)
        if (!indexExists(conn, "ranked_players", "idx_placement")) {
            exec(conn, "CREATE INDEX idx_placement ON ranked_players (is_in_placement, placement_matches_played)");
            System.out.println("✅ Índice creado: idx_placement");
        } else {
            System.out.println("ℹ️ Ya existe índice: idx_placement");
        }

        // 3) Tabla historial (esto sí suele soportar IF NOT EXISTS sin problemas)
        String createPlacementHistoryTable = """
            CREATE TABLE IF NOT EXISTS placement_match_history (
                id INT AUTO_INCREMENT PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                match_id VARCHAR(50) NOT NULL,
                won TINYINT(1) NOT NULL,
                kills INT NOT NULL,
                deaths INT NOT NULL,
                damage DOUBLE NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_player_uuid (player_uuid),
                INDEX idx_match_id (match_id),
                INDEX idx_created_at (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;
        exec(conn, createPlacementHistoryTable);
        System.out.println("✅ Tabla asegurada: placement_match_history");

        return new MigrationResult(addedColumns);
    }

    /**
     * Inicializa los datos de placement para jugadores existentes
     * @param forceAll si true, recalcula para TODOS (ideal cuando recién agregaste las columnas)
     *                 si false, solo arregla casos nulos para no pisar data ya establecida
     */
    private static void initializePlacementData(Connection conn, boolean forceAll) throws SQLException {

        String sql;
        if (forceAll) {
            // Primera vez: dejar consistente a todos
            sql = """
                UPDATE ranked_players
                SET
                    is_in_placement = CASE
                        WHEN games_played < 8 THEN 1
                        ELSE 0
                    END,
                    placement_matches_played = CASE
                        WHEN games_played < 8 THEN games_played
                        ELSE 8
                    END
            """;
        } else {
            // Ya existía: solo arreglar casos rotos (nulos)
            sql = """
                UPDATE ranked_players
                SET
                    is_in_placement = CASE
                        WHEN games_played < 8 THEN 1
                        ELSE 0
                    END,
                    placement_matches_played = CASE
                        WHEN games_played < 8 THEN games_played
                        ELSE 8
                    END
                WHERE is_in_placement IS NULL OR placement_matches_played IS NULL
            """;
        }

        try (Statement stmt = conn.createStatement()) {
            int affected = stmt.executeUpdate(sql);
            System.out.println("✅ Placement init aplicado a " + affected + " jugadores (forceAll=" + forceAll + ")");
        }
    }

    /**
     * Verifica si la migración ya fue aplicada (sin hardcodear schema)
     */
    public static boolean isMigrationApplied() {
        try (Connection conn = DatabaseManager.getConnectionTo("ranked")) {
            String checkColumns = """
                SELECT COUNT(*) AS count
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'ranked_players'
                  AND COLUMN_NAME IN ('is_in_placement', 'placement_matches_played')
            """;

            try (PreparedStatement ps = conn.prepareStatement(checkColumns);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("count") == 2;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error verificando migración: " + e.getMessage());
        }
        return false;
    }

    // -------------------------
    // Helpers
    // -------------------------

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(sql);
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        String q = """
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            LIMIT 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
        String q = """
            SELECT 1
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND INDEX_NAME = ?
            LIMIT 1
        """;
        try (PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void logCurrentDatabase(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            if (rs.next()) {
                System.out.println("ℹ️ PlacementMigration conectado a DB: " + rs.getString(1));
            }
        } catch (SQLException ignored) {}
    }

    private static class MigrationResult {
        final boolean addedAnyPlacementColumns;
        MigrationResult(boolean addedAnyPlacementColumns) {
            this.addedAnyPlacementColumns = addedAnyPlacementColumns;
        }
    }
}