package org.fabricioyv;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import org.fabricioyv.commands.*;
import org.fabricioyv.config.PerformanceConfig;
import org.fabricioyv.database.BatchProcessor;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.discord.DiscordBot;
import org.fabricioyv.listeners.MatchStatsListener;
import org.fabricioyv.listeners.PGMMatchListener;
import org.fabricioyv.listeners.RequeuePearlListener;
import org.fabricioyv.match.AbandonmentDetectionSystem;
import org.fabricioyv.match.ForfeitManager;
import org.fabricioyv.match.MapManager;
import org.fabricioyv.queue.RequeueManager;
import org.fabricioyv.rating.ProgressiveEloCalculator;

import java.io.InputStream;
import java.io.InputStreamReader;

public final class RankedMinecraft extends JavaPlugin {

    private DiscordBot discordBot;
    private static RankedMinecraft instance;

    private AbandonmentDetectionSystem abandonmentSystem;
    private org.fabricioyv.rating.EloDecaySystem eloDecaySystem;

    @Override
    public void onEnable() {
        instance = this;

        // 1) Generar/cargar config.yml principal y asegurar que esté completo
        loadCompleteConfig();

        // ========================================
        // 2) INICIALIZAR SISTEMA DE PERFORMANCE OPTIMIZATION
        // ========================================
        getLogger().info("§a[Performance] Inicializando sistema de optimización de hit registration...");
        PerformanceConfig.init(this);

        // Log del estado de configuración de performance
        if (PerformanceConfig.isUltraPerformanceMode()) {
            getLogger().info("§e[Performance] ⚡ MODO ULTRA PERFORMANCE ACTIVADO - Stats completamente deshabilitados");
        } else if (!PerformanceConfig.isStatsTrackingEnabled()) {
            getLogger().info("§e[Performance] ⚡ Stats tracking deshabilitado - Hit registration optimizado");
        } else {
            getLogger().info("§a[Performance] ✓ Performance balanceado - Stats: " +
                    PerformanceConfig.isAnyTrackingEnabled());
        }

        // 3) Cargar settings del sistema de forfeit/afk desde config.yml
        ForfeitManager.loadSettings(this);

        try {
            // Inicializar sistema de mapas
            MapManager.initialize(this);

            // Inicializar sistema ELO
            ProgressiveEloCalculator.initialize(this);

            // Registrar comandos (con protección anti-null)
            registerCommand("votemap", new VoteCommand());
            registerCommand("ff", new ForfeitCommand(this));
            registerCommand("ready", new ReadyCommand());
            // NO uses getCommand("r") -> "r" es alias de "ready" en plugin.yml
            registerCommand("mapadmin", new MapAdminCommand());
            registerCommand("placement", new PlacementStatsCommand());
            registerCommand("testplacement", new TestPlacementAnalysisCommand());
            registerCommand("pick", new PickCommand());
            registerCommand("requeue", new RequeueCommand());

            // Inicializar base de datos
            if (!DatabaseManager.initialize()) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error al inicializar la base de datos!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Ejecutar migración de ELO Decay
            org.fabricioyv.database.EloDecayMigration.executeMigration();

            // Inicializar Discord bot ANTES de listeners que lo necesiten
            initializeDiscordBot();

            //INYECTAR DiscordBot al RequeueManager (OBLIGATORIO para /requeue)
            if (discordBot != null) {
                RequeueManager.setDiscordBot(discordBot);
                getLogger().info("§a[Requeue] DiscordBot conectado a RequeueManager.");
            } else {
                getLogger().warning("§e[Requeue] DiscordBot es null. /requeue funcionará solo si Discord está inicializado.");
            }

            //Registrar listener de la perla de requeue (OBLIGATORIO para click derecho)
            getServer().getPluginManager().registerEvents(new RequeuePearlListener(), this);
            getLogger().info("§a[Requeue] RequeuePearlListener registrado.");

            // Inicializar sistema de detección de abandono (si hay Discord logger)
            if (discordBot != null && discordBot.getLogger() != null) {
                abandonmentSystem = new AbandonmentDetectionSystem(this, discordBot.getLogger());
                getLogger().info("Sistema de detección de abandono inicializado");
            } else {
                getLogger().warning("Discord bot no inicializado o logger no disponible. Abandonment system no se activó.");
            }

            // Inicializar sistema de ELO Decay
            eloDecaySystem = new org.fabricioyv.rating.EloDecaySystem(this);
            registerCommand("elodecay", new org.fabricioyv.commands.EloDecayCommand(eloDecaySystem));

            // Registrar listener de ELO Decay
            getServer().getPluginManager().registerEvents(
                    new org.fabricioyv.listeners.EloDecayListener(eloDecaySystem), this);

            // Registrar listeners de PGM y Rejoin
            registerPGMListeners();

            // Registrar MatchStatsListener para capturar estadísticas durante las partidas
            getServer().getPluginManager().registerEvents(new MatchStatsListener(), this);

            // Registrar listener del GUI de picks
            getServer().getPluginManager().registerEvents(new org.fabricioyv.listeners.PicksGUIListener(), this);

            // Registrar listener RR
            getServer().getPluginManager().registerEvents(new org.fabricioyv.listeners.CaptainRerollListener(), this);

            // Iniciar tarea de limpieza de entidades para optimización
            startEntityCleanupTask();

            getLogger().info("RankedMinecraft habilitado exitosamente!");

        } catch (Exception e) {
            getLogger().severe("Error crítico durante la inicialización: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void registerCommand(String name, CommandExecutor executor) {
        if (getCommand(name) == null) {
            getLogger().severe("❌ Comando '" + name + "' no está definido en plugin.yml");
            return;
        }
        getCommand(name).setExecutor(executor);
    }

    /**
     * Limpieza automática de entidades para prevenir lag
     */
    private void startEntityCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int removedItems = 0;
            int removedArrows = 0;

            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (org.bukkit.entity.Entity entity : world.getEntities()) {
                    if (entity instanceof org.bukkit.entity.Item) {
                        org.bukkit.entity.Item item = (org.bukkit.entity.Item) entity;
                        if (item.getTicksLived() > 600) { // 30s
                            item.remove();
                            removedItems++;
                        }
                    } else if (entity instanceof org.bukkit.entity.Arrow) {
                        org.bukkit.entity.Arrow arrow = (org.bukkit.entity.Arrow) entity;
                        if (arrow.isOnGround() && arrow.getTicksLived() > 200) { // 10s
                            arrow.remove();
                            removedArrows++;
                        }
                    }
                }
            }

            if (removedItems > 0 || removedArrows > 0) {
                getLogger().info(String.format("§a✓ Limpieza automática: %d items, %d flechas removidas",
                        removedItems, removedArrows));
            }
        }, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        try {
            if (discordBot != null) {
                discordBot.shutdown();
            }

            MatchStatsListener.shutdown();
            BatchProcessor.shutdown();
            DatabaseManager.close();

            getLogger().info("RankedMinecraft deshabilitado correctamente!");
            Bukkit.getScheduler().cancelTasks(this);
        } catch (Exception e) {
            getLogger().severe("Error durante el cierre: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void initializeDiscordBot() {
        boolean enabled = getConfig().getBoolean("discord.enabled", true);
        if (!enabled) {
            getLogger().warning("Discord bot desactivado por config.yml (discord.enabled=false)");
            return;
        }

        String token = getConfig().getString("discord.token", "");
        String guildId = getConfig().getString("discord.guild-id", "");

        if (token == null || token.trim().isEmpty() || token.equalsIgnoreCase("PUT_TOKEN_HERE")) {
            getLogger().severe("Token de Discord no configurado! (config.yml -> discord.token)");
            return;
        }

        if (guildId == null || guildId.trim().isEmpty() || guildId.equalsIgnoreCase("PUT_GUILD_ID_HERE")) {
            getLogger().severe("Guild ID de Discord no configurado! (config.yml -> discord.guild-id)");
            return;
        }

        discordBot = new DiscordBot();
        discordBot.initialize(this, token, guildId);
    }

    private void registerPGMListeners() {
        if (discordBot != null && discordBot.getLogger() != null) {
            getServer().getPluginManager().registerEvents(
                    new PGMMatchListener(this, discordBot.getLogger()), this);

            getServer().getPluginManager().registerEvents(
                    new org.fabricioyv.listeners.PlayerRejoinListener(this, discordBot.getLogger()), this);

            getLogger().info("✅ Listeners de PGM y Rejoin registrados exitosamente");
        } else {
            getLogger().warning("⚠️ Discord bot no está listo, intentando registrar listeners en 5 segundos...");
            Bukkit.getScheduler().runTaskLater(this, this::registerPGMListeners, 100L);
        }
    }

    public AbandonmentDetectionSystem getAbandonmentDetectionSystem() {
        return abandonmentSystem;
    }

    public org.fabricioyv.rating.EloDecaySystem getEloDecaySystem() {
        return eloDecaySystem;
    }

    public DiscordBot getDiscordBot() {
        return discordBot;
    }

    public static RankedMinecraft getInstance() {
        return instance;
    }

    /**
     * Cargar y completar el config.yml desde el archivo de recursos si es necesario
     */
    private void loadCompleteConfig() {
        // Primero intentar guardar el archivo por defecto si no existe
        saveDefaultConfig();

        FileConfiguration config = getConfig();

        // Cargar config.yml desde recursos
        InputStream inputStream = getResource("config.yml");
        if (inputStream == null) {
            getLogger().severe("No se encontró el archivo config.yml en los recursos del plugin.");
            return;
        }

        YamlConfiguration defaultConfig = new YamlConfiguration();
        try {
            defaultConfig.load(new InputStreamReader(inputStream));
        } catch (Exception e) {
            getLogger().severe("Error al cargar el archivo config.yml desde los recursos: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Verificar y completar secciones faltantes
        boolean modified = false;
        for (String key : defaultConfig.getKeys(true)) {
            if (!config.contains(key)) {
                config.set(key, defaultConfig.get(key));
                getLogger().info("§e[Config] Agregada sección faltante: " + key);
                modified = true;
            }
        }

        // Guardar config.yml solo si se realizaron cambios
        if (modified) {
            saveConfig();
            getLogger().info("§a[Config] ✓ config.yml actualizado con las secciones por defecto.");
        } else {
            getLogger().info("§a[Config] ✓ config.yml está completo.");
        }
    }
}
