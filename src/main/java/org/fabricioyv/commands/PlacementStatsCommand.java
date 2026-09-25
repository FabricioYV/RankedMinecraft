package org.fabricioyv.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.model.PlayerData;

import java.util.concurrent.CompletableFuture;

/**
 * Comando para mostrar estadísticas del sistema de placement matches
 *
 * Created by FabricioYV
 * @author FabricioYV
 */
public class PlacementStatsCommand implements CommandExecutor {

    private final RankedMinecraft plugin;

    public PlacementStatsCommand(RankedMinecraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        // Si no se especifica jugador, mostrar stats del propio jugador
        String targetPlayerName;
        CompletableFuture<PlayerData> lookup;

        if (args.length > 0) {
            // Buscar jugador por nombre especificado
            targetPlayerName = args[0];

            // Intentar obtener el UUID del jugador por nombre
            Player targetPlayer = player.getServer().getPlayer(targetPlayerName);
            if (targetPlayer != null) {
                // Jugador está online - usar su UUID
                lookup = DatabaseManager.getPlayerByMinecraftUuidAsync(targetPlayer.getUniqueId().toString());
            } else {
                // Jugador no está online - buscar en jugadores que han estado en el servidor
                org.bukkit.OfflinePlayer offlinePlayer = player.getServer().getOfflinePlayer(targetPlayerName);
                if (offlinePlayer.hasPlayedBefore()) {
                    lookup = DatabaseManager.getPlayerByMinecraftUuidAsync(offlinePlayer.getUniqueId().toString());
                } else {
                    player.sendMessage("§cJugador no encontrado: " + targetPlayerName);
                    return true;
                }
            }
        } else {
            // Mostrar stats del propio jugador
            targetPlayerName = player.getName();
            lookup = DatabaseManager.getPlayerByMinecraftUuidAsync(player.getUniqueId().toString());
        }

        String finalTargetName = targetPlayerName;
        lookup.thenAccept(playerData -> Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (playerData == null) {
                    player.sendMessage("§cNo se encontraron datos para el jugador: " + finalTargetName);
                    return;
                }
                // Mostrar estadísticas de placement
                displayPlacementStats(player, playerData, finalTargetName);
            } catch (Exception e) {
                player.sendMessage("§cError al obtener estadísticas: " + e.getMessage());
                e.printStackTrace();
            }
        }));

        return true;
    }

    private void displayPlacementStats(Player viewer, PlayerData playerData, String targetName) {
        viewer.sendMessage("§6§l=== ESTADÍSTICAS DE PLACEMENT ===");
        viewer.sendMessage("§eJugador: §f" + targetName);

        if (playerData.isInPlacement()) {
            int played = playerData.getPlacementMatchesPlayed();
            int required = PlayerData.getPlacementMatchesRequired();
            int remaining = required - played;

            viewer.sendMessage("§b🔍 EN PERÍODO DE EVALUACIÓN");
            viewer.sendMessage("§7Partidas jugadas: §e" + played + "§7/§e" + required);
            viewer.sendMessage("§7Partidas restantes: §a" + remaining);
            viewer.sendMessage("§7ELO actual: §f" + playerData.getElo() + " §7(No cambia durante placement)");

            if (remaining == 0) {
                viewer.sendMessage("§a✨ ¡Placement completo! Esperando asignación de rango y ELO definitivo.");
            } else {
                viewer.sendMessage("§e💡 Durante placement no pierdes ni ganas ELO");
            }
        } else {
            viewer.sendMessage("§a✅ PLACEMENT COMPLETADO");
            viewer.sendMessage("§7ELO actual: §f" + playerData.getElo());
            viewer.sendMessage("§7Partidas de placement: §e" + playerData.getPlacementMatchesPlayed() + "§7/§e" + PlayerData.getPlacementMatchesRequired());
        }

        // Mostrar estadísticas generales
        int wins = playerData.getWins();
        int losses = playerData.getLosses();
        int totalGames = wins + losses;
        double winRate = totalGames > 0 ? (double) wins / totalGames * 100 : 0;

        viewer.sendMessage("§7Partidas totales: §f" + totalGames + " §7(§a" + wins + "W §c" + losses + "L§7)");
        viewer.sendMessage("§7Ratio de victoria: §f" + String.format("%.1f%%", winRate));
        viewer.sendMessage("§6§l==============================");
    }
}