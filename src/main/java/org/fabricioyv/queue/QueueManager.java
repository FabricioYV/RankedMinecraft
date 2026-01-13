package org.fabricioyv.queue;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.match.MatchManager;
import org.fabricioyv.match.MatchState;
import org.fabricioyv.model.PlayerData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class QueueManager {
    private final JDA jda;
    private final RankedMinecraft plugin;
    private final Guild guild;
    private final DiscordLogger logger;
    // Colas thread-safe
    private final List<PlayerData> queue5v5 = new CopyOnWriteArrayList<>();
    private final List<PlayerData> queue8v8 = new CopyOnWriteArrayList<>();

    // Tracking de jugadores en cola para evitar duplicados
    private final Set<String> playersInQueue = ConcurrentHashMap.newKeySet();

    // Instancia estática para acceso global
    private static QueueManager instance;

    public QueueManager(JDA jda, RankedMinecraft plugin, String guildId) {
        this.jda = jda;
        this.plugin = plugin;
        this.guild = jda.getGuildById(guildId);
        this.logger = new DiscordLogger(jda);
        if (this.guild == null) {
            throw new IllegalArgumentException("Guild not found with ID: " + guildId);
        }
        logger.info("QueueManager Inicializado",
                "Sistema de colas iniciado para el servidor: " + guild.getName());

        instance = this; // Establecer instancia estática
    }

    /**
     * Intenta agregar un jugador a la cola específica basada en el canal donde está
     */
    public QueueResult addPlayerToQueueFromChannel(PlayerData player, String channelId) {
        // Determinar tipo de cola según el canal
        QueueType queueType = getQueueTypeFromChannel(channelId);
        if (queueType == null) {
            return QueueResult.failure("Canal no válido para colas");
        }

        return addPlayerToQueue(player, queueType);


    }

    public QueueResult addPlayerToQueue(PlayerData player, QueueType queueType) {
        //Validacion 1 : Jugador no debe estar en partida activa.
        if(player.isInMatch()){
            return QueueResult.failure("El jugador ya está en una partida");
        }
        //Validacion 2: Jugador no debe estar en cola
        if(playersInQueue.contains(player.getMinecraftUuid())){
            return QueueResult.failure("El jugador ya está en cola");
        }
        //Validacion 3: debe estar en un canal de voz permitido
        if(!isPlayerInCorrectVoiceChannel(player.getDiscordId(), queueType)){
            return QueueResult.failure("Debes estar conectado al canal de voz correcto");
        }
//        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
//        if (mcPlayer == null || !mcPlayer.isOnline()) {
//            return QueueResult.failure("Debes estar conectado al servidor de Minecraft");
//        }

        //Agregar a la cola correspondiente
        List<PlayerData> targetQueue = (queueType == QueueType.FIVE_VS_FIVE) ? queue5v5 : queue8v8;

        synchronized (targetQueue) {
            targetQueue.add(player);
            playersInQueue.add(player.getMinecraftUuid());

            // Verificar si la cola está completa
            if (targetQueue.size() >= queueType.getRequiredPlayers()) {
                handleFullQueue(new ArrayList<>(targetQueue), queueType);
//                targetQueue.clear();
                // Los jugadores se removerán del tracking en handleFullQueue
            }
        }
        return QueueResult.success("Agregado a la cola " + getQueueTypeName(queueType) +
                " (" + getQueueSize(queueType) + "/" + queueType.getRequiredPlayers() + ")");
    }

    public boolean removePlayerFromQueue(String minecraftUuid) {
        boolean removed = false;

        // Remover de ambas colas
        removed |= queue5v5.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));
        removed |= queue8v8.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));

        // Remover del tracking
        playersInQueue.remove(minecraftUuid);

        return removed;
    }

    /**
     * Determina el tipo de cola según el canal de voz
     */
    private QueueType getQueueTypeFromChannel(String channelId) {
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5)) {
            return QueueType.FIVE_VS_FIVE;
        } else if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8)) {
            return QueueType.EIGHT_VS_EIGHT;
        }
        return null;
    }

    /**
     * Verifica si el jugador está en el canal correcto para el tipo de cola
     */
    private boolean isPlayerInCorrectVoiceChannel(String discordId, QueueType queueType) {
        Member member = guild.getMemberById(discordId);
        if (member == null) return false;

        AudioChannelUnion voiceState = member.getVoiceState().getChannel();
        if (voiceState == null) return false;

        String expectedChannelId = getRequiredChannelId(queueType);
        return voiceState.getId().equals(expectedChannelId);
    }

    /**
     * Obtiene el ID del canal requerido según el tipo de cola
     */
    private String getRequiredChannelId(QueueType queueType) {
        switch (queueType) {
            case FIVE_VS_FIVE:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5;
            case EIGHT_VS_EIGHT:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8;
            default:
                throw new IllegalArgumentException("Tipo de cola desconocido: " + queueType);
        }
    }

    /**
     * Obtiene el nombre legible del tipo de cola
     */
    private String getQueueTypeName(QueueType queueType) {
        switch (queueType) {
            case FIVE_VS_FIVE:
                return "5v5";
            case EIGHT_VS_EIGHT:
                return "8v8";
            default:
                return queueType.name();
        }
    }

    /**
     * NUEVO: Remueve jugador de todas las colas de forma forzada (para limpieza tras cancelaciones)
     */
    public void removePlayerFromAllQueues(String minecraftUuid) {
        boolean removed = false;

        // Remover de ambas colas
        removed |= queue5v5.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));
        removed |= queue8v8.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));

        // CRÍTICO: Remover del tracking de jugadores en cola
        playersInQueue.remove(minecraftUuid);

        if (removed) {
            logger.debug("Jugador Removido de Colas",
                    String.format("Jugador %s removido de todas las colas", minecraftUuid));
        }
    }
    private boolean hasSponsorRole(String discordId) {
        Member member = guild.getMemberById(discordId);
        if (member == null) return false;
        // Replace with your actual sponsor role IDs
        String SPONSOR_ROLE_ID = "1413243740231041174";
        String MAIN_SPONSOR_ROLE_ID = "1413241361087332505";
        return member.getRoles().stream().anyMatch(role ->
                role.getId().equals(SPONSOR_ROLE_ID) || role.getId().equals(MAIN_SPONSOR_ROLE_ID)
        );
    }
    /**
     * NUEVO: Limpieza forzada de jugador (para emergencias)
     */
    public void forceRemovePlayer(String minecraftUuid) {
        try {
            // Forzar remoción de colas
            removePlayerFromAllQueues(minecraftUuid);

            // También intentar mover al canal de espera si está conectado a Discord
            movePlayerToWaitingRoom(getDiscordIdByMinecraftUuid(minecraftUuid));

            logger.debug("Limpieza Forzada",
                    String.format("Limpieza forzada completada para jugador %s", minecraftUuid));

        } catch (Exception e) {
            logger.warning("Error en limpieza forzada",
                    String.format("Error en limpieza forzada de %s: %s", minecraftUuid, e.getMessage()));
        }
    }

    /**
     * NUEVO: Obtiene Discord ID por Minecraft UUID (helper method)
     */
    private String getDiscordIdByMinecraftUuid(String minecraftUuid) {
        // Buscar en todas las colas
        for (PlayerData player : queue5v5) {
            if (player.getMinecraftUuid().equals(minecraftUuid)) {
                return player.getDiscordId();
            }
        }
        for (PlayerData player : queue8v8) {
            if (player.getMinecraftUuid().equals(minecraftUuid)) {
                return player.getDiscordId();
            }
        }
        return null;
    }

    /**
     * CORREGIDO: Manejo de cola completa con mejor validación de estado
     */
    private void handleFullQueue(List<PlayerData> players, QueueType queueType) {
        // CRÍTICO: Verificar múltiples veces el estado del match para evitar race conditions
        if (MatchState.isMatchActive()) {
            logger.warning("Cola Completa", "No se puede iniciar partida: ya hay una activa");
            return;
        }

        // Intentar iniciar la partida con doble verificación
        if (!MatchState.startMatch()) {
            logger.warning("Cola Completa", "No se pudo iniciar partida: otra ya está en proceso o estado inconsistente");
            return;
        }

        // Verificación final del estado antes del countdown
        if (!MatchState.isMatchActive()) {
            logger.error("Estado Inconsistente", "El estado del match no es consistente después de startMatch()");
            return;
        }

        // NO vaciar la cola aquí - solo iniciar countdown
        startCountdown(new ArrayList<>(players), queueType);
    }

    /**
     * CORREGIDO: Countdown con mejor manejo de estado y limpieza
     */
    private void startCountdown(List<PlayerData> players, QueueType queueType) {
        new BukkitRunnable() {
            int countdown = 30;

            @Override
            public void run() {
                // CRÍTICO: Verificar estado del match en cada iteración
                if (!MatchState.isMatchActive()) {
                    logger.warning("Countdown Cancelado", "Estado del match ya no es activo, cancelando countdown");
                    this.cancel();
                    return;
                }

                if (countdown <= 0) {
                    handleCountdownEnd(queueType);
                    this.cancel();
                    return;
                }

                // Durante countdown - validaciones y notificaciones
                handleCountdownTick(queueType, countdown);
                countdown--;
            }
        }.runTaskTimer(RankedMinecraft.getInstance(), 0L, 20L);
    }

    /**
     * NUEVO: Maneja el final del countdown con validación completa
     */
    private void handleCountdownEnd(QueueType queueType) {
        List<PlayerData> targetQueue = (queueType == QueueType.FIVE_VS_FIVE) ? queue5v5 : queue8v8;

        synchronized (targetQueue) {
            List<PlayerData> connectedPlayers = new ArrayList<>();
            List<PlayerData> disconnectedPlayers = new ArrayList<>();

            // Validar jugadores que están ACTUALMENTE en la cola
            for (PlayerData playerData : new ArrayList<>(targetQueue)) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                boolean isConnectedToMC = mcPlayer != null && mcPlayer.isOnline();
                boolean isInCorrectChannel = isPlayerInCorrectVoiceChannel(playerData.getDiscordId(), queueType);

                if (isConnectedToMC && isInCorrectChannel) {
                    connectedPlayers.add(playerData);
                } else {
                    disconnectedPlayers.add(playerData);
                }
            }

            // Remover SOLO los jugadores desconectados
            cleanupDisconnectedPlayers(disconnectedPlayers, targetQueue);

            // Verificar si hay suficientes jugadores CONECTADOS
            if (connectedPlayers.size() >= queueType.getRequiredPlayers()) {
                startMatchWithPlayers(connectedPlayers, queueType, targetQueue);
            } else {
                cancelMatchDueToInsufficientPlayers(connectedPlayers, disconnectedPlayers, queueType, targetQueue);
            }
        }
    }

    /**
     * NUEVO: Limpia jugadores desconectados de la cola
     */
    private void cleanupDisconnectedPlayers(List<PlayerData> disconnectedPlayers, List<PlayerData> targetQueue) {
        for (PlayerData disconnected : disconnectedPlayers) {
            targetQueue.remove(disconnected);
            playersInQueue.remove(disconnected.getMinecraftUuid());

            // Mover a waiting room si es posible
            movePlayerToWaitingRoom(disconnected.getDiscordId());

            // Notificar al jugador si está conectado a MC
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(disconnected.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage("§c❌ Has sido removido de la cola por no estar en el canal de voz correcto.");
            }

            logger.queueEvent(
                    getPlayerDisplayName(disconnected),
                    disconnected.getDiscordId(),
                    "Removido de Cola",
                    "Removido por no estar en canal correcto al final del countdown"
            );
        }
    }

    /**
     * NUEVO: Inicia partida con jugadores validados
     */
    // Modify startMatchWithPlayers to prioritize sponsors
    private void startMatchWithPlayers(List<PlayerData> connectedPlayers, QueueType queueType, List<PlayerData> targetQueue) {
        int required = queueType.getRequiredPlayers();

        // Separate sponsors and non-sponsors
        List<PlayerData> sponsors = new ArrayList<>();
        List<PlayerData> nonSponsors = new ArrayList<>();
        for (PlayerData player : connectedPlayers) {
            if (hasSponsorRole(player.getDiscordId())) {
                sponsors.add(player);
            } else {
                nonSponsors.add(player);
            }
        }

        // Build the final list: all sponsors first, then fill with non-sponsors
        List<PlayerData> playersForMatch = new ArrayList<>();
        playersForMatch.addAll(sponsors);
        for (PlayerData player : nonSponsors) {
            if (playersForMatch.size() < required) {
                playersForMatch.add(player);
            } else {
                break;
            }
        }

        // If there are more sponsors than required, only take the first 'required' sponsors
        if (playersForMatch.size() > required) {
            playersForMatch = playersForMatch.subList(0, required);
        }

        try {
            MatchManager.startMatch(playersForMatch);

            // Remove only those who go to the match
            for (PlayerData playerInMatch : playersForMatch) {
                targetQueue.remove(playerInMatch);
                playersInQueue.remove(playerInMatch.getMinecraftUuid());
            }

            logger.matchEvent("QUEUE_" + queueType.name(), "Partida Iniciada",
                    "Partida iniciada con " + playersForMatch.size() + " jugadores. Cola restante: " + targetQueue.size(),
                    playersForMatch.size());

        } catch (Exception e) {
            logger.logError("Error iniciando partida desde cola", e);

            // Reset match state and keep players in queue
            MatchState.endMatch();

            for (PlayerData player : playersForMatch) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage("§c❌ Error iniciando partida. Sigues en la cola.");
                }
            }
        }
    }

    /**
     * NUEVO: Cancela partida por jugadores insuficientes
     */
    private void cancelMatchDueToInsufficientPlayers(List<PlayerData> connectedPlayers, List<PlayerData> disconnectedPlayers,
                                                     QueueType queueType, List<PlayerData> targetQueue) {
        // CRÍTICO: Resetear estado del match
        MatchState.endMatch();

        for (PlayerData connected : connectedPlayers) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(connected.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage("§c❌ Partida cancelada por falta de jugadores conectados. " +
                        "Sigues en la cola (" + targetQueue.size() + "/" + queueType.getRequiredPlayers() + ")");
            }
        }

        logger.warning("Partida Cancelada",
                "Insuficientes jugadores conectados (" + connectedPlayers.size() +
                        "/" + queueType.getRequiredPlayers() + "). " +
                        disconnectedPlayers.size() + " jugadores removidos. Cola actual: " + targetQueue.size());
    }

    /**
     * NUEVO: Maneja cada tick del countdown
     */
    private void handleCountdownTick(QueueType queueType, int countdown) {
        List<PlayerData> targetQueue = (queueType == QueueType.FIVE_VS_FIVE) ? queue5v5 : queue8v8;
        List<PlayerData> currentDisconnected = getCurrentDisconnectedPlayers(queueType);

        // Notificar a jugadores en la cola actual
        for (PlayerData playerData : new ArrayList<>(targetQueue)) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                String message = "§6¡Partida " + getQueueTypeName(queueType) + " iniciando en §c" + countdown + "§6 segundos!";

                if (!currentDisconnected.isEmpty() && countdown % 10 == 0) {
                    message += "\n§e⚠️ Esperando a: " + getDisconnectedPlayersNames(currentDisconnected);
                }

                mcPlayer.sendMessage(message);
            }
        }

        // Mencionar desconectados en momentos clave
        if (!currentDisconnected.isEmpty() && (countdown == 20 || countdown == 10 || countdown <= 5)) {
            mentionDisconnectedPlayers(currentDisconnected, countdown);
        }
    }
    /**
     * Método estático para limpiar SOLO el tracking de jugadores cuando termina una partida
     * Los jugadores ya fueron removidos de las colas cuando inició la partida
     */
    public static void cleanupPlayerTrackingAfterMatch(List<PlayerData> players) {
        if (instance == null) {
            return; // No hay instancia activa
        }

        int cleanedCount = 0;
        for (PlayerData player : players) {
            // SOLO limpiar el tracking, NO las colas (ya fueron removidos al iniciar partida)
            boolean wasTracked = instance.playersInQueue.remove(player.getMinecraftUuid());
            if (wasTracked) {
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            Bukkit.getLogger().info("[QueueManager] Limpiado tracking de " + cleanedCount +
                    " jugadores tras finalizar partida (colas no afectadas)");
        }
    }

    /**
     * DEPRECATED: Usar cleanupPlayerTrackingAfterMatch() en su lugar
     * Método estático para limpiar jugadores de la cola cuando termina una partida
     * Debe ser llamado desde MatchFinisher
     */
    @Deprecated
    public static void removePlayersFromQueueAfterMatch(List<PlayerData> players) {
        if (instance == null) {
            return; // No hay instancia activa
        }

        for (PlayerData player : players) {
            boolean removed = instance.removePlayerFromQueue(player.getMinecraftUuid());
            if (removed) {
                Bukkit.getLogger().info("[QueueManager] Jugador " + player.getMinecraftUuid().substring(0, 8) +
                        " removido de cola tras finalizar partida");
            }
        }
    }




    private void movePlayerToWaitingRoom(String discordId) {
        Member member = guild.getMemberById(discordId);
        if (member == null) return;

        VoiceChannel waitingRoom = guild.getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);
        if (waitingRoom == null) return;

        guild.moveVoiceMember(member, waitingRoom).queue();
    }

    /**
     * Obtiene el tamaño actual de una cola
     */
    public int getQueueSize(QueueType queueType) {
        return (queueType == QueueType.FIVE_VS_FIVE) ? queue5v5.size() : queue8v8.size();
    }

    /**
     * Obtiene información de todas las colas
     */
    public String getQueueInfo() {
        return String.format("Colas activas:\n5v5: %d/%d jugadores\n8v8: %d/%d jugadores",
                queue5v5.size(), QueueType.FIVE_VS_FIVE.getRequiredPlayers(),
                queue8v8.size(), QueueType.EIGHT_VS_EIGHT.getRequiredPlayers());
    }
    /**
     * Obtiene el nombre de display de un jugador
     */
    private String getPlayerDisplayName(PlayerData player) {
        try {
            // Intentar obtener nombre de Minecraft
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }

            // Fallback a Discord
            if (guild != null) {
                net.dv8tion.jda.api.entities.Member discordMember = guild.getMemberById(player.getDiscordId());
                if (discordMember != null) {
                    return discordMember.getEffectiveName();
                }
            }
        } catch (Exception e) {
            // Ignore y usar fallback
        }

        return "UUID:" + player.getMinecraftUuid().substring(0, 8);
    }
    /**
     * NUEVO: Obtiene jugadores actualmente desconectados de una cola específica
     */
    private List<PlayerData> getCurrentDisconnectedPlayers(QueueType queueType) {
        List<PlayerData> targetQueue = (queueType == QueueType.FIVE_VS_FIVE) ? queue5v5 : queue8v8;
        List<PlayerData> disconnectedPlayers = new ArrayList<>();

        for (PlayerData playerData : targetQueue) {
            try {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                boolean isConnectedToMC = mcPlayer != null && mcPlayer.isOnline();
                boolean isInCorrectChannel = isPlayerInCorrectVoiceChannel(playerData.getDiscordId(), queueType);

                if (!isConnectedToMC || !isInCorrectChannel) {
                    disconnectedPlayers.add(playerData);
                }
            } catch (Exception e) {
                // Si hay error verificando, considerar como desconectado
                disconnectedPlayers.add(playerData);
            }
        }

        return disconnectedPlayers;
    }

    /**
     * NUEVO: Obtiene nombres de jugadores desconectados para mostrar en mensajes
     */
    private String getDisconnectedPlayersNames(List<PlayerData> disconnectedPlayers) {
        if (disconnectedPlayers.isEmpty()) {
            return "";
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < disconnectedPlayers.size(); i++) {
            if (i > 0) names.append(", ");
            names.append(getPlayerDisplayName(disconnectedPlayers.get(i)));
        }

        return names.toString();
    }

    /**
     * Menciona a los jugadores desconectados en Discord via mensaje privado
     */
    private void mentionDisconnectedPlayers(List<PlayerData> disconnectedPlayers, int countdown) {
        for (PlayerData player : disconnectedPlayers) {
            try {
                Member member = guild.getMemberById(player.getDiscordId());
                if (member == null) continue;

                String message = String.format(
                        "⚠️ **¡Partida iniciando en %d segundos!**\n" +
                                "Necesitas conectarte al servidor de Minecraft ahora para no perder tu lugar en la cola.\n" +
                                "**IP del servidor:** keke1.sparked.network",
                        countdown
                );

                // Enviar mensaje privado
                member.getUser().openPrivateChannel().queue(
                        privateChannel -> {
                            privateChannel.sendMessage(message).queue(
                                    success -> {
                                        logger.info("Mensaje Privado Enviado",
                                                "Notificación enviada a " + member.getEffectiveName() + " (ID: " + player.getDiscordId() + ")");
                                    },
                                    error -> {
                                        logger.warning("Error Mensaje Privado",
                                                "No se pudo enviar mensaje a " + member.getEffectiveName() + ": " + error.getMessage());

                                        // Fallback: mencionar en canal de logs
                                        logger.warning("Jugador Desconectado",
                                                String.format("<@%s> ¡Partida iniciando en %d segundos! Conéctate al servidor.",
                                                        player.getDiscordId(), countdown));
                                    }
                            );
                        },
                        error -> {
                            logger.warning("Error Canal Privado",
                                    "No se pudo abrir canal privado con " + member.getEffectiveName() + ": " + error.getMessage());
                        }
                );

            } catch (Exception e) {
                logger.systemError("QueueManager",
                        "Error enviando mensaje privado",
                        "Player: " + player.getDiscordId() + " - Error: " + e.getMessage());
            }
        }
    }
}