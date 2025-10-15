package org.fabricioyv.database;

import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Migración para el sistema de ELO Decay y columnas de abandono
 * Agrega columnas: last_match_date, cooldown_end_time, is_permanently_banned
 */
public class EloDecayMigration {
    
    /**
     * Verifica si una columna existe en la tabla
     */
    private static boolean columnExists(String columnName) {
        try (Connection conn = DatabaseManager.getConnection("ranked");
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                 "WHERE TABLE_SCHEMA = DATABASE() " +
                 "AND TABLE_NAME = 'ranked_players' " +
                 "AND COLUMN_NAME = ?")) {

            stmt.setString(1, columnName);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            Bukkit.getLogger().warning("Error verificando columna " + columnName + ": " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Verifica si todas las migraciones ya fueron aplicadas
     */
    public static boolean isMigrationApplied() {
        return columnExists("last_match_date") &&
               columnExists("cooldown_end_time") &&
               columnExists("is_permanently_banned");
    }

    /**
     * Ejecuta todas las migraciones necesarias
     */
    public static void executeMigration() {
        try (Connection conn = DatabaseManager.getConnection("ranked")) {
            int migrationsApplied = 0;

            // 1. Agregar columna last_match_date (para ELO Decay)
            if (!columnExists("last_match_date")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE ranked_players " +
                    "ADD COLUMN last_match_date DATETIME NULL DEFAULT NULL " +
                    "AFTER updated_at")) {

                    stmt.executeUpdate();
                    Bukkit.getLogger().info("§a✓ Columna 'last_match_date' agregada a ranked_players");
                    migrationsApplied++;

                    // Poblar con datos existentes (usar updated_at como aproximación)
                    try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE ranked_players " +
                        "SET last_match_date = updated_at " +
                        "WHERE games_played > 0 AND last_match_date IS NULL")) {

                        int updated = updateStmt.executeUpdate();
                        Bukkit.getLogger().info("§a  └─ Inicializados " + updated + " registros con last_match_date");
                    }

                    // Crear índice
                    try (PreparedStatement indexStmt = conn.prepareStatement(
                        "CREATE INDEX idx_last_match_date ON ranked_players(last_match_date)")) {
                        indexStmt.executeUpdate();
                        Bukkit.getLogger().info("§a  └─ Índice 'idx_last_match_date' creado");
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("Duplicate key name")) {
                            throw e;
                        }
                    }
                } catch (SQLException e) {
                    Bukkit.getLogger().severe("§c✗ Error agregando last_match_date: " + e.getMessage());
                    throw e;
                }
            } else {
                Bukkit.getLogger().info("§7  └─ Columna 'last_match_date' ya existe, omitiendo...");
            }
            
            // 2. Agregar columna cooldown_end_time (para sistema de abandono)
            if (!columnExists("cooldown_end_time")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE ranked_players " +
                    "ADD COLUMN cooldown_end_time BIGINT DEFAULT 0 " +
                    "AFTER placement_matches_played")) {

                    stmt.executeUpdate();
                    Bukkit.getLogger().info("§a✓ Columna 'cooldown_end_time' agregada a ranked_players");
                    migrationsApplied++;

                    // Crear índice
                    try (PreparedStatement indexStmt = conn.prepareStatement(
                        "CREATE INDEX idx_cooldown ON ranked_players(cooldown_end_time)")) {
                        indexStmt.executeUpdate();
                        Bukkit.getLogger().info("§a  └─ Índice 'idx_cooldown' creado");
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("Duplicate key name")) {
                            throw e;
                        }
                    }
                } catch (SQLException e) {
                    Bukkit.getLogger().severe("§c✗ Error agregando cooldown_end_time: " + e.getMessage());
                    throw e;
                }
            } else {
                Bukkit.getLogger().info("§7  └─ Columna 'cooldown_end_time' ya existe, omitiendo...");
            }
            
            // 3. Agregar columna is_permanently_banned (para baneos por abandono)
            if (!columnExists("is_permanently_banned")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER TABLE ranked_players " +
                    "ADD COLUMN is_permanently_banned TINYINT(1) DEFAULT 0 " +
                    "AFTER cooldown_end_time")) {

                    stmt.executeUpdate();
                    Bukkit.getLogger().info("§a✓ Columna 'is_permanently_banned' agregada a ranked_players");
                    migrationsApplied++;

                    // Crear índice
                    try (PreparedStatement indexStmt = conn.prepareStatement(
                        "CREATE INDEX idx_banned ON ranked_players(is_permanently_banned)")) {
                        indexStmt.executeUpdate();
                        Bukkit.getLogger().info("§a  └─ Índice 'idx_banned' creado");
                    } catch (SQLException e) {
                        if (!e.getMessage().contains("Duplicate key name")) {
                            throw e;
                        }
                    }
                } catch (SQLException e) {
                    Bukkit.getLogger().severe("§c✗ Error agregando is_permanently_banned: " + e.getMessage());
                    throw e;
                }
            } else {
                Bukkit.getLogger().info("§7  └─ Columna 'is_permanently_banned' ya existe, omitiendo...");
            }
            
            if (migrationsApplied > 0) {
                Bukkit.getLogger().info("§a✅ Migraciones completadas: " + migrationsApplied + " columnas agregadas");
            } else {
                Bukkit.getLogger().info("§a✅ Todas las migraciones ya estaban aplicadas");
            }

        } catch (SQLException e) {
            Bukkit.getLogger().severe("§c✗ Error ejecutando migraciones: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
