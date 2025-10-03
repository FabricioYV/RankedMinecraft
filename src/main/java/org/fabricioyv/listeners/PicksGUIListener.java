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

    private static final String PICKS_BOOK_NAME = "§6§l⚔ Libro de Picks";

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

        if (!item.getItemMeta().getDisplayName().equals(PICKS_BOOK_NAME)) {
            return;
        }

        // Cancelar evento para evitar que abra libro normal
        event.setCancelled(true);

        // Buscar partida activa del jugador
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(player.getUniqueId().toString());
        if (activeMatch == null) {
            player.sendMessage("§cNo estás en una partida activa con picks.");
            return;
        }

        // Obtener jugadores disponibles para pickear
        List<PlayerData> availablePlayers = CaptainPickSystem.getAvailablePlayers(activeMatch.getMatchId());
        if (availablePlayers == null || availablePlayers.isEmpty()) {
            player.sendMessage("§cNo hay jugadores disponibles para pickear.");
            return;
        }

        // Abrir GUI de picks
        PicksGUI.openPicksGUI(player, availablePlayers, activeMatch);
    }

    /**
     * Maneja los clicks en el GUI de picks
     */
    @EventHandler
    @SuppressWarnings("deprecation")
    public void onInventoryClick(InventoryClickEvent event) {
        // Verificar que sea el GUI de picks
        if (event.getView().getTitle() == null ||
            !event.getView().getTitle().equals("§6§l⚔ Seleccionar Jugador")) {
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

        // Verificar que clickeó en algo
        if (clickedItem == null || clickedItem.getType() == Material.AIR) {
            return;
        }

        // Verificar que sea una cabeza de jugador (1.8.8: SKULL_ITEM con durabilidad 3)
        if (clickedItem.getType() != Material.SKULL_ITEM || clickedItem.getDurability() != 3) {
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
        if (availablePlayers == null) {
            captain.sendMessage("§cError obteniendo jugadores disponibles.");
            captain.closeInventory();
            return;
        }

        // Obtener PlayerData del jugador seleccionado
        PlayerData selectedPlayer = PicksGUI.getPlayerDataFromHead(clickedItem, availablePlayers);
        if (selectedPlayer == null) {
            captain.sendMessage("§cNo se pudo identificar al jugador seleccionado.");
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

        // Llamar al sistema de picks con el jugador seleccionado
        CaptainPickSystem.handlePlayerPick(
            activeMatch.getMatchId(),
            captainData.getDiscordId(),
            selectedPlayer.getMinecraftUuid()
        );

        // Feedback visual
        captain.sendMessage("§a✓ Seleccionaste a §e" + getPlayerName(selectedPlayer));
    }

    /**
     * Obtiene el nombre de un jugador
     */
    private String getPlayerName(PlayerData playerData) {
        try {
            Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                java.util.UUID.fromString(playerData.getMinecraftUuid())
            );
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }
        } catch (Exception e) {
            // Ignorar
        }
        return "Jugador";
    }
}
