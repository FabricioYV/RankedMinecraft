package org.fabricioyv.rating;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sistema de ELO Decay - Penaliza la inactividad de jugadores
 * Los jugadores pierden ELO automáticamente si no juegan durante varios días
 */
public class EloDecaySystem {

    // NUEVO: Zona horaria de Perú (GMT-5) para la mayoría de jugadores
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    private final RankedMinecraft plugin;
    private FileConfiguration config;

    // Configuración del sistema
    private boolean enabled;
    private int inactivityDays;
    private int decayPerDay;
    private int minElo;
    private String executionTime;
    private boolean excludePlacementPlayers;
    private boolean notifyOnLogin;
    private String notificationMessage;

    // Caché de notificaciones pendientes: UUID -> ELO perdido
    private final Map<UUID, DecayNotification> pendingNotifications = new HashMap<>();

    /**
     * Constructor - Inicializa el sistema leyendo la configuración
     */
    public EloDecaySystem(RankedMinecraft plugin) {
        this.plugin = plugin;
        loadEloConfig(); // CORREGIDO: Cargar desde elo-config.yml
        loadConfiguration();

        if (enabled) {
            scheduleDecayTask();
            plugin.getLogger().info("§a✓ Sistema de ELO Decay habilitado");
            plugin.getLogger().info("§7  └─ Decay comienza después de " + inactivityDays + " días");
            plugin.getLogger().info("§7  └─ Pérdida: " + decayPerDay + " ELO/día");
            plugin.getLogger().info("§7  └─ Ejecución diaria: " + executionTime);
        } else {
            plugin.getLogger().info("§e⚠ Sistema de ELO Decay deshabilitado");
        }
    }

    /**
     * NUEVO: Carga el archivo elo-config.yml
     */
    private void loadEloConfig() {
        try {
            File configFile = new File(plugin.getDataFolder(), "elo-config.yml");

            if (!configFile.exists()) {
                plugin.saveResource("elo-config.yml", false);
                plugin.getLogger().info("✅ Archivo elo-config.yml creado desde recursos");
            }

            config = YamlConfiguration.loadConfiguration(configFile);
            plugin.getLogger().info("✅ Configuración de ELO Decay cargada desde elo-config.yml");

        } catch (Exception e) {
            plugin.getLogger().severe("❌ Error cargando elo-config.yml para ELO Decay: " + e.getMessage());
            // Crear configuración por defecto en memoria
            config = new YamlConfiguration();
        }
    }

    /**
     * Carga la configuración desde elo-config.yml
     */
    private void loadConfiguration() {
        enabled = config.getBoolean("elo_decay.enabled", true);
        inactivityDays = config.getInt("elo_decay.inactivity_days", 14);
        decayPerDay = config.getInt("elo_decay.decay_per_day", 5);
        minElo = config.getInt("elo_decay.min_elo", 0);
        executionTime = config.getString("elo_decay.execution_time", "03:00");
        excludePlacementPlayers = config.getBoolean("elo_decay.exclude_placement_players", true);
        notifyOnLogin = config.getBoolean("elo_decay.notify_on_login", true);
        notificationMessage = config.getString("elo_decay.notification_message",
                "§c⚠ Has perdido {elo_lost} ELO por {days} días de inactividad. ¡Juega para recuperarlo!");
    }

    /**
     * Programa la tarea diaria de decay
     */
    private void scheduleDecayTask() {
        // Calcular delay hasta la próxima ejecución
        long delayTicks = calculateDelayUntilExecution();

        plugin.getLogger().info("§7Próxima ejecución de ELO Decay en " + (delayTicks / 20 / 60) + " minutos");

        // Programar tarea recursiva (se repite cada 24 horas)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (enabled) {
                    executeDecayProcess();
                    // Reprogramar para mañana a la misma hora
                    scheduleDecayTask();
                }
            }
        }.runTaskLaterAsynchronously(plugin, delayTicks);
    }

    /**
     * Calcula el delay en ticks hasta la próxima ejecución programada
     * CORREGIDO: Usa zona horaria de Perú (America/Lima)
     */
    private long calculateDelayUntilExecution() {
        try {
            // Parsear hora de ejecución (formato "HH:mm")
            String[] timeParts = executionTime.split(":");
            int targetHour = Integer.parseInt(timeParts[0]);
            int targetMinute = Integer.parseInt(timeParts[1]);

            // CORREGIDO: Usar zona horaria de Perú
            LocalDateTime now = LocalDateTime.now(PERU_ZONE);
            LocalDateTime nextExecution = now.toLocalDate()
                    .atTime(LocalTime.of(targetHour, targetMinute));

            // Si la hora ya pasó hoy, programar para mañana
            if (nextExecution.isBefore(now)) {
                nextExecution = nextExecution.plusDays(1);
            }

            long secondsUntil = ChronoUnit.SECONDS.between(now, nextExecution);
            return secondsUntil * 20L; // Convertir a ticks (20 ticks = 1 segundo)

        } catch (Exception e) {
            plugin.getLogger().warning("Error parseando execution_time. Usando 24 horas por defecto.");
            return 20L * 60 * 60 * 24; // 24 horas en ticks
        }
    }

    /**
     * PROCESO PRINCIPAL: Ejecuta el decay de ELO para todos los jugadores inactivos
     */
    public void executeDecayProcess() {
        plugin.getLogger().info("§e⚡ Iniciando proceso de ELO Decay...");

        long startTime = System.currentTimeMillis();
        int[] stats = {0, 0, 0}; // [jugadores procesados, jugadores afectados, ELO total perdido]

        try (Connection conn = DatabaseManager.getConnection("ranked")) {
            // Query para obtener jugadores elegibles para decay
            String query = buildDecayQuery();

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setInt(1, inactivityDays); // Parámetro para días de inactividad

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        stats[0]++; // Contador de jugadores procesados

                        String minecraftUuid = rs.getString("minecraft_uuid");
                        int currentElo = rs.getInt("elo");
                        String lastMatchDate = rs.getString("last_match_date");
                        int gamesPlayed = rs.getInt("games_played");

                        // Verificar si está en placement
                        if (excludePlacementPlayers && gamesPlayed < 8) {
                            continue; // Saltar jugadores en placement
                        }

                        // Calcular días inactivos
                        long daysInactive = calculateDaysInactive(lastMatchDate);

                        // Calcular decay
                        int eloLost = calculateDecay(daysInactive, currentElo);

                        if (eloLost > 0) {
                            // Aplicar decay
                            int newElo = Math.max(minElo, currentElo - eloLost);
                            applyDecay(minecraftUuid, newElo);

                            stats[1]++; // Jugadores afectados
                            stats[2] += eloLost; // ELO total perdido

                            // Guardar notificación pendiente
                            if (notifyOnLogin) {
                                UUID uuid = UUID.fromString(minecraftUuid);
                                pendingNotifications.put(uuid, new DecayNotification(eloLost, daysInactive));
                            }

                            plugin.getLogger().info(String.format(
                                    "§7  └─ %s: -%d ELO (%d días inactivo)",
                                    minecraftUuid.substring(0, 8), eloLost, daysInactive
                            ));
                        }
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            plugin.getLogger().info(String.format(
                    "§a✓ ELO Decay completado en %dms | Procesados: %d | Afectados: %d | ELO perdido: %d",
                    duration, stats[0], stats[1], stats[2]
            ));

        } catch (SQLException e) {
            plugin.getLogger().severe("§c✗ Error ejecutando proceso de ELO Decay: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Construye la query para obtener jugadores elegibles para decay
     */
    private String buildDecayQuery() {
        return "SELECT minecraft_uuid, elo, " +
                "DATE(last_match_date) as last_match_date, " +
                "games_played " +
                "FROM ranked_players " +
                "WHERE last_match_date IS NOT NULL " +
                "AND DATEDIFF(NOW(), last_match_date) >= ? " +
                "AND elo > ?";
    }

    /**
     * Calcula días inactivos desde la última partida
     */
    private long calculateDaysInactive(String lastMatchDate) {
        try {
            LocalDate lastMatch = LocalDate.parse(lastMatchDate);
            LocalDate today = LocalDate.now();
            return ChronoUnit.DAYS.between(lastMatch, today);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Calcula cuánto ELO debe perder el jugador
     * Fórmula: (días_inactivo - días_gracia) * decay_per_day
     */
    private int calculateDecay(long daysInactive, int currentElo) {
        if (daysInactive <= inactivityDays || currentElo <= minElo) {
            return 0; // No hay decay
        }

        long excessDays = daysInactive - inactivityDays;
        int potentialLoss = (int) (excessDays * decayPerDay);

        // No bajar del mínimo
        int maxLoss = currentElo - minElo;
        return Math.min(potentialLoss, maxLoss);
    }

    /**
     * Aplica el decay a la base de datos
     */
    private void applyDecay(String minecraftUuid, int newElo) {
        try (Connection conn = DatabaseManager.getConnection("ranked");
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE ranked_players SET elo = ?, mmr = ? WHERE minecraft_uuid = ?")) {

            stmt.setInt(1, newElo);
            stmt.setDouble(2, newElo); // Sincronizar MMR con ELO
            stmt.setString(3, minecraftUuid);
            stmt.executeUpdate();

        } catch (SQLException e) {
            plugin.getLogger().warning("Error aplicando decay a " + minecraftUuid + ": " + e.getMessage());
        }
    }

    /**
     * Notifica al jugador cuando se conecta (si perdió ELO por inactividad)
     */
    public void notifyPlayerOnLogin(UUID playerUuid) {
        if (!notifyOnLogin || !pendingNotifications.containsKey(playerUuid)) {
            return;
        }

        DecayNotification notification = pendingNotifications.remove(playerUuid);

        // Programar notificación después de 3 segundos (para que el jugador se cargue)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            org.bukkit.entity.Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.isOnline()) {
                String message = notificationMessage
                        .replace("{elo_lost}", String.valueOf(notification.eloLost))
                        .replace("{days}", String.valueOf(notification.daysInactive));

                player.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                player.sendMessage(message);
                player.sendMessage("§7¡Juega partidas ranked para recuperar tu ELO!");
                player.sendMessage("§8§m━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            }
        }, 60L); // 3 segundos
    }

    /**
     * Comando administrativo para forzar decay inmediato (útil para testing)
     */
    public CompletableFuture<String> forceDecayNow() {
        return CompletableFuture.supplyAsync(() -> {
            if (!enabled) {
                return "§c✗ El sistema de ELO Decay está deshabilitado";
            }

            executeDecayProcess();
            return "§a✓ Proceso de ELO Decay ejecutado manualmente";
        });
    }

    /**
     * Recarga la configuración
     */
    public void reloadConfig() {
        loadConfiguration();
        plugin.getLogger().info("§a✓ Configuración de ELO Decay recargada");
    }

    /**
     * Clase interna para almacenar notificaciones pendientes
     */
    private static class DecayNotification {
        final int eloLost;
        final long daysInactive;

        DecayNotification(int eloLost, long daysInactive) {
            this.eloLost = eloLost;
            this.daysInactive = daysInactive;
        }
    }
}