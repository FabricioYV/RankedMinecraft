package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.fabricioyv.cache.PlayerDataCache;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueType;
import org.fabricioyv.queue.RequeueManager;

public class RequeueCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser ejecutado por jugadores.");
            return true;
        }

        String mcUuid = player.getUniqueId().toString();

        // 0) Preferencia: última PARTIDA (no depende de TTL del cache)
        QueueType lastMatchQueue = RequeueManager.getLastMatchQueueType(mcUuid);

        // 1) Intentar obtener PlayerData desde match activo
        PlayerData playerData = null;
        ActiveMatch match = ActiveMatch.getPlayerActiveMatch(mcUuid);
        if (match != null) {
            playerData = match.getPlayerByUUID(player.getUniqueId());
        }

        // 2) Fallback: cache
        if (playerData == null) {
            playerData = PlayerDataCache.getPlayerByUuid(mcUuid);
        }

        // 3) Fallback: memoria de última partida (PlayerData guardado al iniciar match desde cola)
        if (playerData == null) {
            playerData = RequeueManager.getLastMatchPlayerData(mcUuid);
        }

        // Resolver QueueType:
        // - Primero última partida
        // - Luego lastQueueType del PlayerData
        QueueType queueType = lastMatchQueue;
        if (queueType == null && playerData != null) {
            queueType = parseQueueType(playerData.getLastQueueType());
        }

        if (playerData == null) {
            player.sendMessage("§cNo se pudo cargar tu info para requeue.");
            player.sendMessage("§7Tip: entra a una cola manualmente 1 vez para cachear tu perfil.");
            return true;
        }

        if (queueType == null) {
            player.sendMessage("§cNo se encontró tu última cola/partida.");
            player.sendMessage("§7Solución: entra a una cola manualmente 1 vez.");
            return true;
        }

        player.sendMessage("§eRequeue: procesando... (§7" + queueType.getDisplayName() + "§e)");
        RequeueManager.requeuePlayer(playerData, queueType);
        return true;
    }

    private QueueType parseQueueType(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase().replace(" ", "").replace("_", "").replace("-", "");
        if (s.equals("2v2") || s.equals("2vs2")) return QueueType.TWO_VS_TWO;
        if (s.equals("5v5") || s.equals("5vs5")) return QueueType.FIVE_VS_FIVE;
        if (s.equals("8v8") || s.equals("8vs8")) return QueueType.EIGHT_VS_EIGHT;
        try { return QueueType.valueOf(raw.trim().toUpperCase()); } catch (Exception e) { return null; }
    }
}