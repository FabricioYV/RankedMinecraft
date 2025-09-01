package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.ForfeitManager;
import org.fabricioyv.model.PlayerData;

public class ForfeitCommand implements CommandExecutor {
    private final RankedMinecraft plugin;

    public ForfeitCommand(RankedMinecraft plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        Player player = (Player) sender;
        PlayerData playerData = DatabaseManager.getPlayerByMinecraftUuid(player.getUniqueId().toString());

        if (playerData == null) {
            player.sendMessage("§c❌ No estás registrado en el sistema ranked.");
            return true;
        }

        if (!playerData.isInMatch()) {
            player.sendMessage("§c❌ No estás en una partida activa.");
            return true;
        }

        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(playerData.getMinecraftUuid());
        if (activeMatch == null) {
            player.sendMessage("§c❌ No se encontró tu partida activa.");
            return true;
        }

        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
            player.sendMessage("§c❌ Solo puedes rendirte cuando la partida está en progreso.");
            return true;
        }

        // Procesar la rendición
        ForfeitManager.proccesForfeit(activeMatch, playerData, plugin);
        return true;
    }
}
