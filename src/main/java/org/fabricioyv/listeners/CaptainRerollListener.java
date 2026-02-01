package org.fabricioyv.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.fabricioyv.match.CaptainRerollManager;

public class CaptainRerollListener implements Listener {

    private static final String REROLL_NAME_CONTAINS = "Votar Reroll";

    private boolean isRerollMaterial(Material m) {
        if (m == null) return false;

        String name = m.name(); // ✅ evita enums inexistentes en 1.8

        // 1.8
        if ("RECORD_3".equalsIgnoreCase(name)) return true;

        // 1.13+
        if ("MUSIC_DISC_BLOCKS".equalsIgnoreCase(name)) return true;

        // fallback si en algún punto cae a PAPER
        return "PAPER".equalsIgnoreCase(name);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();

        // Solo click derecho
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (item == null) return;

        // ✅ Validar material primero
        if (!isRerollMaterial(item.getType())) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;

        String plain = ChatColor.stripColor(meta.getDisplayName());
        if (plain == null || !plain.contains(REROLL_NAME_CONTAINS)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        CaptainRerollManager.handleInteract(player);
    }
}