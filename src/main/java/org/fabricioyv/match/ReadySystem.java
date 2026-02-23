package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
        if (activeMatch == null || player == null || playerUuid == null) return;
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

        System.out.println("[Ready System] Player " + player.getName() + " voted ready in match " + matchId + ". Total votes: " + readyVotes.size());

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
        int requiredPerTeam = computeRequiredPerTeam(activeMatch, teams);
        return hasMajorityInEachTeam(teams, readyVotes, requiredPerTeam);
    }

    /**
     * Calcula cuántos votos "ready" se requieren por equipo.
     * Regla: mayoría simple del tamaño del equipo (n/2 + 1).
     * - 5v5: 3
     * - 8v8: 5
     * - 2v2: 2
     */
    private static int computeRequiredPerTeam(ActiveMatch activeMatch, Map<Team, List<PlayerData>> teams) {
        try {
            int blueSize = (teams != null && teams.get(Team.BLUE) != null) ? teams.get(Team.BLUE).size() : 0;
            int redSize  = (teams != null && teams.get(Team.RED)  != null) ? teams.get(Team.RED).size()  : 0;

            int teamSize = Math.max(blueSize, redSize);

            // Fallback si por alguna razón los teams aún no están poblados
            if (teamSize <= 0) {
                int total = (activeMatch.getAllPlayers() != null) ? activeMatch.getAllPlayers().size() : 0;
                teamSize = Math.max(1, total / 2);
            }

            return (teamSize / 2) + 1;
        } catch (Exception ignored) {
            // fallback seguro
            return 3;
        }
    }

    /**
     * Verifica si hay mayoría en cada equipo
     */
    private static boolean hasMajorityInEachTeam(Map<Team, List<PlayerData>> teams,
                                                 Set<String> readyVotes, int requiredVotes) {
        if (teams == null || teams.isEmpty()) return false;

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            List<PlayerData> teamPlayers = entry.getValue();

            int teamReadyCount = 0;
            if (teamPlayers != null) {
                for (PlayerData playerData : teamPlayers) {
                    if (playerData != null && readyVotes.contains(playerData.getMinecraftUuid())) {
                        teamReadyCount++;
                    }
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

        announceToAllPlayers(activeMatch, "§a§l🚀 ¡READY ACTIVADO!");
        announceToAllPlayers(activeMatch, "§e⚡ Acelerando inicio de partida...");

        System.out.println("[Ready System] Match " + matchId + " accelerated - ready votes achieved");

        // Reducir tiempo de inicio (tu comando existente)
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 10");

        // Limpiar votos de esta partida
        matchReadyVotes.remove(matchId);

        announceToAllPlayers(activeMatch, "§a✨ ¡Partida iniciando en 10 segundos!");
    }

    /**
     * Anuncia el progreso de votos ready
     */
    private static void announceReadyProgress(ActiveMatch activeMatch, Set<String> readyVotes) {
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        int requiredPerTeam = computeRequiredPerTeam(activeMatch, teams);

        StringBuilder progressMessage = new StringBuilder("§e⚡ Progreso Ready:\n");

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> teamPlayers = entry.getValue();

            int teamReadyCount = 0;
            if (teamPlayers != null) {
                for (PlayerData playerData : teamPlayers) {
                    if (playerData != null && readyVotes.contains(playerData.getMinecraftUuid())) {
                        teamReadyCount++;
                    }
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
        if (activeMatch == null || activeMatch.getAllPlayers() == null) return;

        for (PlayerData playerData : activeMatch.getAllPlayers()) {
            try {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(message);
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * Limpia los votos de una partida específica
     */
    public static void clearMatchVotes(String matchId) {
        matchReadyVotes.remove(matchId);
    }

    public static int getReadyCount(String matchId) {
        Set<String> votes = matchReadyVotes.get(matchId);
        return votes != null ? votes.size() : 0;
    }

    public static boolean hasPlayerVoted(String matchId, String playerUuid) {
        Set<String> votes = matchReadyVotes.get(matchId);
        return votes != null && votes.contains(playerUuid);
    }

    public static String getEstimatedReadyTime(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();
        Set<String> readyVotes = matchReadyVotes.getOrDefault(matchId, ConcurrentHashMap.newKeySet());
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
        if (teams == null) teams = new java.util.HashMap<>();

        int requiredPerTeam = computeRequiredPerTeam(activeMatch, teams);
        int totalRequired = teams.size() * requiredPerTeam; // mayoría por equipo
        int currentVotes = readyVotes.size();

        if (currentVotes == 0) {
            return "§7Sin votos aún - tiempo estimado: desconocido";
        }

        int remaining = totalRequired - currentVotes;

        if (remaining <= 0) {
            return "§a¡Ready debería activarse ahora!";
        }

        return String.format("§e%d votos restantes para activar ready", remaining);
    }
}