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

    private static final String PREFIX = "§8[§6Keke Ranked§8]§r ";

    private final JDA jda;
    private final RankedMinecraft plugin;
    private final Guild guild;
    private final DiscordLogger logger;

    // Colas thread-safe
    private final List<PlayerData> queue2v2 = new CopyOnWriteArrayList<>();
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

        logger.info("QueueManager", "Sistema de colas iniciado para: " + guild.getName());
        instance = this;
    }

    /**
     * Intenta agregar un jugador a la cola específica basada en el canal donde está
     */
    public QueueResult addPlayerToQueueFromChannel(PlayerData player, String channelId) {
        QueueType queueType = getQueueTypeFromChannel(channelId);
        if (queueType == null) {
            return QueueResult.failure("Canal no válido para colas");
        }
        return addPlayerToQueue(player, queueType);
    }

    public QueueResult addPlayerToQueue(PlayerData player, QueueType queueType) {
        if (player == null) return QueueResult.failure("Jugador inválido");

        if (player.isInMatch()) {
            return QueueResult.failure("Ya estás en una partida");
        }

        if (playersInQueue.contains(player.getMinecraftUuid())) {
            return QueueResult.failure("Ya estás en una cola");
        }

        if (!isPlayerInCorrectVoiceChannel(player.getDiscordId(), queueType)) {
            return QueueResult.failure("Debes estar conectado al canal de voz correcto");
        }

        List<PlayerData> targetQueue = getQueueList(queueType);

        int sizeAfterAdd;
        int required = queueType.getRequiredPlayers();
        String mcName = getMinecraftPlayerNameSafe(player.getMinecraftUuid());

        synchronized (targetQueue) {
            targetQueue.add(player);
            playersInQueue.add(player.getMinecraftUuid());
            sizeAfterAdd = targetQueue.size();
        }

        broadcastQueueJoin(mcName, queueType, sizeAfterAdd, required);

        if (sizeAfterAdd >= required) {
            handleFullQueue(new ArrayList<>(targetQueue), queueType);
        }

        // Por si después lo quieres mostrar en comandos
        return QueueResult.success("En cola " + getQueueTypeName(queueType) +
                " (" + getQueueSize(queueType) + "/" + required + ")");
    }

    public boolean removePlayerFromQueue(String minecraftUuid) {
        if (minecraftUuid == null) return false;

        boolean removed = false;
        QueueType removedType = null;
        int sizeAfterRemove = -1;
        int required = -1;

        // 2v2
        synchronized (queue2v2) {
            boolean r = queue2v2.removeIf(p -> minecraftUuid.equals(p.getMinecraftUuid()));
            if (r) {
                removed = true;
                removedType = QueueType.TWO_VS_TWO;
                sizeAfterRemove = queue2v2.size();
                required = QueueType.TWO_VS_TWO.getRequiredPlayers();
            }
        }

        // 5v5
        if (!removed) {
            synchronized (queue5v5) {
                boolean r = queue5v5.removeIf(p -> minecraftUuid.equals(p.getMinecraftUuid()));
                if (r) {
                    removed = true;
                    removedType = QueueType.FIVE_VS_FIVE;
                    sizeAfterRemove = queue5v5.size();
                    required = QueueType.FIVE_VS_FIVE.getRequiredPlayers();
                }
            }
        }

        // 8v8
        if (!removed) {
            synchronized (queue8v8) {
                boolean r = queue8v8.removeIf(p -> minecraftUuid.equals(p.getMinecraftUuid()));
                if (r) {
                    removed = true;
                    removedType = QueueType.EIGHT_VS_EIGHT;
                    sizeAfterRemove = queue8v8.size();
                    required = QueueType.EIGHT_VS_EIGHT.getRequiredPlayers();
                }
            }
        }

        if (removed) {
            playersInQueue.remove(minecraftUuid);
            String mcName = getMinecraftPlayerNameSafe(minecraftUuid);
            broadcastQueueLeave(mcName, removedType, sizeAfterRemove, required);
        } else {
            playersInQueue.remove(minecraftUuid); // limpieza defensiva
        }

        return removed;
    }

    private List<PlayerData> getQueueList(QueueType queueType) {
        if (queueType == QueueType.TWO_VS_TWO) return queue2v2;
        if (queueType == QueueType.FIVE_VS_FIVE) return queue5v5;
        return queue8v8;
    }

    private QueueType getQueueTypeFromChannel(String channelId) {
        if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2)) {
            return QueueType.TWO_VS_TWO;
        } else if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5)) {
            return QueueType.FIVE_VS_FIVE;
        } else if (channelId.equals(VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8)) {
            return QueueType.EIGHT_VS_EIGHT;
        }
        return null;
    }

    private boolean isPlayerInCorrectVoiceChannel(String discordId, QueueType queueType) {
        if (guild == null) return false;

        Member member = guild.getMemberById(discordId);
        if (member == null || member.getVoiceState() == null) return false;

        AudioChannelUnion voiceState = member.getVoiceState().getChannel();
        if (voiceState == null) return false;

        String expectedChannelId = getRequiredChannelId(queueType);
        return voiceState.getId().equals(expectedChannelId);
    }

    private String getRequiredChannelId(QueueType queueType) {
        switch (queueType) {
            case TWO_VS_TWO:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2;
            case FIVE_VS_FIVE:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5;
            case EIGHT_VS_EIGHT:
                return VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8;
            default:
                throw new IllegalArgumentException("Tipo de cola desconocido: " + queueType);
        }
    }

    private String getQueueTypeName(QueueType queueType) {
        switch (queueType) {
            case TWO_VS_TWO: return "2v2";
            case FIVE_VS_FIVE: return "5v5";
            case EIGHT_VS_EIGHT: return "8v8";
            default: return queueType.name();
        }
    }

    private void broadcastQueueJoin(String mcName, QueueType queueType, int size, int required) {
        String queueName = getQueueTypeName(queueType);
        String msg = "§8[§6Keke Ranked§8] §f" + mcName + " §7se unió a §e" + queueName +
                " §8(§b" + size + "§7/§b" + required + "§8)";
        broadcastSync(msg);
    }

    private void broadcastQueueLeave(String mcName, QueueType queueType, int size, int required) {
        String queueName = getQueueTypeName(queueType);
        String msg = "§8[§6Keke Ranked§8] §f" + mcName + " §7salió de §e" + queueName +
                " §8(§b" + size + "§7/§b" + required + "§8)";
        broadcastSync(msg);
    }

    private void broadcastSync(String message) {
        if (plugin == null) {
            Bukkit.broadcastMessage(message);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(message));
    }

    private String getMinecraftPlayerNameSafe(String minecraftUuid) {
        try {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(minecraftUuid));
            if (mcPlayer != null) return mcPlayer.getName();

            String offlineName = Bukkit.getOfflinePlayer(UUID.fromString(minecraftUuid)).getName();
            if (offlineName != null && !offlineName.isEmpty()) return offlineName;
        } catch (Exception ignored) {}
        return "Jugador";
    }

    public void removePlayerFromAllQueues(String minecraftUuid) {
        boolean removed = false;

        removed |= queue2v2.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));
        removed |= queue5v5.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));
        removed |= queue8v8.removeIf(p -> p.getMinecraftUuid().equals(minecraftUuid));

        playersInQueue.remove(minecraftUuid);

        if (removed) {
            logger.debug("QueueManager", "Jugador removido de todas las colas: " + minecraftUuid);
        }
    }

    public void forceRemovePlayer(String minecraftUuid) {
        try {
            removePlayerFromAllQueues(minecraftUuid);
            movePlayerToWaitingRoom(getDiscordIdByMinecraftUuid(minecraftUuid));
            logger.debug("QueueManager", "Limpieza forzada completada para " + minecraftUuid);
        } catch (Exception e) {
            logger.warning("QueueManager", "Error en limpieza forzada: " + e.getMessage());
        }
    }

    private String getDiscordIdByMinecraftUuid(String minecraftUuid) {
        for (PlayerData player : queue2v2) {
            if (player.getMinecraftUuid().equals(minecraftUuid)) return player.getDiscordId();
        }
        for (PlayerData player : queue5v5) {
            if (player.getMinecraftUuid().equals(minecraftUuid)) return player.getDiscordId();
        }
        for (PlayerData player : queue8v8) {
            if (player.getMinecraftUuid().equals(minecraftUuid)) return player.getDiscordId();
        }
        return null;
    }

    private void handleFullQueue(List<PlayerData> players, QueueType queueType) {
        if (MatchState.isMatchActive()) {
            logger.warning("QueueManager", "No se puede iniciar: ya hay una partida activa");
            return;
        }

        if (!MatchState.startMatch()) {
            logger.warning("QueueManager", "No se pudo iniciar: estado inconsistente");
            return;
        }

        startCountdown(new ArrayList<>(players), queueType);
    }

    private void startCountdown(List<PlayerData> players, QueueType queueType) {
        new BukkitRunnable() {
            int countdown = 30;

            @Override
            public void run() {
                if (!MatchState.isMatchActive()) {
                    logger.warning("QueueManager", "Countdown cancelado: el estado ya no es activo");
                    cancel();
                    return;
                }

                if (countdown <= 0) {
                    handleCountdownEnd(queueType);
                    cancel();
                    return;
                }

                handleCountdownTick(queueType, countdown);
                countdown--;
            }
        }.runTaskTimer(RankedMinecraft.getInstance(), 0L, 20L);
    }

    private void handleCountdownEnd(QueueType queueType) {
        List<PlayerData> targetQueue = getQueueList(queueType);

        synchronized (targetQueue) {
            List<PlayerData> connectedPlayers = new ArrayList<>();
            List<PlayerData> disconnectedPlayers = new ArrayList<>();

            for (PlayerData playerData : new ArrayList<>(targetQueue)) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                boolean isConnectedToMC = mcPlayer != null && mcPlayer.isOnline();
                boolean isInCorrectChannel = isPlayerInCorrectVoiceChannel(playerData.getDiscordId(), queueType);

                if (isConnectedToMC && isInCorrectChannel) connectedPlayers.add(playerData);
                else disconnectedPlayers.add(playerData);
            }

            cleanupDisconnectedPlayers(disconnectedPlayers, targetQueue);

            if (connectedPlayers.size() >= queueType.getRequiredPlayers()) {
                startMatchWithPlayers(connectedPlayers, queueType, targetQueue);
            } else {
                MatchState.endMatch();
                logger.warning("QueueManager",
                        "Partida cancelada: conectados " + connectedPlayers.size() + "/" + queueType.getRequiredPlayers());
            }
        }
    }

    private void cleanupDisconnectedPlayers(List<PlayerData> disconnectedPlayers, List<PlayerData> targetQueue) {
        for (PlayerData disconnected : disconnectedPlayers) {
            targetQueue.remove(disconnected);
            playersInQueue.remove(disconnected.getMinecraftUuid());

            movePlayerToWaitingRoom(disconnected.getDiscordId());

            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(disconnected.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(PREFIX + "§cFuiste removido de la cola por no estar en el canal correcto.");
            }
        }
    }

    private void startMatchWithPlayers(List<PlayerData> connectedPlayers, QueueType queueType, List<PlayerData> targetQueue) {
        int required = queueType.getRequiredPlayers();

        List<PlayerData> playersForMatch = new ArrayList<>(connectedPlayers);
        if (playersForMatch.size() > required) {
            playersForMatch = playersForMatch.subList(0, required);
        }

        try {
            MatchManager.startMatch(playersForMatch);

            for (PlayerData p : playersForMatch) {
                targetQueue.remove(p);
                playersInQueue.remove(p.getMinecraftUuid());
            }

            logger.matchEvent("QUEUE_" + queueType.name(), "Match iniciado",
                    "Partida iniciada con " + playersForMatch.size() + " jugadores", playersForMatch.size());

        } catch (Exception e) {
            logger.logError("QueueManager", e);
            MatchState.endMatch();
        }
    }

    private void handleCountdownTick(QueueType queueType, int countdown) {
        List<PlayerData> targetQueue = getQueueList(queueType);

        for (PlayerData playerData : new ArrayList<>(targetQueue)) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(PREFIX + "§7Iniciando §f" + getQueueTypeName(queueType) +
                        " §7en §f" + countdown + "§7s...");
            }
        }
    }

    private void movePlayerToWaitingRoom(String discordId) {
        if (discordId == null) return;

        Member member = guild.getMemberById(discordId);
        if (member == null) return;

        VoiceChannel waitingRoom = guild.getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);
        if (waitingRoom == null) return;

        guild.moveVoiceMember(member, waitingRoom).queue();
    }

    public int getQueueSize(QueueType queueType) {
        return getQueueList(queueType).size();
    }

    public String getQueueInfo() {
        return String.format(
                "Colas activas:\n2v2: %d/%d\n5v5: %d/%d\n8v8: %d/%d",
                queue2v2.size(), QueueType.TWO_VS_TWO.getRequiredPlayers(),
                queue5v5.size(), QueueType.FIVE_VS_FIVE.getRequiredPlayers(),
                queue8v8.size(), QueueType.EIGHT_VS_EIGHT.getRequiredPlayers()
        );
    }

    public static void cleanupPlayerTrackingAfterMatch(List<PlayerData> players) {
        if (instance == null) return;

        int cleanedCount = 0;
        for (PlayerData player : players) {
            boolean wasTracked = instance.playersInQueue.remove(player.getMinecraftUuid());
            if (wasTracked) cleanedCount++;
        }

        if (cleanedCount > 0) {
            Bukkit.getLogger().info("[QueueManager] Limpiado tracking de " + cleanedCount +
                    " jugadores tras finalizar partida");
        }
    }
}
