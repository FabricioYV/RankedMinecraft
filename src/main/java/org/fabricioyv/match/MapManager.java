package org.fabricioyv.match;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fabricioyv.RankedMinecraft;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MapManager:
 * - Carga pools desde maps.yml
 * - Maneja anti-repeticion (recent maps)
 * - Soporta modos: VOTING / VETO / RANDOM
 *
 * NOTA: Evitamos emojis/caracteres raros en el source para que compile bien en Windows/Java 8 sin UTF-8.
 */
public class MapManager {

    private static final Map<String, List<String>> mapPools = new HashMap<String, List<String>>();
    private static final Map<String, Queue<String>> recentMaps = new HashMap<String, Queue<String>>();

    private static final int MAX_RECENT_MAPS = 3;

    private static RankedMinecraft plugin;

    // Config
    private static String selectionMode = "VOTING"; // VOTING / VETO / RANDOM
    private static boolean playerVotingEnabled = true;
    private static int votingDurationSeconds = 20;

    private static int vetoDurationPerTurnSeconds = 15;
    private static boolean vetoAnnounceToAll = true;

    private static int mapCycleDelaySeconds = 6;
    private static int finalCountdownDurationSeconds = 30;

    public static void initialize(RankedMinecraft plugin) {
        MapManager.plugin = plugin;

        if (plugin != null && !plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }

        // Inicializa colas de recientes UNA vez
        if (recentMaps.isEmpty()) {
            recentMaps.put("2v2", new ConcurrentLinkedQueue<String>());
            recentMaps.put("5v5", new ConcurrentLinkedQueue<String>());
            recentMaps.put("8v8", new ConcurrentLinkedQueue<String>());
        }

        loadMapPools();
        validateMapPools();
    }

    /**
     * Recarga maps.yml (pools + settings) sin borrar recientes.
     */
    public static void reload() {
        if (plugin == null) return;
        loadMapPools();
        validateMapPools();
    }

    /**
     * Cambia el modo de seleccion.
     * @param mode VOTING / VETO / RANDOM
     * @param persist si true, guarda selection_mode en maps.yml
     * @return true si fue valido, false si mode invalido
     */
    public static boolean setSelectionMode(String mode, boolean persist) {
        if (mode == null) return false;
        String m = mode.trim().toUpperCase(Locale.ROOT);
        if (!"VOTING".equals(m) && !"VETO".equals(m) && !"RANDOM".equals(m)) return false;

        selectionMode = m;

        // En VETO/RANDOM no aplica la votacion de jugadores
        if (!"VOTING".equals(selectionMode)) {
            playerVotingEnabled = false;
        }

        if (persist && plugin != null) {
            try {
                File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
                FileConfiguration cfg = YamlConfiguration.loadConfiguration(mapsFile);
                cfg.set("selection_mode", selectionMode);
                cfg.save(mapsFile);
            } catch (Exception ignored) { }
        }

        return true;
    }

    public static String getSelectionMode() {
        return selectionMode;
    }

    public static int getVotingDurationSeconds() {
        return votingDurationSeconds;
    }

    public static int getVetoDurationPerTurnSeconds() {
        return vetoDurationPerTurnSeconds;
    }

    public static boolean isVetoAnnounceToAll() {
        return vetoAnnounceToAll;
    }

    public static int getMapCycleDelaySeconds() {
        return mapCycleDelaySeconds;
    }

    public static int getFinalCountdownDurationSeconds() {
        return finalCountdownDurationSeconds;
    }

    public static boolean isPlayerVotingEnabled() {
        return "VOTING".equalsIgnoreCase(selectionMode) && playerVotingEnabled;
    }

    public static String normalizeMatchTypePublic(String matchType) {
        return normalizeMatchType(matchType);
    }

    public static List<String> getAvailableMaps(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        List<String> pool = mapPools.get(normalizedType);
        return new ArrayList<String>(pool == null ? Collections.<String>emptyList() : pool);
    }

    private static void loadMapPools() {
        if (plugin == null) return;

        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        if (!mapsFile.exists()) {
            try {
                plugin.saveResource("maps.yml", false);
            } catch (Exception ignored) {
                loadDefaultMaps();
                return;
            }
        }

        FileConfiguration mapsConfig;
        try {
            mapsConfig = YamlConfiguration.loadConfiguration(mapsFile);
        } catch (Exception e) {
            loadDefaultMaps();
            return;
        }

        // selection_mode
        String rawMode = mapsConfig.getString("selection_mode", "VOTING");
        selectionMode = (rawMode == null ? "VOTING" : rawMode.trim().toUpperCase(Locale.ROOT));
        if (!"VOTING".equals(selectionMode) && !"VETO".equals(selectionMode) && !"RANDOM".equals(selectionMode)) {
            selectionMode = "VOTING";
        }

        // voting settings
        playerVotingEnabled = mapsConfig.getBoolean("voting.enable_player_voting", true);
        if (!"VOTING".equals(selectionMode)) playerVotingEnabled = false;

        votingDurationSeconds = mapsConfig.getInt("voting.duration", 20);
        if (votingDurationSeconds <= 0) votingDurationSeconds = 20;

        // veto/cycle/countdown
        vetoDurationPerTurnSeconds = mapsConfig.getInt("veto.duration_per_turn", 15);
        vetoAnnounceToAll = mapsConfig.getBoolean("veto.announce_to_all", true);
        mapCycleDelaySeconds = mapsConfig.getInt("map_cycle.delay", 6);
        finalCountdownDurationSeconds = mapsConfig.getInt("final_countdown.duration", 30);

        if (vetoDurationPerTurnSeconds <= 0) vetoDurationPerTurnSeconds = 15;
        if (mapCycleDelaySeconds <= 0) mapCycleDelaySeconds = 6;
        if (finalCountdownDurationSeconds <= 0) finalCountdownDurationSeconds = 30;

        // pools
        List<String> maps2v2 = mapsConfig.getStringList("pools.2v2");
        List<String> maps5v5 = mapsConfig.getStringList("pools.5v5");
        List<String> maps8v8 = mapsConfig.getStringList("pools.8v8");

        mapPools.clear();
        if (maps2v2 != null && !maps2v2.isEmpty()) mapPools.put("2v2", new ArrayList<String>(maps2v2));
        if (maps5v5 != null && !maps5v5.isEmpty()) mapPools.put("5v5", new ArrayList<String>(maps5v5));
        if (maps8v8 != null && !maps8v8.isEmpty()) mapPools.put("8v8", new ArrayList<String>(maps8v8));

        // fallback si falta algo
        if (!mapPools.containsKey("2v2")) mapPools.put("2v2", new ArrayList<String>(Arrays.asList("Boneyard")));
        if (!mapPools.containsKey("5v5")) mapPools.put("5v5", new ArrayList<String>(Arrays.asList("Siege")));
        if (!mapPools.containsKey("8v8")) mapPools.put("8v8", new ArrayList<String>(Arrays.asList("Java II")));
    }

    private static void loadDefaultMaps() {
        mapPools.clear();
        mapPools.put("2v2", new ArrayList<String>(Arrays.asList("Boneyard")));
        mapPools.put("5v5", new ArrayList<String>(Arrays.asList("Siege")));
        mapPools.put("8v8", new ArrayList<String>(Arrays.asList("Java II")));

        selectionMode = "VOTING";
        playerVotingEnabled = true;
        votingDurationSeconds = 20;

        vetoDurationPerTurnSeconds = 15;
        vetoAnnounceToAll = true;
        mapCycleDelaySeconds = 6;
        finalCountdownDurationSeconds = 30;
    }

    private static void validateMapPools() {
        // Silencioso: solo evita nulls/empty críticos
        if (!mapPools.containsKey("2v2")) mapPools.put("2v2", new ArrayList<String>());
        if (!mapPools.containsKey("5v5")) mapPools.put("5v5", new ArrayList<String>());
        if (!mapPools.containsKey("8v8")) mapPools.put("8v8", new ArrayList<String>());
    }

    private static String normalizeMatchType(String matchType) {
        if (matchType == null || matchType.trim().isEmpty()) return "5v5";

        String normalized = matchType.toLowerCase(Locale.ROOT).trim();
        if (normalized.contains("2v2")) return "2v2";
        if (normalized.contains("5v5")) return "5v5";
        if (normalized.contains("8v8")) return "8v8";

        if (matchType.matches("\\d+v\\d+")) return matchType.toLowerCase(Locale.ROOT);

        return "5v5";
    }

    /**
     * NO marca recientes.
     */
    public static String getRandomMap(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        List<String> allMaps = getAvailableMaps(normalizedType);

        if (allMaps.isEmpty()) {
            return getFallbackMap(normalizedType);
        }

        Queue<String> recent = recentMaps.get(normalizedType);
        if (recent == null) {
            recent = new ConcurrentLinkedQueue<String>();
            recentMaps.put(normalizedType, recent);
        }

        List<String> available = new ArrayList<String>();
        for (String map : allMaps) {
            if (!recent.contains(map)) available.add(map);
        }

        if (available.isEmpty()) {
            available = new ArrayList<String>(allMaps);
            recent.clear();
        }

        return available.get(new Random().nextInt(available.size()));
    }

    private static String getFallbackMap(String normalizedType) {
        List<String> pool = mapPools.get(normalizedType);
        if (pool != null && !pool.isEmpty()) return pool.get(0);
        return "Boneyard";
    }

    public static void recordMapUsage(String matchType, String mapName) {
        String normalizedType = normalizeMatchType(matchType);
        Queue<String> q = recentMaps.get(normalizedType);
        if (q == null) {
            q = new ConcurrentLinkedQueue<String>();
            recentMaps.put(normalizedType, q);
        }

        q.offer(mapName);
        while (q.size() > MAX_RECENT_MAPS) q.poll();
    }

    public static List<String> getRecentMaps(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        Queue<String> q = recentMaps.get(normalizedType);
        return q == null ? new ArrayList<String>() : new ArrayList<String>(q);
    }

    public static List<String> getMapsForVoting(String matchType, int count) {
        List<String> all = getAvailableMaps(matchType);
        if (all.size() <= count) return all;

        Collections.shuffle(all);
        return all.subList(0, count);
    }

    public static boolean isValidMap(String mapName, String matchType) {
        return getAvailableMaps(matchType).contains(mapName);
    }

    public static void clearRecentMaps(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        Queue<String> q = recentMaps.get(normalizedType);
        if (q != null) q.clear();
    }

    public static void clearAllRecentMaps() {
        for (Queue<String> q : recentMaps.values()) {
            if (q != null) q.clear();
        }
    }

    public static String getMapStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("Sistema de mapas\n");
        sb.append("Selection mode: ").append(selectionMode).append("\n");
        sb.append("Player voting: ").append(isPlayerVotingEnabled() ? "ON" : "OFF").append("\n");
        sb.append("Voting duration: ").append(votingDurationSeconds).append("s\n");
        sb.append("Veto turn duration: ").append(vetoDurationPerTurnSeconds).append("s\n");
        sb.append("Map cycle delay: ").append(mapCycleDelaySeconds).append("s\n");
        sb.append("Final countdown: ").append(finalCountdownDurationSeconds).append("s\n");
        for (String type : mapPools.keySet()) {
            int poolSize = mapPools.get(type) == null ? 0 : mapPools.get(type).size();
            int recentSize = recentMaps.get(type) == null ? 0 : recentMaps.get(type).size();
            sb.append("- ").append(type).append(": ").append(poolSize).append(" maps, recent=").append(recentSize).append("\n");
        }
        return sb.toString();
    }
}
