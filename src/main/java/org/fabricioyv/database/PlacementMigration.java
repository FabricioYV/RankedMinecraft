package org.fabricioyv.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Migración para agregar campos de placement matches a la base de datos
 */
public class PlacementMigration {

    /**
     * Ejecuta la migración para agregar campos de placement matches
     */
    public static void executePlacementMigration() {
        try (Connection conn = DatabaseManager.getConnectionTo("ranked")) {
            // Agregar campos para placement matches
            addPlacementFields(conn);

            // Inicializar datos de placement para jugadores existentes
            initializePlacementData(conn);

            System.out.println("✅ Migración de placement matches completada exitosamente");

        } catch (SQLException e) {
            System.err.println("❌ Error ejecutando migración de placement matches: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Agrega los campos de placement matches a la tabla
     */
    private static void addPlacementFields(Connection conn) throws SQLException {
        String[] alterTableQueries = {
                // Campo para indicar si el jugador está en placement matches
                "ALTER TABLE ranked_players ADD COLUMN IF NOT EXISTS is_in_placement TINYINT(1) DEFAULT 1",

                // Campo para contar cuántas placement matches ha jugado
                "ALTER TABLE ranked_players ADD COLUMN IF NOT EXISTS placement_matches_played INT DEFAULT 0",

                // Índice para optimizar consultas de placement
                "CREATE INDEX IF NOT EXISTS idx_placement ON ranked_players (is_in_placement, placement_matches_played)"
        };

        // Crear tabla para historial de placement matches
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

        try (Statement stmt = conn.createStatement()) {
            // Agregar campos a tabla existente
            for (String query : alterTableQueries) {
                try {
                    stmt.executeUpdate(query);
                    System.out.println("✅ Ejecutado: " + query);
                } catch (SQLException e) {
                    // Si la columna ya existe, ignorar el error
                    if (e.getMessage().contains("Duplicate column name") ||
                            e.getMessage().contains("already exists")) {
                        System.out.println("ℹ️ Campo ya existe, saltando: " + query);
                    } else {
                        throw e;
                    }
                }
            }

            // Crear tabla de historial
            try {
                stmt.executeUpdate(createPlacementHistoryTable);
                System.out.println("✅ Tabla placement_match_history creada");
            } catch (SQLException e) {
                if (e.getMessage().contains("already exists")) {
                    System.out.println("ℹ️ Tabla placement_match_history ya existe");
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Inicializa los datos de placement para jugadores existentes
     */
    private static void initializePlacementData(Connection conn) throws SQLException {
        // Actualizar jugadores existentes: los que tienen pocas partidas van a placement
        String updateExistingPlayers = """
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

        try (Statement stmt = conn.createStatement()) {
            int affectedRows = stmt.executeUpdate(updateExistingPlayers);
            System.out.println("✅ Inicializados datos de placement para " + affectedRows + " jugadores");
        }
    }

    /**
     * Verifica si la migración ya fue aplicada
     */
    public static boolean isMigrationApplied() {
        try (Connection conn = DatabaseManager.getConnectionTo("ranked")) {
            // Verificar si las columnas existen usando el nombre específico de la base de datos
            String checkColumns = """
                SELECT COUNT(*) as count 
                FROM INFORMATION_SCHEMA.COLUMNS 
                WHERE TABLE_SCHEMA = 's181642_ranked' 
                AND TABLE_NAME = 'ranked_players' 
                AND COLUMN_NAME IN ('is_in_placement', 'placement_matches_played')
            """;

            try (var stmt = conn.prepareStatement(checkColumns)) {
                var rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getInt("count") == 2; // Ambas columnas deben existir
                }
            }
        } catch (SQLException e) {
            System.err.println("Error verificando migración: " + e.getMessage());
        }

        return false;
    }
}