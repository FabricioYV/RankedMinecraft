package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.MapManager;

public class VetoCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando es solo para jugadores.");
            return true;
        }

        Player player = (Player) sender;

        // Gate por modo
        String mode = MapManager.getSelectionMode();
        if (!"VETO".equalsIgnoreCase(mode)) {
            player.sendMessage("§cEl veto de mapas está desactivado. §7Modo actual: §f" + mode);
            return true;
        }

        if (args.length != 1) {
            player.sendMessage("§cUso: /veto <número>");
            return true;
        }

        int mapIndex;
        try {
            mapIndex = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cPor favor, ingresa un número válido de mapa.");
            return true;
        }

        if (mapIndex <= 0) {
            player.sendMessage("§cNúmero inválido. Usa un número mayor a 0.");
            return true;
        }

        ActiveMatch match = ActiveMatch.findActiveMatchForPlayer(player.getUniqueId().toString());

        if (match == null) {
            player.sendMessage("§cNo estás en una partida activa.");
            return true;
        }

        if (match.getMapVeto() == null) {
            player.sendMessage("§cNo hay una fase de vetos activa en tu partida.");
            return true;
        }

        boolean success = match.getMapVeto().processVeto(player.getUniqueId().toString(), mapIndex);

        if (!success) {
            player.sendMessage("§cNo es tu turno, ingresaste un número inválido o no eres el capitán.");
        }

        return true;
    }
}