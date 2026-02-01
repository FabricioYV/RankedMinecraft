package org.fabricioyv.match;

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
import net.dv8tion.jda.api.entities.Guild;

import java.util.*;

public class CaptainRerollManager {

    private static final Map<String, RerollSession> sessions = new HashMap<>();

    // Configuración
    private static final int INITIAL_WAIT_SECONDS = 10;
    private static final int TIME_INCREMENT_PER_REROLL = 5;
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

    // Clase interna para manejar el estado de cada partida
    private static class RerollSession {

        private final ActiveMatch match;
        private final Runnable onPhaseFinished;
        private final Set<UUID> votes = new HashSet<>();
        private final List<PlayerData> players;
        private int successfulRerolls = 0;   // cuántos rerolls aprobaron y cambiaron capitanes
        private boolean rerollLocked = false;

        private int currentTimerSeconds;
        private BukkitTask timerTask;

        private final Set<UUID> currentCaptainsPair = new HashSet<>();
        private final Set<String> usedCaptainPairs = new HashSet<>();
        private final Map<UUID, Integer> rerollSlotByPlayer = new HashMap<>();
        private final Map<UUID, ItemStack> replacedItemByPlayer = new HashMap<>();

        public RerollSession(ActiveMatch match, Runnable onPhaseFinished) {
            this.match = match;
            this.onPhaseFinished = onPhaseFinished;
            List<PlayerData> list = match.getAllPlayers();
            this.players = (list != null) ? list : Collections.emptyList();
            this.currentTimerSeconds = INITIAL_WAIT_SECONDS;
        }

        private String keyFor(PlayerData a, PlayerData b) {
            String au = CaptainPickSystem.normalize(a);
            String bu = CaptainPickSystem.normalize(b);
            if (au == null || bu == null) return null;
            return CaptainPickSystem.pairKey(au, bu);
        }

        private Player safeGet(PlayerData pd) {
            if (pd == null) return null;
            UUID id = CaptainRerollManager.parseUuid(pd.getMinecraftUuid());
            return (id != null) ? Bukkit.getPlayer(id) : null;
        }

        public void start() {
            if (match.getBlueCaptain() != null && match.getRedCaptain() != null) {
                PlayerData cap1 = match.getBlueCaptain();
                PlayerData cap2 = match.getRedCaptain();

                // Registrar pareja inicial si es posible
                UUID u1 = CaptainRerollManager.parseUuid(cap1.getMinecraftUuid());
                UUID u2 = CaptainRerollManager.parseUuid(cap2.getMinecraftUuid());

                currentCaptainsPair.clear();
                if (u1 != null) currentCaptainsPair.add(u1);
                if (u2 != null) currentCaptainsPair.add(u2);

                String initialKey = keyFor(cap1, cap2);
                if (initialKey != null) usedCaptainPairs.add(initialKey);

                // Solo anunciamos los actuales
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
            int required = getRequiredVotes();

            String msg = ChatColor.YELLOW + player.getName() + ChatColor.GRAY + " votó por cambiar capitanes " +
                    ChatColor.AQUA + "(" + votes.size() + "/" + required + ")";
            broadcast(msg);

            playSound(soundByName("CLICK", "UI_BUTTON_CLICK"));

            if (votes.size() >= required) {
                doReroll();
            }
        }

        private void doReroll() {
            if (rerollLocked) return;

            broadcast(ChatColor.GREEN + "¡Votación aprobada! Cambiando capitanes...");
            votes.clear();

            boolean changed = selectSmartCaptains();
            if (!changed) {
                broadcast(ChatColor.RED + "No se encontró una nueva pareja. Continuando con la actual.");
                return;
            }

            // ✅ Solo cuenta si realmente cambió capitanes
            successfulRerolls++;

            // Aumenta el timer por reroll aprobado (como ya lo tenías)
            currentTimerSeconds += TIME_INCREMENT_PER_REROLL;

            // Si ya se aprobó el paso 10 (o llegaste al final de VOTE_STEPS), se bloquea el reroll
            if (successfulRerolls >= VOTE_STEPS.length) {
                rerollLocked = true;
                broadcast(ChatColor.RED + "Reroll deshabilitado: ya se alcanzó el máximo (4→6→8→10).");
                clearItems(); // quita el disco para que no jodan más
            } else {
                // refresca lore para mostrar el nuevo requisito
                giveVotingItems();
            }

            cancelTimer();
            startTimer();
        }

        private boolean selectSmartCaptains() {
            if (players == null || players.size() < 2) return false;

            // Solo candidatos con UUID parseable (evita llaves raras)
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

            // Historial anti-loop global (entre partidas) desde CaptainPickSystem
            Set<String> recentPairs = CaptainPickSystem.snapshotRecentPairs();
            Set<String> lastSet = CaptainPickSystem.snapshotLastCaptainSet();
            Map<String, Integer> recentCount = CaptainPickSystem.snapshotRecentCaptainCounts();

            // Copia de los mismos valores que usas en CaptainPickSystem (para mantener comportamiento)
            final int MIN_WINS_FOR_CAPTAIN = 10;
            final int CAPTAIN_TOP_POOL = 10;
            final long PENALTY_BOTH_LAST_CAPTAINS = 100_000L;
            final long PENALTY_RECENT_CAPTAIN = 1_500L;

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

            // Orden por score base (ELO/VIP/WINS)
            filtered.sort(Comparator.comparingLong((PlayerData p) -> CaptainPickSystem.captainBaseScore(p, guild)).reversed());

            // Caso especial: solo 1 con ELO -> fijo ese y roto el segundo (como tu lógica)
            if (eloPlayers.size() == 1) {
                PlayerData fixed = eloPlayers.get(0);

                List<PlayerData> others = new ArrayList<>(filtered);
                others.remove(fixed);
                PlayerData second = chooseSecondWithFixedFirstSmart(
                        fixed, others, guild, recentPairs, lastSet, recentCount,
                        usedCaptainPairs,
                        CAPTAIN_TOP_POOL,
                        PENALTY_BOTH_LAST_CAPTAINS,
                        PENALTY_RECENT_CAPTAIN
                );

                if (second == null) {
                    broadcast(ChatColor.RED + "No se encontró un segundo capitán válido (sin repetir parejas).");
                    return false;
                }

                String pk = keyFor(fixed, second);
                if (pk == null || usedCaptainPairs.contains(pk)) return false;

                applyCaptains(fixed, second, pk);
                return true;
            }

            // Si existe ELO en la queue, fuerza que al menos 1 de los 2 capitanes tenga ELO
            boolean requireAtLeastOneElo = !eloPlayers.isEmpty();

            int limit = Math.min(CAPTAIN_TOP_POOL, filtered.size());
            List<PlayerData> pool = filtered.subList(0, limit);

            PlayerData bestA = null, bestB = null;
            String bestKey = null;
            long bestScore = Long.MIN_VALUE;

            // 2 pasadas: estricto (no recentPairs), luego relajado
            for (int pass = 0; pass < 2; pass++) {
                boolean allowRecentPairs = (pass == 1);

                for (int i = 0; i < pool.size(); i++) {
                    for (int j = i + 1; j < pool.size(); j++) {
                        PlayerData a = pool.get(i);
                        PlayerData b = pool.get(j);

                        String au = CaptainPickSystem.normalize(a);
                        String bu = CaptainPickSystem.normalize(b);
                        if (au == null || bu == null) continue;

                        if (requireAtLeastOneElo && !(CaptainPickSystem.hasElo(a) || CaptainPickSystem.hasElo(b))) continue;

                        String pk = CaptainPickSystem.pairKey(au, bu);

                        // ✅ NO repetir pareja dentro de esta sesión (incluye primera pareja)
                        if (usedCaptainPairs.contains(pk)) continue;

                        // Anti-loop global (entre matches)
                        if (!allowRecentPairs && recentPairs.contains(pk)) continue;

                        long score = CaptainPickSystem.captainBaseScore(a, guild) + CaptainPickSystem.captainBaseScore(b, guild);

                        // Evitar repetir 2/2 capitanes de la última partida
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
                            bestKey = pk;
                        }
                    }
                }

                if (bestA != null && bestB != null) break;
            }

            // Fallback: busca cualquier pareja no usada (sin score) antes de rendirte
            if (bestA == null || bestB == null) {
                outer:
                for (int i = 0; i < filtered.size(); i++) {
                    for (int j = i + 1; j < filtered.size(); j++) {
                        PlayerData a = filtered.get(i);
                        PlayerData b = filtered.get(j);

                        String pk = keyFor(a, b);
                        if (pk == null) continue;
                        if (usedCaptainPairs.contains(pk)) continue;

                        // si requiere ELO y ninguno lo tiene, salta (solo si hay ELO en pool)
                        if (requireAtLeastOneElo && !(CaptainPickSystem.hasElo(a) || CaptainPickSystem.hasElo(b))) continue;

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

        private PlayerData chooseSecondWithFixedFirstSmart(PlayerData first,
                                                           List<PlayerData> others,
                                                           Guild guild,
                                                           Set<String> recentPairs,
                                                           Set<String> lastSet,
                                                           Map<String, Integer> recentCount,
                                                           Set<String> usedPairs,
                                                           int topPool,
                                                           long penaltyBothLast,
                                                           long penaltyRecent) {
            if (first == null || others == null || others.isEmpty()) return null;

            List<PlayerData> filtered = new ArrayList<>();
            for (PlayerData p : others) {
                try {
                    if (p != null && p.getWins() >= 10) filtered.add(p);
                } catch (Exception ignored) {}
            }
            if (filtered.isEmpty()) filtered = new ArrayList<>(others);

            filtered.sort(Comparator.comparingLong((PlayerData p) -> CaptainPickSystem.captainBaseScore(p, guild)).reversed());

            String fu = CaptainPickSystem.normalize(first);
            if (fu == null) return filtered.get(0);

            int limit = Math.min(topPool, filtered.size());
            List<PlayerData> pool = filtered.subList(0, limit);

            PlayerData best = null;
            long bestScore = Long.MIN_VALUE;

            for (int pass = 0; pass < 2; pass++) {
                boolean allowRecentPairs = (pass == 1);

                for (PlayerData p : pool) {
                    if (p == null) continue;
                    String pu = CaptainPickSystem.normalize(p);
                    if (pu == null) continue;

                    String pk = CaptainPickSystem.pairKey(fu, pu);
                    if (usedPairs.contains(pk)) continue;
                    if (!allowRecentPairs && recentPairs.contains(pk)) continue;

                    long score = CaptainPickSystem.captainBaseScore(p, guild);

                    if (lastSet.contains(fu) && lastSet.contains(pu)) {
                        score -= penaltyBothLast;
                    }

                    score -= penaltyRecent * (long) recentCount.getOrDefault(pu, 0);

                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }

                if (best != null) break;
            }

            return best;
        }

        private void applyCaptains(PlayerData cap1, PlayerData cap2, String pairKey) {
            usedCaptainPairs.add(pairKey);

            match.setBlueCaptain(cap1);
            match.setRedCaptain(cap2);
            match.setPicksMatch(true);

            broadcastTitle(ChatColor.BLUE + "Capitanes Seleccionados",
                    ChatColor.BLUE + cap1.getMinecraftName() + ChatColor.GRAY + " vs " + ChatColor.RED + cap2.getMinecraftName());

            broadcast(ChatColor.GRAY + "--------------------------------");
            broadcast(ChatColor.BLUE + "Capitán Azul: " + ChatColor.WHITE + cap1.getMinecraftName());
            broadcast(ChatColor.RED + "Capitán Rojo: " + ChatColor.WHITE + cap2.getMinecraftName());
            broadcast(ChatColor.YELLOW + "Usa el disco para votar por nuevos capitanes.");
            broadcast(ChatColor.GRAY + "--------------------------------");
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
                            : (ChatColor.YELLOW + "Votos Reroll: " + votes.size() + "/" + getRequiredVotes());

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
            cleanup(match.getMatchId()); // esto ya cancela timer + clearItems + limpia maps
            broadcast(ChatColor.GREEN + "¡Fase de votación terminada! Iniciando picks...");
            if (onPhaseFinished != null) onPhaseFinished.run();
        }

        private void giveVotingItems() {
            if (rerollLocked) return; // recomendado (ver punto 3)

            Material voteMat = materialByName("RECORD_3", "MUSIC_DISC_BLOCKS");
            if (voteMat == null) voteMat = Material.PAPER;

            ItemStack item = new ItemStack(voteMat);
            ItemMeta meta = item.getItemMeta();

            int req = getRequiredVotes();
            int remaining = getRerollsRemaining();

            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + "§lVotar Reroll Capitanes");
                meta.setLore(Arrays.asList(
                        ChatColor.GRAY + "Click derecho para votar",
                        ChatColor.GRAY + "por nuevos capitanes.",
                        "",
                        ChatColor.YELLOW + "Requiere: " + ChatColor.AQUA + req + " votos",
                        ChatColor.YELLOW + "Rerolls restantes: " + ChatColor.AQUA + remaining
                ));
                item.setItemMeta(meta);
            }

            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) {
                    int slot = findBestHotbarSlot(pl);

                    // Guardar para restaurar luego (solo la primera vez por jugador)
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
            Material air = materialByName("AIR");
            ItemStack empty = (air != null) ? new ItemStack(air) : null;

            for (PlayerData p : players) {
                Player pl = safeGet(p);
                if (pl != null) {
                    UUID pid = pl.getUniqueId();

                    Integer slot = rerollSlotByPlayer.remove(pid);
                    if (slot == null) continue;

                    ItemStack prev = replacedItemByPlayer.remove(pid); // puede ser null (equivale a AIR)
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

        private int getRequiredVotes() {
            if (players == null) return Integer.MAX_VALUE;
            int total = players.size();
            if (total <= 0) return Integer.MAX_VALUE;

            int stepIndex = Math.min(successfulRerolls, VOTE_STEPS.length - 1);
            int required = VOTE_STEPS[stepIndex];

            // Para no dejar el match en soft-lock si hay menos jugadores que el paso requerido
            return Math.min(required, total);
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
                } catch (IllegalArgumentException ignored) {
                }
            }
            return null;
        }

        private Material materialByName(String... names) {
            for (String n : names) {
                try {
                    return Material.valueOf(n);
                } catch (IllegalArgumentException ignored) {
                }
            }
            return null;
        }

        private boolean isAir(ItemStack it) {
            return it == null || it.getType() == Material.AIR;
        }

        private int findBestHotbarSlot(Player pl) {
            // Preferimos el último libre (8 -> 0)
            for (int i = 8; i >= 0; i--) {
                ItemStack it = pl.getInventory().getItem(i);
                if (isAir(it)) return i;
            }
            // Si todo está ocupado, usar 8 (pero guardaremos/restauraremos el item anterior)
            return 8;
        }
    }
}