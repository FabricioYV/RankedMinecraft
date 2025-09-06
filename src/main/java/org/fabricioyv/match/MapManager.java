package org.fabricioyv.match;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fabricioyv.RankedMinecraft;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


public class MapManager {
    private static final Map<String, List<String>> mapPools = new HashMap<>();
    private static final Map<String, Queue<String>> recentMaps = new HashMap<>(); // Para evitar repetir mapas
    private static final int MAX_RECENT_MAPS = 3; // Número de mapas recientes a recordar
    private static RankedMinecraft plugin;
    private static boolean playerVotingEnabled = true; // Por defecto habilitado

    public static void initialize(RankedMinecraft plugin) {
        MapManager.plugin = plugin;
        loadMapPools();
        initializeRecentMapsTracking();
    }

    private static void loadMapPools() {
        // Crear archivo de mapas por defecto si no existe
        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        if (!mapsFile.exists()) {
            plugin.saveResource("maps.yml", false);
        }

        FileConfiguration mapsConfig = YamlConfiguration.loadConfiguration(mapsFile);

        // Cargar configuración de votación
        playerVotingEnabled = mapsConfig.getBoolean("voting.enable_player_voting", true);

        // Cargar mapas para 5v5
        List<String> maps5v5 = mapsConfig.getStringList("pools.5v5");
        if (maps5v5.isEmpty()) {
            // Mapas por defecto para 5v5
            maps5v5 = Arrays.asList("Coconut┈Mald", "Summit", "Warlock", "Temple of Kotmogu", "Rage Quit");
        }
        mapPools.put("5v5", maps5v5);

        // Cargar mapas para 8v8
        List<String> maps8v8 = mapsConfig.getStringList("pools.8v8");
        if (maps8v8.isEmpty()) {
            // Mapas por defecto para 8v8
            maps8v8 = Arrays.asList("Golden Drought III", "Battlefront", "Senex", "Bamboo Valley", "Fortress Battles");
        }
        mapPools.put("8v8", maps8v8);

        plugin.getLogger().info("✅ Cargados " + maps5v5.size() + " mapas para 5v5 y " + maps8v8.size() + " mapas para 8v8");
        plugin.getLogger().info("🗳️ Votación de jugadores: " + (playerVotingEnabled ? "Habilitada" : "Deshabilitada"));
        plugin.getLogger().info("🔄 Sistema anti-repetición: Evitará repetir los últimos " + MAX_RECENT_MAPS + " mapas");
    }

    /**
     * Inicializa el tracking de mapas recientes para cada tipo de partida
     */
    private static void initializeRecentMapsTracking() {
        recentMaps.put("5v5", new ConcurrentLinkedQueue<>());
        recentMaps.put("8v8", new ConcurrentLinkedQueue<>());
    }

    /**
     * Verifica si la votación por jugadores está habilitada
     */
    public static boolean isPlayerVotingEnabled() {
        return playerVotingEnabled;
    }

    /**
     * Obtiene los mapas disponibles para un tipo específico de match
     */
    public static List<String> getAvailableMaps(String matchType) {
        return new ArrayList<>(mapPools.getOrDefault(matchType, new ArrayList<>()));
    }

    /**
     * Selecciona un mapa aleatorio del pool, evitando los mapas recientes
     */
    public static String getRandomMap(String matchType) {
        List<String> allMaps = getAvailableMaps(matchType);
        if (allMaps.isEmpty()) {
            return "Warlock"; // Mapa fallback
        }

        // Obtener mapas recientes para este tipo de partida
        Queue<String> recentMapsForType = recentMaps.get(matchType);
        if (recentMapsForType == null) {
            recentMapsForType = new ConcurrentLinkedQueue<>();
            recentMaps.put(matchType, recentMapsForType);
        }

        // Crear lista de mapas disponibles (excluyendo los recientes)
        List<String> availableMaps = new ArrayList<>();
        for (String map : allMaps) {
            if (!recentMapsForType.contains(map)) {
                availableMaps.add(map);
            }
        }

        // Si no hay mapas disponibles (todos están en recientes), usar todos los mapas
        if (availableMaps.isEmpty()) {
            plugin.getLogger().warning("⚠️ Todos los mapas de " + matchType + " están en la lista de recientes. Reiniciando lista.");
            availableMaps = new ArrayList<>(allMaps);
            recentMapsForType.clear(); // Limpiar la lista de recientes
        }

        // Seleccionar mapa aleatorio
        String selectedMap = availableMaps.get(new Random().nextInt(availableMaps.size()));

        // Registrar el mapa como usado
        recordMapUsage(matchType, selectedMap);

        plugin.getLogger().info("🎲 Mapa seleccionado para " + matchType + ": " + selectedMap +
                               " (Evitados: " + recentMapsForType.size() + " mapas recientes)");

        return selectedMap;
    }

    /**
     * Registra el uso de un mapa y mantiene la lista de mapas recientes
     */
    public static void recordMapUsage(String matchType, String mapName) {
        Queue<String> recentMapsForType = recentMaps.get(matchType);
        if (recentMapsForType == null) {
            recentMapsForType = new ConcurrentLinkedQueue<>();
            recentMaps.put(matchType, recentMapsForType);
        }

        // Agregar el mapa a la lista de recientes
        recentMapsForType.offer(mapName);

        // Mantener solo los últimos MAX_RECENT_MAPS mapas
        while (recentMapsForType.size() > MAX_RECENT_MAPS) {
            String removedMap = recentMapsForType.poll();
            plugin.getLogger().fine("📤 Mapa " + removedMap + " removido de la lista de recientes para " + matchType);
        }

        plugin.getLogger().fine("📝 Mapa " + mapName + " registrado como usado para " + matchType +
                               " (Recientes: " + recentMapsForType.size() + "/" + MAX_RECENT_MAPS + ")");
    }

    /**
     * Obtiene la lista de mapas recientes para un tipo de partida
     */
    public static List<String> getRecentMaps(String matchType) {
        Queue<String> recentMapsForType = recentMaps.get(matchType);
        if (recentMapsForType == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(recentMapsForType);
    }

    /**
     * Selecciona mapas para votación, evitando los mapas recientes
     */
    public static List<String> getRandomMapsForVoting(String matchType, int count) {
        List<String> allMaps = getAvailableMaps(matchType);
        if (allMaps.isEmpty()) {
            return Arrays.asList("Warlock"); // Fallback
        }

        // Obtener mapas recientes
        Queue<String> recentMapsForType = recentMaps.getOrDefault(matchType, new ConcurrentLinkedQueue<>());

        // Filtrar mapas recientes
        List<String> availableMaps = new ArrayList<>();
        for (String map : allMaps) {
            if (!recentMapsForType.contains(map)) {
                availableMaps.add(map);
            }
        }

        // Si no hay suficientes mapas disponibles, agregar algunos de los recientes
        if (availableMaps.size() < count) {
            plugin.getLogger().warning("⚠️ No hay suficientes mapas nuevos para votación en " + matchType +
                                     ". Agregando algunos mapas recientes.");

            // Agregar mapas de la lista de recientes hasta completar el count
            List<String> recentMapsList = new ArrayList<>(recentMapsForType);
            Collections.shuffle(recentMapsList);

            for (String recentMap : recentMapsList) {
                if (availableMaps.size() >= count) break;
                if (!availableMaps.contains(recentMap)) {
                    availableMaps.add(recentMap);
                }
            }
        }

        // Si aún no hay suficientes, usar todos los mapas disponibles
        if (availableMaps.size() < count) {
            availableMaps = new ArrayList<>(allMaps);
        }

        // Mezclar y seleccionar
        Collections.shuffle(availableMaps);
        List<String> selectedMaps = new ArrayList<>();
        for (int i = 0; i < Math.min(count, availableMaps.size()); i++) {
            selectedMaps.add(availableMaps.get(i));
        }

        plugin.getLogger().info("🗳️ Mapas seleccionados para votación " + matchType + ": " +
                               String.join(", ", selectedMaps) +
                               " (Evitados " + recentMapsForType.size() + " mapas recientes)");

        return selectedMaps;
    }

    /**
     * Valida si un mapa existe en el pool
     */
    public static boolean isValidMap(String mapName, String matchType) {
        return getAvailableMaps(matchType).contains(mapName);
    }

    /**
     * Obtiene estadísticas del sistema de mapas
     */
    public static String getMapStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("📊 **Estadísticas del Sistema de Mapas**\n");

        for (String matchType : mapPools.keySet()) {
            List<String> allMaps = mapPools.get(matchType);
            Queue<String> recentMapsForType = recentMaps.getOrDefault(matchType, new ConcurrentLinkedQueue<>());

            stats.append(String.format("**%s:** %d mapas totales, %d recientes\n",
                matchType, allMaps.size(), recentMapsForType.size()));

            if (!recentMapsForType.isEmpty()) {
                stats.append(String.format("   Recientes: %s\n", String.join(", ", recentMapsForType)));
            }
        }

        stats.append(String.format("🔄 Anti-repetición: %d mapas máximos en memoria\n", MAX_RECENT_MAPS));
        stats.append(String.format("🗳️ Votación de jugadores: %s",
            playerVotingEnabled ? "Habilitada" : "Deshabilitada"));

        return stats.toString();
    }

    /**
     * Reinicia la lista de mapas recientes para un tipo específico
     */
    public static void clearRecentMaps(String matchType) {
        Queue<String> recentMapsForType = recentMaps.get(matchType);
        if (recentMapsForType != null) {
            int clearedCount = recentMapsForType.size();
            recentMapsForType.clear();
            plugin.getLogger().info("🔄 Lista de mapas recientes para " + matchType + " reiniciada (" + clearedCount + " mapas removidos)");
        }
    }

    /**
     * Reinicia todas las listas de mapas recientes
     */
    public static void clearAllRecentMaps() {
        int totalCleared = 0;
        for (String matchType : recentMaps.keySet()) {
            Queue<String> recentMapsForType = recentMaps.get(matchType);
            totalCleared += recentMapsForType.size();
            recentMapsForType.clear();
        }
        plugin.getLogger().info("🔄 Todas las listas de mapas recientes reiniciadas (" + totalCleared + " mapas removidos)");
    }
}
