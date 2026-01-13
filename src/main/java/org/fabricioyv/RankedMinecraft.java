package org.fabricioyv;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.fabricioyv.commands.*;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.database.BatchProcessor;
import org.fabricioyv.discord.DiscordBot;
import org.fabricioyv.listeners.MatchStatsListener;
import org.fabricioyv.listeners.PGMMatchListener;
import org.fabricioyv.listeners.PlayerRejoinListener;
import org.fabricioyv.match.AbandonmentDetectionSystem;
import org.fabricioyv.match.MapManager;
import org.fabricioyv.rating.ProgressiveEloCalculator;


/**
 * Plugin principal para la gestión de partidas clasificatorias en Minecraft.
 * Integra sistemas de ELO, votación de mapas, y un bot de Discord para administración y estadísticas.
 *
 * Created by FabricioYV
 * @author FabricioYV
 */
public final class RankedMinecraft extends JavaPlugin {
    private DiscordBot discordBot;
    private static RankedMinecraft instance;
    private AbandonmentDetectionSystem abandonmentSystem;
    private org.fabricioyv.rating.EloDecaySystem eloDecaySystem; // NUEVO: Sistema de ELO Decay

    @Override
    public void onEnable() {
        instance = this;
        try {
            // Inicializar sistema de mapas
            MapManager.initialize(this);

            // Inicializar sistema ELO
            ProgressiveEloCalculator.initialize(this);

            // Registrar comandos
            getCommand("votemap").setExecutor(new VoteCommand());
            getCommand("ff").setExecutor(new ForfeitCommand(this));
            getCommand("ready").setExecutor(new ReadyCommand());
            getCommand("r").setExecutor(new ReadyCommand()); // Alias corto
            getCommand("mapadmin").setExecutor(new MapAdminCommand()); // Comando para administrar mapas
            getCommand("placement").setExecutor(new PlacementStatsCommand()); // Comando para estadísticas de placement
            getCommand("testplacement").setExecutor(new TestPlacementAnalysisCommand()); // Comando de testing para análisis avanzado
            getCommand("pick").setExecutor(new PickCommand()); // Comando para elegir jugador (pick)

            // Inicializar base de datos
            if(!DatabaseManager.initialize()) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error al inicializar la base de datos!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // NUEVO: Ejecutar migración de ELO Decay
            org.fabricioyv.database.EloDecayMigration.executeMigration();

            // Inicializar Discord bot ANTES de registrar listeners que lo necesiten
            initializeDiscordBot();

            // NUEVO: Inicializar sistema de detección de abandono
            if (discordBot != null && discordBot.getLogger() != null) {
                abandonmentSystem = new AbandonmentDetectionSystem(this, discordBot.getLogger());
                getLogger().info("Sistema de detección de abandono inicializado");
            }

            // NUEVO: Inicializar sistema de ELO Decay
            eloDecaySystem = new org.fabricioyv.rating.EloDecaySystem(this);
            getCommand("elodecay").setExecutor(new org.fabricioyv.commands.EloDecayCommand(eloDecaySystem));

            // NUEVO: Registrar listener de ELO Decay
            getServer().getPluginManager().registerEvents(
                    new org.fabricioyv.listeners.EloDecayListener(eloDecaySystem), this);

            // Registrar listeners de PGM (ya verifica internamente si discordBot está listo)
            registerPGMListeners();

            // Registrar MatchStatsListener para capturar estadísticas durante las partidas
            getServer().getPluginManager().registerEvents(new MatchStatsListener(), this);

            // NUEVO: Registrar listener del GUI de picks
            getServer().getPluginManager().registerEvents(new org.fabricioyv.listeners.PicksGUIListener(), this);

            // NUEVO: Iniciar tarea de limpieza de entidades para optimización
            startEntityCleanupTask();

            getLogger().info("RankedMinecraft habilitado exitosamente!");

        } catch (Exception e) {
            getLogger().severe("Error crítico durante la inicialización: " + e.getMessage());
            e.printStackTrace();

            // Deshabilitar plugin si falla la inicialización
            getServer().getPluginManager().disablePlugin(this);
        }

    }

    /**
     * NUEVO: Tarea de limpieza automática de entidades para prevenir lag
     * Limpia items en el suelo, arrows, y mobs innecesarios cada 5 minutos
     */
    private void startEntityCleanupTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            int removedItems = 0;
            int removedArrows = 0;

            for (org.bukkit.World world : Bukkit.getWorlds()) {
                // Limpiar items en el suelo (excepto en los primeros 30 segundos)
                for (org.bukkit.entity.Entity entity : world.getEntities()) {
                    if (entity instanceof org.bukkit.entity.Item item) {
                        if (item.getTicksLived() > 600) { // Más de 30 segundos
                            item.remove();
                            removedItems++;
                        }
                    } else if (entity instanceof org.bukkit.entity.Arrow arrow) {
                        if (arrow.isOnGround() && arrow.getTicksLived() > 200) { // Más de 10 segundos
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
        }, 6000L, 6000L); // Cada 5 minutos (6000 ticks)
    }

    @Override
    public void onDisable() {
        try {
            // Cerrar Discord bot
            if (discordBot != null) {
                discordBot.shutdown();
            }

            // OPTIMIZACIÓN: Cerrar MatchStatsListener correctamente
            MatchStatsListener.shutdown();

            // OPTIMIZACIÓN: Cerrar BatchProcessor y procesar operaciones pendientes
            BatchProcessor.shutdown();

            // Cerrar base de datos
            DatabaseManager.close();

            getLogger().info("RankedMinecraft deshabilitado correctamente!");

            // Cancel all Bukkit tasks
            Bukkit.getScheduler().cancelTasks(this);
        } catch (Exception e) {
            getLogger().severe("Error durante el cierre: " + e.getMessage());
            e.printStackTrace();
        }

    }

    private void initializeDiscordBot() {
        String token = "NzcyNTI4MTg5Njc1Mjc0Mjcw.G1laQA.UZv7v6qSvrjfcSoq6z6_9jfvva9ZyECCHy9Z8g";
        String guildId = "1404292846340542554";

        if (token == null || token.isEmpty()) {
            getLogger().severe("Token de Discord no configurado!");
            return;
        }

        if (guildId == null || guildId.isEmpty()) {
            getLogger().severe("Guild ID de Discord no configurado!");
            return;
        }

        discordBot = new DiscordBot();
        discordBot.initialize(this, token, guildId);
    }

    private void registerPGMListeners() {
        // Esperar a que Discord bot esté listo antes de registrar listeners
        if (discordBot != null && discordBot.getLogger() != null) {
            // Registrar listener de PGM
            getServer().getPluginManager().registerEvents(
                    new PGMMatchListener(this, discordBot.getLogger()), this);

            // Registrar listener de rejoin para jugadores
            getServer().getPluginManager().registerEvents(
                    new org.fabricioyv.listeners.PlayerRejoinListener(this, discordBot.getLogger()), this);

            getLogger().info("✅ Listeners de PGM y Rejoin registrados exitosamente");
        } else {
            getLogger().warning("⚠️ Discord bot no está listo, intentando registrar listeners en 5 segundos...");

            // Reintentar en 5 segundos
            Bukkit.getScheduler().runTaskLater(this, this::registerPGMListeners, 100L);
        }
    }
    /**
     * NUEVO: Getter para el sistema de abandono
     */
    public AbandonmentDetectionSystem getAbandonmentDetectionSystem() {
        return abandonmentSystem;
    }
    /**
     * NUEVO: Getter para el sistema de ELO Decay
     */
    public org.fabricioyv.rating.EloDecaySystem getEloDecaySystem() {
        return eloDecaySystem;
    }
    public DiscordBot getDiscordBot() {
        return discordBot;
    }
    public static RankedMinecraft getInstance() {
        return instance;
    }

}