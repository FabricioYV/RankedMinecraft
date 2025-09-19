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

        // ASEGURAR que la carpeta del plugin existe
        if (!plugin.getDataFolder().exists()) {
            boolean created = plugin.getDataFolder().mkdirs();
            plugin.getLogger().info("📁 Creando carpeta de datos del plugin: " + plugin.getDataFolder().getPath() + 
                                  (created ? " (exitoso)" : " (falló)"));
        }

        loadMapPools();
        initializeRecentMapsTracking();

        // VALIDACIÓN POST-CARGA
        validateMapPools();
    }

    private static void loadMapPools() {
        plugin.getLogger().info("🔄 Iniciando carga de pools de mapas...");

        // Ruta del archivo de mapas
        File mapsFile = new File(plugin.getDataFolder(), "maps.yml");
        plugin.getLogger().info("📍 Ruta del archivo maps.yml: " + mapsFile.getAbsolutePath());

        // Si el archivo no existe en la carpeta del plugin, copiarlo desde recursos
        if (!mapsFile.exists()) {
            plugin.getLogger().info("📄 Copiando archivo maps.yml desde recursos...");
            try {
                plugin.saveResource("maps.yml", false);
                plugin.getLogger().info("✅ Archivo maps.yml copiado exitosamente");
            } catch (Exception e) {
                plugin.getLogger().severe("❌ Error copiando maps.yml desde recursos: " + e.getMessage());
                plugin.getLogger().info("🔧 Usando mapas por defecto como fallback");
                loadDefaultMaps();
                return;
            }
        }

        // Cargar el archivo YAML
        FileConfiguration mapsConfig;
        try {
            mapsConfig = YamlConfiguration.loadConfiguration(mapsFile);
            plugin.getLogger().info("📋 Archivo maps.yml cargado exitosamente");
        } catch (Exception e) {
            plugin.getLogger().severe("❌ Error cargando maps.yml: " + e.getMessage());
            plugin.getLogger().info("🔧 Usando mapas por defecto como fallback");
            loadDefaultMaps();
            return;
        }

        // DEBUGGING: Mostrar todas las keys del archivo
        Set<String> keys = mapsConfig.getKeys(true);
        plugin.getLogger().info("🔍 Keys encontradas en maps.yml: " + keys);

        // Cargar configuración de votación
        playerVotingEnabled = mapsConfig.getBoolean("voting.enable_player_voting", true);
        plugin.getLogger().info("🗳️ Votación de jugadores: " + (playerVotingEnabled ? "Habilitada" : "Deshabilitada"));

        // Cargar mapas para 5v5
        List<String> maps5v5 = mapsConfig.getStringList("pools.5v5");
        plugin.getLogger().info("🔍 Mapas 5v5 encontrados en archivo: " + maps5v5.size() + " mapas");

        if (!maps5v5.isEmpty()) {
            plugin.getLogger().info("📋 Lista 5v5: " + String.join(", ", maps5v5));
            mapPools.put("5v5", new ArrayList<>(maps5v5));
        } else {
            plugin.getLogger().warning("⚠️ No se encontraron mapas 5v5 en pools.5v5, usando mapas por defecto");
            List<String> defaultMaps5v5 = Arrays.asList("Siege", "Smoke┈KotH┈5v5", "Topaz", "Willow", "Factori",
                                                       "Revolution", "Space┈Race┈1969", "Rusteze┈5v5");
            mapPools.put("5v5", defaultMaps5v5);
        }

        // Cargar mapas para 8v8
        List<String> maps8v8 = mapsConfig.getStringList("pools.8v8");
        plugin.getLogger().info("🔍 Mapas 8v8 encontrados en archivo: " + maps8v8.size() + " mapas");

        if (!maps8v8.isEmpty()) {
            plugin.getLogger().info("📋 Lista 8v8: " + String.join(", ", maps8v8));
            mapPools.put("8v8", new ArrayList<>(maps8v8));
        } else {
            plugin.getLogger().warning("⚠️ No se encontraron mapas 8v8 en pools.8v8, usando mapas por defecto");
            List<String> defaultMaps8v8 = Arrays.asList("Java II", "Jurassic", "NextGen", "Gobi", "Oriental");
            mapPools.put("8v8", defaultMaps8v8);
        }

        int total5v5 = mapPools.get("5v5").size();
        int total8v8 = mapPools.get("8v8").size();
        plugin.getLogger().info("✅ Pools de mapas cargados exitosamente: " + total5v5 + " mapas 5v5, " + total8v8 + " mapas 8v8");
        plugin.getLogger().info("🔄 Sistema anti-repetición: Evitará repetir los últimos " + MAX_RECENT_MAPS + " mapas");
    }

    /**
     * NUEVO: Carga mapas por defecto como fallback si el archivo no funciona
     */
    private static void loadDefaultMaps() {
        plugin.getLogger().warning("🔧 Cargando mapas por defecto como fallback...");

        List<String> defaultMaps5v5 = Arrays.asList("Siege", "Smoke┈KotH┈5v5", "Topaz", "Willow", "Factori",
                                                   "Revolution", "Space┈Race┈1969", "Rusteze┈5v5");
        List<String> defaultMaps8v8 = Arrays.asList("Java II", "Jurassic", "NextGen", "Gobi", "Oriental");

        mapPools.put("5v5", new ArrayList<>(defaultMaps5v5));
        mapPools.put("8v8", new ArrayList<>(defaultMaps8v8));

        playerVotingEnabled = true;

        plugin.getLogger().info("�� Mapas por defecto cargados: " + defaultMaps5v5.size() + " para 5v5, " + defaultMaps8v8.size() + " para 8v8");
    }

    /**
     * NUEVO: Valida que los pools de mapas estén correctamente cargados
     */
    private static void validateMapPools() {
        plugin.getLogger().info("🔍 Validando pools de mapas cargados...");

        List<String> maps5v5 = mapPools.get("5v5");
        List<String> maps8v8 = mapPools.get("8v8");

        if (maps5v5 == null || maps5v5.isEmpty()) {
            plugin.getLogger().severe("❌ ERROR CRÍTICO: Pool de mapas 5v5 está vacío!");
            return;
        }

        if (maps8v8 == null || maps8v8.isEmpty()) {
            plugin.getLogger().severe("❌ ERROR CRÍTICO: Pool de mapas 8v8 está vacío!");
            return;
        }

        plugin.getLogger().info("✅ Validación exitosa:");
        plugin.getLogger().info("   - Mapas 5v5: " + maps5v5.size() + " disponibles");
        plugin.getLogger().info("   - Mapas 8v8: " + maps8v8.size() + " disponibles");
        plugin.getLogger().info("   - Votación: " + (playerVotingEnabled ? "Habilitada" : "Deshabilitada"));
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
        // Validar primero que el tipo de match sea válido
        if (matchType == null || matchType.trim().isEmpty()) {
            plugin.getLogger().severe("❌ ERROR: Tipo de match inválido (null o vacío)");
            return getFallbackMap("5v5"); // Default fallback
        }

        List<String> allMaps = getAvailableMaps(matchType);

        // Log detallado para debugging
        plugin.getLogger().info("🔍 Seleccionando mapa aleatorio para '" + matchType + "'");
        plugin.getLogger().info("📊 Total mapas configurados: " + allMaps.size());
        plugin.getLogger().info("📋 Mapas disponibles: " + String.join(", ", allMaps));

        if (allMaps.isEmpty()) {
            plugin.getLogger().severe("❌ ERROR CRÍTICO: No hay mapas configurados para '" + matchType + "'");
            plugin.getLogger().severe("📁 Pools disponibles: " + mapPools.keySet());
            plugin.getLogger().severe("🔧 Usando mapa de emergencia...");
            return getFallbackMap(matchType);
        }

        // Obtener mapas recientes para este tipo de partida
        Queue<String> recentMapsForType = recentMaps.computeIfAbsent(matchType, k -> new ConcurrentLinkedQueue<>());

        // Crear lista de mapas disponibles (excluyendo los recientes)
        List<String> availableMaps = new ArrayList<>();
        for (String map : allMaps) {
            if (!recentMapsForType.contains(map)) {
                availableMaps.add(map);
            }
        }

        plugin.getLogger().info("🎯 Mapas después de filtrar recientes: " + availableMaps.size() + "/" + allMaps.size());
        if (!recentMapsForType.isEmpty()) {
            plugin.getLogger().info("🚫 Mapas recientes excluidos: " + String.join(", ", recentMapsForType));
        }

        // Si no hay mapas disponibles (todos están en recientes), reiniciar la lista de recientes
        if (availableMaps.isEmpty()) {
            plugin.getLogger().warning("⚠️ Todos los mapas de '" + matchType + "' están en recientes. Reiniciando lista...");
            availableMaps = new ArrayList<>(allMaps);
            recentMapsForType.clear();
            plugin.getLogger().info("✅ Lista de recientes reiniciada. Mapas disponibles: " + availableMaps.size());
        }

        // Validación final antes de seleccionar
        if (availableMaps.isEmpty()) {
            plugin.getLogger().severe("❌ ERROR FATAL: No se pudieron obtener mapas disponibles para '" + matchType + "'");
            return getFallbackMap(matchType);
        }

        // Seleccionar mapa aleatorio
        String selectedMap = availableMaps.get(new Random().nextInt(availableMaps.size()));

        // Registrar el mapa como usado
        recordMapUsage(matchType, selectedMap);

        plugin.getLogger().info("🎲 ✅ Mapa seleccionado para " + matchType + ": " + selectedMap);
        plugin.getLogger().info("📊 Estado actual - Recientes: " + recentMapsForType.size() + "/" + MAX_RECENT_MAPS);

        return selectedMap;
    }

    /**
     * Obtiene un mapa fallback basado en los mapas configurados en maps.yml
     */
    private static String getFallbackMap(String matchType) {
        // Usar mapas hardcodeados que coinciden con los del maps.yml como último recurso
        if (matchType.equals("8v8")) {
            List<String> fallback8v8 = Arrays.asList("Java II", "Jurassic", "NextGen", "Gobi", "Oriental");
            String selected = fallback8v8.get(new Random().nextInt(fallback8v8.size()));
            plugin.getLogger().warning("⚠️ Usando mapa fallback 8v8: " + selected);
            return selected;
        } else {
            List<String> fallback5v5 = Arrays.asList("Siege", "Smoke┈KotH┈5v5", "Topaz", "Willow", "Factori",
                                                   "Revolution", "Space┈Race┈1969", "Rusteze┈5v5");
            String selected = fallback5v5.get(new Random().nextInt(fallback5v5.size()));
            plugin.getLogger().warning("⚠️ Usando mapa fallback 5v5: " + selected);
            return selected;
        }
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
        return recentMapsForType == null ? new ArrayList<>() : new ArrayList<>(recentMapsForType);
    }

    /**
     * Selecciona mapas para votación, evitando mapas recientes si es necesario
     * Los jugadores pueden votar por cualquier mapa disponible
     */
    public static List<String> getMapsForVoting(String matchType, int count) {
        List<String> allMaps = getAvailableMaps(matchType);
        if (allMaps.size() <= count) {
            return allMaps;
        }
        
        Collections.shuffle(allMaps);
        return allMaps.subList(0, count);
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
