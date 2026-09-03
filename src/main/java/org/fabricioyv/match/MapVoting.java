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

    // Importante: trabajar con matchType normalizado ("2v2", "5v5", "8v8")
    private final String matchType;

    private final List<String> availableMaps;
    private final Map<String, String> votes;          // uuid -> mapName
    private final Set<String> eligibleVoters;         // uuids que pueden votar

    private final int votingDurationSeconds;

    private VotingCompleteCallback callback;

    private volatile boolean votingActive = false;
    private volatile boolean finished = false;

    private BukkitRunnable countdownTask;

    public MapVoting(RankedMinecraft plugin, DiscordLogger logger, List<PlayerData> players, String matchType) {
        this.plugin = plugin;
        this.logger = logger;
        this.players = players;

        // Normaliza para que SIEMPRE coincida con pools y anti-repetición
        this.matchType = MapManager.normalizeMatchTypePublic(matchType);

        // Duración desde maps.yml (voting.duration)
        this.votingDurationSeconds = MapManager.getVotingDurationSeconds();

        // Usar TODOS los mapas del pool correspondiente
        this.availableMaps = MapManager.getAvailableMaps(this.matchType);

        this.votes = new ConcurrentHashMap<>();
        this.eligibleVoters = players.stream()
                .map(PlayerData::getMinecraftUuid)
                .collect(Collectors.toSet());
    }

    public void startVoting(VotingCompleteCallback callback) {
        this.callback = callback;

        // Si el pool viene vacío por lo que sea, no explotes: fallback a random
        if (availableMaps.isEmpty()) {
            logger.error("Votación de Mapas", "No hay mapas disponibles para " + matchType + ". Usando random.");
            finishImmediately(MapManager.getRandomMap(matchType));
            return;
        }

        // Si la votación está deshabilitada: selección automática
        if (!MapManager.isPlayerVotingEnabled()) {
            String selectedMap = MapManager.getRandomMap(matchType);

            logger.info("Selección Automática",
                    "Mapa seleccionado automáticamente para " + matchType + ": " + selectedMap);

            announceAutomaticSelection(selectedMap);
            finishImmediately(selectedMap);
            return;
        }

        // Votación normal
        this.votingActive = true;
        this.finished = false;

        logger.info("Votación Iniciada",
                "Votación de mapas " + matchType + " iniciada con " + availableMaps.size() + " opciones");

        announceVoting();
        startCountdown();
    }

    private void startCountdown() {
        countdownTask = new BukkitRunnable() {
            int timeLeft = votingDurationSeconds;

            @Override
            public void run() {
                if (!votingActive || finished) {
                    cancelSafely();
                    return;
                }

                if (timeLeft <= 0) {
                    finishVoting();
                    cancelSafely();
                    return;
                }

                // avisos de tiempo
                if (timeLeft == 10 || timeLeft == 5 || timeLeft <= 3) {
                    announceTimeRemaining(timeLeft);
                }

                timeLeft--;
            }

            private void cancelSafely() {
                try { this.cancel(); } catch (Exception ignored) {}
            }
        };

        countdownTask.runTaskTimer(plugin, 0L, 20L);
    }

    public boolean processVote(String playerUuid, int mapIndex) {
        if (!votingActive || finished) return false;
        if (!eligibleVoters.contains(playerUuid)) return false;
        if (mapIndex < 1 || mapIndex > availableMaps.size()) return false;

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

        // Si ya votaron todos, termina de una (mejor UX)
        if (votes.size() >= eligibleVoters.size()) {
            Bukkit.getScheduler().runTask(plugin, this::finishVoting);
        }

        return true;
    }

    private void finishVoting() {
        if (finished) return;
        finished = true;
        votingActive = false;

        // Cancelar countdown si sigue vivo
        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Exception ignored) {}
        }

        String selectedMap = determineWinner();

        announceResult(selectedMap);

        Map<String, Long> voteCounts = votes.values().stream()
                .collect(Collectors.groupingBy(map -> map, Collectors.counting()));

        if (voteCounts.isEmpty()) {
            logger.success("Votación Completada", "Sin votos. Seleccionado: " + selectedMap);
        } else {
            StringBuilder resultLog = new StringBuilder("Resultados: ");
            for (Map.Entry<String, Long> entry : voteCounts.entrySet()) {
                resultLog.append(entry.getKey()).append(" (").append(entry.getValue()).append("), ");
            }
            resultLog.append("Seleccionado: ").append(selectedMap);
            logger.success("Votación Completada", resultLog.toString());
        }

        if (callback != null) {
            callback.onVotingComplete(selectedMap);
        }
    }

    private void finishImmediately(String selectedMap) {
        if (finished) return;
        finished = true;
        votingActive = false;

        if (callback != null) {
            callback.onVotingComplete(selectedMap);
        }
    }

    private String determineWinner() {
        if (votes.isEmpty()) {
            return MapManager.getRandomMap(matchType);
        }

        Map<String, Long> voteCounts = votes.values().stream()
                .collect(Collectors.groupingBy(map -> map, Collectors.counting()));

        long maxVotes = voteCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);

        List<String> winners = voteCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        return winners.get(new Random().nextInt(winners.size()));
    }

    private void announceVoting() {
        String mapsList = buildMapsList();

        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage("§6§l=== VOTACIÓN DE MAPA ===");
                player.sendMessage("§eTienes §c" + votingDurationSeconds + " segundos §epara votar:");
                player.sendMessage(mapsList);
                player.sendMessage("§aUsa: §f/votemap <número> §apara votar");
                player.sendMessage("§6§l========================");
            }
        }
    }

    private String buildMapsList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < availableMaps.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append("§b").append(i + 1).append(". §f").append(availableMaps.get(i));
        }
        return sb.toString();
    }

    private void announceTimeRemaining(int seconds) {
        String message = "§e §c" + seconds + " §esegundo" + (seconds == 1 ? "" : "s") +
                " restante" + (seconds == 1 ? "" : "s") + " para votar!";

        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private void announceAutomaticSelection(String selectedMap) {
        for (PlayerData playerData : players) {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null && player.isOnline()) {
                player.sendMessage("§6§l=== MAPA SELECCIONADO ===");
                player.sendMessage("§eMapa asignado automáticamente:");
                player.sendMessage("§a§l" + selectedMap);
                player.sendMessage("§7La votación de mapas está deshabilitada");
                player.sendMessage("§6§l=========================");
            }
        }
    }

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

    public void cancelVoting() {
        votingActive = false;
        finished = true;

        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Exception ignored) {}
        }

        logger.warning("Votación Cancelada", "La votación de mapas fue cancelada");
    }

    public interface VotingCompleteCallback {
        void onVotingComplete(String selectedMap);
    }
}