package org.fabricioyv.match;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fabricioyv.RankedMinecraft;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MapManager {

    private static final Map<String, List<String>> mapPools = new HashMap<>();
    private static final Map<String, Queue<String>> recentMaps = new HashMap<>();

    private static final int MAX_RECENT_MAPS = 3;
    private static RankedMinecraft plugin;
    private static boolean playerVotingEnabled = true;
    public static String normalizeMatchTypePublic(String matchType) {
        return normalizeMatchType(matchType);
    }

    private static int votingDurationSeconds = 20;

    public static int getVotingDurationSeconds() {
        return votingDurationSeconds;
    }


    public static void initialize(RankedMinecraft plugin) {
        MapManager.plugin = plugin;

        if (!plugin.getDataFolder().exists()) {
            boolean created = plugin.getDataFolder().mkdirs();
            plugin.getLogger().info("📁 Creando carpeta de datos del plugin: " + plugin.getDataFolder().getPath() +
                    (created ? " (ok)" : " (falló)"));
        }

        loadMapPools();
        initializeRecentMapsTracking();
        validateMapPools();
    }

    private static void initializeRecentMapsTracking() {
        recentMaps.put("2v2", new ConcurrentLinkedQueue<>());
        recentMaps.put("5v5", new ConcurrentLinkedQueue<>());
        recentMaps.put("8v8", new ConcurrentLinkedQueue<>());
    }

    private static void loadMapPools() {
        plugin.getLogger().info("🔄 Cargando pools de mapas...");

        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        plugin.getLogger().info("📍 maps.yml: " + mapsFile.getAbsolutePath());

        if (!mapsFile.exists()) {
            plugin.getLogger().info("📄 maps.yml no existe, copiando desde resources...");
            try {
                plugin.saveResource("maps.yml", false);
                plugin.getLogger().info("✅ maps.yml copiado");
            } catch (Exception e) {
                plugin.getLogger().severe("❌ Error copiando maps.yml: " + e.getMessage());
                plugin.getLogger().warning("🔧 Usando fallback por defecto");
                loadDefaultMaps();
                return;
            }
        }

        FileConfiguration mapsConfig;
        try {
            mapsConfig = YamlConfiguration.loadConfiguration(mapsFile);
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Error cargando maps.yml: " + e.getMessage());
            plugin.getLogger().warning("🔧 Usando fallback por defecto");
            loadDefaultMaps();
            return;
        }

        // settings
        playerVotingEnabled = mapsConfig.getBoolean("voting.enable_player_voting", true);
        votingDurationSeconds = mapsConfig.getInt("voting.duration", 20);

        // pools
        List<String> maps2v2 = mapsConfig.getStringList("pools.2v2");
        List<String> maps5v5 = mapsConfig.getStringList("pools.5v5");
        List<String> maps8v8 = mapsConfig.getStringList("pools.8v8");

        mapPools.clear();
        if (!maps2v2.isEmpty()) mapPools.put("2v2", new ArrayList<>(maps2v2));
        if (!maps5v5.isEmpty()) mapPools.put("5v5", new ArrayList<>(maps5v5));
        if (!maps8v8.isEmpty()) mapPools.put("8v8", new ArrayList<>(maps8v8));

        // fallback por tipo si faltan pools
        if (!mapPools.containsKey("2v2")) {
            plugin.getLogger().warning("⚠️ No hay pools.2v2, usando fallback 2v2");
            mapPools.put("2v2", new ArrayList<>(Arrays.asList(
                    "Boneyard", "bridge_castle", "chernot", "Climate", "coral"
            )));
        }
        if (!mapPools.containsKey("5v5")) {
            plugin.getLogger().warning("⚠️ No hay pools.5v5, usando fallback 5v5");
            mapPools.put("5v5", new ArrayList<>(Arrays.asList(
                    "Siege", "Smoke┈KotH┈5v5", "Topaz", "Willow", "Factori",
                    "Revolution", "Space┈Race┈1969", "Rusteze┈5v5"
            )));
        }
        if (!mapPools.containsKey("8v8")) {
            plugin.getLogger().warning("⚠️ No hay pools.8v8, usando fallback 8v8");
            mapPools.put("8v8", new ArrayList<>(Arrays.asList(
                    "Java II", "Jurassic", "NextGen", "Gobi", "Oriental"
            )));
        }

        plugin.getLogger().info("✅ Pools listos: 2v2=" + mapPools.get("2v2").size() +
                ", 5v5=" + mapPools.get("5v5").size() +
                ", 8v8=" + mapPools.get("8v8").size());

        plugin.getLogger().info("🔁 Anti-repetición: últimos " + MAX_RECENT_MAPS);
        plugin.getLogger().info("🗳️ Votación: " + (playerVotingEnabled ? "on" : "off") +
                " | duración=" + votingDurationSeconds + "s");
    }



    private static void loadDefaultMaps() {
        mapPools.put("2v2", new ArrayList<>(Arrays.asList("Boneyard", "bridge┈castle", "chernot", "Climate", "coral")));
        mapPools.put("5v5", new ArrayList<>(Arrays.asList("Siege", "Smoke┈KotH┈5v5", "Topaz", "Willow", "Factori",
                "Revolution", "Space┈Race┈1969", "Rusteze┈5v5")));
        mapPools.put("8v8", new ArrayList<>(Arrays.asList("Java II", "Jurassic", "NextGen", "Gobi", "Oriental")));
        playerVotingEnabled = true;
    }

    private static void validateMapPools() {
        plugin.getLogger().info("🔍 Validando pools...");

        for (String type : Arrays.asList("2v2", "5v5", "8v8")) {
            List<String> pool = mapPools.get(type);
            if (pool == null || pool.isEmpty()) {
                plugin.getLogger().severe("❌ Pool vacío: " + type);
            } else {
                plugin.getLogger().info("✅ " + type + ": " + pool.size() + " mapas");
            }
        }
    }

    public static boolean isPlayerVotingEnabled() {
        return playerVotingEnabled;
    }

    public static List<String> getAvailableMaps(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        return new ArrayList<>(mapPools.getOrDefault(normalizedType, new ArrayList<>()));
    }

    private static String normalizeMatchType(String matchType) {
        if (matchType == null || matchType.trim().isEmpty()) return "5v5";

        String normalized = matchType.toLowerCase().trim();

        if (normalized.contains("2v2")) return "2v2";
        if (normalized.contains("5v5")) return "5v5";
        if (normalized.contains("8v8")) return "8v8";

        if (matchType.matches("\\d+v\\d+")) return matchType.toLowerCase();

        plugin.getLogger().warning("⚠️ No se pudo normalizar '" + matchType + "', usando 5v5");
        return "5v5";
    }

    public static String getRandomMap(String matchType) {
        if (matchType == null || matchType.trim().isEmpty()) {
            plugin.getLogger().severe("❌ matchType inválido, usando fallback 5v5");
            return getFallbackMap("5v5");
        }

        String normalizedType = normalizeMatchType(matchType);
        List<String> allMaps = getAvailableMaps(normalizedType);

        if (allMaps.isEmpty()) {
            plugin.getLogger().severe("❌ No hay mapas para " + normalizedType + ", usando fallback");
            return getFallbackMap(normalizedType);
        }

        Queue<String> recentMapsForType = recentMaps.computeIfAbsent(normalizedType, k -> new ConcurrentLinkedQueue<>());

        List<String> availableMaps = new ArrayList<>();
        for (String map : allMaps) {
            if (!recentMapsForType.contains(map)) availableMaps.add(map);
        }

        if (availableMaps.isEmpty()) {
            plugin.getLogger().warning("⚠️ Todos los mapas de " + normalizedType + " están recientes. Reseteando recientes...");
            availableMaps = new ArrayList<>(allMaps);
            recentMapsForType.clear();
        }

        String selectedMap = availableMaps.get(new Random().nextInt(availableMaps.size()));
        recordMapUsage(normalizedType, selectedMap);

        plugin.getLogger().info("🎲 Mapa seleccionado (" + normalizedType + "): " + selectedMap);
        return selectedMap;
    }

    private static String getFallbackMap(String normalizedType) {
        List<String> pool = mapPools.get(normalizedType);
        if (pool != null && !pool.isEmpty()) {
            return pool.get(new Random().nextInt(pool.size()));
        }
        // ultra emergencia
        return "Boneyard";
    }

    public static void recordMapUsage(String matchType, String mapName) {
        String normalizedType = normalizeMatchType(matchType);
        Queue<String> q = recentMaps.computeIfAbsent(normalizedType, k -> new ConcurrentLinkedQueue<>());

        q.offer(mapName);
        while (q.size() > MAX_RECENT_MAPS) q.poll();
    }

    public static List<String> getRecentMaps(String matchType) {
        String normalizedType = normalizeMatchType(matchType);
        Queue<String> q = recentMaps.get(normalizedType);
        return q == null ? new ArrayList<>() : new ArrayList<>(q);
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
        for (Queue<String> q : recentMaps.values()) q.clear();
    }

    public static String getMapStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Sistema de mapas\n");
        for (String type : mapPools.keySet()) {
            sb.append("- ").append(type)
                    .append(": ").append(mapPools.get(type).size()).append(" mapas, recientes=")
                    .append(recentMaps.getOrDefault(type, new ConcurrentLinkedQueue<>()).size())
                    .append("\n");
        }
        sb.append("🗳️ Votación: ").append(playerVotingEnabled ? "on" : "off");
        return sb.toString();
    }
}