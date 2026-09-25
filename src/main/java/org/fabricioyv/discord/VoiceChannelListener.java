package org.fabricioyv.discord;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.queue.QueueResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VoiceChannelListener extends ListenerAdapter {

    private static final String PREFIX = "§8[§6Keke Ranked§8]§r ";

    private final QueueManager queueManager;
    private final DiscordLogger logger;

    // Nota: antes esta clase mantenía su propio cache de PlayerData por Discord ID
    // (TTL de 5 min, invalidación manual). Era un duplicado de PlayerDataCache
    // (mismo shape, mismos datos) que podía quedar desincronizado del caché
    // "real" -- ahora usa DatabaseManager.getPlayerByDiscordIdAsync(), que ya
    // consulta/puebla PlayerDataCache internamente. Una sola fuente de verdad.
    private static final ScheduledExecutorService asyncExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "VoiceListener-Async");
        t.setDaemon(true);
        return t;
    });

    public VoiceChannelListener(QueueManager queueManager, DiscordLogger discordLogger) {
        this.queueManager = queueManager;
        this.logger = discordLogger;
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Member member = event.getMember();
        AudioChannelUnion oldChannel = event.getChannelLeft();
        AudioChannelUnion newChannel = event.getChannelJoined();
        String discordId = member.getId();

        DatabaseManager.getPlayerByDiscordIdAsync(discordId).thenAccept(playerData -> {
            if (playerData != null) {
                processVoiceUpdate(member, oldChannel, newChannel, playerData);
            }
        }).exceptionally(throwable -> {
            logger.logError("Error obteniendo datos de jugador Discord ID " + discordId, throwable);
            return null;
        });
    }

    private void processVoiceUpdate(Member member, AudioChannelUnion oldChannel, AudioChannelUnion newChannel, PlayerData playerData) {

        // Salió de canal cola -> remover (sin mensaje feo al jugador)
        if (oldChannel != null && isQueueChannel(oldChannel.getId())) {
            boolean removed = queueManager.removePlayerFromQueue(playerData.getMinecraftUuid());
            if (removed) {
                logger.queueEvent(
                        member.getEffectiveName(),
                        member.getId(),
                        "Salió de Cola",
                        "Salió de cola " + getQueueTypeName(oldChannel.getId()) + " al cambiar de canal"
                );
            }
        }

        // Entró a canal cola
        if (newChannel != null && isQueueChannel(newChannel.getId())) {
            String discordId = member.getId();
            PlayerDataCache.invalidateByDiscordId(discordId);

            DatabaseManager.getPlayerByDiscordIdAsync(discordId).thenAccept(freshPlayerData -> {
                if (freshPlayerData == null) return;

                String queueTypeName = getQueueTypeName(newChannel.getId());

                if (freshPlayerData.isInMatch()) {
                    sendMinecraftMessage(freshPlayerData,
                            PREFIX + "§cNo puedes entrar a la cola §6" + queueTypeName + " §cmientras estás en una partida.");
                    logger.queueEvent(
                            member.getEffectiveName(),
                            member.getId(),
                            "Acceso Denegado",
                            "Intento de entrar a cola " + queueTypeName + " bloqueado - jugador en partida activa"
                    );
                    moveToWaitingRoomDelayed(member);
                } else {
                    processQueueEntry(member, newChannel, freshPlayerData);
                }
            }).exceptionally(throwable -> {
                logger.logError("Error verificando estado de jugador", throwable);
                processQueueEntry(member, newChannel, playerData);
                return null;
            });
        }
    }

    private void processQueueEntry(Member member, AudioChannelUnion newChannel, PlayerData playerData) {
        QueueResult result = queueManager.addPlayerToQueueFromChannel(playerData, newChannel.getId());
        String queueTypeName = getQueueTypeName(newChannel.getId());

        if (result.isSuccess()) {
            // no mandamos mensajes tipo “te uniste / agregado...”
            // QueueManager ya hace el broadcast global bonito
            logger.queueEvent(
                    member.getEffectiveName(),
                    member.getId(),
                    "Entró a Cola",
                    "Se unió exitosamente a cola " + queueTypeName + " - " + result.getMessage()
            );
        } else {
            // solo en error le avisamos al jugador (corto y limpio)
            sendMinecraftMessage(playerData, PREFIX + "§cNo pudiste unirte a la cola §6" + queueTypeName + "§c.");
            sendMinecraftMessage(playerData, "§7" + result.getMessage());

            logger.queueEvent(
                    member.getEffectiveName(),
                    member.getId(),
                    "Error en Cola",
                    "Falló al unirse a cola " + queueTypeName + " - " + result.getMessage()
            );

            if (!result.getMessage().contains("ya está en")) {
                moveToWaitingRoomDelayed(member);
            }
        }
    }

    private boolean isQueueChannel(String channelId) {
        return channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2)
                || channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5)
                || channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8);
    }

    private String getQueueTypeName(String channelId) {
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2)) return "2v2";
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5)) return "5v5";
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8)) return "8v8";
        return "desconocida";
    }

    private void sendMinecraftMessage(PlayerData playerData, String message) {
        org.bukkit.entity.Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                java.util.UUID.fromString(playerData.getMinecraftUuid())
        );
        if (mcPlayer != null && mcPlayer.isOnline()) {
            mcPlayer.sendMessage(message);
        }
    }

    private void moveToWaitingRoomDelayed(Member member) {
        // antes tenías new Thread + sleep. ahora usamos el scheduler que ya existe
        asyncExecutor.schedule(() -> {
            try {
                VoiceChannel waitingRoom =
                        member.getGuild().getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);

                if (waitingRoom != null) {
                    member.getGuild().moveVoiceMember(member, waitingRoom).queue();
                }
            } catch (Exception ignored) {}
        }, 1, TimeUnit.SECONDS);
    }

    public static void invalidatePlayersCache(java.util.List<String> discordIds) {
        for (String discordId : discordIds) {
            PlayerDataCache.invalidateByDiscordId(discordId);
        }
    }
}