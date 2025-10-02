package org.fabricioyv.cache;

import org.bukkit.Bukkit;
import org.fabricioyv.model.PlayerData;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * OPTIMIZACIÓN: Sistema de cache separado para PlayerData
 * Evita consultas repetidas a la base de datos durante partidas activas
 */
public class PlayerDataCache {
    
    // Cache de jugadores en memoria con TTL
    private static final Map<String, PlayerData> playerCacheByDiscordId = new ConcurrentHashMap<>();
    private static final Map<String, PlayerData> playerCacheByUuid = new ConcurrentHashMap<>();
    private static final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    private static final long CACHE_TTL = 60 * 1000; // 1 minuto TTL (optimizado para partidas cortas)

    /**
     * Inicializa el sistema de cache con limpieza periódica
     */
    public static void initialize() {
        // Iniciar limpieza periódica de cache cada 1 minuto (más frecuente para TTL corto)
        Bukkit.getScheduler().runTaskTimerAsynchronously(
            Bukkit.getPluginManager().getPlugin("RankedMinecraft"), 
            PlayerDataCache::cleanupExpiredCache, 
            1200L, // 1 minuto inicial
            1200L  // cada 1 minuto
        );

        Bukkit.getConsoleSender().sendMessage("§a✅ PlayerDataCache inicializado con TTL de 1 minuto y limpieza cada minuto");
    }

    /**
     * Limpia entradas de cache expiradas
     */
    private static void cleanupExpiredCache() {
        long currentTime = System.currentTimeMillis();
        int cleanedEntries = 0;
        
        // Limpiar entradas expiradas
        cacheTimestamps.entrySet().removeIf(entry -> {
            if (currentTime - entry.getValue() > CACHE_TTL) {
                String key = entry.getKey();
                if (key.startsWith("discord:")) {
                    playerCacheByDiscordId.remove(key.substring(8));
                } else if (key.startsWith("uuid:")) {
                    playerCacheByUuid.remove(key.substring(5));

                }
                return true;
            }
            return false;
        });
        
        // ELIMINADO: Log innecesario de limpieza de cache que se ejecuta cada 5 minutos
        // Solo limpiar en silencio, no spam en consola
    }

    /**
     * Cache jugador en memoria
     */
    public static void cachePlayer(PlayerData player) {
        if (player == null) return;
        
        long currentTime = System.currentTimeMillis();
        
        if (player.getDiscordId() != null) {
            playerCacheByDiscordId.put(player.getDiscordId(), player);
            cacheTimestamps.put("discord:" + player.getDiscordId(), currentTime);
        }
        
        if (player.getMinecraftUuid() != null) {
            playerCacheByUuid.put(player.getMinecraftUuid(), player);
            cacheTimestamps.put("uuid:" + player.getMinecraftUuid(), currentTime);
        }
    }

    /**
     * Verificar si cache está válido
     */
    private static boolean isCacheValid(String key) {
        Long timestamp = cacheTimestamps.get(key);
        if (timestamp == null) return false;
        return (System.currentTimeMillis() - timestamp) < CACHE_TTL;
    }

    /**
     * Obtener jugador por Discord ID desde cache
     */
    public static PlayerData getPlayerByDiscordId(String discordId) {
        String cacheKey = "discord:" + discordId;
        if (isCacheValid(cacheKey)) {
            return playerCacheByDiscordId.get(discordId);
        }
        return null;
    }

    /**
     * Obtener jugador por UUID desde cache
     */
    public static PlayerData getPlayerByUuid(String minecraftUuid) {
        String cacheKey = "uuid:" + minecraftUuid;
        if (isCacheValid(cacheKey)) {
            return playerCacheByUuid.get(minecraftUuid);
        }
        return null;
    }

    /**
     * Invalidar cache cuando se actualiza un jugador
     */
    public static void invalidatePlayer(String minecraftUuid, String discordId) {
        if (minecraftUuid != null) {
            playerCacheByUuid.remove(minecraftUuid);
            cacheTimestamps.remove("uuid:" + minecraftUuid);
        }
        if (discordId != null) {
            playerCacheByDiscordId.remove(discordId);
            cacheTimestamps.remove("discord:" + discordId);
        }
    }

}
