package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.MapManager;

public class VoteCommand implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        Player player = (Player) sender;

        // Gate por modo
        String mode = MapManager.getSelectionMode();
        if (!"VOTING".equalsIgnoreCase(mode)) {
            player.sendMessage("§cLa votación de mapas está desactivada. §7Modo actual: §f" + mode);
            return true;
        }
        if (!MapManager.isPlayerVotingEnabled()) {
            player.sendMessage("§cLa votación de mapas está desactivada por configuración. §7(voting.enable_player_voting=false)");
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§cUso: /votemap <número>");
            return true;
        }

        int mapIndex;
        try {
            mapIndex = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cPor favor, introduce un número válido.");
            return true;
        }

        if (mapIndex <= 0) {
            player.sendMessage("§cNúmero inválido. Usa un número mayor a 0.");
            return true;
        }

        ActiveMatch activeMatch = ActiveMatch.findActiveMatchForPlayer(player.getUniqueId().toString());
        if (activeMatch == null) {
            player.sendMessage("§cNo estás en una partida activa.");
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

        return true;
    }
}