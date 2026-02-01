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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cEste comando solo puede ser usado por jugadores.");
            return true;
        }

        Player player = (Player) sender;
        String uuidStr = player.getUniqueId().toString();

        // 1) Fuente de verdad: ActiveMatch (no dependemos de BD para validar si está en match)
        ActiveMatch activeMatch = ActiveMatch.getPlayerActiveMatch(uuidStr);
        if (activeMatch == null) {
            player.sendMessage("§c❌ No estás en una partida activa.");
            return true;
        }

        // 2) Validar estado
        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
            player.sendMessage("§c❌ Solo puedes rendirte cuando la partida está en progreso.");
            return true;
        }

        // 3) PlayerData desde el match (mejor), con fallback a BD
        PlayerData playerData = activeMatch.getPlayerByUUID(player.getUniqueId());
        if (playerData == null) {
            playerData = DatabaseManager.getPlayerByMinecraftUuid(uuidStr);
        }

        if (playerData == null) {
            player.sendMessage("§c❌ No estás registrado en el sistema ranked.");
            return true;
        }

        // Si por alguna razón te trae playerData, pero no corresponde al match actual
        // (esto es raro, pero evita bugs fantasma)
        if (playerData.getCurrentMatchId() != null && !playerData.getCurrentMatchId().equals(activeMatch.getMatchId())) {
            player.sendMessage("§c❌ Tu estado de jugador no coincide con la partida actual.");
            return true;
        }

        // Procesar rendición
        // OJO: si tu método se llama processForfeit, cambia el nombre aquí.
        ForfeitManager.proccesForfeit(activeMatch, playerData, plugin);
        return true;
    }
}