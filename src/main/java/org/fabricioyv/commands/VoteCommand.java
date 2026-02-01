package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.ActiveMatch;

public class VoteCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§cUso: /votemap <número>");
            return true;
        }

        try {
            int mapIndex = Integer.parseInt(args[0]);

            ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(player.getUniqueId().toString());
            if (activeMatch == null) {
                player.sendMessage("§cNo estás en una votación activa.");
                return true;
            }

            if (activeMatch.getMapVoting() == null) {
                player.sendMessage("§cNo hay votación activa en este momento.");
                return true;
            }

            boolean success = activeMatch.getMapVoting().processVote(player.getUniqueId().toString(), mapIndex);

            if (!success) {
                player.sendMessage("§cVoto inválido. Asegúrate de usar un número válido.");
            }

        } catch (NumberFormatException e) {
            player.sendMessage("§cPor favor, introduce un número válido.");
        }

        return true;
    }

}