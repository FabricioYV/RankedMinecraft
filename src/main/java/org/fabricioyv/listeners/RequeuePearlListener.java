package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueType;
import org.fabricioyv.queue.RequeueManager;
import org.fabricioyv.util.RequeuePearlUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequeuePearlListener implements Listener {

    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 1200;

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player player = e.getPlayer();
        ItemStack item = e.getItem();
        if (!RequeuePearlUtil.isRequeuePearl(item)) return;

        // Evitar que se lance como perla (y evitar consumos raros)
        e.setCancelled(true);

        // Cooldown anti-spam
        long now = System.currentTimeMillis();
        Long last = cooldown.get(player.getUniqueId());
        if (last != null && (now - last) < COOLDOWN_MS) return;
        cooldown.put(player.getUniqueId(), now);

        // Restaurar la perla al slot (por si algún plugin/versión la consume igual)
        Bukkit.getScheduler().runTaskLater(org.fabricioyv.RankedMinecraft.getInstance(), () -> {
            RequeuePearlUtil.forceRestoreInMiddleSlot(player);
        }, 1L);

        String mcUuid = player.getUniqueId().toString();

        // Preferencia: última PARTIDA (no depende del cache TTL)
        QueueType lastMatchQueue = RequeueManager.getLastMatchQueueType(mcUuid);

        // 1) Intentar desde Match Activo
        PlayerData playerData = null;
        ActiveMatch match = ActiveMatch.getPlayerActiveMatch(mcUuid);
        if (match != null) {
            playerData = match.getPlayerByUUID(player.getUniqueId());
        }

        // 2) Fallback: Cache
        if (playerData == null) {
            playerData = PlayerDataCache.getPlayerByUuid(mcUuid);
        }

        // 3) Fallback: memoria de última partida (PlayerData guardado al iniciar match desde cola)
        if (playerData == null) {
            playerData = RequeueManager.getLastMatchPlayerData(mcUuid);
        }

        QueueType queueType = lastMatchQueue;
        if (queueType == null && playerData != null) {
            queueType = parseQueueType(playerData.getLastQueueType());
        }

        if (playerData == null) {
            player.sendMessage("§cRequeue: No se pudo cargar tu info.");
            player.sendMessage("§7Tip: entra a una cola manualmente 1 vez para cachear tu perfil.");
            return;
        }

        if (queueType == null) {
            player.sendMessage("§cRequeue: No se encontró tu última cola/partida.");
            player.sendMessage("§7Solución: entra a una cola manualmente 1 vez.");
            return;
        }

        RequeueManager.requeuePlayer(playerData, queueType);
    }

    private QueueType parseQueueType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
        if (s.equals("2v2") || s.equals("2vs2")) return QueueType.TWO_VS_TWO;
        if (s.equals("5v5") || s.equals("5vs5")) return QueueType.FIVE_VS_FIVE;
        if (s.equals("8v8") || s.equals("8vs8")) return QueueType.EIGHT_VS_EIGHT;
        try { return QueueType.valueOf(raw.trim().toUpperCase()); } catch (Exception e) { return null; }
    }
}