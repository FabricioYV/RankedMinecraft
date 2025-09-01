package org.fabricioyv.discord;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.queue.QueueResult;

public class VoiceChannelListener extends ListenerAdapter {
    private final QueueManager queueManager;
    private final DiscordLogger logger;
    public VoiceChannelListener(QueueManager queueManager, DiscordLogger discordLogger) {
        this.queueManager = queueManager;
        this.logger = discordLogger;
    }
    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Member member = event.getMember();
        AudioChannelUnion oldChannel = event.getChannelLeft();
        AudioChannelUnion newChannel = event.getChannelJoined();

        // Obtener datos del jugador desde la base de datos
        PlayerData playerData = DatabaseManager.getPlayerByDiscordId(member.getId());
        if (playerData == null) {
            // Usuario no registrado, ignorar
            return;
        }

        // Si salió de un canal de cola, remover de la cola
        if (oldChannel != null && isQueueChannel(oldChannel.getId())) {
            boolean removed = queueManager.removePlayerFromQueue(playerData.getMinecraftUuid());
            if (removed) {
                sendMinecraftMessage(playerData, "§eHas salido de la cola al cambiar de canal.");
                // Log de salida de cola
                logger.queueEvent(
                        member.getEffectiveName(),
                        member.getId(),
                        "Salió de Cola",
                        "Salió de cola " + getQueueTypeName(oldChannel.getId()) + " al cambiar de canal"
                );
            }
        }

        // Si entró a un canal de cola, intentar agregar a la cola
        if (newChannel != null && isQueueChannel(newChannel.getId())) {
            QueueResult result = queueManager.addPlayerToQueueFromChannel(playerData, newChannel.getId());

            // Enviar mensaje al jugador en Minecraft
            String queueTypeName = getQueueTypeName(newChannel.getId());
            if (result.isSuccess()) {
                sendMinecraftMessage(playerData, "§a✅ Te has unido a la cola " + queueTypeName + "!");
                sendMinecraftMessage(playerData, "§7" + result.getMessage());
                // Log de entrada exitosa a cola
                logger.queueEvent(
                        member.getEffectiveName(),
                        member.getId(),
                        "Entró a Cola",
                        "Se unió exitosamente a cola " + queueTypeName + " - " + result.getMessage()
                );
            } else {
                sendMinecraftMessage(playerData, "§c❌ No pudiste unirte a la cola " + queueTypeName + ":");
                sendMinecraftMessage(playerData, "§c" + result.getMessage());
                // Log de entrada fallida a cola
                logger.queueEvent(
                        member.getEffectiveName(),
                        member.getId(),
                        "Error en Cola",
                        "Falló al unirse a cola " + queueTypeName + " - " + result.getMessage()
                );
                // Si falló, mover a sala de espera después de un breve delay
                if (!result.getMessage().contains("ya está en")) {
                    moveToWaitingRoomDelayed(member);
                }
            }
        }
    }

    /**
     * Verifica si un canal es un canal de cola
     */
    private boolean isQueueChannel(String channelId) {
        return channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5) ||
                channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8);
    }

    /**
     * Obtiene el nombre del tipo de cola según el canal
     */
    private String getQueueTypeName(String channelId) {
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5)) {
            return "5v5";
        } else if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8)) {
            return "8v8";
        }
        return "desconocida";
    }

    /**
     * Envía un mensaje al jugador en Minecraft si está conectado
     */
    private void sendMinecraftMessage(PlayerData playerData, String message) {
        org.bukkit.entity.Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                java.util.UUID.fromString(playerData.getMinecraftUuid())
        );
        if (mcPlayer != null && mcPlayer.isOnline()) {
            mcPlayer.sendMessage(message);
        }
    }
    private void moveToWaitingRoomDelayed(Member member) {
        // Usar un delay para evitar conflictos con el evento de voz
        new Thread(() -> {
            try {
                Thread.sleep(1000); // 1 segundo de delay

                net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel waitingRoom =
                        member.getGuild().getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);

                if (waitingRoom != null) {
                    member.getGuild().moveVoiceMember(member, waitingRoom).queue();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

}
