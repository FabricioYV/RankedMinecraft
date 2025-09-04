package org.fabricioyv.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.Compression;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.queue.QueueManager;

public class DiscordBot {
    private JDA jda;
    private QueueManager queueManager;
    private DiscordLogger logger;


    public void initialize(RankedMinecraft plugin, String token, String guildId) {
        try {
            // Crear JDA con los intents necesarios para eventos de voz
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Administrando las Kekes"))
                    .setAutoReconnect(true)
                    .setCompression(Compression.ZLIB)
                    .build();

            // Esperar a que JDA esté listo
            jda.awaitReady();

            logger = new DiscordLogger(jda);
            // Crear QueueManager
            queueManager = new QueueManager(jda, plugin, guildId);

            // Registrar listener para eventos de voz
            jda.addEventListener(new VoiceChannelListener(queueManager, logger));

            logger.systemStart();

            plugin.getLogger().info("Discord bot inicializado correctamente!");

        } catch (Exception e) {
            plugin.getLogger().severe("Error al inicializar Discord bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void shutdown() {
        if (jda != null) {
            jda.shutdown();
        }
    }

    public QueueManager getQueueManager() {
        return queueManager;
    }

    public JDA getJda() {
        return jda;
    }
    public DiscordLogger getLogger() {
        return logger;
    }

}