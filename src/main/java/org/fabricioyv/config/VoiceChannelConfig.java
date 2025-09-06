package org.fabricioyv.config;
import org.fabricioyv.rating.Rank;

import java.util.Map;
import java.util.Set;

public class VoiceChannelConfig {
    // Canal donde se moverán los jugadores si fallan las validaciones
    public static final String WAITING_ROOM_CHANNEL_ID = "1405393642847338546";

    // Categoría donde se crearán los canales temporales de equipos
    public static final String TEAM_CHANNELS_CATEGORY_ID = "1405393161123266680";

    // ID del rol @Queue
    public static final String QUEUE_ROLE_ID = "1404726708359401534"; // Reemplaza con el ID real del rol @Queue

    public static final String ID_VOICE_CHANNEL_5vs5 = "1405393573855105075";

    public static final String ID_VOICE_CHANNEL_8vs8 = "1405393438148657324";

    public static final String MATCH_RESULTS_CHANNEL_ID = "1405725093727834183";

    public static final String LOGS_CHANNEL_ID = "1405609965053280408";
    public static final Set<String> ALLOWED_VOICE_CHANNELS = Set.of(
            ID_VOICE_CHANNEL_5vs5,
            ID_VOICE_CHANNEL_8vs8,
            WAITING_ROOM_CHANNEL_ID
    );
    public static final Map<Rank, String> RANK_ROLE_IDS = Map.ofEntries(
            Map.entry(Rank.COBRE_3, "1404636162215247976"),    // ID del rol Cobre III
            Map.entry(Rank.COBRE_2, "1404636300073631876"),    // ID del rol Cobre II
            Map.entry(Rank.COBRE_1, "1404636698092241080"),    // ID del rol Cobre I
            Map.entry(Rank.HIERRO_3, "1404636714940497951"),   // ID del rol Hierro III 1404636714940497951
            Map.entry(Rank.HIERRO_2, "1404636715779625031"),   // ID del rol Hierro II
            Map.entry(Rank.HIERRO_1, "1404636716786253936"),   // ID del rol Hierro I 1404636716786253936
            Map.entry(Rank.ORO_3, "1404636717033722018"),      // ID del rol Oro III 1404636717033722018
            Map.entry(Rank.ORO_2, "1404637011331252247"),      // ID del rol Oro II  1404637011331252247
            Map.entry(Rank.ORO_1, "1404637642993438800"),      // ID del rol Oro I
            Map.entry(Rank.DIAMANTE_3, "1404637905917313226"), // ID del rol Diamante III
            Map.entry(Rank.DIAMANTE_2, "1404637908853461210"), // ID del rol Diamante II
            Map.entry(Rank.DIAMANTE_1, "1404637910463942842"), // ID del rol Diamante I 1404637910463942842
            Map.entry(Rank.ESMERALDA, "1404637917250588723")   // ID del rol Esmeralda
    );


}
