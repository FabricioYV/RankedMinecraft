package org.fabricioyv;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import org.fabricioyv.commands.ForfeitCommand;
import org.fabricioyv.commands.ReadyCommand;
import org.fabricioyv.commands.VoteCommand;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.discord.DiscordBot;
import org.fabricioyv.listeners.MatchStatsListener;
import org.fabricioyv.listeners.OptimizedMatchStatsListener;
import org.fabricioyv.listeners.PGMMatchListener;
import org.fabricioyv.match.MapManager;
import org.fabricioyv.rating.ProgressiveEloCalculator;


public final class RankedMinecraft extends JavaPlugin {
    private DiscordBot discordBot;
    private static RankedMinecraft instance;
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
            getCommand("mapadmin").setExecutor(new org.fabricioyv.commands.MapAdminCommand()); // Comando para administrar mapas

            // Inicializar base de datos
            if(!DatabaseManager.initialize()) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error al inicializar la base de datos!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Inicializar Discord bot ANTES de registrar listeners que lo necesiten
            initializeDiscordBot();

            // Registrar listeners de PGM (ya verifica internamente si discordBot está listo)
            registerPGMListeners();

            // Registrar MatchStatsListener para capturar estadísticas durante las partidas
            getServer().getPluginManager().registerEvents(new MatchStatsListener(), this);

            getLogger().info("RankedMinecraft habilitado exitosamente!");

        } catch (Exception e) {
            getLogger().severe("Error crítico durante la inicialización: " + e.getMessage());
            e.printStackTrace();

            // Deshabilitar plugin si falla la inicialización
            getServer().getPluginManager().disablePlugin(this);
        }

    }

    @Override
    public void onDisable() {
        try {
            // Cerrar Discord bot
            if (discordBot != null) {
                discordBot.shutdown();
            }

            // Cerrar base de datos
            DatabaseManager.close();

            getLogger().info("RankedMinecraft deshabilitado correctamente!");
            OptimizedMatchStatsListener.shutdown();
            // 5. Cancel all Bukkit tasks
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
    public DiscordBot getDiscordBot() {
        return discordBot;
    }
    public static RankedMinecraft getInstance() {
        return instance;
    }

}
