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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requeue:
 * - Permite re-entrar a la misma cola del ÚLTIMO MATCH (no depende del cache TTL).
 * - Mueve al jugador al voice channel correcto y luego lo agrega a la cola con reintentos.
 */
public class RequeueManager {

    private static DiscordBot discordBot;

    /** Memoria corta de la última partida (por jugador). */
    private static final Map<String, LastMatchEntry> LAST_MATCH_BY_MC_UUID = new ConcurrentHashMap<>();

    /** Tiempo máximo para permitir requeue usando la "última partida" (ms). */
    private static final long LAST_MATCH_TTL_MS = 30L * 60L * 1000L; // 30 min

    /** Reintentos para agregar a cola (por delays de JDA / estados) */
    private static final int MAX_ADD_TRIES = 6;

    public RequeueManager(DiscordBot discordBot, RankedMinecraft plugin) {
        RequeueManager.discordBot = discordBot;
    }

    public static void setDiscordBot(DiscordBot bot) {
        RequeueManager.discordBot = bot;
    }

    /**
     * Llamar cuando un match se inicia desde la cola.
     * Guarda "última partida" para que /requeue y la perla funcionen aunque el cache expire.
     */
    public static void rememberLastMatchPlayers(List<PlayerData> players, QueueType queueType) {
        if (players == null || queueType == null) return;

        long now = System.currentTimeMillis();
        cleanupOld(now);

        for (PlayerData p : players) {
            if (p == null) continue;
            String mc = normalizeMinecraftUuidKey(p.getMinecraftUuid());
            if (mc == null) continue;

            LAST_MATCH_BY_MC_UUID.put(mc, new LastMatchEntry(p, queueType, now));
        }
    }

    public static QueueType getLastMatchQueueType(String minecraftUuid) {
        long now = System.currentTimeMillis();
        cleanupOld(now);

        LastMatchEntry e = LAST_MATCH_BY_MC_UUID.get(normalizeMinecraftUuidKey(minecraftUuid));
        return e == null ? null : e.queueType;
    }

    public static PlayerData getLastMatchPlayerData(String minecraftUuid) {
        long now = System.currentTimeMillis();
        cleanupOld(now);

        LastMatchEntry e = LAST_MATCH_BY_MC_UUID.get(normalizeMinecraftUuidKey(minecraftUuid));
        return e == null ? null : e.playerData;
    }

    /** Wrapper legacy (string). */
    public static void requeuePlayer(PlayerData playerData, String queueTypeRaw) {
        QueueType queueTypeEnum = parseQueueType(queueTypeRaw);
        requeuePlayer(playerData, queueTypeEnum);
    }

    /** Nuevo: requeue directo por enum. */
    public static void requeuePlayer(PlayerData playerData, QueueType queueTypeEnum) {
        Player mcPlayer = getOnlinePlayer(playerData);

        if (playerData == null || discordBot == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Error interno (Data/Bot null).");
            return;
        }

        if (queueTypeEnum == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Tipo de cola desconocido.");
            return;
        }

        final String discordId = safe(playerData.getDiscordId());
        if (discordId == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: No estás vinculado a Discord.");
            return;
        }

        JDA jda;
        try {
            jda = discordBot.getJda();
        } catch (Exception e) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Error conectando con Discord.");
            return;
        }

        Guild guild = jda.getGuildById(discordBot.getGuildId());
        if (guild == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Guild de Discord no encontrado.");
            return;
        }

        Member member = guild.getMemberById(discordId);
        if (member == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: No estás en el Discord (o no estás vinculado).");
            return;
        }

        String targetVoiceChannelID = getQueueVoiceChannelId(queueTypeEnum);
        if (targetVoiceChannelID == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Canal de voz no configurado para " + queueTypeEnum.getDisplayName());
            return;
        }

        VoiceChannel target = guild.getVoiceChannelById(targetVoiceChannelID);
        if (target == null) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Canal de voz no existe / no configurado.");
            return;
        }

        GuildVoiceState vs = member.getVoiceState();
        if (vs == null || !vs.inAudioChannel()) {
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: ¡Debes estar en un canal de voz!");
            return;
        }

        if (mcPlayer != null) {
            mcPlayer.sendMessage("§eRequeue: Entrando a la cola §f" + queueTypeEnum.getDisplayName() + "§e...");
        }

        // Si ya está en el canal correcto, intentamos agregar directo (con retries).
        if (vs.getChannel() != null && vs.getChannel().getId().equals(targetVoiceChannelID)) {
            scheduleAddTry(playerData, queueTypeEnum, discordId, targetVoiceChannelID, 0, 5L);
            return;
        }

        // Si no está en el canal, lo movemos y luego intentamos agregar (con retries).
        guild.moveVoiceMember(member, target).queue(
                success -> scheduleAddTry(playerData, queueTypeEnum, discordId, targetVoiceChannelID, 0, 10L),
                error -> {
                    if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: No pude moverte de canal en Discord (permisos).");
                    error.printStackTrace();
                }
        );
    }

    /**
     * Intenta añadir a cola con reintentos para:
     * - delay de estado de JDA al moverse
     * - flags/estado de PlayerData desfasados post-match
     */
    private static void scheduleAddTry(PlayerData playerData,
                                       QueueType queueTypeEnum,
                                       String discordId,
                                       String targetVoiceChannelID,
                                       int attempt,
                                       long delayTicks) {

        Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
            Player mcPlayer = getOnlinePlayer(playerData);

            if (discordBot == null || discordBot.getQueueManager() == null) {
                if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Cola no disponible (QueueManager null).");
                return;
            }

            // 1) Verificar voice channel (por si JDA aún no actualiza).
            if (!isMemberInChannel(discordId, targetVoiceChannelID)) {
                if (attempt >= MAX_ADD_TRIES) {
                    if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: No pude confirmar tu canal de voz. Intenta otra vez.");
                    return;
                }
                // Reintento simple (sin spamear move). Espera y vuelve a intentar.
                scheduleAddTry(playerData, queueTypeEnum, discordId, targetVoiceChannelID, attempt + 1, 10L);
                return;
            }

            // 2) Fix defensivo: algunos flujos dejan isInMatch=true un rato post-match.
            // Intentamos limpiarlo silenciosamente (si existe setter).
            trySetBooleanIfExists(playerData, "setInMatch", false);
            trySetBooleanIfExists(playerData, "setInGame", false);
            trySetBooleanIfExists(playerData, "setInQueue", false);

            QueueResult result;
            try {
                result = discordBot.getQueueManager().addPlayerToQueue(playerData, queueTypeEnum);
            } catch (Exception e) {
                if (attempt >= MAX_ADD_TRIES) {
                    if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: Falló al entrar a cola. Intenta otra vez.");
                    e.printStackTrace();
                    return;
                }
                scheduleAddTry(playerData, queueTypeEnum, discordId, targetVoiceChannelID, attempt + 1, 10L);
                return;
            }

            // 3) Feedback y reintentos solo cuando tiene sentido
            if (result != null && result.isSuccess()) {
                if (mcPlayer != null) mcPlayer.sendMessage("§aRequeue: Listo. Estás en cola §f" + queueTypeEnum.getDisplayName() + "§a.");
                return;
            }

            String msg = (result == null ? "Error desconocido" : result.getMessage());
            if (mcPlayer != null) mcPlayer.sendMessage("§cRequeue: " + msg);

            // Reintentar solo si parece transitorio (voz / canal / actualización)
            if (attempt < MAX_ADD_TRIES && looksTransient(msg)) {
                scheduleAddTry(playerData, queueTypeEnum, discordId, targetVoiceChannelID, attempt + 1, 12L);
            }
        }, delayTicks);
    }

    private static boolean looksTransient(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase();
        return s.contains("canal") || s.contains("voz") || s.contains("conect");
    }

    private static boolean isMemberInChannel(String discordId, String channelId) {
        try {
            if (discordBot == null) return false;

            JDA jda = discordBot.getJda();
            Guild guild = jda.getGuildById(discordBot.getGuildId());
            if (guild == null) return false;

            Member member = guild.getMemberById(discordId);
            if (member == null) return false;

            GuildVoiceState vs = member.getVoiceState();
            if (vs == null || !vs.inAudioChannel() || vs.getChannel() == null) return false;

            return vs.getChannel().getId().equals(channelId);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void cleanupOld(long now) {
        if (LAST_MATCH_BY_MC_UUID.isEmpty()) return;
        LAST_MATCH_BY_MC_UUID.entrySet().removeIf(e -> (now - e.getValue().ts) > LAST_MATCH_TTL_MS);
    }

    private static String safe(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String normalizeMinecraftUuidKey(String raw) {
        UUID u = parseUuid(raw);
        if (u != null) return u.toString();
        String s = safe(raw);
        return s == null ? null : s.toLowerCase();
    }

    private static String getQueueVoiceChannelId(QueueType queueTypeEnum) {
        switch (queueTypeEnum) {
            case TWO_VS_TWO: return VoiceChannelConfig.ID_VOICE_CHANNEL_2vs2;
            case FIVE_VS_FIVE: return VoiceChannelConfig.ID_VOICE_CHANNEL_5vs5;
            case EIGHT_VS_EIGHT: return VoiceChannelConfig.ID_VOICE_CHANNEL_8vs8;
            default: return null;
        }
    }

    private static QueueType parseQueueType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
        if (s.equals("2v2") || s.equals("2vs2")) return QueueType.TWO_VS_TWO;
        if (s.equals("5v5") || s.equals("5vs5")) return QueueType.FIVE_VS_FIVE;
        if (s.equals("8v8") || s.equals("8vs8")) return QueueType.EIGHT_VS_EIGHT;
        try { return QueueType.valueOf(raw.trim().toUpperCase()); } catch (Exception e) { return null; }
    }

    private static Player getOnlinePlayer(PlayerData playerData) {
        try {
            if (playerData == null) return null;
            UUID uuid = parseUuid(playerData.getMinecraftUuid());
            if (uuid == null) return null;
            return Bukkit.getPlayer(uuid);
        } catch (Exception e) { return null; }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
            // Sin guiones (32 chars)
            if (s.length() == 32) {
                String dashed = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" +
                        s.substring(16, 20) + "-" + s.substring(20);
                try {
                    return UUID.fromString(dashed);
                } catch (Exception ignored2) {
                    return null;
                }
            }
            return null;
        }
    }

    private static void trySetBooleanIfExists(PlayerData playerData, String methodName, boolean value) {
        try {
            Method m = playerData.getClass().getMethod(methodName, boolean.class);
            m.invoke(playerData, value);
        } catch (Exception ignored) {
        }
    }

    private static class LastMatchEntry {
        final PlayerData playerData;
        final QueueType queueType;
        final long ts;

        LastMatchEntry(PlayerData playerData, QueueType queueType, long ts) {
            this.playerData = playerData;
            this.queueType = queueType;
            this.ts = ts;
        }
    }
}