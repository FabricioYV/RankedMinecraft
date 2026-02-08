package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.queue.QueueType;
import org.fabricioyv.queue.RequeueManager;

public class RequeueCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] strings) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        PlayerData playerData = PlayerDataCache.getPlayerByUuid(player.getUniqueId().toString());
        if (playerData == null) {
            player.sendMessage("§cNo se pudo cargar tu información. Intenta nuevamente más tarde.");
            return true;
        }

        String lastQueueType = playerData.getLastQueueType();
        if (lastQueueType == null) {
            player.sendMessage("§cNo se encontró información de tu última cola. Intenta unirte manualmente a una cola.");
            return true;
        }

        RequeueManager.requeuePlayer(playerData, lastQueueType);
        player.sendMessage("§aHas sido reingresado a la cola de " + lastQueueType + " correctamente.");

        return false;
    }
}
