package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.Rank;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GUI de Picks - Interfaz gráfica para que los capitanes seleccionen jugadores
 * Muestra cabezas de jugadores con información detallada (ELO, rango, stats)
 */
public class PicksGUI {

    private static final String GUI_TITLE = "§6Seleccionar Jugador";
    private static final int GUI_SIZE = 54; // 6 filas

    /**
     * Abre el GUI de picks para un capitán mostrando los jugadores disponibles
     */
    public static void openPicksGUI(Player captain, List<PlayerData> availablePlayers, ActiveMatch activeMatch) {
        Inventory gui = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);

        // Agregar cabezas de jugadores disponibles
        int slot = 10; // Comenzar en slot 10 para layout más limpio
        for (PlayerData playerData : availablePlayers) {
            if (slot >= 44) break; // Límite de slots disponibles

            ItemStack playerHead = createPlayerHead(playerData);
            gui.setItem(slot, playerHead);

            // Avanzar al siguiente slot (con espaciado)
            slot++;
            if ((slot + 1) % 9 == 0) slot += 2; // Saltar bordes
        }

        // Agregar item decorativo de información
        ItemStack infoItem = createInfoItem(captain, activeMatch);
        gui.setItem(4, infoItem);

        // Abrir GUI
        captain.openInventory(gui);
    }

    /**
     * Crea una cabeza de jugador con información detallada
     */
    @SuppressWarnings("deprecation")
    private static ItemStack createPlayerHead(PlayerData playerData) {
        // Compatible con 1.8.8: usar SKULL_ITEM con durabilidad 3
        ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        if (meta != null) {
            // Obtener nombre del jugador (online u offline)
            String playerName = "Jugador";
            try {
                UUID uuid = UUID.fromString(playerData.getMinecraftUuid());

                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    playerName = online.getName();
                } else {
                    org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                    if (off != null && off.getName() != null) {
                        playerName = off.getName();
                    }
                }
            } catch (Exception ignored) {
            }

            meta.setDisplayName("§a§l" + playerName);

            // Establecer textura de la cabeza (setOwner usa el nombre, no importa si está offline)
            try {
                meta.setOwner(playerName);
            } catch (Exception ignored) {
            }

            // Crear lore con información detallada
            List<String> lore = new ArrayList<>();
            lore.add("");

            // Información de rango y ELO
            if (playerData.isInPlacement()) {
                lore.add("§7Rango: §e⭐ En Evaluación");
                lore.add("§7Partidas: §f" + playerData.getPlacementMatchesPlayed() + "/8");
            } else {
                Rank rank = Rank.getRankByElo(playerData.getElo());
                lore.add("§7Rango: " + rank.getFormattedName());
                lore.add("§7ELO: §f" + playerData.getElo());
            }

            lore.add("§7MMR: §f" + String.format("%.0f", playerData.getMmr()));
            lore.add("");

            // Estadísticas generales
            lore.add("§7Estadísticas:");
            lore.add("§7├ Partidas: §f" + playerData.getGamesPlayed());
            lore.add("§7├ Victorias: §a" + playerData.getWins());
            lore.add("§7├ Derrotas: §c" + playerData.getLosses());
            lore.add("§7└ Win Rate: §e" + String.format("%.1f%%", playerData.getWinRate()));
            lore.add("");

            // K/D ratio
            double kdRatio = playerData.getTotalDeaths() > 0
                    ? (double) playerData.getTotalKills() / playerData.getTotalDeaths()
                    : playerData.getTotalKills();
            lore.add("§7K/D Ratio: §f" + String.format("%.2f", kdRatio));
            lore.add("");

            // Indicación de acción
            lore.add("§e§l▶ Click para seleccionar");

            meta.setLore(lore);
            head.setItemMeta(meta);
        }

        return head;
    }

    /**
     * Crea item de información sobre el proceso de picks
     */
    private static ItemStack createInfoItem(Player captain, ActiveMatch activeMatch) {
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta meta = info.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§6§l📖 Información de Picks");

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Capitán: §a" + captain.getName());
            lore.add("§7Match ID: §f" + activeMatch.getMatchId().substring(0, 8) + "...");
            lore.add("");
            lore.add("§7§oSelecciona un jugador haciendo click");
            lore.add("§7§oen su cabeza para agregarlo a tu equipo");

            meta.setLore(lore);
            info.setItemMeta(meta);
        }

        return info;
    }

    /**
     * Obtiene el PlayerData desde una cabeza de jugador en el inventario
     */
    @SuppressWarnings("deprecation")
    public static PlayerData getPlayerDataFromHead(ItemStack item, List<PlayerData> availablePlayers) {
        if (item == null) {
            return null;
        }

        // Verificar si es una cabeza de jugador (1.8.8: SKULL_ITEM con durabilidad 3)
        if (item.getType() != Material.SKULL_ITEM || item.getDurability() != 3) {
            return null;
        }

        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta == null) {
            return null;
        }

        // En 1.8.8 se usa getOwner() en lugar de getOwningPlayer()
        String ownerName = meta.getOwner();
        if (ownerName == null) {
            return null;
        }

        // Buscar PlayerData por nombre (online u offline)
        for (PlayerData playerData : availablePlayers) {
            try {
                UUID uuid = UUID.fromString(playerData.getMinecraftUuid());

                String name = null;
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    name = online.getName();
                } else {
                    org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
                    if (off != null && off.getName() != null) {
                        name = off.getName();
                    }
                }

                if (name != null && name.equalsIgnoreCase(ownerName)) {
                    return playerData;
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }
}