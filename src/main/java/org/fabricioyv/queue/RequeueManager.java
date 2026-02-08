package org.fabricioyv.queue;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.discord.DiscordBot;
import org.fabricioyv.model.PlayerData;

public class RequeueManager {

    private static DiscordBot discordBot;
    private RankedMinecraft plugin;

    public RequeueManager(DiscordBot discordBot, RankedMinecraft plugin) {
        RequeueManager.discordBot = discordBot;
        this.plugin = plugin;
    }

    public static void requeuePlayer(PlayerData playerData, String queueType) {

        JDA jda = discordBot.getJda();

        Guild guild = jda.getGuildById(discordBot.getGuildId());
        Member member = guild.getMemberById(playerData.getDiscordId());

        String targetVoiceChannelID = switch (queueType) {
            case "2vs2" -> VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2;
            case "5vs5" -> VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5;
            case "8vs8" -> VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8;
            default -> "Unknown";
        };

        VoiceChannel target = guild.getVoiceChannelById(targetVoiceChannelID);

        if (member == null || target == null) return;
        GuildVoiceState vs = member.getVoiceState();
        if (vs == null || !vs.inAudioChannel()) {
            // El usuario no está en voice: no se le puede mover
            return;
        }

        guild.moveVoiceMember(member, target).queue(
                success -> System.out.println("Usuario movido correctamente"),
                Throwable::printStackTrace
        );

        QueueType lastQueueTypeEnum = switch (queueType) {
            case "2vs2" -> QueueType.TWO_VS_TWO;
            case "5vs5" -> QueueType.FIVE_VS_FIVE;
            case "8vs8" -> QueueType.EIGHT_VS_EIGHT;
            default -> null;
        };

        discordBot.getQueueManager().addPlayerToQueue(playerData, lastQueueTypeEnum);
    }
}
