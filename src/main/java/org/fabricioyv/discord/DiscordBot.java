package org.fabricioyv.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.Compression;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.commands.MatchDetailsCommand;
import org.fabricioyv.commands.RecentMatchesCommand;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.rating.Rank;

import java.util.List;

public class DiscordBot {

    private JDA jda;
    private QueueManager queueManager;
    private DiscordLogger logger;

    public void initialize(RankedMinecraft plugin, String token, String guildId) {
        try {
            // ✅ IMPORTANTÍSIMO para mover miembros sin depender de cache flojo:
            // - MemberCachePolicy.ALL + ChunkingFilter.ALL
            // - intents: GUILD_MEMBERS + GUILD_VOICE_STATES
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MEMBERS)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .setStatus(OnlineStatus.ONLINE)
                    .setActivity(Activity.playing("Administrando Keke Rankeds"))
                    .setAutoReconnect(true)
                    .setCompression(Compression.ZLIB)
                    .build();

            jda.awaitReady();

            logger = new DiscordLogger(jda);

            // ✅ QueueManager con guildId (ideal)
            queueManager = new QueueManager(jda, plugin, guildId);

            // Listener voice
            jda.addEventListener(new VoiceChannelListener(queueManager, logger));

            // Registrar listeners comandos
            registerMatchCommands();

            // Registrar slash commands (una vez, no doble)
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

    private void registerMatchCommands() {
        try {
            RecentMatchesCommand recentMatchesCommand = new RecentMatchesCommand();
            MatchDetailsCommand matchDetailsCommand = new MatchDetailsCommand();

            jda.addEventListener(recentMatchesCommand);
            jda.addEventListener(matchDetailsCommand);

        } catch (Exception e) {
            if (logger != null) {
                logger.systemError("DiscordBot", "Error registrando comandos de partidas", e.getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * Asigna el rol de Discord correspondiente al rango del jugador
     */
    public void assignRankRole(String discordId, Rank rank) {
        if (jda == null || discordId == null || discordId.isEmpty() || rank == null) return;

        try {
            List<Guild> guilds = jda.getGuilds();
            if (guilds == null || guilds.isEmpty()) return;

            Guild guild = guilds.get(0); // si tienes 1 guild está ok
            // ✅ retrieveMemberById evita null por cache
            guild.retrieveMemberById(discordId).queue(member -> {
                if (member == null) return;

                List<Role> roles = guild.getRolesByName(rank.getDisplayName(), true);
                if (!roles.isEmpty()) {
                    Role role = roles.get(0);
                    guild.addRoleToMember(member, role).queue();
                }
            }, err -> {
                if (logger != null) logger.error("DiscordBot", "Error retrieveMemberById: " + err.getMessage());
            });

        } catch (Exception e) {
            if (logger != null) logger.error("DiscordBot", "Error asignando rol de rango: " + e.getMessage());
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