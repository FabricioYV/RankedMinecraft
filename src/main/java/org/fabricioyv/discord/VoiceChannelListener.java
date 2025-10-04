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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * VoiceChannelListener OPTIMIZADO - No bloquea threads de Discord
 */
public class VoiceChannelListener extends ListenerAdapter {
    private final QueueManager queueManager;
    private final DiscordLogger logger;

    // Cache de Discord ID → PlayerData para evitar consultas repetidas
    private static final ConcurrentHashMap<String, PlayerData> discordPlayerCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastCacheUpdate = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION = 300000; // 5 minutos

    // Executor para operaciones asíncronas
    private static final ScheduledExecutorService asyncExecutor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "VoiceListener-Async");
        t.setDaemon(true);
        return t;
    });

    public VoiceChannelListener(QueueManager queueManager, DiscordLogger discordLogger) {
        this.queueManager = queueManager;
        this.logger = discordLogger;

        // Inicializar limpieza de cache cada 10 minutos
        asyncExecutor.scheduleAtFixedRate(this::cleanExpiredCache, 10, 10, TimeUnit.MINUTES);
    }

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Member member = event.getMember();
        AudioChannelUnion oldChannel = event.getChannelLeft();
        AudioChannelUnion newChannel = event.getChannelJoined();
        String discordId = member.getId();

        // OPTIMIZACIÓN: Verificar cache primero
        PlayerData cachedPlayer = getCachedPlayerData(discordId);

        if (cachedPlayer != null) {
            // Cache hit - procesar inmediatamente
            processVoiceUpdate(member, oldChannel, newChannel, cachedPlayer);
        } else {
            // Cache miss - consultar asíncronamente
            getPlayerDataAsync(discordId).thenAccept(playerData -> {
                if (playerData != null) {
                    processVoiceUpdate(member, oldChannel, newChannel, playerData);
                }
                // Si playerData es null, el usuario no está registrado - ignorar
            }).exceptionally(throwable -> {
                // Log error pero no fallar
                logger.logError("Error obteniendo datos de jugador Discord ID " + discordId, throwable);
                return null;
            });
        }
    }

    /**
     * Obtiene PlayerData del cache si está disponible y válido
     */
    private PlayerData getCachedPlayerData(String discordId) {
        PlayerData cached = discordPlayerCache.get(discordId);
        if (cached == null) return null;

        Long lastUpdate = lastCacheUpdate.get(discordId);
        if (lastUpdate == null || (System.currentTimeMillis() - lastUpdate) > CACHE_DURATION) {
            // Cache expirado - remover
            discordPlayerCache.remove(discordId);
            lastCacheUpdate.remove(discordId);
            return null;
        }

        return cached;
    }

    /**
     * Obtiene PlayerData asíncronamente y actualiza el cache
     */
    private CompletableFuture<PlayerData> getPlayerDataAsync(String discordId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                PlayerData playerData = DatabaseManager.getPlayerByDiscordId(discordId);
                if (playerData != null) {
                    // Actualizar cache
                    discordPlayerCache.put(discordId, playerData);
                    lastCacheUpdate.put(discordId, System.currentTimeMillis());
                }
                return playerData;
            } catch (Exception e) {
                // Log pero no fallar
                System.err.println("Error async obteniendo PlayerData para Discord ID " + discordId + ": " + e.getMessage());
                return null;
            }
        }, asyncExecutor);
    }

    /**
     * Procesa el cambio de canal de voz
     */
    private void processVoiceUpdate(Member member, AudioChannelUnion oldChannel, AudioChannelUnion newChannel, PlayerData playerData) {
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

        // OPTIMIZADO: Si entró a un canal de cola, verificar estado EN MEMORIA (no BD)
        if (newChannel != null && isQueueChannel(newChannel.getId())) {
            // CRÍTICO: El cache puede estar obsoleto, así que lo invalidamos y obtenemos datos frescos
            String discordId = member.getId();

            // Invalidar cache viejo para forzar obtención de datos actualizados
            discordPlayerCache.remove(discordId);
            lastCacheUpdate.remove(discordId);

            // Obtener datos frescos de BD (que tiene el estado actualizado de memoria)
            getPlayerDataAsync(discordId).thenAccept(freshPlayerData -> {
                if (freshPlayerData == null) {
                    logger.warning("Player Data Not Found",
                        "No se encontraron datos para " + member.getEffectiveName());
                    return;
                }

                // VERIFICACIÓN DIRECTA EN MEMORIA: usar el PlayerData fresco que tiene el estado actual
                if (freshPlayerData.isInMatch()) {
                    // Jugador está en partida - bloquear acceso
                    String queueTypeName = getQueueTypeName(newChannel.getId());
                    sendMinecraftMessage(freshPlayerData,
                        "§c❌ No puedes entrar a la cola " + queueTypeName + " mientras estás en una partida activa.");

                    logger.queueEvent(
                        member.getEffectiveName(),
                        member.getId(),
                        "Acceso Denegado",
                        "Intento de entrar a cola " + queueTypeName + " bloqueado - jugador en partida activa"
                    );

                    moveToWaitingRoomDelayed(member);
                } else {
                    // Jugador NO está en partida - permitir acceso a cola
                    logger.info("Queue Entry Allowed",
                        member.getEffectiveName() + " verificado como disponible - permitiendo acceso a cola");

                    // Procesar entrada a cola con datos frescos
                    processQueueEntry(member, newChannel, freshPlayerData);
                }
            }).exceptionally(throwable -> {
                logger.logError("Error verificando estado de jugador", throwable);
                // En caso de error, NO bloquear - mejor permitir el acceso
                processQueueEntry(member, newChannel, playerData);
                return null;
            });
        }
    }

    /**
     * Procesa la entrada a una cola de forma separada para reutilización
     */
    private void processQueueEntry(Member member, AudioChannelUnion newChannel, PlayerData playerData) {
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

    /**
     * Limpia el cache de jugadores Discord expirado
     */
    private void cleanExpiredCache() {
        long now = System.currentTimeMillis();
        lastCacheUpdate.forEach((discordId, lastUpdate) -> {
            if (now - lastUpdate > CACHE_DURATION) {
                discordPlayerCache.remove(discordId);
                lastCacheUpdate.remove(discordId);
            }
        });
    }


    /**
     * Invalida el cache para múltiples jugadores por Discord ID
     * Útil cuando termina una partida y necesitamos actualizar el estado de todos los jugadores
     */
    public static void invalidatePlayersCache(java.util.List<String> discordIds) {
        for (String discordId : discordIds) {
            discordPlayerCache.remove(discordId);
            lastCacheUpdate.remove(discordId);
        }
    }

}
