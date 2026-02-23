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



    // Evita doble countdown (antes se usaba MatchState.startMatch() acá y chocaba con MatchManager.startMatch()).
    private final Object countdownLock = new Object();
    private boolean countdownActive = false;
    private QueueType countdownQueueType = null;

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

        // Safety: UUID inválido rompe el flujo (UUID.fromString) y deja la cola "congelada"
        if (player.getMinecraftUuid() == null || player.getMinecraftUuid().trim().isEmpty()) {
            return QueueResult.failure("UUID de Minecraft inválido");
        }

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

    private UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Acepta UUID con o sin guiones (por si tu DB guarda "compacto")
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {}

        if (s.length() == 32) {
            try {
                String dashed = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" +
                        s.substring(16, 20) + "-" + s.substring(20);
                return UUID.fromString(dashed);
            } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private String getMinecraftPlayerNameSafe(String minecraftUuid) {
        try {
            UUID u = parseUuid(minecraftUuid);
            if (u == null) return "Jugador";

            Player mcPlayer = Bukkit.getPlayer(u);
            if (mcPlayer != null) return mcPlayer.getName();

            String offlineName = Bukkit.getOfflinePlayer(u).getName();
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
        // Si ya hay match activo, no hacemos nada.
        if (MatchState.isMatchActive()) {
            logger.warning("QueueManager", "No se puede iniciar: ya hay una partida activa");
            return;
        }

        // Evitar que el mismo fill dispare 2+ count-downs.
        synchronized (countdownLock) {
            if (countdownActive) {
                // Ya hay un countdown corriendo. Ignorar este fill extra.
                return;
            }
            countdownActive = true;
            countdownQueueType = queueType;
        }

        startCountdown(new ArrayList<>(players), queueType);
    }

    private void startCountdown(List<PlayerData> players, QueueType queueType) {
        new BukkitRunnable() {
            int countdown = 30;

            @Override
            public void run() {
                // Si otro sistema ya inició un match, este countdown ya no tiene sentido.
                if (MatchState.isMatchActive()) {
                    logger.warning("QueueManager", "Countdown cancelado: ya hay una partida activa");
                    clearCountdown();
                    cancel();
                    return;
                }

                // Si por algún motivo el countdown fue invalidado (nuevo fill, reinicio, etc.)
                if (!isCountdownActive(queueType)) {
                    clearCountdown();
                    cancel();
                    return;
                }

                if (countdown <= 0) {
                    try {
                        handleCountdownEnd(queueType);
                    } finally {
                        clearCountdown();
                    }
                    cancel();
                    return;
                }

                handleCountdownTick(queueType, countdown);
                countdown--;
            }
        }.runTaskTimer(RankedMinecraft.getInstance(), 0L, 20L);
    }


    private boolean isCountdownActive(QueueType queueType) {
        synchronized (countdownLock) {
            return countdownActive && countdownQueueType == queueType;
        }
    }

    private void clearCountdown() {
        synchronized (countdownLock) {
            countdownActive = false;
            countdownQueueType = null;
        }
    }

    private void handleCountdownEnd(QueueType queueType) {
        List<PlayerData> targetQueue = getQueueList(queueType);

        try {
            synchronized (targetQueue) {
                List<PlayerData> connectedPlayers = new ArrayList<>();
                List<PlayerData> disconnectedPlayers = new ArrayList<>();

                for (PlayerData playerData : new ArrayList<>(targetQueue)) {
                    if (playerData == null) continue;
                    UUID u = parseUuid(playerData.getMinecraftUuid());
                    Player mcPlayer = (u == null) ? null : Bukkit.getPlayer(u);
                    boolean isConnectedToMC = mcPlayer != null && mcPlayer.isOnline();
                    boolean isInCorrectChannel = isPlayerInCorrectVoiceChannel(playerData.getDiscordId(), queueType);

                    if (isConnectedToMC && isInCorrectChannel) connectedPlayers.add(playerData);
                    else disconnectedPlayers.add(playerData);
                }

                cleanupDisconnectedPlayers(disconnectedPlayers, targetQueue);

                if (connectedPlayers.size() >= queueType.getRequiredPlayers()) {
                    startMatchWithPlayers(connectedPlayers, queueType, targetQueue);
                } else {
                    logger.warning("QueueManager",
                            "Partida cancelada: conectados " + connectedPlayers.size() + "/" + queueType.getRequiredPlayers());
                }
            }
        } catch (Exception e) {
            logger.warning("QueueManager", "Error iniciando match desde cola: " + e.getMessage());
        }
    }

    private void cleanupDisconnectedPlayers(List<PlayerData> disconnectedPlayers, List<PlayerData> targetQueue) {
        for (PlayerData disconnected : disconnectedPlayers) {
            targetQueue.remove(disconnected);
            playersInQueue.remove(disconnected.getMinecraftUuid());

            movePlayerToWaitingRoom(disconnected.getDiscordId());

            UUID u = parseUuid(disconnected.getMinecraftUuid());
            Player mcPlayer = (u == null) ? null : Bukkit.getPlayer(u);
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

        // Detectar si el match realmente se inició (evita limpiar cola si MatchManager rechazó por match activo).
        boolean wasActiveBefore = MatchState.isMatchActive();

        try {
            MatchManager.startMatchFromQueue(playersForMatch);

            boolean isActiveAfter = MatchState.isMatchActive();
            boolean startedByUs = (!wasActiveBefore && isActiveAfter);

            if (startedByUs) {
                // Guardar referencia de la última partida para Requeue (no depende del cache TTL)
                RequeueManager.rememberLastMatchPlayers(playersForMatch, queueType);
                for (PlayerData p : playersForMatch) {
                    targetQueue.remove(p);
                    playersInQueue.remove(p.getMinecraftUuid());
                }

                logger.matchEvent("QUEUE_" + queueType.name(), "Match iniciado",
                        "Partida iniciada con " + playersForMatch.size() + " jugadores", playersForMatch.size());
            } else {
                // No se inició (o ya había match). Dejar a los jugadores en cola.
                logger.warning("QueueManager", "MatchManager no inició el match (posible match activo). Se mantiene la cola.");
            }

        } catch (Exception e) {
            logger.logError("QueueManager", e);
            // No llamamos MatchState.endMatch() aquí: MatchManager es el dueño del lock/estado.
        }
    }

    private void handleCountdownTick(QueueType queueType, int countdown) {
        List<PlayerData> targetQueue = getQueueList(queueType);

        for (PlayerData playerData : new ArrayList<>(targetQueue)) {
            UUID u = parseUuid(playerData.getMinecraftUuid());
            Player mcPlayer = (u == null) ? null : Bukkit.getPlayer(u);
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

    public static String getQueueTypeFromSize(int size) {
        size = size / 2;

        if (size == 2) {
            return "2vs2";
        } else if (size == 5) {
            return "5vs5";
        } else if (size == 8) {
            return "8vs8";
        } else {
            return "Unknown";
        }
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