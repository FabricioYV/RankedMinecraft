package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ReadySystem {
    private static final Map<String, Set<String>> matchReadyVotes = new ConcurrentHashMap<>();

    /**
     * Procesa un voto de ready de un jugador
     */
    public static void proccessReadyVote(ActiveMatch activeMatch, String playerUuid, Player player) {
        String matchId = activeMatch.getMatchId();

        Set<String> readyVotes = matchReadyVotes.computeIfAbsent(matchId, k -> ConcurrentHashMap.newKeySet());

        // Verificar si ya votó
        if (readyVotes.contains(playerUuid)) {
            player.sendMessage("§e⚡ Ya votaste ready. Esperando otros jugadores...");
            return;
        }
        // Agregar voto
        readyVotes.add(playerUuid);
        player.sendMessage("§a✅ Voto ready registrado!");

        // Verificar si se cumple la condición para acelerar
        if (shouldAccelerateMatch(activeMatch, readyVotes)) {
            accelerateMatchStart(activeMatch);
        } else {
            announceReadyProgress(activeMatch, readyVotes);
        }
    }
    /**
     * Verifica si se debe acelerar el inicio de la partida
     */
    private static boolean shouldAccelerateMatch(ActiveMatch activeMatch, Set<String> readyVotes) {
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        if (activeMatch.getMatchType().equals("5v5")) {
            // Para 5v5: mayoría de cada equipo (3/5)
            return hasMajorityInEachTeam(teams, readyVotes, 3);
        } else {
            // Para 8v8: mayoría de cada equipo (3/4)
            return hasMajorityInEachTeam(teams, readyVotes, 5);
        }
    }
    /**
     * Verifica si hay mayoría en cada equipo
     */
    private static boolean hasMajorityInEachTeam(Map<Team, List<PlayerData>> teams,
                                                 Set<String> readyVotes, int requiredVotes) {
        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            List<PlayerData> teamPlayers = entry.getValue();

            int teamReadyCount = 0;
            for (PlayerData player : teamPlayers) {
                if (readyVotes.contains(player.getMinecraftUuid())) {
                    teamReadyCount++;
                }
            }

            if (teamReadyCount < requiredVotes) {
                return false;
            }
        }

        return true;
    }
    /**
     * Acelera el inicio de la partida
     */
    private static void accelerateMatchStart(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();

        // Anunciar aceleración
        announceToAllPlayers(activeMatch, "§a§l🚀 ¡READY ACTIVADO!");
        announceToAllPlayers(activeMatch, "§e⚡ Acelerando inicio de partida...");

        // Log del evento


        // Ejecutar comando para acelerar (reducir tiempo de inicio)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 10");

        // Limpiar votos de esta partida
        matchReadyVotes.remove(matchId);

        announceToAllPlayers(activeMatch, "§a✨ ¡Partida iniciando en 10 segundos!");
    }
    /**
     * Anuncia el progreso de votos ready
     */
    /**
     * Anuncia el progreso de votos ready
     */
    private static void announceReadyProgress(ActiveMatch activeMatch, Set<String> readyVotes) {
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        // Calcular votos requeridos según el tipo de partida
        int requiredPerTeam;
        if (activeMatch.getMatchType().equals("5v5")) {
            requiredPerTeam = 3; // 3/5 jugadores
        } else {
            requiredPerTeam = 5; // 5/8 jugadores
        }

        StringBuilder progressMessage = new StringBuilder("§e⚡ Progress Ready:\n");

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> teamPlayers = entry.getValue();

            int teamReadyCount = 0;
            for (PlayerData player : teamPlayers) {
                if (readyVotes.contains(player.getMinecraftUuid())) {
                    teamReadyCount++;
                }
            }

            progressMessage.append(team.getFormattedName())
                    .append(": §f").append(teamReadyCount)
                    .append("/").append(requiredPerTeam)
                    .append(" ready\n");
        }

        announceToAllPlayers(activeMatch, progressMessage.toString());
    }

    /**
     * Anuncia mensaje a todos los jugadores de la partida
     */
    private static void announceToAllPlayers(ActiveMatch activeMatch, String message) {
        for (PlayerData playerData : activeMatch.getAllPlayers()) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(message);
            }
        }
    }

    /**
     * Limpia los votos de una partida específica
     */
    public static void clearMatchVotes(String matchId) {
        matchReadyVotes.remove(matchId);
    }

    /**
     * Obtiene el progreso de ready para una partida
     */
    public static int getReadyCount(String matchId) {
        Set<String> votes = matchReadyVotes.get(matchId);
        return votes != null ? votes.size() : 0;
    }

}
