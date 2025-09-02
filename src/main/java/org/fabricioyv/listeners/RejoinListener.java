package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;

public class RejoinListener implements Listener {
    private final DiscordLogger logger;

    public RejoinListener(DiscordLogger logger) {
        this.logger = logger;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Buscar la partida activa
        ActiveMatch activeMatch = findActiveMatchForPlayer(player);
        if (activeMatch == null) return;

        // Marcar al jugador como desconectado
        PlayerData playerData = findPlayerData(activeMatch, player);
        if (playerData != null) {
            playerData.setInMatch(false);
            logger.warning("Jugador Desconectado", "Jugador " + player.getName() + " se desconectó de la partida " + activeMatch.getMatchId());
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Buscar la partida activa
        ActiveMatch activeMatch = findActiveMatchForPlayer(player);
        if (activeMatch == null) return;

        // Verificar si la partida sigue activa
        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) return;

        // Reasignar al jugador a su equipo
        PlayerData playerData = findPlayerData(activeMatch, player);
        if (playerData != null) {
            playerData.setInMatch(true);
            Team team = activeMatch.getTeamForPlayer(playerData);

            // Validar que el jugador tenga un equipo asignado
            if (team == null) {
                logger.error("Error de Equipo", "Jugador " + player.getName() + " no tiene un equipo asignado.");
                player.sendMessage("§cError: No tienes un equipo asignado. Contacta a un administrador.");
                return;
            }

            // Asignar al jugador al equipo correcto en Minecraft
            activeMatch.assignPlayersInMinecraft();

            logger.success("Jugador Reintegrado", "Jugador " + player.getName() + " se reintegró al equipo " + team.getDisplayName() + " en la partida " + activeMatch.getMatchId());

            // Restaurar estado del jugador en Minecraft
            restorePlayerState(player, team);
        }
    }

    private ActiveMatch findActiveMatchForPlayer(Player player) {
        return ActiveMatch.getAllActiveMatches().stream()
                .filter(match -> match.getAllPlayers().stream()
                        .anyMatch(p -> p.getMinecraftUuid().equals(player.getUniqueId().toString())))
                .findFirst()
                .orElse(null);
    }

    private PlayerData findPlayerData(ActiveMatch activeMatch, Player player) {
        return activeMatch.getAllPlayers().stream()
                .filter(p -> p.getMinecraftUuid().equals(player.getUniqueId().toString()))
                .findFirst()
                .orElse(null);
    }

    private void restorePlayerState(Player player, Team team) {
        player.sendMessage("§aHas sido reintegrado al equipo " + team.getFormattedName() + ".");
    }
}