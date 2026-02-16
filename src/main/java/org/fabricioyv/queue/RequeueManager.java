package org.fabricioyv.queue;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.discord.DiscordBot;
import org.fabricioyv.model.PlayerData;

import java.util.UUID;

/**
 * Requeue:
 * - Mueve al jugador al canal de voz de la cola
 * - Luego lo intenta meter en la cola (con delays) para evitar el típico bug
 *   de "aún no se actualizó el voiceState".
 */
public class RequeueManager {

    private static DiscordBot discordBot;

    // Mantengo el constructor por compatibilidad con tu init actual
    public RequeueManager(DiscordBot discordBot, RankedMinecraft plugin) {
        RequeueManager.discordBot = discordBot;
    }

    /** Por si prefieres inicializarlo sin instanciar la clase */
    public static void setDiscordBot(DiscordBot bot) {
        RequeueManager.discordBot = bot;
    }

    public static void requeuePlayer(PlayerData playerData, String queueTypeRaw) {
        Player mcPlayer = getOnlinePlayer(playerData);

        if (playerData == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: PlayerData inválido.");
            return;
        }

        if (discordBot == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: DiscordBot no está inicializado (discordBot=null).");
            return;
        }

        QueueType queueTypeEnum = parseQueueType(queueTypeRaw);
        if (queueTypeEnum == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: tipo de cola inválido: §f" + queueTypeRaw);
            return;
        }

        JDA jda;
        try {
            jda = discordBot.getJda();
        } catch (Exception e) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: no pude obtener JDA.");
            return;
        }

        Guild guild = jda.getGuildById(discordBot.getGuildId());
        if (guild == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: guild no encontrado (ID mal?).");
            return;
        }

        Member member = guild.getMemberById(playerData.getDiscordId());
        if (member == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: no encontré tu usuario en Discord (member=null). ¿Estás en el guild?");
            return;
        }

        String targetVoiceChannelID = getQueueVoiceChannelId(queueTypeEnum);
        VoiceChannel target = guild.getVoiceChannelById(targetVoiceChannelID);
        if (target == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: canal de voz de cola no encontrado. Revisa VoiceChannelConfig.");
            return;
        }

        GuildVoiceState vs = member.getVoiceState();
        if (vs == null || !vs.inAudioChannel()) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: debes estar conectado a un canal de voz para requeue.");
            return;
        }

        if (mcPlayer != null) {
            mcPlayer.sendMessage("§eRequeue: moviéndote a la cola §f" + pretty(queueTypeEnum) + "§e...");
        }

        // 1) Mover a canal
        guild.moveVoiceMember(member, target).queue(
                ok -> {
                    // 2) Intentar meterlo a la cola con delays (voiceState no se actualiza instant)
                    scheduleQueueAdd(playerData, queueTypeEnum, 5L);
                    scheduleQueueAdd(playerData, queueTypeEnum, 15L);
                    scheduleQueueAdd(playerData, queueTypeEnum, 30L);

                    if (mcPlayer != null) {
                        mcPlayer.sendMessage("§aRequeue: listo. Si no te metió, vuelve a clickear la perla.");
                    }
                },
                err -> {
                    if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: no pude moverte al canal de cola.");
                    err.printStackTrace();
                }
        );
    }

    private static void scheduleQueueAdd(PlayerData playerData, QueueType queueTypeEnum, long delayTicks) {
        try {
            Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
                try {
                    // OJO: addPlayerToQueue() valida canal de voz, por eso el delay.
                    discordBot.getQueueManager().addPlayerToQueue(playerData, queueTypeEnum);
                } catch (Exception ignored) {}
            }, delayTicks);
        } catch (Exception ignored) {}
    }

    private static String getQueueVoiceChannelId(QueueType queueTypeEnum) {
        switch (queueTypeEnum) {
            case TWO_VS_TWO:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2;
            case FIVE_VS_FIVE:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5;
            case EIGHT_VS_EIGHT:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8;
            default:
                return null;
        }
    }

    private static QueueType parseQueueType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        s = s.replace(" ", "").replace("_", "").replace("-", "");

        if (s.equals("2v2") || s.equals("2vs2") || s.equals("twovstwo")) return QueueType.TWO_VS_TWO;
        if (s.equals("5v5") || s.equals("5vs5") || s.equals("fivevsfive")) return QueueType.FIVE_VS_FIVE;
        if (s.equals("8v8") || s.equals("8vs8") || s.equals("eightvseight")) return QueueType.EIGHT_VS_EIGHT;

        // Soporta valores exactos del enum
        try {
            return QueueType.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {}

        return null;
    }

    private static String pretty(QueueType qt) {
        switch (qt) {
            case TWO_VS_TWO:
                return "2v2";
            case FIVE_VS_FIVE:
                return "5v5";
            case EIGHT_VS_EIGHT:
                return "8v8";
            default:
                return qt.name();
        }
    }

    private static Player getOnlinePlayer(PlayerData playerData) {
        try {
            if (playerData == null) return null;
            return Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
        } catch (Exception ignored) {
            return null;
        }
    }
}