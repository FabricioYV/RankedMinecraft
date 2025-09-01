package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MapVoting {
    private final RankedMinecraft plugin;
    private final DiscordLogger logger;
    private final List<PlayerData> players;
    private final String matchType;
    private final List<String> availableMaps;
    private final Map<String, String> votes; // UUID -> mapName
    private final Set<String> eligibleVoters; // UUIDs de jugadores que pueden votar
    private VotingCompleteCallback callback;
    private boolean votingActive = false;

    public MapVoting(RankedMinecraft plugin, DiscordLogger logger, List<PlayerData> players, String matchType) {
        this.plugin = plugin;
        this.logger = logger;
        this.players = players;
        this.matchType = matchType;
        this.availableMaps = MapManager.getAvailableMaps(matchType);
        this.votes = new ConcurrentHashMap<>();
        this.eligibleVoters = players.stream()
                .map(PlayerData::getMinecraftUuid)
                .collect(Collectors.toSet());
    }
    /**
     * Inicia el proceso de votación
     */
    public void startVoting(VotingCompleteCallback callback) {
        this.callback = callback;
        this.votingActive = true;

        if (availableMaps.isEmpty()) {
            logger.error("Votación de Mapas", "No hay mapas disponibles para " + matchType);
            callback.onVotingComplete(MapManager.getRandomMap(matchType));
            return;
        }

        logger.info("Votación Iniciada",
                "Votación de mapas " + matchType + " iniciada con " + availableMaps.size() + " opciones");

        // Anunciar votación a todos los jugadores
        announceVoting();

        // Iniciar countdown de 20 segundos
        startCountdown();
    }
    /**
     * Anuncia la votación a los jugadores
     */
    private void announceVoting() {
        String mapsList = buildMapsList();

        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage("§6§l=== VOTACIÓN DE MAPA ===");
                player.sendMessage("§eTienes §c20 segundos §epara votar:");
                player.sendMessage(mapsList);
                player.sendMessage("§aUsa: §f/votemap <número> §apara votar");
                player.sendMessage("§6§l========================");
            }
        }
    }

    /**
     * Construye la lista formateada de mapas
     */
    private String buildMapsList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < availableMaps.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append("§b").append(i + 1).append(". §f").append(availableMaps.get(i));
        }
        return sb.toString();
    }
    /**
     * Inicia el countdown de votación
     */
    private void startCountdown() {
        new BukkitRunnable() {
            int timeLeft = 20;

            @Override
            public void run() {
                if (timeLeft <= 0 || !votingActive) {
                    finishVoting();
                    this.cancel();
                    return;
                }

                // Avisos de tiempo
                if (timeLeft == 10 || timeLeft == 5 || timeLeft <= 3) {
                    announceTimeRemaining(timeLeft);
                }

                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
    /**
     * Anuncia el tiempo restante
     */
    private void announceTimeRemaining(int seconds) {
        String message = "§e⏰ §c" + seconds + " §esegundo" + (seconds == 1 ? "" : "s") + " restante" + (seconds == 1 ? "" : "s") + " para votar!";

        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }
    /**
     * Procesa un voto de un jugador
     */
    public boolean processVote(String playerUuid, int mapIndex) {
        if (!votingActive) {
            return false;
        }

        if (!eligibleVoters.contains(playerUuid)) {
            return false;
        }

        if (mapIndex < 1 || mapIndex > availableMaps.size()) {
            return false;
        }

        String mapName = availableMaps.get(mapIndex - 1);
        String previousVote = votes.put(playerUuid, mapName);

        Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
        if (player != null && player.isOnline()) {
            if (previousVote != null) {
                player.sendMessage("§eCambiaste tu voto a: §a" + mapName);
            } else {
                player.sendMessage("§aVotaste por: §e" + mapName);
            }
        }

        logger.info("Voto Registrado",
                (player != null ? player.getName() : "Jugador") + " votó por " + mapName +
                        (previousVote != null ? " (cambió de " + previousVote + ")" : ""));

        return true;
    }

    /**
     * Finaliza la votación y determina el ganador
     */
    private void finishVoting() {
        votingActive = false;

        String selectedMap = determineWinner();

        // Anunciar resultado
        announceResult(selectedMap);

        // Log del resultado
        Map<String, Long> voteCounts = votes.values().stream()
                .collect(Collectors.groupingBy(map -> map, Collectors.counting()));

        StringBuilder resultLog = new StringBuilder("Resultados: ");
        for (Map.Entry<String, Long> entry : voteCounts.entrySet()) {
            resultLog.append(entry.getKey()).append(" (").append(entry.getValue()).append("), ");
        }
        resultLog.append("Seleccionado: ").append(selectedMap);

        logger.success("Votación Completada", resultLog.toString());

        // Callback con el mapa seleccionado
        if (callback != null) {
            callback.onVotingComplete(selectedMap);
        }
    }
    /**
     * Determina el mapa ganador
     */
    private String determineWinner() {
        if (votes.isEmpty()) {
            // Sin votos, selección aleatoria
            return MapManager.getRandomMap(matchType);
        }

        // Contar votos por mapa
        Map<String, Long> voteCounts = votes.values().stream()
                .collect(Collectors.groupingBy(map -> map, Collectors.counting()));

        // Encontrar el máximo número de votos
        long maxVotes = voteCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);

        // Obtener todos los mapas con el máximo de votos
        List<String> winners = voteCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Si hay empate, selección aleatoria entre los ganadores
        return winners.get(new Random().nextInt(winners.size()));
    }

    /**
     * Anuncia el resultado a los jugadores
     */
    private void announceResult(String selectedMap) {
        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage("§6§l=== RESULTADO ===");
                player.sendMessage("§aMapa seleccionado: §e§l" + selectedMap);
                player.sendMessage("§7Total de votos: " + votes.size() + "/" + players.size());
                player.sendMessage("§6§l==================");
            }
        }
    }

    /**
     * Cancela la votación
     */
    public void cancelVoting() {
        votingActive = false;
        logger.warning("Votación Cancelada", "La votación de mapas fue cancelada");
    }

    /**
     * Interface para callback de votación completada
     */
    public interface VotingCompleteCallback {
        void onVotingComplete(String selectedMap);
    }



}
