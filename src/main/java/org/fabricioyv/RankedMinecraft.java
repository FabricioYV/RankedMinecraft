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
import org.fabricioyv.listeners.PGMMatchListener;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.MapManager;
import org.fabricioyv.match.MatchFinisher;
import org.fabricioyv.match.MatchState;


public final class RankedMinecraft extends JavaPlugin {
    private DiscordBot discordBot;
    private static RankedMinecraft instance;
    @Override
    public void onEnable() {
        instance = this;
        try {


            // Inicializar sistema de mapas
            MapManager.initialize(this);

            // Registrar comandos
            getCommand("votemap").setExecutor(new VoteCommand());
            getCommand("ff").setExecutor(new ForfeitCommand(this));
            getCommand("ready").setExecutor(new ReadyCommand());
            getCommand("r").setExecutor(new ReadyCommand()); // Alias corto

            // Inicializar base de datos
            if(!DatabaseManager.initialize()) {
                Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "Error al inicializar la base de datos!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            // Inicializar Discord bot
            initializeDiscordBot();
            // Registrar listeners de PGM
            registerPGMListeners();
            getServer().getPluginManager().registerEvents(new MatchStatsListener(this,discordBot.getLogger()), this);



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
    public DiscordBot getDiscordBot() {
        return discordBot;
    }
    /**
     * Registra los listeners para eventos de PGM
     */
    private void registerPGMListeners() {
        if (discordBot != null && discordBot.getLogger() != null) {
            getServer().getPluginManager().registerEvents(
                    new PGMMatchListener(this, discordBot.getLogger()),
                    this
            );
            getLogger().info("Listeners de PGM registrados correctamente!");
        } else {
            getLogger().warning("No se pudieron registrar listeners de PGM - Discord bot no inicializado");
        }
    }
    public static RankedMinecraft getInstance() {
        return instance;
    }

}
