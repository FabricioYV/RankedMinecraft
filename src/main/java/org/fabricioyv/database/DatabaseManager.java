package org.fabricioyv.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.model.PlayerData;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {

    // Mapa para almacenar múltiples datasources
    private static final Map<String, HikariDataSource> dataSources = new HashMap<>();

    // Configuración de pools y timeouts OPTIMIZADAS para PvP y Discord
    private static final int MAX_POOL_SIZE = 15;
    private static final int MIN_IDLE = 6;
    private static final long CONNECTION_TIMEOUT = 8000;
    private static final long IDLE_TIMEOUT = 300000;
    private static final long MAX_LIFETIME = 1800000;
    private static final long VALIDATION_TIMEOUT = 3000;

    // =====================================================
    // Configuración de DB
    // =====================================================
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

    /**
     * ✅ Inicializa pools leyendo config.yml (para no hardcodear credenciales)
     *
     * Estructura esperada en config.yml:
     *
     * databases:
     *   ranked:
     *     host: "..."
     *     port: "3306"
     *     database: "..."
     *     username: "..."
     *     password: "..."
     *   match_logs:
     *     host: "..."
     *     port: "3306"
     *     database: "..."
     *     username: "..."
     *     password: "..."
     */
    public static boolean initialize() {
        RankedMinecraft plugin = RankedMinecraft.getInstance();

        DatabaseConfig rankedConfig = new DatabaseConfig(
                "ranked",
                plugin.getConfig().getString("databases.ranked.host", "127.0.0.1"),
                plugin.getConfig().getString("databases.ranked.port", "3306"),
                plugin.getConfig().getString("databases.ranked.database", "ranked_db"),
                plugin.getConfig().getString("databases.ranked.username", "user"),
                plugin.getConfig().getString("databases.ranked.password", "pass")
        );

        DatabaseConfig matchLogsConfig = new DatabaseConfig(
                "match_logs",
                plugin.getConfig().getString("databases.match_logs.host", "127.0.0.1"),
                plugin.getConfig().getString("databases.match_logs.port", "3306"),
                plugin.getConfig().getString("databases.match_logs.database", "match_logs_db"),
                plugin.getConfig().getString("databases.match_logs.username", "user"),
                plugin.getConfig().getString("databases.match_logs.password", "pass")
        );

        boolean success = initializeDatabase(rankedConfig);
        success &= initializeDatabase(matchLogsConfig);

        PlayerDataCache.initialize();

        // Si tienes migraciones externas, mantenlas aquí
        try {
            if (!PlacementMigration.isMigrationApplied()) {
                Bukkit.getConsoleSender().sendMessage("§e⚡ Ejecutando migración de placement matches...");
                PlacementMigration.executePlacementMigration();
                Bukkit.getConsoleSender().sendMessage("§a✅ Sistema de placement matches inicializado");
            } else {
                Bukkit.getConsoleSender().sendMessage("§a✅ Sistema de placement matches ya está configurado");
            }
        } catch (Throwable t) {
            Bukkit.getConsoleSender().sendMessage("§c⚠️ Error en migración placement: " + t.getMessage());
        }

        return success;
    }

    private static boolean initializeDatabase(DatabaseConfig config) {
        try {
            HikariConfig hikariConfig = new HikariConfig();

            String url = "jdbc:mysql://" + config.host + ":" + config.port + "/" + config.database +
                    "?useSSL=false" +
                    "&autoReconnect=true" +
                    "&characterEncoding=utf8" +
                    "&allowPublicKeyRetrieval=true" +
                    "&serverTimezone=America/Lima" +
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

            hikariConfig.setMaximumPoolSize(MAX_POOL_SIZE);
            hikariConfig.setMinimumIdle(MIN_IDLE);
            hikariConfig.setConnectionTimeout(CONNECTION_TIMEOUT);
            hikariConfig.setIdleTimeout(IDLE_TIMEOUT);
            hikariConfig.setMaxLifetime(MAX_LIFETIME);
            hikariConfig.setLeakDetectionThreshold(60000);

            hikariConfig.setPoolName("RankedMC-" + config.name);

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

            hikariConfig.setConnectionTestQuery("SELECT 1");
            hikariConfig.setValidationTimeout(VALIDATION_TIMEOUT);

            HikariDataSource dataSource = new HikariDataSource(hikariConfig);

            try (Connection testConn = dataSource.getConnection()) {
                if (config.name.equals("ranked")) {
                    createRankedTables(testConn);
                } else if (config.name.equals("match_logs")) {
                    createMatchLogsTables(testConn);
                }

                dataSources.put(config.name, dataSource);

                Bukkit.getConsoleSender().sendMessage("§a✅ Pool '" + config.name + "' inicializado!");
                Bukkit.getConsoleSender().sendMessage("§7📊 Pool '" + config.name + "': max=" + MAX_POOL_SIZE + ", idle=" + MIN_IDLE);
                return true;
            }

        } catch (SQLException e) {
            Bukkit.getConsoleSender().sendMessage("§c❌ Error inicializando pool '" + config.name + "': " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // =====================================================
    // Conexiones
    // =====================================================
    public static Connection getConnection(String databaseName) throws SQLException {
        HikariDataSource dataSource = dataSources.get(databaseName);
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pool '" + databaseName + "' no inicializado o cerrado");
        }
        return dataSource.getConnection();
    }

    public static Connection getConnectionTo(String databaseName) throws SQLException {
        return getConnection(databaseName);
    }

    private static Connection getConnection() throws SQLException {
        return getConnection("ranked");
    }

    // =====================================================
    // Creación de tablas (para DB nueva)
    // NOTA: IF NOT EXISTS no altera tablas viejas.
    // =====================================================
    private static void createRankedTables(Connection conn) throws SQLException {

        // ✅ Ajusté la tabla para incluir columnas que tu código usa.
        // Si tu DB actual no las tiene, no se agregan (IF NOT EXISTS no hace ALTER).
        // Para DB nueva, quedará completa.
        String createPlayersTable = """
        CREATE TABLE IF NOT EXISTS ranked_players (
            id INT AUTO_INCREMENT PRIMARY KEY,
            minecraft_uuid VARCHAR(36) NOT NULL UNIQUE,
            minecraft_username VARCHAR(16) NOT NULL,
            discord_id VARCHAR(20),
            is_verified TINYINT(1) DEFAULT 0,
            elo INT DEFAULT 500,
            mmr DOUBLE DEFAULT 950.0,
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

            -- Placement
            is_in_placement TINYINT(1) DEFAULT 1,
            placement_matches_played INT DEFAULT 0,

            -- Cooldown / bans (tu código los usa)
            cooldown_end_time BIGINT DEFAULT 0,
            is_permanently_banned TINYINT(1) DEFAULT 0,

            -- (Opcional) Para estadísticas
            last_match_date TIMESTAMP NULL DEFAULT NULL,

            INDEX idx_discord_id (discord_id),
            INDEX idx_elo (elo),
            INDEX idx_mmr (mmr),
            INDEX idx_in_match (is_in_match),
            INDEX idx_minecraft_uuid (minecraft_uuid),
            INDEX idx_in_placement (is_in_placement),
            INDEX idx_placement_matches (placement_matches_played),
            INDEX idx_cooldown (cooldown_end_time)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
        """;

        // TABLA: Registro de abandonos
        // ✅ SIN UNIQUE por ahora (tu pedido).
        // ⚠️ Con UNIQUE sería lo ideal, te lo dejo comentado abajo.
        String createAbandonmentsTable = """
        CREATE TABLE IF NOT EXISTS player_abandonments (
            id INT AUTO_INCREMENT PRIMARY KEY,
            player_uuid VARCHAR(36) NOT NULL,
            match_id VARCHAR(50) NOT NULL,
            elo_penalty INT NOT NULL,
            cooldown_minutes INT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_player_uuid (player_uuid),
            INDEX idx_match_id (match_id),
            INDEX idx_created_at (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
        """;

        // ✅ Para cuando recuperes control y quieras hacerlo perfecto:
        // ALTER TABLE player_abandonments ADD UNIQUE KEY uq_abandonment_player_match (player_uuid, match_id);

        // TABLA: Protecciones contra pérdida de ELO
        // ✅ SIN depender de UNIQUE por ahora.
        // (En tu snippet anterior ya tenías UNIQUE, pero pediste no depender de ello.)
        String createLossProtectionsTable = """
        CREATE TABLE IF NOT EXISTS match_loss_protections (
            id INT AUTO_INCREMENT PRIMARY KEY,
            player_uuid VARCHAR(36) NOT NULL,
            match_id VARCHAR(50) NOT NULL,
            protection_reason VARCHAR(100) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_player_uuid (player_uuid),
            INDEX idx_match_id (match_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
        """;

        // ✅ Para cuando quieras hacerlo perfecto:
        // ALTER TABLE match_loss_protections ADD UNIQUE KEY uq_protection_player_match (player_uuid, match_id);

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createPlayersTable);
            stmt.executeUpdate(createAbandonmentsTable);
            stmt.executeUpdate(createLossProtectionsTable);
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

    // =====================================================
    // PlayerData getters con cache
    // =====================================================
    public static PlayerData getPlayerByDiscordId(String discordId) {
        PlayerData cached = PlayerDataCache.getPlayerByDiscordId(discordId);
        if (cached != null) return cached;
        return getPlayerByDiscordIdFromDB(discordId);
    }

    public static CompletableFuture<PlayerData> getPlayerByDiscordIdAsync(String discordId) {
        PlayerData cached = PlayerDataCache.getPlayerByDiscordId(discordId);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> getPlayerByDiscordIdFromDB(discordId));
    }

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

                        boolean isInPlacement = safeGetBoolean(rs, "is_in_placement", true);
                        int placementMatchesPlayed = safeGetInt(rs, "placement_matches_played", 0);
                        player.setPlacementData(isInPlacement, placementMatchesPlayed);

                        PlayerDataCache.cachePlayer(player);
                        return player;
                    }
                }

            } catch (SQLException e) {
                retryLog(attempt, 3, "getPlayerByDiscordId", e);
            }
        }
        return null;
    }

    public static PlayerData getPlayerByMinecraftUuid(String minecraftUuid) {
        PlayerData cached = PlayerDataCache.getPlayerByUuid(minecraftUuid);
        if (cached != null) return cached;
        return getPlayerByMinecraftUuidFromDB(minecraftUuid);
    }

    public static CompletableFuture<PlayerData> getPlayerByMinecraftUuidAsync(String minecraftUuid) {
        PlayerData cached = PlayerDataCache.getPlayerByUuid(minecraftUuid);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        return CompletableFuture.supplyAsync(() -> getPlayerByMinecraftUuidFromDB(minecraftUuid));
    }

    private static PlayerData getPlayerByMinecraftUuidFromDB(String minecraftUuid) {
        String query = "SELECT * FROM ranked_players WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setString(1, minecraftUuid);

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

                        boolean isInPlacement = safeGetBoolean(rs, "is_in_placement", true);
                        int placementMatchesPlayed = safeGetInt(rs, "placement_matches_played", 0);
                        player.setPlacementData(isInPlacement, placementMatchesPlayed);

                        PlayerDataCache.cachePlayer(player);
                        return player;
                    }
                }

            } catch (SQLException e) {
                retryLog(attempt, 3, "getPlayerByMinecraftUuid", e);
            }
        }
        return null;
    }

    public static PlayerData getPlayerData(String playerUuid) {
        return getPlayerByMinecraftUuid(playerUuid);
    }

    // =====================================================
    // Updates de stats (con fallback por columnas faltantes)
    // =====================================================
    public static void updatePlayerStats(List<PlayerStatUpdate> updates) {

        // Intento 1: con last_match_date (si existe)
        String queryWithLastDate = "UPDATE ranked_players SET " +
                "elo = ?, " +
                "mmr = ?, " +
                "wins = wins + ?, " +
                "losses = losses + ?, " +
                "games_played = games_played + 1, " +
                "total_kills = total_kills + ?, " +
                "total_deaths = total_deaths + ?, " +
                "is_in_match = ?, " +
                "current_match_id = ?, " +
                "last_match_date = NOW() " +
                "WHERE minecraft_uuid = ?";

        // Fallback: sin last_match_date (si tu tabla no tiene esa columna)
        String queryNoLastDate = "UPDATE ranked_players SET " +
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
            try (Connection conn = getConnection("ranked")) {
                conn.setAutoCommit(false);

                try {
                    batchUpdateStats(conn, queryWithLastDate, updates, true);
                    conn.commit();
                    System.out.println("✅ Batch update exitoso (con last_match_date): " + updates.size());
                    return;
                } catch (SQLException e) {
                    // Si es unknown column last_match_date, fallback
                    if (isUnknownColumn(e)) {
                        conn.rollback();
                        try {
                            batchUpdateStats(conn, queryNoLastDate, updates, false);
                            conn.commit();
                            System.out.println("✅ Batch update exitoso (sin last_match_date): " + updates.size());
                            return;
                        } catch (SQLException ex2) {
                            conn.rollback();
                            throw ex2;
                        }
                    } else {
                        conn.rollback();
                        throw e;
                    }
                }

            } catch (SQLException e) {
                retryLog(attempt, 3, "updatePlayerStats", e);
            }
        }
    }

    private static void batchUpdateStats(Connection conn, String query, List<PlayerStatUpdate> updates, boolean hasLastMatchDate)
            throws SQLException {
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

                if (hasLastMatchDate) {
                    // last_match_date está hardcodeado en la query, así que uuid va en param 9
                    stmt.setString(9, update.minecraftUuid);
                } else {
                    // sin last_match_date, uuid va en param 9 igualmente (porque solo cambiamos 1 parte de la query)
                    stmt.setString(9, update.minecraftUuid);
                }
                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        // Invalidar cache
        for (PlayerStatUpdate update : updates) {
            PlayerDataCache.invalidatePlayer(update.minecraftUuid, null);
        }
    }

    public static void updatePlayerStatsWithPlacement(List<PlayerStatUpdateWithPlacement> updates) {

        // Intento 1: con last_match_date (si existe)
        String queryWithLastDate = "UPDATE ranked_players SET " +
                "elo = ?, " +
                "mmr = ?, " +
                "wins = wins + ?, " +
                "losses = losses + ?, " +
                "games_played = games_played + 1, " +
                "total_kills = total_kills + ?, " +
                "total_deaths = total_deaths + ?, " +
                "is_in_match = ?, " +
                "current_match_id = ?, " +
                "is_in_placement = ?, " +
                "placement_matches_played = ?, " +
                "last_match_date = NOW() " +
                "WHERE minecraft_uuid = ?";

        // Fallback: sin last_match_date
        String queryNoLastDate = "UPDATE ranked_players SET " +
                "elo = ?, " +
                "mmr = ?, " +
                "wins = wins + ?, " +
                "losses = losses + ?, " +
                "games_played = games_played + 1, " +
                "total_kills = total_kills + ?, " +
                "total_deaths = total_deaths + ?, " +
                "is_in_match = ?, " +
                "current_match_id = ?, " +
                "is_in_placement = ?, " +
                "placement_matches_played = ? " +
                "WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection("ranked")) {
                conn.setAutoCommit(false);

                try {
                    batchUpdateStatsWithPlacement(conn, queryWithLastDate, updates, true);
                    conn.commit();
                    System.out.println("✅ Batch placement update (con last_match_date): " + updates.size());
                    return;
                } catch (SQLException e) {
                    if (isUnknownColumn(e)) {
                        conn.rollback();
                        try {
                            batchUpdateStatsWithPlacement(conn, queryNoLastDate, updates, false);
                            conn.commit();
                            System.out.println("✅ Batch placement update (sin last_match_date): " + updates.size());
                            return;
                        } catch (SQLException ex2) {
                            conn.rollback();
                            throw ex2;
                        }
                    } else {
                        conn.rollback();
                        throw e;
                    }
                }

            } catch (SQLException e) {
                retryLog(attempt, 3, "updatePlayerStatsWithPlacement", e);
            }
        }
    }

    private static void batchUpdateStatsWithPlacement(Connection conn, String query,
                                                      List<PlayerStatUpdateWithPlacement> updates,
                                                      boolean hasLastMatchDate) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            for (PlayerStatUpdateWithPlacement update : updates) {
                stmt.setInt(1, update.newElo);
                stmt.setDouble(2, update.newMMR);
                stmt.setInt(3, update.won ? 1 : 0);
                stmt.setInt(4, update.won ? 0 : 1);
                stmt.setInt(5, update.matchKills);
                stmt.setInt(6, update.matchDeaths);
                stmt.setBoolean(7, false);
                stmt.setString(8, null);
                stmt.setBoolean(9, update.isInPlacement);
                stmt.setInt(10, update.placementMatchesPlayed);

                // last_match_date está en la query (NOW()), uuid es el último param
                if (hasLastMatchDate) {
                    stmt.setString(11, update.minecraftUuid);
                } else {
                    stmt.setString(11, update.minecraftUuid);
                }

                stmt.addBatch();
            }
            stmt.executeBatch();
        }

        for (PlayerStatUpdateWithPlacement update : updates) {
            PlayerDataCache.invalidatePlayer(update.minecraftUuid, null);
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
                PlayerDataCache.invalidatePlayer(minecraftUuid, null);
                return;

            } catch (SQLException e) {
                retryLog(attempt, 3, "updatePlayerMatchStatus", e);
            }
        }
    }

    public static void updatePlayerPlacementData(String minecraftUuid, boolean isInPlacement, int placementMatchesPlayed) {
        String query = "UPDATE ranked_players SET is_in_placement = ?, placement_matches_played = ? WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setBoolean(1, isInPlacement);
                stmt.setInt(2, placementMatchesPlayed);
                stmt.setString(3, minecraftUuid);

                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    PlayerDataCache.invalidatePlayer(minecraftUuid, null);
                }
                return;

            } catch (SQLException e) {
                retryLog(attempt, 3, "updatePlayerPlacementData", e);
            }
        }
    }

    // =====================================================
    // Sistema de abandono / protecciones
    // =====================================================

    public static int getPlayerAbandonmentCount(String playerUuid) {
        String query = """
            SELECT COUNT(*) as abandonment_count
            FROM player_abandonments
            WHERE player_uuid = ?
            AND created_at > DATE_SUB(NOW(), INTERVAL 30 DAY)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt("abandonment_count");
            }
        } catch (SQLException e) {
            System.err.println("❌ Error getPlayerAbandonmentCount: " + e.getMessage());
        }
        return 0;
    }

    /**
     * ✅ SIN UNIQUE: idempotente “best effort”
     * - Si ya existe (player_uuid, match_id): UPDATE
     * - Si no existe: INSERT
     *
     * (Con UNIQUE sería perfecto; lo dejo comentado más abajo.)
     */
    public static void recordAbandonment(String playerUuid, String matchId, int eloPenalty, int cooldownMinutes) {
        String select = "SELECT id FROM player_abandonments WHERE player_uuid=? AND match_id=? LIMIT 1";
        String update = "UPDATE player_abandonments SET elo_penalty=?, cooldown_minutes=? WHERE id=?";
        String insert = """
            INSERT INTO player_abandonments (player_uuid, match_id, elo_penalty, cooldown_minutes, created_at)
            VALUES (?, ?, ?, ?, NOW())
        """;

        // ✅ Solución ideal cuando tengas UNIQUE:
        // INSERT ... ON DUPLICATE KEY UPDATE ...

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            Integer existingId = null;
            try (PreparedStatement ps = conn.prepareStatement(select)) {
                ps.setString(1, playerUuid);
                ps.setString(2, matchId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) existingId = rs.getInt("id");
                }
            }

            if (existingId != null) {
                try (PreparedStatement ps = conn.prepareStatement(update)) {
                    ps.setInt(1, eloPenalty);
                    ps.setInt(2, cooldownMinutes);
                    ps.setInt(3, existingId);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, matchId);
                    ps.setInt(3, eloPenalty);
                    ps.setInt(4, cooldownMinutes);
                    ps.executeUpdate();
                }
            }

            conn.commit();
            System.out.println("✅ recordAbandonment ok: " + playerUuid + " match " + matchId);

        } catch (SQLException e) {
            System.err.println("❌ Error recordAbandonment: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void markPlayerProtectedFromLoss(String playerUuid, String matchId, String reason) {
        // SIN UNIQUE: UPDATE -> si 0 rows, INSERT
        String update = """
            UPDATE match_loss_protections
            SET protection_reason = ?, created_at = NOW()
            WHERE player_uuid = ? AND match_id = ?
        """;

        String insert = """
            INSERT INTO match_loss_protections (player_uuid, match_id, protection_reason, created_at)
            VALUES (?, ?, ?, NOW())
        """;

        // ✅ Solución ideal cuando tengas UNIQUE:
        // INSERT ... ON DUPLICATE KEY UPDATE ...

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            int updated;
            try (PreparedStatement ps = conn.prepareStatement(update)) {
                ps.setString(1, reason);
                ps.setString(2, playerUuid);
                ps.setString(3, matchId);
                updated = ps.executeUpdate();
            }

            if (updated == 0) {
                try (PreparedStatement ps = conn.prepareStatement(insert)) {
                    ps.setString(1, playerUuid);
                    ps.setString(2, matchId);
                    ps.setString(3, reason);
                    ps.executeUpdate();
                }
            }

            conn.commit();
            System.out.println("✅ markPlayerProtectedFromLoss ok: " + playerUuid + " match " + matchId + " reason=" + reason);

        } catch (SQLException e) {
            System.err.println("❌ Error markPlayerProtectedFromLoss: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static boolean isPlayerProtectedFromLoss(String playerUuid, String matchId) {
        String query = "SELECT 1 FROM match_loss_protections WHERE player_uuid=? AND match_id=? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("❌ Error isPlayerProtectedFromLoss: " + e.getMessage());
        }
        return false;
    }

    /**
     * ✅ Importante: “abandonment processed” NO debería depender de match_loss_protections.
     * Como NO tenemos UNIQUE, lo más robusto es:
     * - Si existe en player_abandonments => ya fue procesado.
     * - Si no existe, fallback a match_loss_protections(reason=abandonment_processed) por compatibilidad.
     */
    public static boolean isPlayerAbandonmentProcessed(String playerUuid, String matchId) {
        // 1) Fuente de verdad: player_abandonments
        String q1 = "SELECT 1 FROM player_abandonments WHERE player_uuid=? AND match_id=? LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(q1)) {
            ps.setString(1, playerUuid);
            ps.setString(2, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
            }
        } catch (SQLException ignored) {}

        // 2) Fallback compat (por si tu código antiguo marcaba en protections)
        String q2 = "SELECT 1 FROM match_loss_protections WHERE player_uuid=? AND match_id=? AND protection_reason='abandonment_processed' LIMIT 1";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(q2)) {
            ps.setString(1, playerUuid);
            ps.setString(2, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("❌ Error isPlayerAbandonmentProcessed: " + e.getMessage());
        }
        return false;
    }

    /**
     * ✅ Mantengo este método por compatibilidad, pero SIN UNIQUE.
     * (Realmente ya no es necesario si usas recordAbandonment como verdad.)
     */
    public static void markPlayerAsAbandonmentProcessed(String playerUuid, String matchId) {
        markPlayerProtectedFromLoss(playerUuid, matchId, "abandonment_processed");
    }

    public static class MatchProtectionSnapshot {
        public final Set<String> abandonmentProcessed = new HashSet<>();
        public final Set<String> lossProtected = new HashSet<>();
    }

    /**
     * ✅ Snapshot robusto (sin UNIQUE):
     * - abandonmentProcessed: viene de player_abandonments (verdad)
     * - lossProtected: viene de match_loss_protections (excluye abandonment_processed)
     */
    public static MatchProtectionSnapshot getMatchProtectionSnapshot(String matchId) {
        MatchProtectionSnapshot snap = new MatchProtectionSnapshot();

        String qAb = "SELECT DISTINCT player_uuid FROM player_abandonments WHERE match_id = ?";
        String qProt = """
            SELECT player_uuid, protection_reason
            FROM match_loss_protections
            WHERE match_id = ?
        """;

        try (Connection conn = getConnection()) {

            try (PreparedStatement ps = conn.prepareStatement(qAb)) {
                ps.setString(1, matchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        snap.abandonmentProcessed.add(rs.getString(1));
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(qProt)) {
                ps.setString(1, matchId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String uuid = rs.getString("player_uuid");
                        String reason = rs.getString("protection_reason");
                        if ("abandonment_processed".equalsIgnoreCase(reason)) {
                            // lo dejo también aquí por compatibilidad
                            snap.abandonmentProcessed.add(uuid);
                        } else {
                            snap.lossProtected.add(uuid);
                        }
                    }
                }
            }

        } catch (SQLException e) {
            System.err.println("❌ Error getMatchProtectionSnapshot: " + e.getMessage());
            e.printStackTrace();
        }

        return snap;
    }

    /**
     * ✅ Limpieza (recomendado)
     */
    public static void clearMatchProtections(String matchId) {
        String q = "DELETE FROM match_loss_protections WHERE match_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(q)) {
            ps.setString(1, matchId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("❌ Error clearMatchProtections: " + e.getMessage());
        }
    }

    // =====================================================
    // Elo/cooldown/bans
    // =====================================================
    public static void updatePlayerElo(String playerUuid, int newElo) {
        String query = "UPDATE ranked_players SET elo = ? WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setInt(1, newElo);
            stmt.setString(2, playerUuid);
            stmt.executeUpdate();
            PlayerDataCache.invalidatePlayer(playerUuid, null);
        } catch (SQLException e) {
            System.err.println("❌ Error updatePlayerElo: " + e.getMessage());
        }
    }

    public static void setPlayerCooldown(String playerUuid, long cooldownEndTime) {
        String query = "UPDATE ranked_players SET cooldown_end_time = ? WHERE minecraft_uuid = ?";
        String fallback = "UPDATE ranked_players SET minecraft_uuid = minecraft_uuid WHERE minecraft_uuid = ?"; // no-op

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setLong(1, cooldownEndTime);
            stmt.setString(2, playerUuid);
            stmt.executeUpdate();

        } catch (SQLException e) {
            if (isUnknownColumn(e)) {
                // Si tu tabla actual no tiene cooldown_end_time, no rompemos todo.
                try (Connection conn = getConnection();
                     PreparedStatement stmt2 = conn.prepareStatement(fallback)) {
                    stmt2.setString(1, playerUuid);
                    stmt2.executeUpdate();
                } catch (SQLException ignored) {}
            } else {
                System.err.println("❌ Error setPlayerCooldown: " + e.getMessage());
            }
        }
    }

    public static boolean isPlayerInCooldown(String playerUuid) {
        String query = "SELECT cooldown_end_time FROM ranked_players WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long t = safeGetLong(rs, "cooldown_end_time", 0L);
                    return t > System.currentTimeMillis();
                }
            }
        } catch (SQLException e) {
            // si no existe columna, asumimos no cooldown
            if (!isUnknownColumn(e)) {
                System.err.println("❌ Error isPlayerInCooldown: " + e.getMessage());
            }
        }
        return false;
    }

    public static long getCooldownRemainingMinutes(String playerUuid) {
        String query = "SELECT cooldown_end_time FROM ranked_players WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long end = safeGetLong(rs, "cooldown_end_time", 0L);
                    long now = System.currentTimeMillis();
                    if (end > now) return (end - now) / (60 * 1000);
                }
            }
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) {
                System.err.println("❌ Error getCooldownRemainingMinutes: " + e.getMessage());
            }
        }
        return 0;
    }

    public static void setPermanentBan(String playerUuid, boolean banned) {
        String query = "UPDATE ranked_players SET is_permanently_banned = ? WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setBoolean(1, banned);
            stmt.setString(2, playerUuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) {
                System.err.println("❌ Error setPermanentBan: " + e.getMessage());
            }
        }
    }

    public static boolean isPlayerPermanentlyBanned(String playerUuid) {
        String query = "SELECT is_permanently_banned FROM ranked_players WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, playerUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return safeGetBoolean(rs, "is_permanently_banned", false);
            }
        } catch (SQLException e) {
            if (!isUnknownColumn(e)) {
                System.err.println("❌ Error isPlayerPermanentlyBanned: " + e.getMessage());
            }
        }
        return false;
    }

    public static void addDoubleLossesToPlayer(String playerUuid) {
        String query = "UPDATE ranked_players SET losses = losses + 2, games_played = games_played + 2 WHERE minecraft_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setString(1, playerUuid);
            int rows = stmt.executeUpdate();
            if (rows > 0) PlayerDataCache.invalidatePlayer(playerUuid, null);
        } catch (SQLException e) {
            System.err.println("❌ Error addDoubleLossesToPlayer: " + e.getMessage());
        }
    }

    public static void setPlayerEloAndMmr(String minecraftUuid, int elo, double mmr) {
        String query = "UPDATE ranked_players SET elo = ?, mmr = ? WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setInt(1, elo);
                stmt.setDouble(2, mmr);
                stmt.setString(3, minecraftUuid);

                stmt.executeUpdate();
                PlayerDataCache.invalidatePlayer(minecraftUuid, null);
                return;

            } catch (SQLException e) {
                retryLog(attempt, 3, "setPlayerEloAndMmr", e);
            }
        }
    }

    // =====================================================
    // Placement stats (lo dejo igual, pero con try/catch)
    // =====================================================
    public static PlacementStats getPlacementStats() {
        String query = """
            SELECT
                COUNT(*) as total_players,
                COUNT(CASE WHEN is_in_placement = 1 THEN 1 END) as placement_players,
                AVG(CASE WHEN is_in_placement = 1 THEN placement_matches_played END) as avg_placement_progress,
                AVG(CASE WHEN is_in_placement = 0 THEN mmr END) as avg_established_mmr
            FROM ranked_players
            WHERE games_played > 0
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return new PlacementStats(
                        rs.getInt("total_players"),
                        rs.getInt("placement_players"),
                        rs.getDouble("avg_placement_progress"),
                        rs.getDouble("avg_established_mmr")
                );
            }

        } catch (SQLException e) {
            System.err.println("❌ Error getPlacementStats: " + e.getMessage());
        }

        return new PlacementStats(0, 0, 0.0, 1000.0);
    }

    // =====================================================
    // Async wrappers (mantengo tu estructura)
    // =====================================================
    public static void updatePlayerStatsAsync(PlayerStatUpdate update) {
        boolean enqueued = BatchProcessor.enqueuePlayerStatsUpdate(update);
        if (!enqueued) {
            Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
                updatePlayerStats(List.of(update));
            });
        }
    }

    public static void updatePlayerMatchStatusAsync(String minecraftUuid, boolean isInMatch, String currentMatchId) {
        boolean enqueued = BatchProcessor.enqueueMatchStatusUpdate(minecraftUuid, isInMatch, currentMatchId);
        if (!enqueued) {
            Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
                updatePlayerMatchStatus(minecraftUuid, isInMatch, currentMatchId);
            });
        }
    }

    // =====================================================
    // Placement history (igual)
    // =====================================================
    public static List<PlacementMatchData> getPlayerPlacementMatches(String playerUuid) {
        List<PlacementMatchData> placementHistory = new ArrayList<>();

        String query = """
            SELECT won, kills, deaths, damage, created_at
            FROM placement_match_history
            WHERE player_uuid = ?
            ORDER BY created_at ASC
            LIMIT 8
        """;

        try (Connection conn = getConnection("ranked");
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    boolean won = rs.getBoolean("won");
                    int kills = rs.getInt("kills");
                    int deaths = rs.getInt("deaths");
                    double damage = rs.getDouble("damage");
                    placementHistory.add(new PlacementMatchData(won, kills, deaths, damage));
                }
            }

        } catch (SQLException e) {
            Bukkit.getConsoleSender().sendMessage("§c❌ Error getPlayerPlacementMatches: " + e.getMessage());
        }

        return placementHistory;
    }

    // =====================================================
    // Close
    // =====================================================
    public static void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                System.out.println("🔌 Pool cerrado: " + dataSource.getPoolName());
            }
        }
    }

    // =====================================================
    // Data classes
    // =====================================================
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

    public static class PlayerStatUpdateWithPlacement {
        public final String minecraftUuid;
        public final boolean won;
        public final int newElo;
        public final double newMMR;
        public final int matchKills;
        public final int matchDeaths;
        public final boolean isInPlacement;
        public final int placementMatchesPlayed;

        public PlayerStatUpdateWithPlacement(String minecraftUuid, boolean won, int newElo,
                                             double newMMR, int matchKills, int matchDeaths,
                                             boolean isInPlacement, int placementMatchesPlayed) {
            this.minecraftUuid = minecraftUuid;
            this.won = won;
            this.newElo = newElo;
            this.newMMR = newMMR;
            this.matchKills = matchKills;
            this.matchDeaths = matchDeaths;
            this.isInPlacement = isInPlacement;
            this.placementMatchesPlayed = placementMatchesPlayed;
        }
    }

    public static class PlacementStats {
        public final int totalPlayers;
        public final int placementPlayers;
        public final double avgPlacementProgress;
        public final double avgEstablishedMMR;

        public PlacementStats(int totalPlayers, int placementPlayers, double avgPlacementProgress, double avgEstablishedMMR) {
            this.totalPlayers = totalPlayers;
            this.placementPlayers = placementPlayers;
            this.avgPlacementProgress = avgPlacementProgress;
            this.avgEstablishedMMR = avgEstablishedMMR;
        }
    }

    public static class PlacementMatchData {
        public final boolean won;
        public final int kills;
        public final int deaths;
        public final double damage;

        public PlacementMatchData(boolean won, int kills, int deaths, double damage) {
            this.won = won;
            this.kills = kills;
            this.deaths = deaths;
            this.damage = damage;
        }
    }

    // =====================================================
    // Utils
    // =====================================================
    private static void retryLog(int attempt, int max, String op, SQLException e) {
        if (attempt == max) {
            System.err.println("❌ " + op + " falló tras " + max + " intentos: " + e.getMessage());
            e.printStackTrace();
        } else {
            System.err.println("⚠️ " + op + " intento " + attempt + " falló: " + e.getMessage());
            try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    private static boolean isUnknownColumn(SQLException e) {
        String msg = (e.getMessage() == null) ? "" : e.getMessage().toLowerCase();
        return msg.contains("unknown column");
    }

    private static int safeGetInt(ResultSet rs, String col, int def) {
        try {
            int v = rs.getInt(col);
            return rs.wasNull() ? def : v;
        } catch (SQLException ignored) {
            return def;
        }
    }

    private static long safeGetLong(ResultSet rs, String col, long def) {
        try {
            long v = rs.getLong(col);
            return rs.wasNull() ? def : v;
        } catch (SQLException ignored) {
            return def;
        }
    }

    private static boolean safeGetBoolean(ResultSet rs, String col, boolean def) {
        try {
            boolean v = rs.getBoolean(col);
            // si columna no existe, lanzará exception arriba
            return v;
        } catch (SQLException ignored) {
            return def;
        }
    }
}