package org.fabricioyv.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class MapVeto {

    private final RankedMinecraft plugin;
    private final DiscordLogger logger;
    private final ActiveMatch match;
    private final String matchType;

    private List<String> remainingMaps;

    private PlayerData blueCaptain;
    private PlayerData redCaptain;

    private PlayerData currentTurnCaptain;
    private BukkitRunnable turnTask;

    private final int turnDurationSeconds;
    private MapVoting.VotingCompleteCallback callback;
    private boolean finished = false;


    // Bloqueo de estado para evitar spam de vetos / saltos de turno mientras se procesa un veto.
    private boolean turnProcessing = false;
    public MapVeto(RankedMinecraft plugin, DiscordLogger logger, ActiveMatch match) {
        this.plugin = plugin;
        this.logger = logger;
        this.match = match;
        this.matchType = MapManager.normalizeMatchTypePublic(match.getMatchType());

        // Duración por turno (si MapManager tiene getter nuevo lo usa; si no, fallback)
        this.turnDurationSeconds = Math.max(5, getIntStaticFromMapManager("getVetoDurationPerTurnSeconds", 15));

        // Asegurar capitanes (misma lógica de picks)
        try { CaptainPickSystem.ensureCaptainsAssigned(match, logger); } catch (Exception ignored) {}

        this.blueCaptain = match.getBlueCaptain();
        this.redCaptain = match.getRedCaptain();

        // Fallback para casos raros
        if (this.blueCaptain == null || this.redCaptain == null) {
            try {
                List<PlayerData> blueTeam = match.getTeams().get(Team.BLUE);
                List<PlayerData> redTeam = match.getTeams().get(Team.RED);

                if (this.blueCaptain == null && blueTeam != null && !blueTeam.isEmpty()) this.blueCaptain = blueTeam.get(0);
                if (this.redCaptain == null && redTeam != null && !redTeam.isEmpty()) this.redCaptain = redTeam.get(0);
            } catch (Exception ignored) {}
        }
    }

    public void startVeto(MapVoting.VotingCompleteCallback callback) {
        this.callback = callback;


        // Reset defensivo (por si se reutiliza la instancia por error)
        this.finished = false;
        this.turnProcessing = false;

        if (blueCaptain == null || redCaptain == null) {
            if (logger != null) logger.error("Veto", "No se encontraron capitanes, usando random.");
            finishImmediately(MapManager.getRandomMap(matchType));
            return;
        }

        setupMapPool();

        if (remainingMaps == null || remainingMaps.isEmpty()) {
            finishImmediately(MapManager.getRandomMap(matchType));
            return;
        }
        if (remainingMaps.size() == 1) {
            finishImmediately(remainingMaps.get(0));
            return;
        }

        currentTurnCaptain = new Random().nextBoolean() ? blueCaptain : redCaptain;

        if (logger != null) {
            logger.info("Veto Iniciado", "Iniciando veto de mapas para " + matchType + " con " + remainingMaps.size() + " mapas.");
        }

        announceToMatch("§6§l=== FASE DE VETOS ===");
        announceToMatch("§7Vetos alternados hasta quedar un solo mapa.");
        nextTurn();
    }

    /**
     * VETO INTELIGENTE:
     * - Partimos de un pool PAR
     * - Quitamos el último mapa jugado -> debería quedar IMPAR
     * - Si queda PAR, quitamos 1 extra al azar -> IMPAR
     */

    private void setupMapPool() {
        List<String> pool = MapManager.getAvailableMaps(matchType);
        if (pool == null) pool = new ArrayList<String>();
        else pool = new ArrayList<String>(pool);

        // Quitar último jugado (si existe), para evitar repetir mapa consecutivo
        String lastPlayed = null;
        try {
            List<String> recent = MapManager.getRecentMaps(matchType);
            if (recent != null && !recent.isEmpty()) lastPlayed = recent.get(recent.size() - 1);
        } catch (Exception ignored) {}

        if (lastPlayed != null) {
            // Remoción normal (case-sensitive)
            boolean removed = pool.remove(lastPlayed);

            // Fallback: remoción case-insensitive (por si MapManager devuelve nombres con casing distinto)
            if (!removed) {
                for (int i = 0; i < pool.size(); i++) {
                    String m = pool.get(i);
                    if (m != null && m.equalsIgnoreCase(lastPlayed)) {
                        pool.remove(i);
                        break;
                    }
                }
            }
        }

        // Garantizar IMPAR: si quedó PAR, quitamos 1 al azar (solo si hay más de 1 mapa)
        while (pool.size() > 1 && pool.size() % 2 == 0) {
            Collections.shuffle(pool);
            pool.remove(0);
        }

        remainingMaps = new ArrayList<String>(pool);
    }

    private void nextTurn() {
        if (finished) return;

        // Liberar el bloqueo: a partir de aquí el capitán del turno puede vetar.
        turnProcessing = false;

        // Seguridad: evitar que queden múltiples timers de turno corriendo.
        if (turnTask != null) {
            try { turnTask.cancel(); } catch (Exception ignored) {}
            turnTask = null;
        }
        if (remainingMaps == null || remainingMaps.isEmpty()) {
            finishImmediately(MapManager.getRandomMap(matchType));
            return;
        }
        if (remainingMaps.size() <= 1) {
            finishImmediately(remainingMaps.get(0));
            return;
        }

        String capName = getPlayerName(currentTurnCaptain);
        Team capTeam = currentTurnCaptain.equals(blueCaptain) ? Team.BLUE : Team.RED;
        String color = (capTeam == Team.BLUE) ? "§b" : "§c";

        announceToMatch("\n§8[§6Veto§8] " + color + "§lTurno de " + capName + " §7para vetar un mapa.");
        announceMaps();

        Player capPlayer = safeGetPlayer(currentTurnCaptain);
        if (capPlayer != null && capPlayer.isOnline()) {
            capPlayer.sendMessage("§eUsa: §a/veto <número> §epara eliminar un mapa.");
        }

        startTurnTimer();
    }


    public boolean processVeto(String playerUuid, int mapIndex) {
        if (finished) return false;

        // Bloquear spam de /veto mientras se procesa un veto o se está cambiando de turno.
        if (turnProcessing) return false;

        if (currentTurnCaptain == null) return false;
        if (playerUuid == null) return false;

        String capUuid = currentTurnCaptain.getMinecraftUuid();
        if (capUuid == null || !capUuid.equalsIgnoreCase(playerUuid)) return false;

        if (remainingMaps == null || mapIndex < 1 || mapIndex > remainingMaps.size()) return false;

        turnProcessing = true; // Bloqueamos hasta que nextTurn() libere el estado.

        String vetoedMap = remainingMaps.remove(mapIndex - 1);

        if (turnTask != null) {
            try { turnTask.cancel(); } catch (Exception ignored) {}
            turnTask = null;
        }

        String capName = getPlayerName(currentTurnCaptain);
        Team capTeam = currentTurnCaptain.equals(blueCaptain) ? Team.BLUE : Team.RED;
        String color = (capTeam == Team.BLUE) ? "§b" : "§c";

        announceToMatch("§8[§cX§8] " + color + capName + " §7vetó: §c§m" + vetoedMap);
        if (logger != null) logger.info("Mapa Vetado", capName + " vetó " + vetoedMap);

        // Si por alguna razón quedamos sin mapas, hacer fallback seguro (esto NO debería pasar si el flujo es correcto).
        if (remainingMaps == null || remainingMaps.isEmpty()) {
            if (logger != null) logger.error("Veto", "remainingMaps quedó vacío tras un veto. Usando random de emergencia.");
            finishImmediately(MapManager.getRandomMap(matchType));
            return true;
        }

        // Si ya solo queda 1 mapa, terminamos INMEDIATO (sin esperar 20 ticks), evitando carreras.
        if (remainingMaps.size() == 1) {
            finishImmediately(remainingMaps.get(0));
            return true;
        }

        // Cambiar turno y programar el próximo anuncio
        currentTurnCaptain = currentTurnCaptain.equals(blueCaptain) ? redCaptain : blueCaptain;

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                nextTurn();
            }
        }, 20L);

        return true;
    }

    private void startTurnTimer(
    ) {
        turnTask = new BukkitRunnable() {
            int timeLeft = turnDurationSeconds;

            @Override
            public void run() {
                if (finished) {
                    cancelSafe();
                    return;
                }

                if (remainingMaps == null || remainingMaps.isEmpty()) {
                    finishImmediately(MapManager.getRandomMap(matchType));
                    cancelSafe();
                    return;
                }

                if (timeLeft <= 0) {
                    if (currentTurnCaptain == null || currentTurnCaptain.getMinecraftUuid() == null) {
                        finishImmediately(MapManager.getRandomMap(matchType));
                        cancelSafe();
                        return;
                    }

                    int randomIndex = new Random().nextInt(remainingMaps.size());
                    processVeto(currentTurnCaptain.getMinecraftUuid(), randomIndex + 1);
                    cancelSafe();
                    return;
                }

                if (timeLeft == 5) {
                    Player p = safeGetPlayer(currentTurnCaptain);
                    if (p != null) p.sendMessage("§c!Te quedan 5s para vetar!");
                }

                timeLeft--;
            }

            private void cancelSafe() {
                try { cancel(); } catch (Exception ignored) {}
            }
        };

        turnTask.runTaskTimer(plugin, 0L, 20L);
    }

    private void announceMaps() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7Mapas disponibles:\n");
        for (int i = 0; i < remainingMaps.size(); i++) {
            sb.append(" §8- §e").append(i + 1).append(". §f").append(remainingMaps.get(i)).append("\n");
        }
        announceToMatch(sb.toString());
    }

    private void finishImmediately(String selectedMap) {
        if (finished) return;
        finished = true;
        turnProcessing = false;

        if (turnTask != null) {
            try { turnTask.cancel(); } catch (Exception ignored) {}
            turnTask = null;
        }

        announceToMatch("§6§l========================");
        announceToMatch("§a§l!VETO FINALIZADO!");
        announceToMatch("§7Mapa seleccionado: §e§l" + selectedMap);
        announceToMatch("§6§l========================");

        if (logger != null) logger.success("Veto Completado", "Mapa ganador: " + selectedMap);

        if (callback != null) {
            callback.onVotingComplete(selectedMap);
        }
    }

    private void announceToMatch(String message) {
        if (message == null) return;

        // Si existe config nueva, usarla; si no, default: anunciar a todos.
        boolean announceAll = getBooleanStaticFromMapManager("isVetoAnnounceToAll", true);

        if (!announceAll) {
            sendTo(blueCaptain, message);
            sendTo(redCaptain, message);
            return;
        }

        List<PlayerData> all = match.getAllPlayers();
        if (all == null) return;

        for (PlayerData pd : all) {
            sendTo(pd, message);
        }
    }

    private void sendTo(PlayerData pd, String msg) {
        Player p = safeGetPlayer(pd);
        if (p != null && p.isOnline()) p.sendMessage(msg);
    }

    private Player safeGetPlayer(PlayerData data) {
        try {
            if (data == null) return null;
            UUID u = parseUuid(data.getMinecraftUuid());
            if (u == null) return null;
            return Bukkit.getPlayer(u);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getPlayerName(PlayerData data) {
        if (data == null) return "Desconectado";
        String uuidStr = null;
        try { uuidStr = data.getMinecraftUuid(); } catch (Exception ignored) {}
        if (uuidStr == null || uuidStr.trim().isEmpty()) return "Desconectado";

        try {
            Player p = Bukkit.getPlayer(UUID.fromString(uuidStr));
            return p != null ? p.getName() : "Desconectado";
        } catch (Exception ignored) {
            // UUID inválido u otro problema
            try {
                String name = data.getMinecraftName();
                if (name != null && !name.trim().isEmpty()) return name;
            } catch (Exception ignored2) {}
            return "Desconectado";
        }
    }


    private static UUID parseUuid(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}

        if (s.length() == 32) {
            String dashed = s.substring(0, 8) + "-" +
                    s.substring(8, 12) + "-" +
                    s.substring(12, 16) + "-" +
                    s.substring(16, 20) + "-" +
                    s.substring(20);
            try { return UUID.fromString(dashed); } catch (IllegalArgumentException ignored) {}
        }
        return null;
    }

    private static int getIntStaticFromMapManager(String methodName, int fallback) {
        try {
            Method m = MapManager.class.getMethod(methodName);
            Object o = m.invoke(null);
            if (o instanceof Number) return ((Number) o).intValue();
        } catch (Exception ignored) {}
        return fallback;
    }

    private static boolean getBooleanStaticFromMapManager(String methodName, boolean fallback) {
        try {
            Method m = MapManager.class.getMethod(methodName);
            Object o = m.invoke(null);
            if (o instanceof Boolean) return (Boolean) o;
        } catch (Exception ignored) {}
        return fallback;
    }

    public void cancelVeto() {
        this.finished = true;
        this.turnProcessing = false;
        if (turnTask != null) {
            try { turnTask.cancel(); } catch (Exception ignored) {}
            turnTask = null;
        }
    }
}