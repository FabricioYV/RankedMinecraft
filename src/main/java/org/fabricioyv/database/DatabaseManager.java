
package org.fabricioyv.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.fabricioyv.model.PlayerData;

import java.sql.*;
import java.util.List;
import java.util.UUID;

public class DatabaseManager {
    private static HikariDataSource dataSource;

    // Configuración de base de datos
    private static final String HOST = "db-dtx-03.sparkedhost.us";
    private static final String PORT = "3306";
    private static final String DATABASE = "s181642_ranked";
    private static final String USERNAME = "u181642_EmzxRextoT";
    private static final String PASSWORD = "O=VjTvdt30P=tA3=QRhBMOks";

    // Configuración de pool y timeouts
    private static final int MAX_POOL_SIZE = 8;
    private static final int MIN_IDLE = 2;
    private static final long CONNECTION_TIMEOUT = 10000; // 10 segundos
    private static final long IDLE_TIMEOUT = 300000; // 5 minutos
    private static final long MAX_LIFETIME = 1800000; // 30 minutos

    public static boolean initialize() {
        try {
            HikariConfig config = new HikariConfig();
            // URL de conexión con configuración optimizada
            String url = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE +
                    "?useSSL=false" +
                    "&autoReconnect=true" +
                    "&characterEncoding=utf8" +
                    "&allowPublicKeyRetrieval=true" +
                    "&serverTimezone=America/Chicago" +
                    "&useTimezone=true" +
                    "&useLegacyDatetimeCode=false" +
                    "&connectTimeout=10000" +
                    "&socketTimeout=30000" +
                    "&useLocalSessionState=true" +
                    "&useLocalTransactionState=true" +
                    "&rewriteBatchedStatements=true" +
                    "&maintainTimeStats=false" +
                    "&useServerPrepStmts=true" +
                    "&cachePrepStmts=true" +
                    "&prepStmtCacheSize=250" +
                    "&prepStmtCacheSqlLimit=2048";

            config.setJdbcUrl(url);
            config.setUsername(USERNAME);
            config.setPassword(PASSWORD);
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Configuración del pool
            config.setMaximumPoolSize(MAX_POOL_SIZE);
            config.setMinimumIdle(MIN_IDLE);
            config.setConnectionTimeout(CONNECTION_TIMEOUT);
            config.setIdleTimeout(IDLE_TIMEOUT);
            config.setMaxLifetime(MAX_LIFETIME);
            config.setLeakDetectionThreshold(60000); // 1 minuto

            // Configuración de conexión
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
            config.addDataSourceProperty("cacheResultSetMetadata", "true");
            config.addDataSourceProperty("cacheServerConfiguration", "true");
            config.addDataSourceProperty("elideSetAutoCommits", "true");
            config.addDataSourceProperty("maintainTimeStats", "false");

            // Test de conexión
            config.setConnectionTestQuery("SELECT 1");
            config.setValidationTimeout(5000);

            dataSource = new HikariDataSource(config);

            // Verificar conexión inicial
            try (Connection testConn = dataSource.getConnection()) {
                createTables();
                Bukkit.getConsoleSender().sendMessage("§a✅ Pool de conexiones MySQL inicializado correctamente!");
                Bukkit.getConsoleSender().sendMessage("§7📊 Pool configurado: " + MAX_POOL_SIZE + " conexiones máximas");
                return true;
            }

        } catch (SQLException e) {
            Bukkit.getConsoleSender().sendMessage("§c❌ Error al inicializar pool de conexiones!");
            e.printStackTrace();
            return false;
        }
    }

    private static Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pool de conexiones no inicializado o cerrado");
        }
        return dataSource.getConnection();
    }

    private static void createTables() throws SQLException {
        String createPlayersTable = """
        CREATE TABLE IF NOT EXISTS ranked_players (
            id INT AUTO_INCREMENT PRIMARY KEY,
            minecraft_uuid VARCHAR(36) NOT NULL UNIQUE,
            minecraft_username VARCHAR(16) NOT NULL,
            discord_id VARCHAR(20),
            is_verified TINYINT(1) DEFAULT 0,
            elo INT DEFAULT 500,
            mmr DOUBLE DEFAULT 1000.0,
            is_in_match TINYINT(1) DEFAULT 0,
            current_match_id VARCHAR(50),
            wins INT DEFAULT 0,
            losses INT DEFAULT 0,
            games_played INT DEFAULT 0,
            total_kills INT DEFAULT 0,
            total_deaths INT DEFAULT 0,
            verification_code VARCHAR(8),
            verification_expiry BIGINT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            INDEX idx_discord_id (discord_id),
            INDEX idx_elo (elo),
            INDEX idx_mmr (mmr),
            INDEX idx_in_match (is_in_match),
            INDEX idx_minecraft_uuid (minecraft_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createPlayersTable);
        }
    }

    public static PlayerData getPlayerByDiscordId(String discordId) {
        String query = "SELECT * FROM ranked_players WHERE discord_id = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, discordId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(
                                rs.getString("minecraft_uuid"),
                                rs.getString("discord_id"),
                                rs.getInt("elo"),
                                rs.getBoolean("is_in_match"),
                                rs.getString("current_match_id"),
                                rs.getDouble("mmr"),
                                rs.getInt("wins"),
                                rs.getInt("losses"),
                                rs.getInt("games_played"),
                                rs.getInt("total_kills"),
                                rs.getInt("total_deaths")
                        );
                    }
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error obteniendo jugador por Discord ID después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return null;
    }

    public static PlayerData getPlayerByMinecraftUuid(String minecraftUuid) {
        String query = "SELECT * FROM ranked_players WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, minecraftUuid);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(
                                rs.getString("minecraft_uuid"),
                                rs.getString("discord_id"),
                                rs.getInt("elo"),
                                rs.getBoolean("is_in_match"),
                                rs.getString("current_match_id"),
                                rs.getDouble("mmr"),
                                rs.getInt("wins"),
                                rs.getInt("losses"),
                                rs.getInt("games_played"),
                                rs.getInt("total_kills"),
                                rs.getInt("total_deaths")
                        );
                    }
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error obteniendo jugador por UUID después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return null;
    }

    public static void updatePlayerStats(List<PlayerStatUpdate> updates) {
        String query = "UPDATE ranked_players SET " +
                "elo = ?, " +
                "mmr = ?, " +
                "wins = wins + ?, " +
                "losses = losses + ?, " +
                "games_played = games_played + 1, " +
                "total_kills = total_kills + ?, " +
                "total_deaths = total_deaths + ?, " +
                "is_in_match = ?, " +
                "current_match_id = ? " +
                "WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);

                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    for (PlayerStatUpdate update : updates) {
                        stmt.setInt(1, update.newElo);
                        stmt.setDouble(2, update.newMMR);
                        stmt.setInt(3, update.won ? 1 : 0);
                        stmt.setInt(4, update.won ? 0 : 1);
                        stmt.setInt(5, update.matchKills);
                        stmt.setInt(6, update.matchDeaths);
                        stmt.setBoolean(7, false);
                        stmt.setString(8, null);
                        stmt.setString(9, update.minecraftUuid);
                        stmt.addBatch();
                    }

                    stmt.executeBatch();
                    conn.commit();
                    System.out.println("✅ Batch update exitoso: " + updates.size() + " jugadores actualizados");
                    return; // Éxito, salir del loop

                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error en batch update después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Batch update intento " + attempt + " fallido, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    public static void updatePlayerMatchStatus(String minecraftUuid, boolean isInMatch, String currentMatchId) {
        String query = "UPDATE ranked_players SET is_in_match = ?, current_match_id = ? WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setBoolean(1, isInMatch);
                stmt.setString(2, currentMatchId);
                stmt.setString(3, minecraftUuid);

                stmt.executeUpdate();
                return;

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error actualizando estado de partida después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido actualizando estado, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    public static boolean registerPlayer(String minecraftUuid, String discordId) {
        String query = "INSERT INTO ranked_players (minecraft_uuid, discord_id) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE discord_id = VALUES(discord_id)";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, minecraftUuid);
                stmt.setString(2, discordId);

                int affectedRows = stmt.executeUpdate();
                return affectedRows > 0;

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error registrando jugador después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido registrando jugador, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return false;
    }

    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("🔌 Pool de conexiones cerrado correctamente");
        }
    }

    public static class PlayerStatUpdate {
        public final String minecraftUuid;
        public final boolean won;
        public final int newElo;
        public final double newMMR;
        public final int matchKills;
        public final int matchDeaths;

        public PlayerStatUpdate(String minecraftUuid, boolean won, int newElo,
                                double newMMR, int matchKills, int matchDeaths) {
            this.minecraftUuid = minecraftUuid;
            this.won = won;
            this.newElo = newElo;
            this.newMMR = newMMR;
            this.matchKills = matchKills;
            this.matchDeaths = matchDeaths;
        }
    }
}