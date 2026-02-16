package org.fabricioyv.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.RequeueManager;
import org.fabricioyv.util.RequeuePearlUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequeuePearlListener implements Listener {

    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 1500;

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player player = e.getPlayer();
        ItemStack item = e.getItem();
        if (!RequeuePearlUtil.isRequeuePearl(item)) return;

        // Evita que se lance como perla
        e.setCancelled(true);

        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && (now - last) < COOLDOWN_MS) return;
        cooldown.put(player.getUniqueId(), now);

        String uuid = player.getUniqueId().toString();

        // 1) Intentar obtener PlayerData desde el match activo (NO depende del TTL del cache)
        PlayerData playerData = null;
        ActiveMatch match = ActiveMatch.getPlayerActiveMatch(uuid);
        if (match != null) {
            playerData = match.getPlayerByUUID(player.getUniqueId());
        }

        // 2) Fallback: cache
        if (playerData == null) {
            playerData = PlayerDataCache.getPlayerByUuid(uuid);
        }

        if (playerData == null) {
            player.sendMessage("§cNo pude obtener tu PlayerData (cache expirado y no estás en un match activo). Usa /queue manual 1 vez.");
            return;
        }

        String lastQueueType = playerData.getLastQueueType();
        if (lastQueueType == null || lastQueueType.trim().isEmpty() || lastQueueType.equalsIgnoreCase("Unknown")) {
            player.sendMessage("§cNo se encontró tu última cola. Únete manualmente a una cola 1 vez.");
            return;
        }

        player.sendMessage("§eRequeue: procesando... (§7" + lastQueueType + "§e)");
        RequeueManager.requeuePlayer(playerData, lastQueueType);
    }
}