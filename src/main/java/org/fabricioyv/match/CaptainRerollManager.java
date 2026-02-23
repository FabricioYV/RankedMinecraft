package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.util.ActionBarUtil;
import org.fabricioyv.util.TitleUtil;

import java.util.*;

public class CaptainRerollManager {

    private static final Map<String, RerollSession> sessions = new HashMap<>();

    // ===================== Configuración =====================
    private static final int INITIAL_WAIT_SECONDS = 10;
    private static final int TIME_INCREMENT_PER_REROLL = 5;

    // OK Solo los capitanes valen x2 (RR capitanes). El resto vale x1.
    private static final int CAPTAIN_VOTE_POWER = 2;

    // Requisitos por reroll aprobado: 4 -> 6 -> 8 -> 10, luego se bloquea
    private static final int[] VOTE_STEPS = {4, 6, 8, 10};

    public static void startRerollPhase(ActiveMatch match, Runnable onPhaseFinished) {
        RerollSession session = new RerollSession(match, onPhaseFinished);
        sessions.put(match.getMatchId(), session);
        session.start();
    }

    public static void handleInteract(Player player) {
        for (RerollSession session : sessions.values()) {
            if (session.hasPlayer(player)) {
                session.vote(player);
                return;
            }
        }
    }

    public static void cleanup(String matchId) {
        RerollSession session = sessions.remove(matchId);
        if (session != null) {
            session.cancel();
        }
    }

    // ===================== UUID helper (con o sin guiones) =====================

    private static UUID parseUuid(String raw) {
        if (raw == null) return null;

        String s = raw.trim();
        if (s.isEmpty()) return null;

        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ignored) {
        }

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

    // ===================== Sesión =====================

    private static class RerollSession {

        private final ActiveMatch match;
        private final Runnable onPhaseFinished;
        private final Set<UUID> votes = new HashSet<>();
        private final List<PlayerData> players;

        private int successfulRerolls = 0;
        private boolean rerollLocked = false;

        private int currentTimerSeconds;
        private BukkitTask timerTask;

        // Session anti-loop (parejas ya usadas EN ESTA partida)
        private final Set<String> usedCaptainPairs = new HashSet<>();

        // OK Anti "JugadorA se queda siempre": historial por capitán en esta sesión
        private final Map<String, Integer> sessionCaptainPickCount = new HashMap<>();

        // OK Capitanes actuales (normalizados) para poder evitar reusar
        private final Set<String> currentCaptainNorms = new HashSet<>();

        private final Map<UUID, Integer> rerollSlotByPlayer = new HashMap<>();
        private final Map<UUID, ItemStack> replacedItemByPlayer = new HashMap<>();

        public RerollSession(ActiveMatch match, Runnable onPhaseFinished) {
            this.match = match;
            this.onPhaseFinished = onPhaseFinished;
            List<PlayerData> list = match.getAllPlayers();
            this.players = (list != null) ? list : Collections.emptyList();
            this.currentTimerSeconds = INITIAL_WAIT_SECONDS;
        }

        private Player safeGet(PlayerData pd) {
            if (pd == null) return null;
            UUID id = CaptainRerollManager.parseUuid(pd.getMinecraftUuid());
            return (id != null) ? Bukkit.getPlayer(id) : null;
        }

        private String keyFor(PlayerData a, PlayerData b) {
            String au = CaptainPickSystem.normalize(a);
            String bu = CaptainPickSystem.normalize(b);
            if (au == null || bu == null) return null;
            return CaptainPickSystem.pairKey(au, bu);
        }

        public void start() {
            // Si ya vienen capitanes seteados, registrarlos como "pareja inicial"
            if (match.getBlueCaptain() != null && match.getRedCaptain() != null) {
                PlayerData cap1 = match.getBlueCaptain();
                PlayerData cap2 = match.getRedCaptain();

                registerCurrentCaptains(cap1, cap2);

                String initialKey = keyFor(cap1, cap2);
                if (initialKey != null) usedCaptainPairs.add(initialKey);

                broadcastTitle("§9Capitanes Seleccionados",
                        "§9" + cap1.getMinecraftName() + " §7vs §c" + cap2.getMinecraftName());

                broadcast("§7--------------------------------");
                broadcast("§9Capitán Azul: §f" + cap1.getMinecraftName());
                broadcast("§cCapitán Rojo: §f" + cap2.getMinecraftName());
                broadcast("§eUsa el disco para votar por nuevos capitanes (Reroll).");
                broadcast("§7--------------------------------");
            } else {
                boolean ok = selectSmartCaptains();
                if (!ok) {
                    finishPhase();
                    return;
                }
            }

            giveVotingItems();

            // OK Anuncio llamativo
            announceRerollAvailable();

            startTimer();
        }

        public boolean hasPlayer(Player player) {
            if (player == null) return false;
            UUID pid = player.getUniqueId();

            for (PlayerData p : players) {
                UUID id = CaptainRerollManager.parseUuid(p.getMinecraftUuid());
                if (id != null && id.equals(pid)) return true;
            }
            return false;
        }

        // OK Puntos actuales: votos normales x1, votos de capitanes xCAPTAIN_VOTE_POWER
        private int getVotePoints() {
            int sum = 0;
            for (UUID u : votes) {
                sum += isCurrentCaptain(u) ? CAPTAIN_VOTE_POWER : 1;
            }
            return sum;
        }

        private boolean isCurrentCaptain(UUID voterUuid) {
            if (voterUuid == null) return false;

            PlayerData blue = match.getBlueCaptain();
            PlayerData red = match.getRedCaptain();

            UUID blueId = (blue != null) ? CaptainRerollManager.parseUuid(blue.getMinecraftUuid()) : null;
            UUID redId = (red != null) ? CaptainRerollManager.parseUuid(red.getMinecraftUuid()) : null;

            return (blueId != null && blueId.equals(voterUuid)) || (redId != null && redId.equals(voterUuid));
        }

        private int getVoteWeight(Player player) {
            if (player == null) return 1;
            return isCurrentCaptain(player.getUniqueId()) ? CAPTAIN_VOTE_POWER : 1;
        }

        public void vote(Player player) {
            if (player == null) return;

            if (rerollLocked) {
                player.sendMessage(ChatColor.RED + "El reroll ya está deshabilitado (límite alcanzado).");
                return;
            }

            if (votes.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Ya has votado por reroll.");
                return;
            }

            votes.add(player.getUniqueId());

            int points = getVotePoints();
            int required = getRequiredVotePoints();

            int weight = getVoteWeight(player);

            broadcast(ChatColor.YELLOW + player.getName() + ChatColor.GRAY +
                    " votó por cambiar capitanes " + ChatColor.AQUA +
                    "(" + points + "/" + required + ")" + ChatColor.GRAY +
                    " §7(capitanes x" + CAPTAIN_VOTE_POWER + (weight == CAPTAIN_VOTE_POWER ? ", tu voto x" + CAPTAIN_VOTE_POWER : ", tu voto x1") + ")");

            player.sendMessage(ChatColor.GREEN + "OK Tu voto vale x" + weight + ChatColor.GRAY +
                    (weight == CAPTAIN_VOTE_POWER ? " (eres capitán)." : "."));

            playSound(soundByName("CLICK", "UI_BUTTON_CLICK"));

            if (points >= required) {
                doReroll();
            }
        }

        private void doReroll() {
            if (rerollLocked) return;

            PlayerData oldBlue = match.getBlueCaptain();
            PlayerData oldRed = match.getRedCaptain();

            broadcast(ChatColor.GREEN + "!Votación aprobada! Cambiando capitanes...");
            votes.clear();

            boolean changed = selectSmartCaptains();
            if (!changed) {
                broadcast(ChatColor.RED + "No se encontró una nueva pareja. Continuando con la actual.");
                return;
            }

            // OK Solo cuenta si realmente cambió
            successfulRerolls++;

            // Aumenta el timer por reroll aprobado
            currentTimerSeconds += TIME_INCREMENT_PER_REROLL;

            // Mensaje comparativo (para que se note el cambio)
            PlayerData newBlue = match.getBlueCaptain();
            PlayerData newRed = match.getRedCaptain();
            if (oldBlue != null && oldRed != null && newBlue != null && newRed != null) {
                broadcast(ChatColor.GRAY + "Antes: " + ChatColor.BLUE + oldBlue.getMinecraftName()
                        + ChatColor.GRAY + " vs " + ChatColor.RED + oldRed.getMinecraftName()
                        + ChatColor.DARK_GRAY + "  |  "
                        + ChatColor.GRAY + "Ahora: " + ChatColor.BLUE + newBlue.getMinecraftName()
                        + ChatColor.GRAY + " vs " + ChatColor.RED + newRed.getMinecraftName());
            }

            // Bloqueo por límite
            if (successfulRerolls >= VOTE_STEPS.length) {
                rerollLocked = true;
                broadcast(ChatColor.RED + "Reroll deshabilitado: ya se alcanzó el máximo (4->6->8->10).");
                clearItems();
            } else {
                giveVotingItems();
            }

            cancelTimer();
            startTimer();
        }

        /**
         * OK Selección "smart" + anti "JugadorA se queda fijo"
         * - No repite pareja dentro de esta sesión (usedCaptainPairs)
         * - Prefiere NO reutilizar capitanes actuales (para evitar A vs B -> A vs C -> A vs D...)
         * - Penaliza capitanes repetidos en esta sesión (sessionCaptainPickCount)
         * - Mantiene anti-loop global (CaptainPickSystem snapshotRecentPairs, lastSet, recentCount)
         */
        private boolean selectSmartCaptains() {
            if (players == null || players.size() < 2) return false;

            List<PlayerData> valid = new ArrayList<>();
            for (PlayerData p : players) {
                if (p == null) continue;
                UUID u = CaptainRerollManager.parseUuid(p.getMinecraftUuid());
                if (u != null) valid.add(p);
            }
            if (valid.size() < 2) return false;

            int maxPairs = (valid.size() * (valid.size() - 1)) / 2;
            if (usedCaptainPairs.size() >= maxPairs) {
                broadcast(ChatColor.RED + "Ya no quedan combinaciones nuevas de capitanes en esta sesión.");
                return false;
            }

            final Guild guild = match.getGuild();

            Set<String> recentPairs = CaptainPickSystem.snapshotRecentPairs();
            Set<String> lastSet = CaptainPickSystem.snapshotLastCaptainSet();
            Map<String, Integer> recentCount = CaptainPickSystem.snapshotRecentCaptainCounts();

            final int MIN_WINS_FOR_CAPTAIN = 10;
            final int CAPTAIN_TOP_POOL = 10;
            final long PENALTY_BOTH_LAST_CAPTAINS = 100_000L;
            final long PENALTY_RECENT_CAPTAIN = 1_500L;

            // OK Penalizaciones "session anti-repeat"
            final long PENALTY_KEEP_CURRENT_CAPTAIN = 35_000L;     // mantener a un capitán actual
            final long PENALTY_SESSION_REUSE_CAPTAIN = 20_000L;    // repetir capitán a lo largo de rerolls

            // Detectar quién tiene ELO
            List<PlayerData> eloPlayers = new ArrayList<>();
            for (PlayerData p : valid) {
                if (CaptainPickSystem.hasElo(p)) eloPlayers.add(p);
            }

            // Filtrado suave por wins>=MIN si hay suficientes
            List<PlayerData> filtered = new ArrayList<>();
            for (PlayerData p : valid) {
                try {
                    if (p.getWins() >= MIN_WINS_FOR_CAPTAIN) filtered.add(p);
                } catch (Exception ignored) {}
            }
            if (filtered.size() < 2) filtered = valid;

            // Orden por score base
            filtered.sort(Comparator.comparingLong((PlayerData p) -> CaptainPickSystem.captainBaseScore(p, guild)).reversed());

            boolean requireAtLeastOneElo = !eloPlayers.isEmpty();

            int limit = Math.min(CAPTAIN_TOP_POOL, filtered.size());
            List<PlayerData> pool = filtered.subList(0, limit);

            PlayerData bestA = null, bestB = null;
            String bestKey = null;
            long bestScore = Long.MIN_VALUE;

            /*
             * 3 pasadas:
             * pass 0: NO permitir mantener capitanes actuales (ideal)
             * pass 1: permitir mantener 1 (si no hay opción)
             * pass 2: permitir cualquiera (último recurso)
             *
             * + 2 sub-pasadas internas para recentPairs: estricto y luego relajado
             */
            for (int passKeep = 0; passKeep < 3; passKeep++) {
                for (int passRecent = 0; passRecent < 2; passRecent++) {
                    boolean allowRecentPairs = (passRecent == 1);

                    for (int i = 0; i < pool.size(); i++) {
                        for (int j = i + 1; j < pool.size(); j++) {
                            PlayerData a = pool.get(i);
                            PlayerData b = pool.get(j);

                            String au = CaptainPickSystem.normalize(a);
                            String bu = CaptainPickSystem.normalize(b);
                            if (au == null || bu == null) continue;

                            if (requireAtLeastOneElo && !(CaptainPickSystem.hasElo(a) || CaptainPickSystem.hasElo(b))) continue;

                            String pk = CaptainPickSystem.pairKey(au, bu);

                            if (usedCaptainPairs.contains(pk)) continue;
                            if (!allowRecentPairs && recentPairs.contains(pk)) continue;

                            // OK evitar patrón A fijo (control de "keep")
                            int keepCount = 0;
                            if (currentCaptainNorms.contains(au)) keepCount++;
                            if (currentCaptainNorms.contains(bu)) keepCount++;
                            if (keepCount > passKeep) continue;

                            long score = CaptainPickSystem.captainBaseScore(a, guild) + CaptainPickSystem.captainBaseScore(b, guild);

                            // Evitar repetir 2/2 capitanes de la última partida
                            if (lastSet.contains(au) && lastSet.contains(bu)) {
                                score -= PENALTY_BOTH_LAST_CAPTAINS;
                            }

                            // Penalización por frecuencia global
                            score -= PENALTY_RECENT_CAPTAIN * (long) recentCount.getOrDefault(au, 0);
                            score -= PENALTY_RECENT_CAPTAIN * (long) recentCount.getOrDefault(bu, 0);

                            // OK Penalización por mantener capitanes actuales
                            if (keepCount > 0) score -= PENALTY_KEEP_CURRENT_CAPTAIN * (long) keepCount;

                            // OK Penalización por repetir capitanes en esta sesión
                            score -= PENALTY_SESSION_REUSE_CAPTAIN * (long) sessionCaptainPickCount.getOrDefault(au, 0);
                            score -= PENALTY_SESSION_REUSE_CAPTAIN * (long) sessionCaptainPickCount.getOrDefault(bu, 0);

                            if (score > bestScore) {
                                bestScore = score;
                                bestA = a;
                                bestB = b;
                                bestKey = pk;
                            }
                        }
                    }

                    if (bestA != null && bestB != null) break;
                }
                if (bestA != null && bestB != null) break;
            }

            // Fallback: cualquier pareja no usada
            if (bestA == null || bestB == null) {
                outer:
                for (int i = 0; i < filtered.size(); i++) {
                    for (int j = i + 1; j < filtered.size(); j++) {
                        PlayerData a = filtered.get(i);
                        PlayerData b = filtered.get(j);

                        String au = CaptainPickSystem.normalize(a);
                        String bu = CaptainPickSystem.normalize(b);
                        if (au == null || bu == null) continue;

                        if (requireAtLeastOneElo && !(CaptainPickSystem.hasElo(a) || CaptainPickSystem.hasElo(b))) continue;

                        String pk = CaptainPickSystem.pairKey(au, bu);
                        if (pk == null) continue;
                        if (usedCaptainPairs.contains(pk)) continue;

                        bestA = a; bestB = b; bestKey = pk;
                        break outer;
                    }
                }
            }

            if (bestA == null || bestB == null || bestKey == null) {
                broadcast(ChatColor.RED + "No se pudo encontrar una pareja nueva de capitanes. Manteniendo la actual.");
                return false;
            }

            applyCaptains(bestA, bestB, bestKey);
            return true;
        }

        private void applyCaptains(PlayerData cap1, PlayerData cap2, String pairKey) {
            usedCaptainPairs.add(pairKey);

            match.setBlueCaptain(cap1);
            match.setRedCaptain(cap2);
            match.setPicksMatch(true);

            // OK registrar current captains + session counts
            registerCurrentCaptains(cap1, cap2);

            broadcastTitle(ChatColor.BLUE + "Capitanes Seleccionados",
                    ChatColor.BLUE + cap1.getMinecraftName() + ChatColor.GRAY + " vs " + ChatColor.RED + cap2.getMinecraftName());

            broadcast(ChatColor.GRAY + "--------------------------------");
            broadcast(ChatColor.BLUE + "Capitán Azul: " + ChatColor.WHITE + cap1.getMinecraftName());
            broadcast(ChatColor.RED + "Capitán Rojo: " + ChatColor.WHITE + cap2.getMinecraftName());
            broadcast(ChatColor.YELLOW + "Usa el disco para votar por nuevos capitanes. §7(capitanes x" + CAPTAIN_VOTE_POWER + ")");
            broadcast(ChatColor.GRAY + "--------------------------------");
        }

        private void registerCurrentCaptains(PlayerData cap1, PlayerData cap2) {
            currentCaptainNorms.clear();

            String u1 = CaptainPickSystem.normalize(cap1);
            String u2 = CaptainPickSystem.normalize(cap2);

            if (u1 != null) {
                currentCaptainNorms.add(u1);
                sessionCaptainPickCount.put(u1, sessionCaptainPickCount.getOrDefault(u1, 0) + 1);
            }
            if (u2 != null) {
                currentCaptainNorms.add(u2);
                sessionCaptainPickCount.put(u2, sessionCaptainPickCount.getOrDefault(u2, 0) + 1);
            }
        }

        private void startTimer() {
            cancelTimer();

            BukkitRunnable runnable = new BukkitRunnable() {
                int timeLeft = currentTimerSeconds;

                @Override
                public void run() {
                    if (timeLeft <= 0) {
                        finishPhase();
                        this.cancel();
                        return;
                    }

                    String rrPart = rerollLocked
                            ? (ChatColor.RED + "Reroll: OFF")
                            : (ChatColor.YELLOW + "Reroll: " + getVotePoints() + "/" + getRequiredVotePoints() + ChatColor.GRAY + " (capitanes x" + CAPTAIN_VOTE_POWER + ")");

                    String bar = ChatColor.GOLD + "Iniciando Picks en: " + ChatColor.WHITE + timeLeft + "s " +
                            ChatColor.GRAY + "| " + rrPart;

                    for (PlayerData p : players) {
                        Player pl = safeGet(p);
                        if (pl != null) ActionBarUtil.send(pl, bar);
                    }

                    if (timeLeft <= 5 || timeLeft == 10) {
                        playSound(soundByName("NOTE_STICKS", "BLOCK_NOTE_HAT", "BLOCK_NOTE_BLOCK_HAT"));
                    }

                    timeLeft--;
                }
            };

            timerTask = runnable.runTaskTimer(RankedMinecraft.getInstance(), 0L, 20L);
        }

        private void finishPhase() {
            cleanup(match.getMatchId());
            broadcast(ChatColor.GREEN + "!Fase de votación terminada! Iniciando picks...");
            if (onPhaseFinished != null) onPhaseFinished.run();
        }

        private void announceRerollAvailable() {
            // Un anuncio "no puedes decir que no lo viste"
            String title = "§d§lREROLL DE CAPITANES";
            String subtitle = "§fClick derecho al §bDISCO §fpara votar §7(capitanes x" + CAPTAIN_VOTE_POWER + ")";
            broadcastTitle(title, subtitle);

            broadcast("§7--------------------------------");
            broadcast("§d§lREROLL DISPONIBLE §7> §fClick derecho al §bDISCO §fpara votar por nuevos capitanes.");
            broadcast("§7Si eres §bCAPITÁN§7, tu voto vale §ax" + CAPTAIN_VOTE_POWER + "§7 (si no, vale x1).");
            broadcast("§7--------------------------------");

            playSound(soundByName("LEVEL_UP", "ENTITY_PLAYER_LEVELUP"));
        }

        private void giveVotingItems() {
            if (rerollLocked) return;

            Material voteMat = materialByName("RECORD_3", "MUSIC_DISC_BLOCKS");
            if (voteMat == null) voteMat = Material.PAPER;

            ItemStack item = new ItemStack(voteMat);
            ItemMeta meta = item.getItemMeta();

            int req = getRequiredVotePoints();
            int remaining = getRerollsRemaining();

            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + "§lVotar Reroll Capitanes");
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Click derecho para votar",
                        ChatColor.GRAY + "por nuevos capitanes.",
                        "",
                        ChatColor.YELLOW + "Capitanes: " + ChatColor.AQUA + "x" + CAPTAIN_VOTE_POWER + ChatColor.YELLOW + " | Jugadores: " + ChatColor.AQUA + "x1",
                        ChatColor.YELLOW + "Requiere: " + ChatColor.AQUA + req + " puntos",
                        ChatColor.YELLOW + "Rerolls restantes: " + ChatColor.AQUA + remaining
                ));
                item.setItemMeta(meta);
            }

            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) {
                    int slot = findBestHotbarSlot(pl);

                    UUID pid = pl.getUniqueId();
                    if (!rerollSlotByPlayer.containsKey(pid)) {
                        rerollSlotByPlayer.put(pid, slot);

                        ItemStack prev = pl.getInventory().getItem(slot);
                        replacedItemByPlayer.put(pid, prev == null ? null : prev.clone());
                    }

                    pl.getInventory().setItem(rerollSlotByPlayer.get(pid), item.clone());
                    pl.updateInventory();
                }
            }
        }

        private void clearItems() {
            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) {
                    UUID pid = pl.getUniqueId();

                    Integer slot = rerollSlotByPlayer.remove(pid);
                    if (slot == null) continue;

                    ItemStack prev = replacedItemByPlayer.remove(pid);
                    pl.getInventory().setItem(slot, prev);
                    pl.updateInventory();
                }
            }
        }

        private void cancelTimer() {
            if (timerTask != null) {
                timerTask.cancel();
                timerTask = null;
            }
        }

        private void cancel() {
            cancelTimer();
            clearItems();
            rerollSlotByPlayer.clear();
            replacedItemByPlayer.clear();
        }

        // OK Requerido en "puntos", capado para no soft-lock (max = players + capitanes*(CAPTAIN_VOTE_POWER-1))
        private int getRequiredVotePoints() {
            if (players == null) return Integer.MAX_VALUE;
            int total = players.size();
            if (total <= 0) return Integer.MAX_VALUE;

            int stepIndex = Math.min(successfulRerolls, VOTE_STEPS.length - 1);
            int required = VOTE_STEPS[stepIndex];

            // OK Máximo posible:
            //   - jugadores normales: x1
            //   - capitanes (máx 2): xCAPTAIN_VOTE_POWER
            int maxPossible = getMaxPossibleVotePoints();
            return Math.min(required, maxPossible);
        }

        private int getMaxPossibleVotePoints() {
            if (players == null || players.isEmpty()) return 0;

            // Capitanes (únicos)
            Set<UUID> captainUuids = new HashSet<>();
            PlayerData blue = match.getBlueCaptain();
            PlayerData red = match.getRedCaptain();
            UUID blueId = (blue != null) ? CaptainRerollManager.parseUuid(blue.getMinecraftUuid()) : null;
            UUID redId = (red != null) ? CaptainRerollManager.parseUuid(red.getMinecraftUuid()) : null;
            if (blueId != null) captainUuids.add(blueId);
            if (redId != null) captainUuids.add(redId);

            // Solo cuenta capitanes que realmente estén dentro del roster de esta partida
            int captainsInPlayers = 0;
            if (!captainUuids.isEmpty()) {
                for (PlayerData p : players) {
                    if (p == null) continue;
                    UUID u = CaptainRerollManager.parseUuid(p.getMinecraftUuid());
                    if (u != null && captainUuids.contains(u)) captainsInPlayers++;
                }
            }

            // Base: todos los jugadores cuentan x1, y cada capitán agrega (power-1) extra
            return players.size() + (captainsInPlayers * (CAPTAIN_VOTE_POWER - 1));
        }

        private int getRerollsRemaining() {
            return Math.max(0, VOTE_STEPS.length - successfulRerolls);
        }

        private void broadcast(String msg) {
            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) pl.sendMessage(msg);
            }
        }

        private void broadcastTitle(String title, String subtitle) {
            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) TitleUtil.send(pl, title, subtitle, 10, 40, 10);
            }
        }

        private void playSound(Sound sound) {
            if (sound == null) return;
            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) pl.playSound(pl.getLocation(), sound, 1f, 1f);
            }
        }

        private Sound soundByName(String... names) {
            for (String n : names) {
                try {
                    return Sound.valueOf(n);
                } catch (IllegalArgumentException ignored) {}
            }
            return null;
        }

        private Material materialByName(String... names) {
            for (String n : names) {
                try {
                    return Material.valueOf(n);
                } catch (IllegalArgumentException ignored) {}
            }
            return null;
        }

        private boolean isAir(ItemStack it) {
            return it == null || it.getType() == Material.AIR;
        }

        private int findBestHotbarSlot(Player pl) {
            for (int i = 8; i >= 0; i--) {
                ItemStack it = pl.getInventory().getItem(i);
                if (isAir(it)) return i;
            }
            return 8;
        }
    }
}