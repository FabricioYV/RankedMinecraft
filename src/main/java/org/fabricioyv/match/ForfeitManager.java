package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ForfeitManager {

    private static final Map<String, ForfeitVote> forfeitVotes = new ConcurrentHashMap<>();

    public static void proccesForfeit(ActiveMatch activeMatch, PlayerData playerData, RankedMinecraft plugin) {
        DiscordLogger logger = plugin.getDiscordBot().getLogger();
        String matchId = activeMatch.getMatchId();

        Team playerTeam = getPlayerTeam(activeMatch, playerData);
        if (playerTeam == null) {
            sendMessage(playerData, "§c❌ No se pudo determinar tu equipo.");
            return;
        }
        //Obtener o crear la votación de rendición
        ForfeitVote vote = forfeitVotes.computeIfAbsent(matchId, k -> new ForfeitVote(activeMatch));

        //Register the vote
        if (vote.addVote(playerData.getMinecraftUuid(), playerTeam)) {
            String playerName = getPlayerName(playerData);

            // Notificar al equipo
            notifyTeam(activeMatch, playerTeam,
                    "§e⚠️ " + playerName + " §evotó por rendirse. §7(" +
                            vote.getVoteCount(playerTeam) + "/" + vote.getRequiredVotes(playerTeam) + ")");

            logger.info("Voto de Rendición",
                    playerName + " votó rendirse en " + matchId + " - Equipo " + playerTeam.getDisplayName());

            // Verificar si se alcanzó el consenso
            if (vote.hasConsensus(playerTeam)) {
                executeForfeit(activeMatch, playerTeam, plugin, logger);
                forfeitVotes.remove(matchId);
            }
        } else {
            sendMessage(playerData, "§c❌ Ya votaste por rendirte.");
        }
    }
    private static void executeForfeit(ActiveMatch activeMatch, Team forfeitTeam,
                                       RankedMinecraft plugin, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();
        Team winnerTeam = forfeitTeam == Team.BLUE ? Team.RED : Team.BLUE;


        // MARCAR LA PARTIDA COMO FINALIZADA POR FORFEIT ANTES DE EJECUTAR COMANDO
        activeMatch.setFinishedByForfeit(true);

        // Anunciar la rendición
        announceToAll(activeMatch, "§c§l⚠️ RENDICIÓN ⚠️");
        announceToAll(activeMatch, "§eEl equipo " + forfeitTeam.getFormattedName() + " §ese ha rendido.");
        announceToAll(activeMatch, "§a🏆 Victoria para el equipo " + winnerTeam.getFormattedName() + "§a!");

        logger.matchEvent(matchId, "Partida Terminada por Rendición",
                "Equipo " + forfeitTeam.getDisplayName() + " se rindió",
                activeMatch.getAllPlayers().size());

        // Ejecutar tu comando personalizado aquí
        executeCustomCommand(activeMatch, winnerTeam);

        // Esperar un momento y finalizar la partida
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            MatchFinisher.finishMatch(activeMatch, winnerTeam, plugin, logger);
        }, 60L); // 3 segundos
    }
    private static void executeCustomCommand(ActiveMatch activeMatch, Team winnerTeam) {
        // Aquí puedes ejecutar el comando que necesites
        // Por ejemplo:
        String command = "finish " + winnerTeam.getPgmName();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

        // O si necesitas más comandos específicos:
    }
    private static void notifyTeam(ActiveMatch activeMatch, Team team, String message) {
        List<PlayerData> teamPlayers = activeMatch.getTeams().get(team);
        if (teamPlayers != null) {
            for (PlayerData playerData : teamPlayers) {
                sendMessage(playerData, message);
            }
        }
    }
    private static Team getPlayerTeam(ActiveMatch activeMatch, PlayerData playerData) {
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            if (entry.getValue().contains(playerData)) {
                return entry.getKey();
            }
        }
        return null;
    }
    private static void announceToAll(ActiveMatch activeMatch, String message) {
        for (PlayerData playerData : activeMatch.getAllPlayers()) {
            sendMessage(playerData, message);
        }
    }
    private static void sendMessage(PlayerData playerData, String message) {
        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
        if (mcPlayer != null && mcPlayer.isOnline()) {
            mcPlayer.sendMessage(message);
        }
    }
    private static String getPlayerName(PlayerData playerData) {
        Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
        return mcPlayer != null ? mcPlayer.getName() : "Jugador";
    }
    public static void cleanupMatchVotes(String matchId) {
        forfeitVotes.remove(matchId);
    }
    // Clase interna para manejar votos
    private static class ForfeitVote {
        private final ActiveMatch match;
        private final Map<String, Team> votes = new ConcurrentHashMap<>();

        public ForfeitVote(ActiveMatch match) {
            this.match = match;
        }

        public boolean addVote(String playerUuid, Team team) {
            return votes.putIfAbsent(playerUuid, team) == null;
        }

        public int getVoteCount(Team team) {
            return (int) votes.values().stream().filter(t -> t == team).count();
        }

        public int getRequiredVotes(Team team) {
            List<PlayerData> teamPlayers = match.getTeams().get(team);
            if (teamPlayers == null) return 1;

            int teamSize = teamPlayers.size();
            if (teamSize == 5) {
                return 4; // 4 de 5 para equipos 5v5
            } else if (teamSize == 8) {
                return 6; // 6 de 8 para equipos 8v8
            } else {
                return Math.max(1, teamSize - 1); // Para otros tamaños: todos menos 1
            }
        }

        public boolean hasConsensus(Team team) {
            return getVoteCount(team) >= getRequiredVotes(team);
        }
    }
}