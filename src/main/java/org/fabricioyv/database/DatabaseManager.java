package org.fabricioyv.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.model.PlayerData;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {
    // Mapa para almacenar múltiples datasources
    private static final Map<String, HikariDataSource> dataSources = new HashMap<>();

    // Configuración de pools y timeouts
    private static final int MAX_POOL_SIZE = 8;
    private static final int MIN_IDLE = 2;
    private static final long CONNECTION_TIMEOUT = 10000; // 10 segundos
    private static final long IDLE_TIMEOUT = 300000; // 5 minutos
    private static final long MAX_LIFETIME = 1800000; // 30 minutos

    // Configuraciones de bases de datos
    public static class DatabaseConfig {
        public final String name;
        public final String host;
        public final String port;
        public final String database;
        public final String username;
        public final String password;

        public DatabaseConfig(String name, String host, String port, String database, String username, String password) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.database = database;
            this.username = username;
            this.password = password;
        }
    }

    public static boolean initialize() {
        // Configuración de la base de datos principal (ranked)
        DatabaseConfig rankedConfig = new DatabaseConfig(
            "ranked",
            "db-dtx-03.sparkedhost.us",
            "3306",
            "s181642_ranked",
            "u181642_EmzxRextoT",
            "O=VjTvdt30P=tA3=QRhBMOks"
        );

        // Configuración de la base de datos de logs de matches
        DatabaseConfig matchLogsConfig = new DatabaseConfig(
            "match_logs",
            "db-mfl-01.sparkedhost.us",
            "3306",
            "s181642_matches",
            "u181642_kO2S4fweyJ",
            "@JQbP+M@A1!ingF9cola4OT1"
        );

        // Inicializar ambas bases de datos
        boolean success = initializeDatabase(rankedConfig);
        success &= initializeDatabase(matchLogsConfig);

        // OPTIMIZACIÓN: Inicializar cache de PlayerData
        PlayerDataCache.initialize();

        return success;
    }

    private static boolean initializeDatabase(DatabaseConfig config) {
        try {
            HikariConfig hikariConfig = new HikariConfig();

            // URL de conexión con configuración optimizada
            String url = "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.database +
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

            hikariConfig.setJdbcUrl(url);
            hikariConfig.setUsername(config.username);
            hikariConfig.setPassword(config.password);
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // Configuración del pool
            hikariConfig.setMaximumPoolSize(MAX_POOL_SIZE);
            hikariConfig.setMinimumIdle(MIN_IDLE);
            hikariConfig.setConnectionTimeout(CONNECTION_TIMEOUT);
            hikariConfig.setIdleTimeout(IDLE_TIMEOUT);
            hikariConfig.setMaxLifetime(MAX_LIFETIME);
            hikariConfig.setLeakDetectionThreshold(60000); // 1 minuto

            // Nombre único del pool
            hikariConfig.setPoolName("RankedMC-" + config.name);

            // Configuración de conexión
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
            hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
            hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
            hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
            hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
            hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
            hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

            // Test de conexión
            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setValidationTimeout(5000);

            HikariDataSource dataSource = new HikariDataSource(hikariConfig);

            // Verificar conexión inicial y crear tablas si es necesario
            try (Connection testConn = dataSource.getConnection()) {
                if (config.name.equals("ranked")) {
                    createRankedTables(testConn);
                } else if (config.name.equals("match_logs")) {
                    createMatchLogsTables(testConn);
                }

                dataSources.put(config.name, dataSource);

                Bukkit.getConsoleSender().sendMessage("§a✅ Pool de conexiones '" + config.name + "' inicializado correctamente!");
                Bukkit.getConsoleSender().sendMessage("§7📊 Pool '" + config.name + "' configurado: " + MAX_POOL_SIZE + " conexiones máximas");
                return true;
            }

        } catch (SQLException e) {
            Bukkit.getConsoleSender().sendMessage("§c❌ Error al inicializar pool de conexiones '" + config.name + "'!");
            e.printStackTrace();
            return false;
        }
    }

    // Método para obtener conexión de una base de datos específica
    private static Connection getConnection(String databaseName) throws SQLException {
        HikariDataSource dataSource = dataSources.get(databaseName);
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pool de conexiones '" + databaseName + "' no inicializado o cerrado");
        }
        return dataSource.getConnection();
    }

    // Método para obtener conexión de la base de datos principal (retrocompatibilidad)
    private static Connection getConnection() throws SQLException {
        return getConnection("ranked");
    }

    private static void createRankedTables(Connection conn) throws SQLException {
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

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createPlayersTable);
        }
    }

    private static void createMatchLogsTables(Connection conn) throws SQLException {
        String createMatchesTable = """
        CREATE TABLE IF NOT EXISTS matches (
            id INT AUTO_INCREMENT PRIMARY KEY,
            match_id VARCHAR(50) NOT NULL UNIQUE,
            match_type VARCHAR(20) NOT NULL,
            map_name VARCHAR(50) NOT NULL,
            winner_team VARCHAR(10) NOT NULL,
            duration_seconds BIGINT NOT NULL,
            start_time TIMESTAMP NOT NULL,
            end_time TIMESTAMP NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_match_id (match_id),
            INDEX idx_match_type (match_type),
            INDEX idx_map_name (map_name),
            INDEX idx_start_time (start_time)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        String createMatchPlayersTable = """
        CREATE TABLE IF NOT EXISTS match_players (
            id INT AUTO_INCREMENT PRIMARY KEY,
            match_id VARCHAR(50) NOT NULL,
            player_uuid VARCHAR(36) NOT NULL,
            player_name VARCHAR(16) NOT NULL,
            team VARCHAR(10) NOT NULL,
            kills INT DEFAULT 0,
            deaths INT DEFAULT 0,
            damage_dealt DOUBLE DEFAULT 0,
            damage_received DOUBLE DEFAULT 0,
            arrows_shot INT DEFAULT 0,
            arrows_hit INT DEFAULT 0,
            arrow_accuracy DOUBLE DEFAULT 0,
            old_elo INT NOT NULL,
            new_elo INT NOT NULL,
            elo_change INT NOT NULL,
            old_mmr DOUBLE NOT NULL,
            new_mmr DOUBLE NOT NULL,
            mmr_change DOUBLE NOT NULL,
            won TINYINT(1) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_match_id (match_id),
            INDEX idx_player_uuid (player_uuid),
            INDEX idx_team (team),
            FOREIGN KEY (match_id) REFERENCES matches(match_id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        String createMatchEventsTable = """
        CREATE TABLE IF NOT EXISTS match_events (
            id INT AUTO_INCREMENT PRIMARY KEY,
            match_id VARCHAR(50) NOT NULL,
            event_type VARCHAR(30) NOT NULL,
            player_uuid VARCHAR(36),
            event_data TEXT,
            event_timestamp TIMESTAMP NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_match_id (match_id),
            INDEX idx_event_type (event_type),
            INDEX idx_player_uuid (player_uuid),
            INDEX idx_event_timestamp (event_timestamp),
            FOREIGN KEY (match_id) REFERENCES matches(match_id) ON DELETE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createMatchesTable);
            stmt.executeUpdate(createMatchPlayersTable);
            stmt.executeUpdate(createMatchEventsTable);
        }
    }

    // Métodos públicos para trabajar con bases de datos específicas
    public static Connection getConnectionTo(String databaseName) throws SQLException {
        return getConnection(databaseName);
    }



    /**
     * OPTIMIZACIÓN: Obtener jugador por Discord ID con cache
     */
    public static PlayerData getPlayerByDiscordId(String discordId) {
        // Verificar cache primero
        PlayerData cached = PlayerDataCache.getPlayerByDiscordId(discordId);
        if (cached != null) {
            return cached;
        }

        // Si no está en cache, consultar DB
        return getPlayerByDiscordIdFromDB(discordId);
    }

    /**
     * OPTIMIZACIÓN: Versión asíncrona para obtener jugador por Discord ID
     */
    public static CompletableFuture<PlayerData> getPlayerByDiscordIdAsync(String discordId) {
        // Verificar cache primero
        PlayerData cached = PlayerDataCache.getPlayerByDiscordId(discordId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        // Consultar DB de forma asíncrona
        return CompletableFuture.supplyAsync(() -> getPlayerByDiscordIdFromDB(discordId));
    }

    /**
     * Consulta síncrona a la base de datos (solo cuando cache falla)
     */
    private static PlayerData getPlayerByDiscordIdFromDB(String discordId) {
        String query = "SELECT * FROM ranked_players WHERE discord_id = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, discordId);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        PlayerData player = new PlayerData(
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

                        // OPTIMIZACIÓN: Cache el resultado
                        PlayerDataCache.cachePlayer(player);
                        return player;
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

    /**
     * OPTIMIZACIÓN: Obtener jugador por UUID con cache
     */
    public static PlayerData getPlayerByMinecraftUuid(String minecraftUuid) {
        // Verificar cache primero
        PlayerData cached = PlayerDataCache.getPlayerByUuid(minecraftUuid);
        if (cached != null) {
            return cached;
        }

        // Si no está en cache, consultar DB
        return getPlayerByMinecraftUuidFromDB(minecraftUuid);
    }

    /**
     * OPTIMIZACIÓN: Versión asíncrona para obtener jugador por UUID
     */
    public static CompletableFuture<PlayerData> getPlayerByMinecraftUuidAsync(String minecraftUuid) {
        // Verificar cache primero
        PlayerData cached = PlayerDataCache.getPlayerByUuid(minecraftUuid);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        // Consultar DB de forma asíncrona
        return CompletableFuture.supplyAsync(() -> getPlayerByMinecraftUuidFromDB(minecraftUuid));
    }

    /**
     * Consulta síncrona a la base de datos por UUID (solo cuando cache falla)
     */
    private static PlayerData getPlayerByMinecraftUuidFromDB(String minecraftUuid) {
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



    public static void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                System.out.println("🔌 Pool de conexiones '" + dataSource.getPoolName() + "' cerrado correctamente");
            }
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
