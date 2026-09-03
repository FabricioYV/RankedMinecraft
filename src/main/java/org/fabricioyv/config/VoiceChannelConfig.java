package org.fabricioyv.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.fabricioyv.rating.Rank;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * IDs de canales y roles de Discord usados por el bot.
 * Se cargan desde config.yml (sección discord.channels / discord.roles) en {@link #init(Plugin)}
 * para que el plugin no quede atado a un servidor de Discord en particular.
 */
public class VoiceChannelConfig {

    private static final String PLACEHOLDER_PREFIX = "PUT_";

    // Canal donde se moverán los jugadores si fallan las validaciones
    public static String WAITING_ROOM_CHANNEL_ID;

    // Categoría donde se crearán los canales temporales de equipos
    public static String TEAM_CHANNELS_CATEGORY_ID;

    // Categoría donde se crean los canales temporales de picks
    public static String PICKS_CATEGORY_ID;

    // ID del rol @Queue
    public static String QUEUE_ROLE_ID;

    public static String ID_VOICE_CHANNEL_2vs2;
    public static String ID_VOICE_CHANNEL_5vs5;
    public static String ID_VOICE_CHANNEL_8vs8;

    public static String MATCH_RESULTS_CHANNEL_ID;
    public static String LOGS_CHANNEL_ID;

    public static Set<String> ALLOWED_VOICE_CHANNELS;

    // Roles adicionales (aparte del historial de victorias) habilitados para ser capitanes
    public static Set<String> CAPTAIN_ELIGIBLE_ROLE_IDS;

    public static Map<Rank, String> RANK_ROLE_IDS;

    public static void init(Plugin plugin) {
        FileConfiguration config = plugin.getConfig();

        WAITING_ROOM_CHANNEL_ID = channel(config, "waiting-room");
        TEAM_CHANNELS_CATEGORY_ID = channel(config, "team-category");
        PICKS_CATEGORY_ID = channel(config, "picks-category");
        ID_VOICE_CHANNEL_2vs2 = channel(config, "voice-2v2");
        ID_VOICE_CHANNEL_5vs5 = channel(config, "voice-5v5");
        ID_VOICE_CHANNEL_8vs8 = channel(config, "voice-8v8");
        MATCH_RESULTS_CHANNEL_ID = channel(config, "match-results");
        LOGS_CHANNEL_ID = channel(config, "logs");

        QUEUE_ROLE_ID = config.getString("discord.roles.queue", "PUT_ROLE_ID_HERE");

        CAPTAIN_ELIGIBLE_ROLE_IDS = new LinkedHashSet<>(
                config.getStringList("discord.roles.captain-eligible"));

        ALLOWED_VOICE_CHANNELS = Set.of(
                ID_VOICE_CHANNEL_2vs2,
                ID_VOICE_CHANNEL_5vs5,
                ID_VOICE_CHANNEL_8vs8,
                WAITING_ROOM_CHANNEL_ID
        );

        Map<Rank, String> rankRoles = new EnumMap<>(Rank.class);
        for (Rank rank : Rank.values()) {
            if (rank == Rank.PLACEMENT) continue;
            rankRoles.put(rank, config.getString("discord.roles.ranks." + rank.name(), "PUT_ROLE_ID_HERE"));
        }
        RANK_ROLE_IDS = rankRoles;

        if (WAITING_ROOM_CHANNEL_ID.startsWith(PLACEHOLDER_PREFIX) || QUEUE_ROLE_ID.startsWith(PLACEHOLDER_PREFIX)) {
            plugin.getLogger().warning("Canales/roles de Discord no configurados (config.yml -> discord.channels / discord.roles). " +
                    "El bot no podrá mover jugadores ni asignar roles hasta que los completes.");
        }
    }

    private static String channel(FileConfiguration config, String key) {
        return config.getString("discord.channels." + key, "PUT_CHANNEL_ID_HERE");
    }
}
