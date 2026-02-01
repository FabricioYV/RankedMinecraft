package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;
import org.fabricioyv.util.ActionBarUtil;
import org.fabricioyv.util.MessageUtil;
import org.fabricioyv.util.TitleUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.fabricioyv.match.MatchFinisher.movePlayersToWaitingRoom;

/**
 * Sistema de picks con capitanes para RankedMinecraft
 */
public class CaptainPickSystem {

    private static final Map<String, PickSession> activeSessions = new ConcurrentHashMap<>();

    // ids de roles que pueden ser capitanes (según tu configuración actual)
    private static final String VIP_PLUS_ROLE_ID = "1413241361087332505";
    private static final String VIP_ROLE_ID = "1413241361087332505";
    private static final String SERVER_BOOSTER_ROLE_ID = "1407203727076491295";

    private static final int PICK_TIMEOUT_SECONDS = 20;
    private static final int MIN_WINS_FOR_CAPTAIN = 10;

    // ===================== Anti-loop capitanes (memoria en RAM) =====================

    /**
     * Evita bucles tipo AB/CD/AB: no repetir la MISMA pareja en las últimas N partidas.
     * (Ajusta si tu playerbase es pequeña)
     */
    private static final int CAPTAIN_PAIR_MEMORY = 6;

    /**
     * Ventana para penalizar capitanes repetidos recientemente (evita rotaciones pequeñas).
     */
    private static final int CAPTAIN_RECENT_MEMORY = 6;

    /**
     * Top N candidatos a evaluar (mantiene nivel alto y limita combinaciones).
     * Si tu queue suele ser grande, 10–14 es buen rango.
     */
    private static final int CAPTAIN_TOP_POOL = 10;

    // Castigos (solo afectan el "score" interno; NO tocan ELO real)
    private static final long PENALTY_BOTH_LAST_CAPTAINS = 100_000L;  // evita repetir 2/2 de la partida anterior
    private static final long PENALTY_RECENT_CAPTAIN = 1_500L;        // castigo por aparecer como capitán recientemente

    private static final Deque<String> recentCaptainPairs = new ArrayDeque<>();
    private static final Deque<Set<String>> recentCaptainSets = new ArrayDeque<>();
    private static final Object captainHistoryLock = new Object();

    /**
     * IMPORTANTE: Mucha de esta lógica puede ser invocada desde callbacks de Discord (JDA) que NO corren
     * en el hilo principal de Bukkit. Si llamas Bukkit.getPlayer(), sendMessage(), títulos, actionbar, etc.
     * fuera del hilo principal, Spigot puede ignorarlo o romper cosas silenciosamente.
     */
    private static void runSync(Runnable task) {
        try {
            RankedMinecraft plugin = RankedMinecraft.getInstance();
            if (plugin == null) {
                task.run();
                return;
            }
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        } catch (Exception e) {
            // último recurso: no explotar por thread issues
            try { task.run(); } catch (Exception ignored) {}
        }
    }

    /**
     * Inicia el sistema de picks después de seleccionar el mapa
     */
    public static void startPickPhase(ActiveMatch activeMatch, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();

        // 1. Validaciones (IGUAL QUE ANTES)
        if (allPlayers == null || allPlayers.isEmpty()) {
            logger.warning("Sistema de picks", "No hay jugadores en la partida para iniciar picks.");
            fallbackToAutomaticBalancing(activeMatch, logger);
            return;
        }

        // 2. Seleccionar capitanes iniciales usando TU LÓGICA DEFINIDA
        // (Esto respeta tu deseo de que los PRIMEROS sean los definidos por el método)
        List<PlayerData> selectedCaptains = selectCaptainsForMatch(allPlayers, activeMatch.getGuild());

        if (selectedCaptains.size() < 2) {
            logger.info("Sistema de picks", String.format("No se pudieron seleccionar 2 capitanes (%d/2).", selectedCaptains.size()));
            fallbackToAutomaticBalancing(activeMatch, logger);
            return;
        }

        // 3. ¡CAMBIO IMPORTANTE! Guardamos los capitanes en el Match
        activeMatch.setBlueCaptain(selectedCaptains.get(0));
        activeMatch.setRedCaptain(selectedCaptains.get(1));
        activeMatch.setPicksMatch(true);

        // 4. En lugar de crear la PickSession, iniciamos la FASE DE REROLL
        // El Manager se encargará de hacer cambios al azar si la gente vota.
        // Cuando el tiempo termine, ejecutará el código dentro de () -> { ... }
        runSync(() -> CaptainRerollManager.startRerollPhase(activeMatch, () -> {
            iniciarSesionDePicksReal(activeMatch, logger);
        }));
    }

    private static void iniciarSesionDePicksReal(ActiveMatch activeMatch, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();

        // Obtener los capitanes finales desde el match (pueden haber cambiado por el reroll)
        PlayerData cap1 = activeMatch.getBlueCaptain();
        PlayerData cap2 = activeMatch.getRedCaptain();

        // Validación de seguridad
        if (cap1 == null || cap2 == null) {
            fallbackToAutomaticBalancing(activeMatch, logger);
            return;
        }

        List<PlayerData> finalCaptains = new ArrayList<>();
        finalCaptains.add(cap1);
        finalCaptains.add(cap2);

        // --- AQUÍ CONTINÚA TU CÓDIGO ORIGINAL ---
        PickSession session = new PickSession(matchId, allPlayers, finalCaptains, activeMatch, logger);
        activeSessions.put(matchId, session);

        logger.info("Sistema de picks",
                String.format("Iniciando fase de picks REAL para partida %s con capitanes: %s vs %s",
                        matchId,
                        getPlayerDisplayName(cap1),
                        getPlayerDisplayName(cap2)));

        runSync(session::startPickProcess);
    }

    // ===================== UUID helper (con o sin guiones) =====================

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;

        String s = raw.trim();
        if (s.isEmpty()) return null;

        // Con guiones (formato estándar)
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
        }

        // Sin guiones (32 hex)
        if (s.length() == 32) {
            String dashed = s.substring(0, 8) + "-" +
                    s.substring(8, 12) + "-" +
                    s.substring(12, 16) + "-" +
                    s.substring(16, 20) + "-" +
                    s.substring(20);
            try {
                return UUID.fromString(dashed);
            } catch (IllegalArgumentException ignored) {
            }
        }

        return null;
    }

    static String normalizeUuidString(String raw) {
        if (raw == null) return null;
        UUID u = parseUuid(raw);
        return (u != null) ? u.toString() : null;
    }

    static String normalize(PlayerData p) {
        if (p == null) return null;
        try {
            return normalizeUuidString(p.getMinecraftUuid());
        } catch (Exception e) {
            return null;
        }
    }

    static String pairKey(String a, String b) {
        if (a == null || b == null) return String.valueOf(a) + "|" + String.valueOf(b);
        return (a.compareTo(b) <= 0) ? (a + "|" + b) : (b + "|" + a);
    }

    /**
     * Score base: prioriza ELO (si existe), luego VIP, luego wins.
     * (Esto NO toca tu sistema de ELO real; solo decide capitanes)
     */
    static long captainBaseScore(PlayerData p, Guild guild) {
        if (p == null) return Long.MIN_VALUE;

        long score = 0;

        int elo = 0;
        try { elo = p.getElo(); } catch (Exception ignored) {}
        if (elo > 0) score += (long) elo * 10L;

        if (hasVipRole(p, guild)) score += 3_000L;

        int wins = 0;
        try { wins = p.getWins(); } catch (Exception ignored) {}
        score += (long) wins * 20L;

        return score;
    }

    static Set<String> snapshotRecentPairs() {
        synchronized (captainHistoryLock) {
            return new HashSet<>(recentCaptainPairs);
        }
    }

    static Set<String> snapshotLastCaptainSet() {
        synchronized (captainHistoryLock) {
            if (recentCaptainSets.isEmpty()) return Collections.emptySet();
            return new HashSet<>(recentCaptainSets.peekFirst());
        }
    }

    static Map<String, Integer> snapshotRecentCaptainCounts() {
        synchronized (captainHistoryLock) {
            Map<String, Integer> count = new HashMap<>();
            for (Set<String> s : recentCaptainSets) {
                for (String u : s) count.merge(u, 1, Integer::sum);
            }
            return count;
        }
    }

    private static void rememberCaptains(PlayerData c1, PlayerData c2) {
        String u1 = normalize(c1);
        String u2 = normalize(c2);
        if (u1 == null || u2 == null) return;

        String pk = pairKey(u1, u2);

        synchronized (captainHistoryLock) {
            // parejas (anti-loop)
            recentCaptainPairs.remove(pk);
            recentCaptainPairs.addFirst(pk);
            while (recentCaptainPairs.size() > CAPTAIN_PAIR_MEMORY) recentCaptainPairs.removeLast();

            // sets (últimos capitanes + frecuencia)
            Set<String> set = new HashSet<>();
            set.add(u1);
            set.add(u2);
            recentCaptainSets.addFirst(set);
            while (recentCaptainSets.size() > CAPTAIN_RECENT_MEMORY) recentCaptainSets.removeLast();
        }
    }

    // ===================== selección de capitanes (ANTI-LOOP) =====================

    private static List<PlayerData> selectCaptainsForMatch(List<PlayerData> allPlayers, Guild guild) {
        List<PlayerData> result = new ArrayList<>();

        if (allPlayers == null || allPlayers.size() <= 2) {
            return allPlayers == null ? result : new ArrayList<>(allPlayers);
        }

        // Snapshots del historial anti-loop
        Set<String> recentPairs = snapshotRecentPairs();
        Set<String> lastSet = snapshotLastCaptainSet();
        Map<String, Integer> recentCount = snapshotRecentCaptainCounts();

        // Detectar ELO presence
        List<PlayerData> eloPlayers = new ArrayList<>();
        for (PlayerData p : allPlayers) {
            if (hasElo(p)) eloPlayers.add(p);
        }

        // Si SOLO existe 1 con ELO: fijarlo como uno de los capitanes y rotar el segundo
        if (eloPlayers.size() == 1) {
            PlayerData fixed = eloPlayers.get(0);

            List<PlayerData> others = new ArrayList<>(allPlayers);
            others.remove(fixed);

            PlayerData second = chooseSecondWithFixedFirst(
                    fixed,
                    others,
                    guild,
                    recentPairs,
                    lastSet,
                    recentCount,
                    true // si hay ELO, queremos mantener la calidad rotando con uno fuerte
            );

            result.add(fixed);
            if (second != null) result.add(second);
        } else {
            // Si hay 0 o 2+ con ELO, elegimos la mejor pareja desde TODA la lista.
            // Esto evita el problema que tú detectaste: “si solo 2 tienen ELO, siempre serán capitanes”.
            boolean requireAtLeastOneElo = (eloPlayers.size() > 0);
            result = chooseBestPair(allPlayers, guild, recentPairs, lastSet, recentCount, requireAtLeastOneElo);
        }

        // Fallback duro (por si algo raro deja <2)
        while (result.size() < 2 && !allPlayers.isEmpty()) {
            PlayerData random = allPlayers.get(ThreadLocalRandom.current().nextInt(allPlayers.size()));
            if (!result.contains(random)) result.add(random);
        }

        return result;
    }

    private static List<PlayerData> chooseBestPair(List<PlayerData> candidates,
                                                   Guild guild,
                                                   Set<String> recentPairs,
                                                   Set<String> lastSet,
                                                   Map<String, Integer> recentCount,
                                                   boolean requireAtLeastOneElo) {
        List<PlayerData> result = new ArrayList<>();
        if (candidates == null || candidates.size() < 2) return result;

        // 1) Filtrar por wins>=MIN si hay suficientes (soft filter)
        List<PlayerData> filtered = new ArrayList<>();
        for (PlayerData p : candidates) {
            try {
                if (p != null && p.getWins() >= MIN_WINS_FOR_CAPTAIN) filtered.add(p);
            } catch (Exception ignored) {}
        }
        if (filtered.size() < 2) filtered = new ArrayList<>(candidates);

        // 2) Ordenar por score base (ELO/VIP/WINS)
        filtered.sort(Comparator.comparingLong((PlayerData p) -> captainBaseScore(p, guild)).reversed());

        int limit = Math.min(CAPTAIN_TOP_POOL, filtered.size());
        List<PlayerData> pool = filtered.subList(0, limit);

        PlayerData bestA = null, bestB = null;
        long bestScore = Long.MIN_VALUE;

        // 2 pasadas: (0) estricto (no repetir pareja), (1) relajado si no hay alternativa
        for (int pass = 0; pass < 2; pass++) {
            boolean allowRecentPairs = (pass == 1);

            for (int i = 0; i < pool.size(); i++) {
                for (int j = i + 1; j < pool.size(); j++) {
                    PlayerData a = pool.get(i);
                    PlayerData b = pool.get(j);

                    String au = normalize(a);
                    String bu = normalize(b);
                    if (au == null || bu == null) continue;

                    // Si queremos mantener calidad, al menos uno debe tener ELO cuando exista ELO en la queue
                    if (requireAtLeastOneElo && !(hasElo(a) || hasElo(b))) continue;

                    String pk = pairKey(au, bu);
                    if (!allowRecentPairs && recentPairs.contains(pk)) continue;

                    long score = captainBaseScore(a, guild) + captainBaseScore(b, guild);

                    // Regla clave: evitar repetir 2/2 capitanes de la última partida
                    if (lastSet.contains(au) && lastSet.contains(bu)) {
                        score -= PENALTY_BOTH_LAST_CAPTAINS;
                    }

                    // Penalización por frecuencia reciente
                    score -= PENALTY_RECENT_CAPTAIN * (long) recentCount.getOrDefault(au, 0);
                    score -= PENALTY_RECENT_CAPTAIN * (long) recentCount.getOrDefault(bu, 0);

                    if (score > bestScore) {
                        bestScore = score;
                        bestA = a;
                        bestB = b;
                    }
                }
            }

            if (bestA != null && bestB != null) break;
        }

        // Fallback (si no hay pareja que cumpla)
        if (bestA == null || bestB == null) {
            result.add(filtered.get(0));
            result.add(filtered.get(1));
        } else {
            result.add(bestA);
            result.add(bestB);
        }

        return result;
    }

    private static PlayerData chooseSecondWithFixedFirst(PlayerData first,
                                                         List<PlayerData> others,
                                                         Guild guild,
                                                         Set<String> recentPairs,
                                                         Set<String> lastSet,
                                                         Map<String, Integer> recentCount,
                                                         boolean requireAtLeastOneEloWhenPossible) {
        if (first == null || others == null || others.isEmpty()) return null;

        List<PlayerData> sorted = new ArrayList<>(others);

        // Filtrar por wins>=MIN si hay suficientes
        List<PlayerData> filtered = new ArrayList<>();
        for (PlayerData p : sorted) {
            try {
                if (p != null && p.getWins() >= MIN_WINS_FOR_CAPTAIN) filtered.add(p);
            } catch (Exception ignored) {}
        }
        if (filtered.isEmpty()) filtered = sorted;

        filtered.sort(Comparator.comparingLong((PlayerData p) -> captainBaseScore(p, guild)).reversed());

        String fu = normalize(first);
        if (fu == null) return filtered.get(0);

        PlayerData best = null;
        long bestScore = Long.MIN_VALUE;

        int limit = Math.min(CAPTAIN_TOP_POOL, filtered.size());
        List<PlayerData> pool = filtered.subList(0, limit);

        boolean requireAtLeastOneElo = requireAtLeastOneEloWhenPossible && (hasElo(first));

        for (int pass = 0; pass < 2; pass++) {
            boolean allowRecentPairs = (pass == 1);

            for (PlayerData p : pool) {
                if (p == null) continue;
                String pu = normalize(p);
                if (pu == null) continue;

                // Si el primero ya tiene ELO, el segundo puede ser cualquiera (rotación).
                // Si el primero NO tuviese ELO (caso raro aquí), podrías forzar uno con ELO.
                if (requireAtLeastOneElo && !(hasElo(first) || hasElo(p))) continue;

                String pk = pairKey(fu, pu);
                if (!allowRecentPairs && recentPairs.contains(pk)) continue;

                long score = captainBaseScore(p, guild);

                // Evita repetir 2/2 de la última partida
                if (lastSet.contains(fu) && lastSet.contains(pu)) {
                    score -= PENALTY_BOTH_LAST_CAPTAINS;
                }

                // Penaliza frecuencia
                score -= PENALTY_RECENT_CAPTAIN * (long) recentCount.getOrDefault(pu, 0);

                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }

            if (best != null) break;
        }

        return best != null ? best : pool.get(0);
    }

    static boolean hasElo(PlayerData p) {
        try {
            return p != null && p.getElo() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean hasVipRole(PlayerData player, Guild guild) {
        if (player == null || guild == null) return false;
        try {
            Member member = guild.getMemberById(player.getDiscordId());
            return member != null && hasSponsorRole(member);
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== utilidades =====================

    private static String getPlayerDisplayName(PlayerData playerData) {
        if (playerData == null) return "Unknown";
        try {
            UUID uuid = parseUuid(playerData.getMinecraftUuid());
            if (uuid == null) return "Unknown";

            Player player = Bukkit.getPlayer(uuid);
            if (player != null) return player.getName();

            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer.getName() != null) return offlinePlayer.getName();

            String raw = playerData.getMinecraftUuid();
            return "Player_" + raw.substring(0, Math.min(8, raw.length()));
        } catch (Exception e) {
            return "Unknown";
        }
    }

    static boolean hasSponsorRole(Member member) {
        for (Role role : member.getRoles()) {
            if (role.getId().equals(VIP_PLUS_ROLE_ID)
                    || role.getId().equals(VIP_ROLE_ID)
                    || role.getId().equals(SERVER_BOOSTER_ROLE_ID)) {
                return true;
            }
        }
        return false;
    }

    private static void fallbackToAutomaticBalancing(ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Balanceo automático", "Ejecutando balanceo automático por falta de capitanes");
        activeMatch.balanceTeams();
        continueAfterTeamFormation(activeMatch, logger);
    }

    private static void continueAfterTeamFormation(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            if (!activeMatch.isPicksMatch()) {
                activeMatch.createTeamChannels();
                activeMatch.assignPlayersInMinecraft();
            } else {
                logger.info("Picks match", "Saltando creación de canales - ya fueron creados en el sistema de picks");
                activeMatch.assignPlayersInMinecraft();
            }

            startFinalCountdown(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error en flujo después de formación de equipos", e);
            activeMatch.cleanup();
        }
    }

    private static void startFinalCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        if (!validateAllPlayersConnected(activeMatch, logger)) {
            logger.warning("Validación jugadores", "No todos los jugadores están conectados. Cancelando partida.");
            handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
            return;
        }

        announceToPlayers(activeMatch.getAllPlayers(), MessageUtil.c("&aEquipos listos. &7La partida comienza en &f10s&7."));

        logger.matchEvent(activeMatch.getMatchId(), "COUNTDOWN_INICIADO",
                "Cuenta regresiva de 10 segundos iniciada", activeMatch.getAllPlayers().size());

        new BukkitRunnable() {
            private int countdown = 10;

            @Override
            public void run() {
                if (!validateAllPlayersConnected(activeMatch, logger)) {
                    logger.warning("Validación continua",
                            "Un jugador se desconectó durante la cuenta regresiva. Cancelando partida.");
                    handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
                    this.cancel();
                    return;
                }

                countdown--;

                if (countdown > 0) {
                    if (countdown <= 5) {
                        announceToPlayers(activeMatch.getAllPlayers(), MessageUtil.c("&e" + countdown + "..."));
                    }
                } else {
                    if (validateAllPlayersConnected(activeMatch, logger)) {
                        startOfficialMatch(activeMatch, logger);
                    } else {
                        handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(RankedMinecraft.getInstance(), 20L, 20L);
    }

    private static boolean validateAllPlayersConnected(ActiveMatch activeMatch, DiscordLogger logger) {
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();
        int connectedCount = 0;

        for (PlayerData playerData : allPlayers) {
            try {
                UUID uuid = parseUuid(playerData.getMinecraftUuid());
                Player player = (uuid != null) ? Bukkit.getPlayer(uuid) : null;

                if (player != null && player.isOnline()) {
                    connectedCount++;
                } else {
                    logger.debug("Jugador desconectado",
                            String.format("Jugador %s no está conectado (uuid parse=%s)",
                                    playerData.getMinecraftUuid(), uuid != null));
                }
            } catch (Exception e) {
                logger.debug("Error validación", "Error validando jugador: " + e.getMessage());
            }
        }

        boolean isValid = connectedCount == allPlayers.size();
        logger.debug("Validación jugadores",
                String.format("Conectados: %d/%d - válido: %s", connectedCount, allPlayers.size(), isValid));

        return isValid;
    }

    private static void handlePlayerDisconnectionDuringCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            announceToPlayers(activeMatch.getAllPlayers(),
                    MessageUtil.c("&cPartida cancelada. &7Alguien se desconectó durante la preparación."));

            logger.matchEvent(activeMatch.getMatchId(), "PARTIDA_CANCELADA_DESCONEXION",
                    "Partida cancelada por desconexión durante cuenta regresiva", activeMatch.getAllPlayers().size());

            cleanupCancelledMatch(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error manejando desconexión durante countdown", e);
            forceCleanupResources(activeMatch, logger);
        }
    }

    private static void cleanupCancelledMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            if (MatchState.isMatchActive()) {
                MatchState.endMatch();
                logger.info("Estado match", "MatchState reseteado tras cancelación");
            }

            QueueManager queueManager = RankedMinecraft.getInstance().getDiscordBot().getQueueManager();
            for (PlayerData playerData : activeMatch.getAllPlayers()) {
                try {
                    queueManager.removePlayerFromAllQueues(playerData.getMinecraftUuid());
                    playerData.setInMatch(false);
                    playerData.setCurrentMatchId(null);
                } catch (Exception e) {
                    logger.warning("Error limpiando jugador",
                            String.format("Error limpiando %s: %s", playerData.getMinecraftUuid(), e.getMessage()));
                }
            }

            movePlayersToWaitingRoom(activeMatch, RankedMinecraft.getInstance(), logger);
            cleanupDiscordChannels(activeMatch, logger);

            activeSessions.remove(activeMatch.getMatchId());
            activeMatch.cleanup();

            logger.info("Limpieza completa",
                    String.format("Partida %s cancelada y recursos liberados", activeMatch.getMatchId()));

        } catch (Exception e) {
            logger.logError("Error en limpieza de partida cancelada", e);
            forceCleanupResources(activeMatch, logger);
        }
    }

    private static void cleanupDiscordChannels(ActiveMatch activeMatch, DiscordLogger logger) {
        Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
            try {
                if (activeMatch.getBlueTeamChannel() != null) {
                    activeMatch.getBlueTeamChannel().delete().queue();
                }
                if (activeMatch.getRedTeamChannel() != null) {
                    activeMatch.getRedTeamChannel().delete().queue();
                }
            } catch (Exception e) {
                logger.logError("Error en limpieza de canales Discord", e);
            }
        }, 60L);
    }

    private static void forceCleanupResources(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            logger.warning("Limpieza forzada", "Ejecutando limpieza de emergencia");

            MatchState.endMatch();
            activeSessions.remove(activeMatch.getMatchId());

            QueueManager queueManager = RankedMinecraft.getInstance().getDiscordBot().getQueueManager();
            for (PlayerData playerData : activeMatch.getAllPlayers()) {
                try {
                    queueManager.forceRemovePlayer(playerData.getMinecraftUuid());
                } catch (Exception ignored) {
                }
            }

            logger.info("Limpieza forzada", "Limpieza de emergencia completada");

        } catch (Exception e) {
            logger.logError("Error incluso en limpieza forzada", e);
        }
    }

    private static void startOfficialMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            announceToPlayers(activeMatch.getAllPlayers(), MessageUtil.c("&aPartida iniciada. &7Buena suerte."));

            if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
                activeMatch.setStatus(ActiveMatch.MatchStatus.IN_PROGRESS);
                logger.matchEvent(activeMatch.getMatchId(), "PARTIDA_INICIADA",
                        "Partida iniciada oficialmente via sistema de picks", activeMatch.getAllPlayers().size());
            }

        } catch (Exception e) {
            logger.logError("Error iniciando partida oficial", e);
        }
    }

    private static void announceToPlayers(List<PlayerData> players, String message) {
        for (PlayerData playerData : players) {
            try {
                UUID uuid = parseUuid(playerData.getMinecraftUuid());
                Player player = (uuid != null) ? Bukkit.getPlayer(uuid) : null;

                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static void handlePlayerPick(String matchId, String captainDiscordId, String pickedPlayerUuid) {
        PickSession session = activeSessions.get(matchId);
        if (session == null) return;

        // Este método normalmente viene de comandos Discord/JDA → hilo async.
        runSync(() -> session.handlePlayerPick(captainDiscordId, pickedPlayerUuid));
    }

    public static void cleanupSession(String matchId) {
        activeSessions.remove(matchId);
    }

    public static List<PlayerData> getAvailablePlayers(String matchId) {
        PickSession session = activeSessions.get(matchId);
        if (session == null) return null;
        return session.getAvailablePlayers();
    }

    public static void givePickBook(Player captain) {
        ItemStack pickBook = new ItemStack(Material.BOOK);
        ItemMeta meta = pickBook.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(MessageUtil.c("&6&lLibro de picks"));

            List<String> lore = new ArrayList<>();
            lore.add(MessageUtil.c("&7Click derecho para abrir"));
            lore.add(MessageUtil.c("&7y seleccionar jugadores."));
            lore.add("");
            lore.add(MessageUtil.c("&eTip: &7también funciona &a/pick <jugador>"));

            meta.setLore(lore);
            pickBook.setItemMeta(meta);
        }

        captain.getInventory().addItem(pickBook);
        MessageUtil.send(captain, "&aRecibiste el &6libro de picks&7. Click derecho para abrir.");
        ActionBarUtil.send(captain, "&eLibro de picks &8» &7click derecho");
    }

    // ===================== sesión de picks =====================

    private static class PickSession {
        private final String matchId;
        private final List<PlayerData> allPlayers;
        private final List<PlayerData> eligibleCaptains;
        private final ActiveMatch activeMatch;
        private final DiscordLogger logger;

        private PlayerData captain1;
        private PlayerData captain2;
        private final List<PlayerData> team1 = new ArrayList<>();
        private final List<PlayerData> team2 = new ArrayList<>();
        private final List<PlayerData> availablePlayers = new ArrayList<>();

        private PlayerData currentCaptain;
        private int pickNumber = 1;
        private boolean finished = false;
        private BukkitRunnable timeoutTask;

        private int consecutivePicksRemaining = 1;

        private VoiceChannel tempPickChannel;

        public PickSession(String matchId, List<PlayerData> allPlayers,
                           List<PlayerData> eligibleCaptains, ActiveMatch activeMatch,
                           DiscordLogger logger) {
            this.matchId = matchId;
            this.allPlayers = new ArrayList<>(allPlayers);
            this.eligibleCaptains = new ArrayList<>(eligibleCaptains);
            this.activeMatch = activeMatch;
            this.logger = logger;
        }

        public void startPickProcess() {
            try {
                if (eligibleCaptains.size() == 2) {
                    captain1 = eligibleCaptains.get(0);
                    captain2 = eligibleCaptains.get(1);
                } else {
                    Collections.shuffle(eligibleCaptains);
                    captain1 = eligibleCaptains.get(0);
                    captain2 = eligibleCaptains.get(1);
                }

                logger.info("Picks DEBUG", "Cap1 uuid=" + captain1.getMinecraftUuid() + " | Cap2 uuid=" + captain2.getMinecraftUuid());

                team1.add(captain1);
                team2.add(captain2);

                availablePlayers.addAll(allPlayers);
                availablePlayers.remove(captain1);
                availablePlayers.remove(captain2);

                currentCaptain = captain1;
                consecutivePicksRemaining = 1;

                createTemporaryPickChannelSingle();

                announcePickStart();

                alertCaptainWithSound(captain1, MessageUtil.c("&6Eres capitán. &7Cuando sea tu turno, pickea."), 3);
                alertCaptainWithSound(captain2, MessageUtil.c("&6Eres capitán. &7Cuando sea tu turno, pickea."), 3);

                givePickBooksToCaptains();
                startNextPick();

            } catch (Exception e) {
                logger.logError("Error en startPickProcess()", e);
            }
        }

        private void givePickBooksToCaptains() {
            try {
                Player p1 = safeGet(captain1);
                if (p1 != null) CaptainPickSystem.givePickBook(p1);

                Player p2 = safeGet(captain2);
                if (p2 != null) CaptainPickSystem.givePickBook(p2);

            } catch (Exception e) {
                logger.warning("Error dando libros de picks", "Error: " + e.getMessage());
            }
        }

        public List<PlayerData> getAvailablePlayers() {
            return new ArrayList<>(availablePlayers);
        }

        // --- estilo / helpers ---

        private Player safeGet(PlayerData pd) {
            try {
                if (pd == null) return null;
                UUID uuid = parseUuid(pd.getMinecraftUuid());
                if (uuid == null) return null;

                Player p = Bukkit.getPlayer(uuid);
                return (p != null && p.isOnline()) ? p : null;
            } catch (Exception e) {
                return null;
            }
        }

        private void sendToAllChat(String msg) {
            for (PlayerData pd : allPlayers) {
                Player p = safeGet(pd);
                if (p != null) MessageUtil.send(p, msg);
            }
        }

        private void sendToAllRaw(String raw) {
            for (PlayerData pd : allPlayers) {
                Player p = safeGet(pd);
                if (p != null) MessageUtil.sendRaw(p, raw);
            }
        }

        private void sendToAllActionBar(String msg) {
            for (PlayerData pd : allPlayers) {
                Player p = safeGet(pd);
                if (p != null) ActionBarUtil.send(p, msg);
            }
        }

        private String getAvailablePlayersInline(int max) {
            StringBuilder sb = new StringBuilder();
            int count = 0;

            for (PlayerData pd : availablePlayers) {
                if (count >= max) break;
                if (count > 0) sb.append("&7, &f");
                sb.append(getPlayerName(pd));
                count++;
            }

            if (availablePlayers.size() > max) {
                sb.append(" &7... (&e").append(availablePlayers.size()).append("&7)");
            }
            return MessageUtil.c(sb.toString());
        }

        // --- canal temporal ---

        private void createTemporaryPickChannelSingle() {
            try {
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH-mm"));

                activeMatch.getGuild().createVoiceChannel("🎯 Picks " + timestamp)
                        .setParent(activeMatch.getGuild().getCategoryById("1412199394536898631"))
                        .queue(channel -> {
                            tempPickChannel = channel;
                            logger.info("Canal temporal creado", "Canal temporal de picks: " + channel.getName());
                            moveAllPlayersToTempPickChannel();
                        }, error -> logger.logError("Error creando canal temporal de picks", error));

            } catch (Exception e) {
                logger.logError("Error creando canal temporal para picks", e);
            }
        }

        private void moveAllPlayersToTempPickChannel() {
            if (tempPickChannel == null) return;

            for (PlayerData player : allPlayers) {
                try {
                    Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                    if (member != null && member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
                        activeMatch.getGuild().moveVoiceMember(member, tempPickChannel).queue();
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // --- anuncios con estilo ---

        private void announcePickStart() {
            try {
                String cap1 = getPlayerName(captain1);
                String cap2 = getPlayerName(captain2);

                for (PlayerData pd : allPlayers) {
                    Player p = safeGet(pd);
                    if (p == null) continue;
                    TitleUtil.send(p, "&6&lCapitanes", "&c" + cap1 + " &8vs &9" + cap2, 10, 50, 10);
                    ActionBarUtil.send(p, "&7Usa el &elibro de picks &7o &a/pick <jugador>");
                }

                sendToAllRaw(MessageUtil.sep());
                sendToAllRaw(MessageUtil.c("&6&lFase de picks"));
                sendToAllRaw(MessageUtil.c("&7Azul: &9" + cap1 + " &8| &7Rojo: &c" + cap2));
                sendToAllRaw(MessageUtil.c("&7Tiempo por pick: &e" + PICK_TIMEOUT_SECONDS + "s"));
                sendToAllRaw(MessageUtil.c("&7Pickea primero: &f" + getPlayerName(currentCaptain)));
                sendToAllRaw(MessageUtil.c("&8Tip: &7click derecho con el &elibro de picks&7."));
                sendToAllRaw(MessageUtil.sep());

                logger.info("Picks iniciados",
                        String.format("Capitanes: %s vs %s. Pickea primero: %s", cap1, cap2, getPlayerName(currentCaptain)));
            } catch (Exception e) {
                logger.logError("Error en announcePickStart()", e);
            }
        }

        private void startNextPick() {
            if (availablePlayers.isEmpty()) {
                finishPicks();
                return;
            }

            if (availablePlayers.size() == 1) {
                PlayerData lastPlayer = availablePlayers.get(0);

                sendToAllActionBar("&eÚltimo jugador: &a" + getPlayerName(lastPlayer) + " &7(auto)");
                sendToAllChat("&eAutoPick &8» &b" + getPlayerName(lastPlayer) + " &7va para &a" + getPlayerName(currentCaptain));

                performPick(lastPlayer);
                return;
            }

            if (!isCaptainConnected(currentCaptain)) {
                handleCaptainDisconnection();
                return;
            }

            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception ignored) {
                }
            }

            int left = availablePlayers.size();

            sendToAllActionBar("&6Pick &8#&f" + pickNumber + " &8» &7turno: &a" + getPlayerName(currentCaptain) +
                    " &8| &7quedan: &e" + left);

            Player cap = safeGet(currentCaptain);
            if (cap != null) {
                MessageUtil.send(cap, "&aEs tu turno. &7Usa &e/pick <jugador> &7o el &elibro de picks&7.");
                MessageUtil.send(cap, "&7Disponibles: &f" + getAvailablePlayersInline(10));
            }

            alertCaptainWithSound(
                    currentCaptain,
                    MessageUtil.c("&aEs tu turno. &7Pickea con &e/pick <jugador>"),
                    3
            );

            schedulePickTimeout();
        }

        private void schedulePickTimeout() {
            timeoutTask = new BukkitRunnable() {
                @Override
                public void run() {
                    handlePickTimeout();
                }
            };
            timeoutTask.runTaskLater(RankedMinecraft.getInstance(), PICK_TIMEOUT_SECONDS * 20L);
        }

        private void handlePickTimeout() {
            if (finished) return;

            if (!isCaptainConnected(currentCaptain)) {
                handleCaptainDisconnection();
                return;
            }

            if (!availablePlayers.isEmpty()) {
                PlayerData randomPick = availablePlayers.get(
                        ThreadLocalRandom.current().nextInt(availablePlayers.size())
                );

                sendToAllChat("&cTiempo agotado &8» &7AutoPick: &b" + getPlayerName(randomPick) +
                        " &7para &a" + getPlayerName(currentCaptain));

                performPick(randomPick);
            }
        }

        public void handlePlayerPick(String captainDiscordId, String pickedPlayerUuid) {
            if (finished) return;

            if (!currentCaptain.getDiscordId().equals(captainDiscordId)) {
                PlayerData requester = findPlayerByDiscordId(captainDiscordId);
                Player p = requester != null ? safeGet(requester) : null;
                if (p != null) {
                    MessageUtil.send(p, "&cNo es tu turno. &7Le toca a &f" + getPlayerName(currentCaptain) + "&7.");
                }
                return;
            }

            PlayerData pickedPlayer = null;
            for (PlayerData player : availablePlayers) {
                if (player.getMinecraftUuid().equals(pickedPlayerUuid)) {
                    pickedPlayer = player;
                    break;
                }
            }

            Player cap = safeGet(currentCaptain);
            if (pickedPlayer == null) {
                if (cap != null) MessageUtil.send(cap, "&cEse jugador no está disponible para pickear.");
                return;
            }

            performPick(pickedPlayer);
        }

        private void performPick(PlayerData pickedPlayer) {
            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception ignored) {
                }
            }

            if (currentCaptain == captain1) team1.add(pickedPlayer);
            else team2.add(pickedPlayer);

            availablePlayers.remove(pickedPlayer);

            int left = availablePlayers.size();

            sendToAllChat("&ePick &8» &a" + getPlayerName(currentCaptain) +
                    " &7→ &b" + getPlayerName(pickedPlayer) +
                    " &8(&fquedan &e" + left + "&8)");

            sendToAllActionBar("&ePick &8» &a" + getPlayerName(currentCaptain) + " &7→ &b" + getPlayerName(pickedPlayer));

            // ===================== ORDEN NUEVO: ABABBABA =====================
            // pickNumber representa el pick actual. Luego incrementamos para preparar el "siguiente pick".
            pickNumber++;

            if (!availablePlayers.isEmpty()) {
                // Secuencia para picks 1..8 (10 jugadores total => 8 picks tras elegir capitanes)
                final String order = "ABABBABA";

                int nextIndex = pickNumber - 1; // pickNumber ya apunta al siguiente pick
                if (nextIndex >= 0 && nextIndex < order.length()) {
                    char next = order.charAt(nextIndex);
                    currentCaptain = (next == 'A') ? captain1 : captain2;
                } else {
                    // Fallback por si el tamaño de match no calza con 8 picks (no debería en tu caso)
                    currentCaptain = (currentCaptain == captain1) ? captain2 : captain1;
                }
            }
            // ================================================================

            if (availablePlayers.isEmpty()) {
                finishPicks();
            } else {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        startNextPick();
                    }
                }.runTaskLater(RankedMinecraft.getInstance(), 40L);
            }
        }

        private void finishPicks() {
            finished = true;

            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception ignored) {
                }
            }

            activeMatch.setPicksMatch(true);
            activeMatch.setBlueCaptain(captain1);
            activeMatch.setRedCaptain(captain2);

            // Guardar historial ANTI-LOOP cuando realmente termina la fase de picks
            rememberCaptains(captain1, captain2);

            Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
            teams.clear();
            teams.put(Team.BLUE, new ArrayList<>(team1));
            teams.put(Team.RED, new ArrayList<>(team2));

            for (PlayerData pd : allPlayers) {
                Player p = safeGet(pd);
                if (p == null) continue;
                TitleUtil.send(p, "&a&lEquipos listos", "&7Buena suerte.", 10, 40, 10);
            }

            sendToAllRaw(MessageUtil.sep());
            sendToAllRaw(MessageUtil.c("&9&lAzul &8(&fcap: &9" + getPlayerName(captain1) + "&8)"));
            for (PlayerData player : team1) {
                sendToAllRaw(MessageUtil.c("&9• &f" + getPlayerName(player)));
            }
            sendToAllRaw(MessageUtil.c("&c&lRojo &8(&fcap: &c" + getPlayerName(captain2) + "&8)"));
            for (PlayerData player : team2) {
                sendToAllRaw(MessageUtil.c("&c• &f" + getPlayerName(player)));
            }
            sendToAllRaw(MessageUtil.sep());

            logger.info("Picks completados",
                    String.format("Equipos formados - Azul: %d, Rojo: %d", team1.size(), team2.size()));

            createFinalChannelsAndMovePlayersThenCleanup();
        }

        private void createFinalChannelsAndMovePlayersThenCleanup() {
            try {
                logger.info("Proceso final", "Creando canales finales y moviendo jugadores");

                activeMatch.createTeamChannels();

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            activeMatch.assignPlayersInMinecraft();

                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    cleanupTemporaryChannels();
                                    startFinalCountdown(activeMatch, logger);
                                    cleanupSession(matchId);
                                }
                            }.runTaskLater(RankedMinecraft.getInstance(), 40L);

                        } catch (Exception e) {
                            logger.logError("Error en asignación de equipos", e);
                            cleanupTemporaryChannels();
                            activeMatch.cleanup();
                        }
                    }
                }.runTaskLater(RankedMinecraft.getInstance(), 60L);

            } catch (Exception e) {
                logger.logError("Error en proceso final de picks", e);
                cleanupTemporaryChannels();
                activeMatch.cleanup();
            }
        }

        private void cleanupTemporaryChannels() {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (tempPickChannel != null) {
                        try {
                            tempPickChannel.delete().queue();
                        } catch (Exception ignored) {
                        }
                        tempPickChannel = null;
                    }
                }
            }.runTaskLater(RankedMinecraft.getInstance(), 60L);
        }

        private String getPlayerName(PlayerData playerData) {
            try {
                UUID uuid = parseUuid(playerData.getMinecraftUuid());
                if (uuid == null) return "Unknown";

                Player player = Bukkit.getPlayer(uuid);
                if (player != null) return player.getName();

                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                if (offlinePlayer.getName() != null) return offlinePlayer.getName();

                return "Player_" + playerData.getMinecraftUuid().substring(0, 8);
            } catch (Exception e) {
                return "Unknown";
            }
        }

        private boolean isCaptainConnected(PlayerData captain) {
            try {
                UUID uuid = parseUuid(captain.getMinecraftUuid());
                if (uuid == null) return false;

                Player player = Bukkit.getPlayer(uuid);
                return player != null && player.isOnline();
            } catch (Exception e) {
                return false;
            }
        }

        private void handleCaptainDisconnection() {
            logger.warning("Capitán desconectado",
                    String.format("El capitán %s se desconectó durante los picks", getPlayerName(currentCaptain)));

            sendToAllChat("&cCapitán desconectado &8» &7" + getPlayerName(currentCaptain));

            PlayerData otherCaptain = (currentCaptain == captain1) ? captain2 : captain1;
            if (!isCaptainConnected(otherCaptain)) {
                sendToAllChat("&cPartida cancelada &8» &7ambos capitanes se desconectaron.");
                cancelPickSession();
                return;
            }

            PlayerData replacementCaptain = findReplacementCaptain();
            if (replacementCaptain != null) {
                replaceDisconnectedCaptain(replacementCaptain);
            } else {
                fallbackToAutomaticFromPicks();
            }
        }

        private PlayerData findReplacementCaptain() {
            for (PlayerData player : availablePlayers) {
                if (isEligibleCaptain(player) && isCaptainConnected(player)) return player;
            }

            PlayerData otherCaptain = (currentCaptain == captain1) ? captain2 : captain1;
            List<PlayerData> otherTeam = (currentCaptain == captain1) ? team2 : team1;

            for (PlayerData player : otherTeam) {
                if (!player.equals(otherCaptain) && isEligibleCaptain(player) && isCaptainConnected(player)) {
                    return player;
                }
            }
            return null;
        }

        private boolean isEligibleCaptain(PlayerData player) {
            try {
                Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                return member != null && hasSponsorRole(member);
            } catch (Exception e) {
                return false;
            }
        }

        private void replaceDisconnectedCaptain(PlayerData replacementCaptain) {
            sendToAllChat("&aNuevo capitán &8» &f" + getPlayerName(replacementCaptain));

            availablePlayers.remove(replacementCaptain);
            team1.remove(replacementCaptain);
            team2.remove(replacementCaptain);

            if (currentCaptain == captain1) {
                team1.remove(captain1);
                team1.add(replacementCaptain);
                captain1 = replacementCaptain;
                currentCaptain = captain1;
            } else {
                team2.remove(captain2);
                team2.add(replacementCaptain);
                captain2 = replacementCaptain;
                currentCaptain = captain2;
            }

            startNextPick();
        }

        private void cancelPickSession() {
            finished = true;

            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception ignored) {
                }
            }

            cleanupTemporaryChannels();
            handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
            cleanupSession(matchId);
        }

        private void fallbackToAutomaticFromPicks() {
            finished = true;

            if (timeoutTask != null) {
                try {
                    timeoutTask.cancel();
                } catch (Exception ignored) {
                }
            }

            cleanupTemporaryChannels();
            sendToAllChat("&eBalanceo automático &8» &7no hay reemplazo de capitán.");

            cleanupSession(matchId);
            fallbackToAutomaticBalancing(activeMatch, logger);
        }

        private PlayerData findPlayerByDiscordId(String discordId) {
            for (PlayerData player : allPlayers) {
                if (Objects.equals(player.getDiscordId(), discordId)) return player;
            }
            return null;
        }

        /**
         * Overload para evitar confusión de firma.
         * Por defecto usa 3 segundos de sonido.
         */
        private void alertCaptainWithSound(PlayerData captain, String chatMsg) {
            alertCaptainWithSound(captain, chatMsg, 3);
        }

        private void alertCaptainWithSound(PlayerData captain, String chatMsg, int seconds) {
            if (captain == null) return;

            try {
                Player p = safeGet(captain);
                if (p == null) return;

                if (chatMsg != null && !chatMsg.isEmpty()) {
                    p.sendMessage(chatMsg);
                }

                final Sound alarmSound = resolvePlingSound();
                if (alarmSound == null) return;

                final int intervalTicks = 10;
                final int totalTicks = Math.max(1, seconds) * 20;
                final int repeats = Math.max(1, totalTicks / intervalTicks);

                new BukkitRunnable() {
                    int count = 0;

                    @Override
                    public void run() {
                        if (!p.isOnline()) {
                            cancel();
                            return;
                        }
                        p.playSound(p.getLocation(), alarmSound, 1.0f, 1.2f);
                        count++;
                        if (count >= repeats) cancel();
                    }
                }.runTaskTimer(RankedMinecraft.getInstance(), 0L, intervalTicks);

            } catch (Exception ignored) {
            }
        }

        private Sound resolvePlingSound() {
            String[] candidates = {
                    "BLOCK_NOTE_BLOCK_PLING",
                    "BLOCK_NOTE_PLING",
                    "NOTE_PLING"
            };
            for (String name : candidates) {
                try {
                    return Sound.valueOf(name);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return null;
        }
    }
}