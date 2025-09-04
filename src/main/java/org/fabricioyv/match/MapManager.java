package org.fabricioyv.match;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.fabricioyv.RankedMinecraft;

import java.io.File;
import java.util.*;


public class MapManager {
    private static final Map<String, List<String>> mapPools = new HashMap<>();
    private static RankedMinecraft plugin;
    private static boolean playerVotingEnabled = true; // Por defecto habilitado

    public static void initialize(RankedMinecraft plugin) {
        MapManager.plugin = plugin;
        loadMapPools();
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
            maps5v5 = Arrays.asList("Coconut┈Mald,Summit");
        }
        mapPools.put("5v5", maps5v5);

        // Cargar mapas para 8v8
        List<String> maps8v8 = mapsConfig.getStringList("pools.8v8");
        if (maps8v8.isEmpty()) {
            // Mapas por defecto para 8v8
            maps8v8 = Arrays.asList("Golden Drought III");
        }
        mapPools.put("8v8", maps8v8);

        plugin.getLogger().info("Cargados " + maps5v5.size() + " mapas para 5v5 y " + maps8v8.size() + " mapas para 8v8");
        plugin.getLogger().info("Votación de jugadores: " + (playerVotingEnabled ? "Habilitada" : "Deshabilitada"));
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
     * Selecciona un mapa aleatorio del pool
     */
    public static String getRandomMap(String matchType) {
        List<String> maps = getAvailableMaps(matchType);
        if (maps.isEmpty()) {
            return "Warlock"; // Mapa fallback
        }
        return maps.get(new Random().nextInt(maps.size()));
    }

    /**
     * Valida si un mapa existe en el pool
     */
    public static boolean isValidMap(String mapName, String matchType) {
        return getAvailableMaps(matchType).contains(mapName);
    }


}
