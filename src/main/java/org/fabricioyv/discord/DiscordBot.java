package org.fabricioyv.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.Compression;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.commands.MatchDetailsCommand;
import org.fabricioyv.commands.RecentMatchesCommand;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.rating.Rank;

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
                    .setActivity(Activity.playing("Administrando Keke Rankeds"))
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

            // REGISTRAR COMANDOS DE MATCH LOGS
            registerMatchCommands();
            // Registrar DuoCommand
            // Registrar comandos slash globalmente
            jda.updateCommands()
                    .addCommands(
                            RecentMatchesCommand.getSlashCommand(),
                            MatchDetailsCommand.getSlashCommand()
                    )
                    .queue(
                            success -> logger.info("Commands Registered", "Todos los comandos registrados exitosamente"),
                            error -> logger.error("Command Registration Failed", "Error registrando comandos: " + error.getMessage())
                    );

            logger.systemStart();

            plugin.getLogger().info("Discord bot inicializado correctamente!");

        } catch (Exception e) {
            plugin.getLogger().severe("Error al inicializar Discord bot: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Registra los comandos slash para consultar partidas
     */
    private void registerMatchCommands() {
        try {
            // Crear instancias de los comandos
            RecentMatchesCommand recentMatchesCommand = new RecentMatchesCommand();
            MatchDetailsCommand matchDetailsCommand = new MatchDetailsCommand();

            // Registrar listeners para los comandos
            jda.addEventListener(recentMatchesCommand);
            jda.addEventListener(matchDetailsCommand);

            // Registrar comandos slash globalmente
            jda.updateCommands()
                    .addCommands(
                            RecentMatchesCommand.getSlashCommand(),
                            MatchDetailsCommand.getSlashCommand()
                    )
                    .queue(
                            success -> logger.info("Commands Registered", "Todos los comandos registrados exitosamente"),
                            error -> logger.error("Command Registration Failed", "Error registrando comandos: " + error.getMessage())
                    );

        } catch (Exception e) {
            logger.systemError("DiscordBot", "Error registrando comandos de partidas", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Asigna el rol de Discord correspondiente al rango del jugador
     */
    public void assignRankRole(String discordId, Rank rank) {
        if (jda == null || discordId == null || rank == null) return;
        try {
            var guild = jda.getGuilds().get(0); // Asume un solo guild
            var member = guild.retrieveMemberById(discordId).complete();
            if (member == null) return;
            // Buscar el rol por nombre exacto del rango
            var roles = guild.getRolesByName(rank.getDisplayName(), true);
            if (!roles.isEmpty()) {
                var role = roles.get(0);
                guild.addRoleToMember(member, role).queue();
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.error("DiscordBot", "Error asignando rol de rango: " + e.getMessage());
            }
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