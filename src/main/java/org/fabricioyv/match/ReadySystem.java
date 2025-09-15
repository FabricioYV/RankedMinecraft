package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.util.HashSet;
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

        // Debug logging
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

        if (activeMatch.getMatchType().equals("5v5")) {
            // Para 5v5: mayoría de cada equipo (3/5)
            return hasMajorityInEachTeam(teams, readyVotes, 3);
        } else {
            // Para 8v8: mayoría de cada equipo (5/8) - CORREGIDO
            return hasMajorityInEachTeam(teams, readyVotes, 3);
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
        System.out.println("[Ready System] Match " + matchId + " accelerated - ready votes achieved");

        // Ejecutar comando para acelerar (reducir tiempo de inicio)
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

        // Calcular votos requeridos según el tipo de partida
        int requiredPerTeam;
        if (activeMatch.getMatchType().equals("5v5")) {
            requiredPerTeam = 3; // 3/5 jugadores
        } else {
            requiredPerTeam = 3; // 3/4 jugadores para 8v8 - CORREGIDO
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

    /**
     * Verifica si un jugador ya votó ready en una partida
     */
    public static boolean hasPlayerVoted(String matchId, String playerUuid) {
        Set<String> votes = matchReadyVotes.get(matchId);
        return votes != null && votes.contains(playerUuid);
    }

    /**
     * Obtiene la lista de jugadores que han votado ready en una partida
     */
    public static Set<String> getPlayersWhoVoted(String matchId) {
        Set<String> votes = matchReadyVotes.get(matchId);
        if (votes != null) {
            return new HashSet<>(votes);
        }
        return new HashSet<>();
    }

    /**
     * Permite que un jugador retire su voto de ready
     */
    public static boolean removeReadyVote(ActiveMatch activeMatch, String playerUuid, Player player) {
        String matchId = activeMatch.getMatchId();
        Set<String> readyVotes = matchReadyVotes.get(matchId);

        if (readyVotes == null || !readyVotes.contains(playerUuid)) {
            player.sendMessage("§c❌ No has votado ready aún.");
            return false;
        }

        readyVotes.remove(playerUuid);
        player.sendMessage("§e⚠️ Voto ready removido.");

        // Log del evento
        System.out.println("[Ready System] Player " + player.getName() + " removed ready vote in match " + matchId + ". Remaining votes: " + readyVotes.size());

        // Anunciar progreso actualizado
        announceReadyProgress(activeMatch, readyVotes);

        return true;
    }

    /**
     * Obtiene estadísticas detalladas del progreso de ready por equipo
     */
    public static String getDetailedReadyStatus(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();
        Set<String> readyVotes = matchReadyVotes.getOrDefault(matchId, ConcurrentHashMap.newKeySet());
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        StringBuilder status = new StringBuilder("§6📊 Estado detallado de Ready:\n");

        int requiredPerTeam = 3; // Mayoría para ambos tipos de partida

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> teamPlayers = entry.getValue();

            int teamReadyCount = 0;
            StringBuilder teamStatus = new StringBuilder();

            for (PlayerData playerData : teamPlayers) {
                boolean hasVoted = readyVotes.contains(playerData.getMinecraftUuid());
                if (hasVoted) teamReadyCount++;

                // Get player name from Minecraft player since getDiscordNickname doesn't exist
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                String playerName = mcPlayer != null ? mcPlayer.getName() : "Unknown";

                teamStatus.append("  §f")
                          .append(hasVoted ? "✅" : "❌")
                          .append(" ")
                          .append(playerName)
                          .append("\n");
            }

            status.append(team.getFormattedName())
                  .append(" §f[").append(teamReadyCount).append("/").append(requiredPerTeam).append("]\n")
                  .append(teamStatus);
        }

        return status.toString();
    }

    /**
     * Verifica si todos los jugadores de la partida han votado ready
     */
    public static boolean areAllPlayersReady(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();
        Set<String> readyVotes = matchReadyVotes.getOrDefault(matchId, ConcurrentHashMap.newKeySet());

        int totalPlayers = activeMatch.getAllPlayers().size();
        return readyVotes.size() == totalPlayers;
    }

    /**
     * Fuerza el inicio de la partida independientemente de los votos ready
     */
    public static void forceMatchStart(ActiveMatch activeMatch, String adminName) {
        String matchId = activeMatch.getMatchId();

        // Anunciar inicio forzado
        announceToAllPlayers(activeMatch, "§c⚡ ¡Inicio forzado por administrador!");
        announceToAllPlayers(activeMatch, "§e⚡ Iniciando partida inmediatamente...");

        // Log del evento
        System.out.println("[Ready System] Match " + matchId + " force started by admin " + adminName);

        // Ejecutar comando para iniciar inmediatamente
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 3");

        // Limpiar votos de esta partida
        matchReadyVotes.remove(matchId);

        announceToAllPlayers(activeMatch, "§a✨ ¡Partida iniciando en 3 segundos!");
    }

    /**
     * Reinicia todos los votos de ready para una partida específica
     */
    public static void resetMatchVotes(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();
        Set<String> previousVotes = matchReadyVotes.remove(matchId);

        if (previousVotes != null && !previousVotes.isEmpty()) {
            announceToAllPlayers(activeMatch, "§e🔄 Votos de ready reiniciados.");

            System.out.println("[Ready System] Ready votes reset for match " + matchId + ". Previous votes: " + previousVotes.size());
        }
    }

    /**
     * Obtiene el tiempo estimado hasta que se active el ready automáticamente
     * Basado en la tendencia de votación actual
     */
    public static String getEstimatedReadyTime(ActiveMatch activeMatch) {
        String matchId = activeMatch.getMatchId();
        Set<String> readyVotes = matchReadyVotes.getOrDefault(matchId, ConcurrentHashMap.newKeySet());
        Map<Team, List<PlayerData>> teams = activeMatch.getTeams();

        int totalRequired = teams.size() * 3; // 3 votos por equipo
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
