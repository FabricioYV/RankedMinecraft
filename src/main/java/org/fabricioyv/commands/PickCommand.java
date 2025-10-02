package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.CaptainPickSystem;
import org.fabricioyv.model.PlayerData;

import java.util.UUID;

/**
 * Comando para manejar picks de capitanes
 * Uso: /pick <jugador>
 */
public class PickCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length != 1) {
            player.sendMessage("§cUso: /pick <jugador>");
            player.sendMessage("§7Ejemplo: /pick CyDarkCat");
            return true;
        }

        // Encontrar la partida activa del jugador
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(player.getUniqueId().toString());
        if (activeMatch == null) {
            player.sendMessage("§cNo estás en una partida activa o no hay picks en curso.");
            return true;
        }

        // Buscar al jugador objetivo
        Player targetPlayer = org.bukkit.Bukkit.getPlayer(args[0]);
        if (targetPlayer == null) {
            player.sendMessage("§cJugador no encontrado: §e" + args[0]);
            player.sendMessage("§7¿Está en línea? ¿Escribiste bien el nombre?");
            return true;
        }

        // Verificar que el jugador objetivo esté en la misma partida
        ActiveMatch targetActiveMatch = ActiveMatch.getPlayerActiveMatch(targetPlayer.getUniqueId().toString());
        if (targetActiveMatch == null || !targetActiveMatch.getMatchId().equals(activeMatch.getMatchId())) {
            player.sendMessage("§cEse jugador no está en tu partida.");
            return true;
        }

        // Obtener PlayerData del remitente
        PlayerData senderData = activeMatch.getPlayerByUUID(player.getUniqueId());
        if (senderData == null) {
            player.sendMessage("§cError: No se pudo encontrar tus datos de jugador.");
            return true;
        }

        // Manejar el pick
        CaptainPickSystem.handlePlayerPick(
                activeMatch.getMatchId(),
                senderData.getDiscordId(),
                targetPlayer.getUniqueId().toString()
        );

        return true;
    }
}