package org.fabricioyv.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.CaptainPickSystem;
import org.fabricioyv.match.PicksGUI;
import org.fabricioyv.model.PlayerData;

import java.util.List;

/**
 * Listener para el sistema de GUI de picks
 * Maneja la interacción con el libro de picks y los clicks en el inventario
 */
public class PicksGUIListener implements Listener {

    private static final String PICKS_BOOK_NAME = "§6§lLibro de picks";

    /**
     * Maneja el click derecho en el libro de picks
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Verificar que sea click derecho
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Verificar que sea el libro de picks
        if (item == null || item.getType() != Material.BOOK) {
            return;
        }

        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
            return;
        }

        // Comparar sin códigos de color para mayor compatibilidad
        String displayName = org.bukkit.ChatColor.stripColor(item.getItemMeta().getDisplayName());
        String expectedName = org.bukkit.ChatColor.stripColor(PICKS_BOOK_NAME);

        if (!displayName.equals(expectedName)) {
            return;
        }

        // Cancelar evento para evitar que abra libro normal
        event.setCancelled(true);

        // DEBUG: Confirmar que se detectó el libro
        player.sendMessage("§a[DEBUG] Libro de picks detectado correctamente.");

        // Buscar partida activa del jugador
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(player.getUniqueId().toString());
        if (activeMatch == null) {
            player.sendMessage("§cNo estás en una partida activa con picks.");
            return;
        }

        // Verificación: Solo capitanes pueden usar el libro de picks
        PlayerData playerData = activeMatch.getPlayerByUUID(player.getUniqueId());
        if (playerData == null) {
            player.sendMessage("§cError: No se pudieron encontrar tus datos de jugador.");
            return;
        }

        // Verificar si el jugador es uno de los capitanes
        PlayerData blueCaptain = activeMatch.getBlueCaptain();
        PlayerData redCaptain = activeMatch.getRedCaptain();

        boolean isCaptain = false;
        if (blueCaptain != null && playerData.getMinecraftUuid().equals(blueCaptain.getMinecraftUuid())) {
            isCaptain = true;
        } else if (redCaptain != null && playerData.getMinecraftUuid().equals(redCaptain.getMinecraftUuid())) {
            isCaptain = true;
        }

        if (!isCaptain) {
            player.sendMessage("§cSolo los capitanes pueden usar el libro de picks.");
            return;
        }

        // Obtener jugadores disponibles para pickear
        List<PlayerData> availablePlayers = CaptainPickSystem.getAvailablePlayers(activeMatch.getMatchId());
        if (availablePlayers == null || availablePlayers.isEmpty()) {
            player.sendMessage("§cNo hay jugadores disponibles para pickear o no es tu turno.");
            return;
        }

        // DEBUG: Informar al jugador
        player.sendMessage("§aAbriendo GUI de picks con " + availablePlayers.size() + " jugadores disponibles.");

        // Abrir GUI de picks
        PicksGUI.openPicksGUI(player, availablePlayers, activeMatch);
    }

    /**
     * Maneja los clicks en el GUI de picks
     */
    @EventHandler
    @SuppressWarnings("deprecation")
    public void onInventoryClick(InventoryClickEvent event) {
        // Verificar que sea el GUI de picks usando getTitle()
        String title = null;
        try {
            title = event.getView().getTitle();
        } catch (Exception e) {
            // Fallback para versiones más antiguas
            if (event.getInventory() != null) {
                try {
                    title = event.getInventory().getTitle();
                } catch (Exception ignored) {}
            }
        }

        if (title == null || !title.equals("§6Seleccionar Jugador")) {
            return;
        }

        // Cancelar evento para evitar que saquen items
        event.setCancelled(true);

        // Verificar que sea un jugador
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player captain = (Player) event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();

        // Verificar que clickeó en algo válido
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        // DEBUG: Mostrar información del item clickeado
        captain.sendMessage("§7[DEBUG] Item clickeado: " + clickedItem.getType() + " | Durabilidad: " + clickedItem.getDurability());

        // Verificar que sea una cabeza de jugador (1.8.8: SKULL_ITEM con durabilidad 3)
        if (clickedItem.getType() != Material.SKULL_ITEM) {
            captain.sendMessage("§7[DEBUG] No es SKULL_ITEM. Tipo: " + clickedItem.getType());
            return;
        }

        if (clickedItem.getDurability() != 3) {
            captain.sendMessage("§7[DEBUG] Durabilidad incorrecta: " + clickedItem.getDurability() + " (esperado: 3)");
            return;
        }

        // Buscar partida activa
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(captain.getUniqueId().toString());
        if (activeMatch == null) {
            captain.sendMessage("§cNo estás en una partida activa.");
            captain.closeInventory();
            return;
        }

        // Obtener jugadores disponibles
        List<PlayerData> availablePlayers = CaptainPickSystem.getAvailablePlayers(activeMatch.getMatchId());
        if (availablePlayers == null || availablePlayers.isEmpty()) {
            captain.sendMessage("§cError obteniendo jugadores disponibles.");
            captain.closeInventory();
            return;
        }

        // Obtener PlayerData del jugador seleccionado
        PlayerData selectedPlayer = PicksGUI.getPlayerDataFromHead(clickedItem, availablePlayers);
        if (selectedPlayer == null) {
            captain.sendMessage("§cNo se pudo identificar al jugador seleccionado.");
            captain.sendMessage("§7[DEBUG] Cabezas disponibles:");
            for (PlayerData pd : availablePlayers) {
                String playerName = getPlayerName(pd);
                captain.sendMessage("§7- " + playerName);
            }
            return;
        }

        // Cerrar inventario
        captain.closeInventory();

        // Ejecutar comando de pick automáticamente
        PlayerData captainData = activeMatch.getPlayerByUUID(captain.getUniqueId());
        if (captainData == null) {
            captain.sendMessage("§cError: No se pudo encontrar tus datos de jugador.");
            return;
        }

        // DEBUG: Confirmar datos antes del pick
        captain.sendMessage("§a[DEBUG] Realizando pick de " + getPlayerName(selectedPlayer));

        // Llamar al sistema de picks con el jugador seleccionado
        CaptainPickSystem.handlePlayerPick(
                activeMatch.getMatchId(),
                captain.getUniqueId().toString(),
                selectedPlayer.getMinecraftUuid()
        );

        // Feedback visual
        captain.sendMessage("§aSeleccionaste a §e" + getPlayerName(selectedPlayer));
    }

    /**
     * Obtiene el nombre de un jugador
     */
    private String getPlayerName(PlayerData playerData) {
        try {
            java.util.UUID uuid = java.util.UUID.fromString(playerData.getMinecraftUuid());

            Player online = org.bukkit.Bukkit.getPlayer(uuid);
            if (online != null) {
                return online.getName();
            }

            org.bukkit.OfflinePlayer off = org.bukkit.Bukkit.getOfflinePlayer(uuid);
            if (off != null && off.getName() != null) {
                return off.getName();
            }
        } catch (Exception ignored) {
        }
        return "Jugador";
    }
}