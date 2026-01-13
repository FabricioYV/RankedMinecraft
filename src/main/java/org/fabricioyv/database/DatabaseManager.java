package org.fabricioyv.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.model.PlayerData;

import java.sql.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class DatabaseManager {
    // Mapa para almacenar múltiples datasources
    private static final Map<String, HikariDataSource> dataSources = new HashMap<>();

    // Configuración de pools y timeouts OPTIMIZADAS para PvP y Discord
    private static final int MAX_POOL_SIZE = 15; // Incrementado para Discord + PvP
    private static final int MIN_IDLE = 6; // Más conexiones idle para evitar timeouts
    private static final long CONNECTION_TIMEOUT = 8000; // Incrementado para conexiones lentas
    private static final long IDLE_TIMEOUT = 300000; // 5 minutos
    private static final long MAX_LIFETIME = 1800000; // 30 minutos
    private static final long VALIDATION_TIMEOUT = 3000; // Timeout para validación de conexión

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
                "X4^pijD5zUWpY5UCrYM=W2e!"
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

        // Ejecutar migración de placement matches si es necesario
        if (!PlacementMigration.isMigrationApplied()) {
            Bukkit.getConsoleSender().sendMessage("§e⚡ Ejecutando migración de placement matches...");
            PlacementMigration.executePlacementMigration();
            Bukkit.getConsoleSender().sendMessage("§a✅ Sistema de placement matches inicializado");
        } else {
            Bukkit.getConsoleSender().sendMessage("§a✅ Sistema de placement matches ya está configurado");
        }

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
                    "&serverTimezone=America/Lima" + // CORREGIDO: Zona horaria de Perú (GMT-5)
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
            hikariConfig.setValidationTimeout(VALIDATION_TIMEOUT);

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

    //Method to get connection from a specific database pool
    public static Connection getConnection(String databaseName) throws SQLException {
        HikariDataSource dataSource = dataSources.get(databaseName);
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("Pool de conexiones '" + databaseName + "' no inicializado o cerrado");
        }
        return dataSource.getConnection();
    }

    //Method to get connection from the ranked database pool
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
            is_in_placement TINYINT(1) DEFAULT 1,
            placement_matches_played INT DEFAULT 0,
            INDEX idx_discord_id (discord_id),
            INDEX idx_elo (elo),
            INDEX idx_mmr (mmr),
            INDEX idx_in_match (is_in_match),
            INDEX idx_minecraft_uuid (minecraft_uuid),
            INDEX idx_in_placement (is_in_placement),
            INDEX idx_placement_matches (placement_matches_played)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
        """;

        // TABLA: Registro de abandonos
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

        // TABLA: Protecciones contra pérdida de ELO
        String createLossProtectionsTable = """
        CREATE TABLE IF NOT EXISTS match_loss_protections (
            id INT AUTO_INCREMENT PRIMARY KEY,
            player_uuid VARCHAR(36) NOT NULL,
            match_id VARCHAR(50) NOT NULL,
            protection_reason VARCHAR(100) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY unique_protection (player_uuid, match_id),
            INDEX idx_player_uuid (player_uuid),
            INDEX idx_match_id (match_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
        """;

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

                        // ✅ Cargar placement DIRECTO desde la BD
                        boolean isInPlacement = rs.getBoolean("is_in_placement");
                        int placementMatchesPlayed = rs.getInt("placement_matches_played");
                        player.setPlacementData(isInPlacement, placementMatchesPlayed);

                        // Cachear resultado
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
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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

                        // ✅ Cargar placement DIRECTO desde la BD
                        boolean isInPlacement = rs.getBoolean("is_in_placement");
                        int placementMatchesPlayed = rs.getInt("placement_matches_played");
                        player.setPlacementData(isInPlacement, placementMatchesPlayed);

                        // Cachear también por UUID
                        PlayerDataCache.cachePlayer(player);
                        return player;
                    }
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error obteniendo jugador por UUID después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido, reintentando...");
                    try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
                "current_match_id = ?, " +
                "last_match_date = NOW() " + // NUEVO: Registrar fecha de última partida
                "WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection("ranked")) {
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
                } else {
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

    /**
     * Actualiza los datos de placement matches de un jugador
     */
    public static void updatePlayerPlacementData(String minecraftUuid, boolean isInPlacement, int placementMatchesPlayed) {
        String query = "UPDATE ranked_players SET is_in_placement = ?, placement_matches_played = ? WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setBoolean(1, isInPlacement);
                stmt.setInt(2, placementMatchesPlayed);
                stmt.setString(3, minecraftUuid);

                int rowsAffected = stmt.executeUpdate();

                if (rowsAffected > 0) {
                    // Invalidar cache para forzar reload con datos actualizados
                    // Necesitamos obtener el discordId primero
                    PlayerData player = getPlayerByMinecraftUuidFromDB(minecraftUuid);
                    if (player != null) {
                        PlayerDataCache.invalidatePlayer(minecraftUuid, player.getDiscordId());
                    }
                    return;
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error actualizando datos de placement después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Intento " + attempt + " fallido actualizando placement, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    /**
     * Actualiza las estadísticas del jugador incluyendo datos de placement
     */
    public static void updatePlayerStatsWithPlacement(List<PlayerStatUpdateWithPlacement> updates) {
        String query = "UPDATE ranked_players SET " +
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
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);

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
                        stmt.setString(11, update.minecraftUuid);
                        stmt.addBatch();
                    }

                    stmt.executeBatch();
                    conn.commit();

                    // Invalidar cache para todos los jugadores actualizados
                    for (PlayerStatUpdateWithPlacement update : updates) {
                        PlayerDataCache.invalidatePlayer(update.minecraftUuid, null);
                    }

                    System.out.println("✅ Batch update con placement exitoso: " + updates.size() + " jugadores actualizados");
                    return;

                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }

            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error en batch update con placement después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    System.err.println("⚠️ Batch update placement intento " + attempt + " fallido, reintentando...");
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    /**
     * Obtiene estadísticas de placement matches del servidor
     */
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
            System.err.println("❌ Error obteniendo estadísticas de placement: " + e.getMessage());
            e.printStackTrace();
        }

        return new PlacementStats(0, 0, 0.0, 1000.0);
    }

    public static void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                System.out.println("🔌 Pool de conexiones '" + dataSource.getPoolName() + "' cerrado correctamente");
            }
        }
    }

    /**
     * ========================================
     * MÉTODOS PARA SISTEMA DE ABANDONO
     * ========================================
     */

    /**
     * Obtiene el número de abandonos de un jugador en los últimos 30 días
     */
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
                if (rs.next()) {
                    return rs.getInt("abandonment_count");
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error obteniendo conteo de abandonos: " + e.getMessage());
            e.printStackTrace();
        }

        return 0;
    }

    /**
     * Registra un abandono en la base de datos
     */
    public static void recordAbandonment(String playerUuid, String matchId, int eloPenalty, int cooldownMinutes) {
        String query = """
            INSERT INTO player_abandonments 
            (player_uuid, match_id, elo_penalty, cooldown_minutes, created_at) 
            VALUES (?, ?, ?, ?, NOW())
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);
            stmt.setInt(3, eloPenalty);
            stmt.setInt(4, cooldownMinutes);

            stmt.executeUpdate();

            System.out.println("✅ Abandono registrado: " + playerUuid + " en partida " + matchId);

        } catch (SQLException e) {
            System.err.println("❌ Error registrando abandono: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Actualiza el ELO de un jugador
     */
    public static void updatePlayerElo(String playerUuid, int newElo) {
        String query = "UPDATE ranked_players SET elo = ? WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setInt(1, newElo);
            stmt.setString(2, playerUuid);

            stmt.executeUpdate();

            // Invalidar cache
            PlayerDataCache.invalidatePlayer(playerUuid, null);

        } catch (SQLException e) {
            System.err.println("❌ Error actualizando ELO: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Establece un cooldown para un jugador
     */
    public static void setPlayerCooldown(String playerUuid, long cooldownEndTime) {
        String query = "UPDATE ranked_players SET cooldown_end_time = ? WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setLong(1, cooldownEndTime);
            stmt.setString(2, playerUuid);

            stmt.executeUpdate();

        } catch (SQLException e) {
            System.err.println("❌ Error estableciendo cooldown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Verifica si un jugador está en cooldown
     */
    public static boolean isPlayerInCooldown(String playerUuid) {
        String query = "SELECT cooldown_end_time FROM ranked_players WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long cooldownEndTime = rs.getLong("cooldown_end_time");
                    return cooldownEndTime > System.currentTimeMillis();
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error verificando cooldown: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Obtiene el tiempo restante de cooldown en minutos
     */
    public static long getCooldownRemainingMinutes(String playerUuid) {
        String query = "SELECT cooldown_end_time FROM ranked_players WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long cooldownEndTime = rs.getLong("cooldown_end_time");
                    long currentTime = System.currentTimeMillis();

                    if (cooldownEndTime > currentTime) {
                        return (cooldownEndTime - currentTime) / (60 * 1000); // Convertir a minutos
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error obteniendo tiempo de cooldown: " + e.getMessage());
            e.printStackTrace();
        }

        return 0;
    }

    /**
     * Marca a un jugador como protegido de pérdida de ELO debido a abandono de compañero
     */
    public static void markPlayerProtectedFromLoss(String playerUuid, String matchId, String reason) {
        String query = """
            INSERT INTO match_loss_protections 
            (player_uuid, match_id, protection_reason, created_at) 
            VALUES (?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE protection_reason = VALUES(protection_reason)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);
            stmt.setString(3, reason);

            stmt.executeUpdate();

            System.out.println("✅ Jugador protegido de pérdida: " + playerUuid + " en partida " + matchId);

        } catch (SQLException e) {
            System.err.println("❌ Error marcando protección: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Verifica si un jugador está protegido de pérdida de ELO en una partida específica
     */
    public static boolean isPlayerProtectedFromLoss(String playerUuid, String matchId) {
        String query = """
            SELECT COUNT(*) as protection_count 
            FROM match_loss_protections 
            WHERE player_uuid = ? AND match_id = ?
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("protection_count") > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error verificando protección: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Obtiene el alias para PlayerData por compatibilidad
     */
    public static PlayerData getPlayerData(String playerUuid) {
        return getPlayerByMinecraftUuid(playerUuid);
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

    /**
     * OPTIMIZACIÓN: Actualizar estadísticas de jugador usando batch processing
     * No bloquea el main thread
     */
    public static void updatePlayerStatsAsync(PlayerStatUpdate update) {
        // Usar BatchProcessor para agrupar operaciones
        boolean enqueued = BatchProcessor.enqueuePlayerStatsUpdate(update);

        if (!enqueued) {
            // Fallback: ejecutar síncronamente si la cola está llena
            Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
                List<PlayerStatUpdate> singleUpdate = List.of(update);
                updatePlayerStats(singleUpdate);
            });
        }
    }

    /**
     * OPTIMIZACIÓN: Actualizar estado de partida usando batch processing
     */
    public static void updatePlayerMatchStatusAsync(String minecraftUuid, boolean isInMatch, String currentMatchId) {
        // Usar BatchProcessor para no bloquear main thread
        boolean enqueued = BatchProcessor.enqueueMatchStatusUpdate(minecraftUuid, isInMatch, currentMatchId);

        if (!enqueued) {
            // Fallback: ejecutar síncronamente
            Bukkit.getScheduler().runTaskAsynchronously(RankedMinecraft.getInstance(), () -> {
                updatePlayerMatchStatus(minecraftUuid, isInMatch, currentMatchId);
            });
        }
    }

    /**
     * NUEVO: Obtiene el historial de placement matches de un jugador para evaluación final
     * Usado cuando un jugador completa sus 8 partidas de placement
     */
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
            Bukkit.getConsoleSender().sendMessage("§c❌ Error obteniendo historial de placement para " + playerUuid + ": " + e.getMessage());
        }

        return placementHistory;
    }

    /**
     * Clase para datos de placement matches
     */
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

    public static void setPlayerEloAndMmr(String minecraftUuid, int elo, double mmr) {
        String query = "UPDATE ranked_players SET elo = ?, mmr = ? WHERE minecraft_uuid = ?";

        for (int attempt = 1; attempt <= 3; attempt++) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(query)) {

                stmt.setInt(1, elo);
                stmt.setDouble(2, mmr);
                stmt.setString(3, minecraftUuid);

                int rows = stmt.executeUpdate();
                if (rows > 0) {
                    // Invalidate cache so next reads reflect updated elo/mmr
                    PlayerData player = getPlayerByMinecraftUuidFromDB(minecraftUuid);
                    if (player != null) {
                        PlayerDataCache.invalidatePlayer(minecraftUuid, player.getDiscordId());
                    }
                }

                return;
            } catch (SQLException e) {
                if (attempt == 3) {
                    System.err.println("❌ Error actualizando elo/mmr después de 3 intentos: " + e.getMessage());
                    e.printStackTrace();
                } else {
                    try { Thread.sleep(1000 * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    /**
     * NUEVO: Establece baneo permanente para un jugador
     */
    public static void setPermanentBan(String playerUuid, boolean banned) {
        String query = "UPDATE ranked_players SET is_permanently_banned = ? WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setBoolean(1, banned);
            stmt.setString(2, playerUuid);

            stmt.executeUpdate();

            System.out.println("✅ Estado de baneo permanente actualizado: " + playerUuid + " = " + banned);

        } catch (SQLException e) {
            System.err.println("❌ Error estableciendo baneo permanente: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * NUEVO: Verifica si un jugador está baneado permanentemente
     */
    public static boolean isPlayerPermanentlyBanned(String playerUuid) {
        String query = "SELECT is_permanently_banned FROM ranked_players WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("is_permanently_banned");
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error verificando baneo permanente: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    /**
     * NUEVO: Aplica pérdidas dobles a un jugador (cuenta como 2 derrotas adicionales)
     */
    public static void addDoubleLossesToPlayer(String playerUuid) {
        String query = "UPDATE ranked_players SET losses = losses + 2, games_played = games_played + 2 WHERE minecraft_uuid = ?";

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);

            int rowsAffected = stmt.executeUpdate();

            if (rowsAffected > 0) {
                System.out.println("✅ Pérdidas dobles aplicadas a: " + playerUuid + " (+2 derrotas)");

                // Invalidar cache para reflejar los cambios
                PlayerDataCache.invalidatePlayer(playerUuid, null);
            }

        } catch (SQLException e) {
            System.err.println("❌ Error aplicando pérdidas dobles: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * NUEVO: Marca a un jugador como ya procesado por abandono para evitar doble penalización
     */
    public static void markPlayerAsAbandonmentProcessed(String playerUuid, String matchId) {
        String query = """
            INSERT INTO match_loss_protections 
            (player_uuid, match_id, protection_reason, created_at) 
            VALUES (?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE protection_reason = VALUES(protection_reason)
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);
            stmt.setString(3, "abandonment_processed");

            stmt.executeUpdate();

            System.out.println("✅ Jugador marcado como procesado por abandono: " + playerUuid + " en partida " + matchId);

        } catch (SQLException e) {
            System.err.println("❌ Error marcando jugador como procesado por abandono: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * NUEVO: Verifica si un jugador ya fue procesado por abandono
     */
    public static boolean isPlayerAbandonmentProcessed(String playerUuid, String matchId) {
        String query = """
            SELECT COUNT(*) as processed_count 
            FROM match_loss_protections 
            WHERE player_uuid = ? AND match_id = ? AND protection_reason = 'abandonment_processed'
        """;

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setString(1, playerUuid);
            stmt.setString(2, matchId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("processed_count") > 0;
                }
            }
        } catch (SQLException e) {
            System.err.println("❌ Error verificando si jugador fue procesado por abandono: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }
}