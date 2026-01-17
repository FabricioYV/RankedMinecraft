package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.AbandonmentDetectionSystem;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.logging.DiscordLogger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Listener para manejar el rejoin de jugadores a partidas activas
 */
public class PlayerRejoinListener implements Listener {

    private final RankedMinecraft plugin;
    private final DiscordLogger logger;
    private final AbandonmentDetectionSystem abandonmentSystem;

    public PlayerRejoinListener(RankedMinecraft plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.abandonmentSystem = plugin.getAbandonmentDetectionSystem();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();

        // NUEVO: Notificar al sistema de abandono sobre la reconexión
        if (abandonmentSystem != null) {
            abandonmentSystem.onPlayerReconnect(playerUuid.toString());
        }

        // OPTIMIZACIÓN: Mover búsqueda a thread asíncrono para no bloquear main thread
        CompletableFuture.runAsync(() -> {
            // Verificar si el jugador estaba en una partida activa
            ActiveMatch activeMatch = findActiveMatchForPlayer(playerUuid);

            if (activeMatch != null) {
                // Encontrar el equipo del jugador
                Team playerTeam = findPlayerTeam(activeMatch, playerUuid);

                if (playerTeam != null) {
                    logger.info("Rejoin Detectado",
                            String.format("Jugador %s reconectándose a partida %s en equipo %s",
                                    player.getName(), activeMatch.getMatchId(), playerTeam.getDisplayName()));

                    // Volver al main thread para operaciones de Bukkit
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        handlePlayerRejoin(player, activeMatch, playerTeam);
                    }, 60L); // 3 segundos de espera
                }
            }
        });
    }

    /**
     * Busca si el jugador está en alguna partida activa
     */
    private ActiveMatch findActiveMatchForPlayer(UUID playerUuid) {
        for (ActiveMatch match : ActiveMatch.getActiveMatches().values()) {
            for (List<PlayerData> team : match.getTeams().values()) {
                for (PlayerData playerData : team) {
                    if (playerData.getUuid().equals(playerUuid)) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Encuentra el equipo al que pertenece el jugador
     */
    private Team findPlayerTeam(ActiveMatch match, UUID playerUuid) {
        for (Team team : Team.values()) {
            List<PlayerData> teamPlayers = match.getTeams().get(team);
            if (teamPlayers != null) {
                for (PlayerData playerData : teamPlayers) {
                    if (playerData.getUuid().equals(playerUuid)) {
                        return team;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Maneja el rejoin del jugador a la partida
     */
    private void handlePlayerRejoin(Player player, ActiveMatch activeMatch, Team playerTeam) {
        try {
            // 1. Asignar al equipo en PGM
            assignPlayerToTeamInPGM(player, playerTeam);


            // 3. Notificar al jugador
            player.sendMessage("§a✅ Te has reconectado a la partida " + activeMatch.getMatchId());
            player.sendMessage("§b🔵 Asignado al equipo: " + playerTeam.getDisplayName());

            // 4. Log del rejoin
            logger.success("Rejoin Completado",
                    String.format("Jugador %s reconectado exitosamente a %s (Equipo: %s)",
                            player.getName(), activeMatch.getMatchId(), playerTeam.getDisplayName()));

        } catch (Exception e) {
            logger.systemError("PlayerRejoinListener",
                    "Error manejando rejoin de " + player.getName(), e.getMessage());

            player.sendMessage("§c❌ Error al reconectarte a la partida. Contacta a un administrador.");
        }
    }

    /**
     * Asigna al jugador al equipo correcto en PGM
     */
    private void assignPlayerToTeamInPGM(Player player, Team playerTeam) {
        try {
            String teamName = playerTeam == Team.BLUE ? "blue" : "red";

            // Ejecutar comando de PGM para asignar al equipo (comando correcto: team force)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    String.format("team force %s %s", player.getName(), teamName));

            logger.debug("PGM Team Assignment",
                    String.format("Comando ejecutado: team force %s %s", player.getName(), teamName));

        } catch (Exception e) {
            logger.error("PGM Assignment Failed",
                    "Error asignando " + player.getName() + " al equipo " + playerTeam.getDisplayName() + ": " + e.getMessage());
        }
    }



}