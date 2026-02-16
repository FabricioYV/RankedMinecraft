package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.RequeueManager;

public class RequeueCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        String uuid = player.getUniqueId().toString();

        // 1) Intentar obtener PlayerData desde match activo (no depende del TTL del cache)
        PlayerData playerData = null;
        ActiveMatch match = ActiveMatch.getPlayerActiveMatch(uuid);
        if (match != null) {
            playerData = match.getPlayerByUUID(player.getUniqueId());
        }

        // 2) Fallback: cache
        if (playerData == null) {
            playerData = PlayerDataCache.getPlayerByUuid(uuid);
        }

        if (playerData == null) {
            player.sendMessage("§cNo se pudo cargar tu información (cache expirado y no estás en un match activo).\n§7Tip: entra a una cola manualmente 1 vez.");
            return true;
        }

        String lastQueueType = playerData.getLastQueueType();
        if (lastQueueType == null || lastQueueType.trim().isEmpty() || lastQueueType.equalsIgnoreCase("Unknown")) {
            player.sendMessage("§cNo se encontró información de tu última cola. Únete manualmente a una cola 1 vez.");
            return true;
        }

        player.sendMessage("§eRequeue: procesando... (§7" + lastQueueType + "§e)");
        RequeueManager.requeuePlayer(playerData, lastQueueType);

        return true;
    }
}