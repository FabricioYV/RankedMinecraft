package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.ReadySystem;

public class ReadyCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        String playerUuid = player.getUniqueId().toString();

        // Buscar partida activa del jugador
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(playerUuid);

        if (activeMatch == null) {
            player.sendMessage("§c❌ No estás en una partida activa.");
            return true;
        }

        // Verificar que la partida esté en estado válido para ready
        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
            player.sendMessage("§c❌ Solo puedes usar /ready cuando la partida está iniciando.");
            return true;
        }

        // Procesar voto ready
        ReadySystem.proccessReadyVote(activeMatch, playerUuid, player);

        return true;
    }
}