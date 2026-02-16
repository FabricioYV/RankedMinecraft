package org.fabricioyv.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;

/**
 * Utilidad para el item de Requeue (ENDER_PEARL) en hotbar slot 4.
 * Compatible con versiones antiguas (sin PersistentDataContainer).
 */
public final class RequeuePearlUtil {

    public static final int HOTBAR_MIDDLE_SLOT = 4; // slot del medio (0..8)

    private static final String DISPLAY_NAME = "§aRequeue §7(Clic derecho)";
    private static final String LORE_MARKER = "§8[KEKE_REQUEUE]";

    private RequeuePearlUtil() {}

    public static ItemStack createRequeuePearl() {
        ItemStack item = new ItemStack(Material.ENDER_PEARL, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(DISPLAY_NAME);
            meta.setLore(Arrays.asList(
                    "§7Vuelve a entrar a tu última cola.",
                    "§7(No se lanza como perla)",
                    LORE_MARKER
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isRequeuePearl(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.ENDER_PEARL) return false;
        if (!item.hasItemMeta()) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return false;
        if (!DISPLAY_NAME.equals(meta.getDisplayName())) return false;

        if (!meta.hasLore()) return false;
        List<String> lore = meta.getLore();
        return lore != null && lore.contains(LORE_MARKER);
    }

    /**
     * Coloca la perla en el slot medio. Si el slot está ocupado, intenta mover el item a un hueco libre.
     * Si no hay huecos, dropea el item anterior al suelo.
     */
    public static void giveToMiddleSlot(Player player) {
        if (player == null) return;

        PlayerInventory inv = player.getInventory();
        ItemStack pearl = createRequeuePearl();

        ItemStack current = inv.getItem(HOTBAR_MIDDLE_SLOT);
        if (current != null && current.getType() != Material.AIR) {
            int empty = inv.firstEmpty();
            if (empty != -1) {
                inv.setItem(empty, current);
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), current);
            }
        }

        inv.setItem(HOTBAR_MIDDLE_SLOT, pearl);
        player.updateInventory();
    }

    /**
     * Quita todas las perlas de requeue del inventario (defensivo).
     */
    public static void removeFromInventory(Player player) {
        if (player == null) return;

        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (isRequeuePearl(it)) {
                inv.setItem(i, null);
            }
        }
        player.updateInventory();
    }
}