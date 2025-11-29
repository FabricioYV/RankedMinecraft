package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static org.fabricioyv.match.MatchFinisher.movePlayersToWaitingRoom;

/**
 * Sistema de picks con capitanes para RankedMinecraft
 * Los jugadores con roles "Main Sponsor", "Sponsor" o "Server Booster" pueden ser capitanes
 */
public class CaptainPickSystem {

    private static final Map<String, PickSession> activeSessions = new ConcurrentHashMap<>();

    // IDs de roles que pueden ser capitanes
    private static final String MAIN_SPONSOR_ROLE_ID = "1413241361087332505";
    private static final String SPONSOR_ROLE_ID = "1413243740231041174";
    private static final String SERVER_BOOSTER_ROLE_ID = "1407203727076491295";

    private static final int PICK_TIMEOUT_SECONDS = 20; // AUMENTADO: 15 -> 20 segundos
    private static final int MIN_WINS_FOR_CAPTAIN = 10;
    /**
     * Inicia el sistema de picks después de seleccionar el mapa
     */
    public static void startPickPhase(ActiveMatch activeMatch, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();

        // Seleccionar los dos capitanes por MMR (desc), usando el rol sponsor SOLO como desempate
        List<PlayerData> selectedCaptains = selectTopTwoCaptainsByMmr(allPlayers, activeMatch.getGuild());

        if (selectedCaptains.size() < 2) {
            logger.info("Sistema de Picks",
                    String.format("No se pudieron seleccionar 2 capitanes (%d/2). Usando balanceo automático.",
                            selectedCaptains.size()));

            // Fallback al sistema de balanceo automático
            fallbackToAutomaticBalancing(activeMatch, logger);
            return;
        }

        // Crear sesión de picks usando los capitanes seleccionados
        PickSession session = new PickSession(matchId, allPlayers, selectedCaptains, activeMatch, logger);
        activeSessions.put(matchId, session);

        logger.info("Sistema de Picks",
                String.format("Iniciando fase de picks para partida %s con capitanes seleccionados: %s y %s",
                        matchId, getPlayerDisplayName(selectedCaptains.get(0)), getPlayerDisplayName(selectedCaptains.get(1))));

        // Iniciar el proceso de picks
        session.startPickProcess();
    }

    /**
     * Obtener nombre para logs/announces en contexto estático. Similar a getPlayerName pero estático
     */
    private static String getPlayerDisplayName(PlayerData playerData) {
        if (playerData == null) return "Unknown";
        try {
            Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (player != null) return player.getName();

            org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (offlinePlayer.getName() != null) return offlinePlayer.getName();

            return "Player_" + playerData.getMinecraftUuid().substring(0, Math.min(8, playerData.getMinecraftUuid().length()));
        } catch (Exception e) {
            return "Unknown";
        }
    }

    // IMPORTANTE: asegúrate de tener este import
// import java.util.Comparator;

    private static List<PlayerData> selectTopTwoCaptainsByMmr(List<PlayerData> players, Guild guild) {
        // 1) Filtrar jugadores con suficientes victorias
        List<PlayerData> eligible = new ArrayList<>();
        for (PlayerData p : players) {
            int wins = p.getWins();

            if (wins >= MIN_WINS_FOR_CAPTAIN) {
                eligible.add(p);
            }
        }

        // 2) Si hay menos de 2 “veteranos”, usar a todos como fallback
        if (eligible.size() < 2) {
            eligible = new ArrayList<>(players);
        }

        // Si al final solo hay 0, 1 o 2 jugadores, devolverlos tal cual
        if (eligible.size() <= 2) {
            return new ArrayList<>(eligible);
        }

        // 3) Ordenar por MMR descendente (sin sponsor, solo Elo)
        eligible.sort(Comparator.comparingDouble(PlayerData::getMmr).reversed());

        List<PlayerData> result = new ArrayList<>(2);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // --- Elegir primer capitán ---
        double topMmr = eligible.get(0).getMmr();

        // Grupo de jugadores empatados en el MMR más alto
        List<PlayerData> topGroup = new ArrayList<>();
        for (PlayerData p : eligible) {
            if (Double.compare(p.getMmr(), topMmr) == 0) {
                topGroup.add(p);
            } else {
                break; // ya pasamos a un MMR menor
            }
        }

        if (topGroup.size() >= 2) {
            // Hay varios con el MMR más alto -> elegir 2 aleatorios de ese grupo
            int idx1 = rnd.nextInt(topGroup.size());
            int idx2;
            do {
                idx2 = rnd.nextInt(topGroup.size());
            } while (idx2 == idx1);

            result.add(topGroup.get(idx1));
            result.add(topGroup.get(idx2));
            return result;
        } else {
            // Solo 1 con el MMR más alto -> es el primer capitán
            PlayerData firstCaptain = topGroup.get(0);
            result.add(firstCaptain);

            // --- Elegir segundo capitán ---
            // Quitar al primer capitán de la lista y volver a buscar el mejor MMR
            eligible.remove(firstCaptain);
            eligible.sort(Comparator.comparingDouble(PlayerData::getMmr).reversed());

            double secondMmr = eligible.get(0).getMmr();
            List<PlayerData> secondGroup = new ArrayList<>();
            for (PlayerData p : eligible) {
                if (Double.compare(p.getMmr(), secondMmr) == 0) {
                    secondGroup.add(p);
                } else {
                    break;
                }
            }

            // Elegir uno aleatorio entre los empatados en el segundo mejor MMR
            PlayerData secondCaptain = secondGroup.get(rnd.nextInt(secondGroup.size()));
            result.add(secondCaptain);

            return result;
        }
    }

    /**
     * Verifica si un miembro tiene rol de "Main Sponsor", "Sponsor" o "Server Booster"
     */
    private static boolean hasSponsorRole(Member member) {
        for (Role role : member.getRoles()) {
            if (role.getId().equals(MAIN_SPONSOR_ROLE_ID) || role.getId().equals(SPONSOR_ROLE_ID) || role.getId().equals(SERVER_BOOSTER_ROLE_ID)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fallback al sistema de balanceo automático
     */
    private static void fallbackToAutomaticBalancing(ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Balanceo Automático", "Ejecutando balanceo automático por falta de capitanes");

        // Usar el sistema de balanceo existente
        activeMatch.balanceTeams();

        // Continuar con el flujo normal de la partida
        continueAfterTeamFormation(activeMatch, logger);
    }

    /**
     * Continúa el flujo después de formar equipos (picks o balanceo automático)
     * CORREGIDO: NO crear canales aquí si ya se crearon en el sistema de picks
     */
    private static void continueAfterTeamFormation(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // CORREGIDO: Solo crear canales si NO es una partida de picks (evitar duplicados)
            if (!activeMatch.isPicksMatch()) {
                // 1. Crear canales de equipo (solo para matchmaking normal)
                activeMatch.createTeamChannels();

                // 2. Asignar equipos en Minecraft (incluye mover a canales Discord automáticamente)
                activeMatch.assignPlayersInMinecraft();
            } else {
                // Para partidas de picks, los canales ya fueron creados, solo asignar en Minecraft
                logger.info("Picks Match", "Saltando creación de canales - ya fueron creados en el sistema de picks");
                activeMatch.assignPlayersInMinecraft();
            }

            // 3. Iniciar cuenta regresiva final
            startFinalCountdown(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error en flujo después de formación de equipos", e);
            // Cancelar partida en caso de error crítico
            activeMatch.cleanup();
        }
    }

    /**
     * Inicia la cuenta regresiva final - VERSIÓN CORREGIDA CON PROTECCIÓN ANTI-BUG
     */
    private static void startFinalCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        // CRÍTICO: Verificar que todos los jugadores siguen conectados antes de la cuenta regresiva
        if (!validateAllPlayersConnected(activeMatch, logger)) {
            logger.warning("Validación Jugadores", "No todos los jugadores están conectados. Cancelando partida.");
            handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
            return;
        }

        announceToPlayers(activeMatch.getAllPlayers(),
                "§a§l¡EQUIPOS LISTOS! §fLa partida comenzará en 10 segundos...");

        logger.matchEvent(activeMatch.getMatchId(), "COUNTDOWN_INICIADO",
                "Cuenta regresiva de 10 segundos iniciada", activeMatch.getAllPlayers().size());

        // Programar inicio oficial en 10 segundos con validación continua
        new BukkitRunnable() {
            private int countdown = 10;

            @Override
            public void run() {
                // Verificar cada segundo que los jugadores siguen conectados
                if (!validateAllPlayersConnected(activeMatch, logger)) {
                    logger.warning("Validación Continua",
                            "Un jugador se desconectó durante la cuenta regresiva. Cancelando partida.");
                    handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
                    this.cancel();
                    return;
                }

                countdown--;

                if (countdown > 0) {
                    if (countdown <= 5) {
                        announceToPlayers(activeMatch.getAllPlayers(),
                                "§e§l" + countdown + "...");
                    }
                } else {
                    // Validación final antes de iniciar
                    if (validateAllPlayersConnected(activeMatch, logger)) {
                        startOfficialMatch(activeMatch, logger);
                    } else {
                        handlePlayerDisconnectionDuringCountdown(activeMatch, logger);
                    }
                    this.cancel();
                }
            }
        }.runTaskTimer(RankedMinecraft.getInstance(), 20L, 20L); // Cada segundo
    }

    /**
     * CRÍTICO: Valida que todos los jugadores sigan conectados
     */
    private static boolean validateAllPlayersConnected(ActiveMatch activeMatch, DiscordLogger logger) {
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();
        int connectedCount = 0;

        for (PlayerData playerData : allPlayers) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (player != null && player.isOnline()) {
                    connectedCount++;
                } else {
                    logger.debug("Jugador Desconectado",
                            String.format("Jugador %s no está conectado", playerData.getMinecraftUuid()));
                }
            } catch (Exception e) {
                logger.debug("Error Validación", "Error validando jugador: " + e.getMessage());
            }
        }

        boolean isValid = connectedCount == allPlayers.size();
        logger.debug("Validación Jugadores",
                String.format("Conectados: %d/%d - Válido: %s", connectedCount, allPlayers.size(), isValid));

        return isValid;
    }

    /**
     * CRÍTICO: Maneja desconexiones durante cuenta regresiva - VERSIÓN CORREGIDA
     */
    private static void handlePlayerDisconnectionDuringCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            announceToPlayers(activeMatch.getAllPlayers(),
                    "§c§l¡PARTIDA CANCELADA! §fUn jugador se desconectó durante la preparación.");

            logger.matchEvent(activeMatch.getMatchId(), "PARTIDA_CANCELADA_DESCONEXION",
                    "Partida cancelada por desconexión durante cuenta regresiva", activeMatch.getAllPlayers().size());

            // CRÍTICO: Limpiar completamente la partida y liberar jugadores
            cleanupCancelledMatch(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error manejando desconexión durante countdown", e);
            // FALLBACK: Forzar limpieza incluso con errores
            forceCleanupResources(activeMatch, logger);
        }
    }

    /**
     * CRÍTICO: Limpieza completa de partida cancelada - VERSIÓN CORREGIDA
     */
    private static void cleanupCancelledMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // 1. CRÍTICO: Resetear estado de match INMEDIATAMENTE
            if (MatchState.isMatchActive()) {
                MatchState.endMatch();
                logger.info("Estado Match", "MatchState reseteado tras cancelación");
            }

            // 2. Remover jugadores de TODAS las estructuras
            QueueManager queueManager = RankedMinecraft.getInstance().getDiscordBot().getQueueManager();
            for (PlayerData playerData : activeMatch.getAllPlayers()) {
                try {
                    // CORREGIDO: Usar método mejorado que limpia COMPLETAMENTE
                    queueManager.removePlayerFromAllQueues(playerData.getMinecraftUuid());

                    // NUEVO: Resetear estado de "en partida" en PlayerData
                    playerData.setInMatch(false);
                    playerData.setCurrentMatchId(null);

                    logger.debug("Jugador Liberado",
                        String.format("Jugador %s liberado completamente", playerData.getMinecraftUuid()));

                } catch (Exception e) {
                    logger.warning("Error limpiando jugador",
                        String.format("Error limpiando %s: %s", playerData.getMinecraftUuid(), e.getMessage()));
                }
            }

            // 3. Mover jugadores al canal de espera
            movePlayersToWaitingRoom(activeMatch, RankedMinecraft.getInstance(), logger);

            // 4. CORREGIDO: Eliminar canales con retry logic
            cleanupDiscordChannels(activeMatch, logger);

            // 5. NUEVO: Remover sesión de picks activa si existe
            activeSessions.remove(activeMatch.getMatchId());

            // 6. Limpiar recursos de la partida
            activeMatch.cleanup();

            logger.info("Limpieza Completa",
                    String.format("Partida %s cancelada y TODOS los recursos liberados", activeMatch.getMatchId()));

        } catch (Exception e) {
            logger.logError("Error en limpieza de partida cancelada", e);
            // FALLBACK crítico
            forceCleanupResources(activeMatch, logger);
        }
    }

    /**
     * NUEVO: Limpieza de canales Discord con retry logic
     */
    private static void cleanupDiscordChannels(ActiveMatch activeMatch, DiscordLogger logger) {
        // Eliminar canales con delay para evitar rate limits
        Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
            try {
                if (activeMatch.getBlueTeamChannel() != null) {
                    activeMatch.getBlueTeamChannel().delete().queue(
                        success -> logger.info("Canal Limpieza", "Canal azul eliminado tras cancelación"),
                        error -> {
                            logger.warning("Error Canal", "Error borrando canal azul: " + error.getMessage());
                            // Retry después de 5 segundos
                            Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
                                try {
                                    activeMatch.getBlueTeamChannel().delete().queue();
                                } catch (Exception retryError) {
                                    logger.warning("Retry Canal", "Retry fallido para canal azul");
                                }
                            }, 100L);
                        }
                    );
                }

                if (activeMatch.getRedTeamChannel() != null) {
                    activeMatch.getRedTeamChannel().delete().queue(
                        success -> logger.info("Canal Limpieza", "Canal rojo eliminado tras cancelación"),
                        error -> {
                            logger.warning("Error Canal", "Error borrando canal rojo: " + error.getMessage());
                            // Retry después de 5 segundos
                            Bukkit.getScheduler().runTaskLater(RankedMinecraft.getInstance(), () -> {
                                try {
                                    activeMatch.getRedTeamChannel().delete().queue();
                                } catch (Exception retryError) {
                                    logger.warning("Retry Canal", "Retry fallido para canal rojo");
                                }
                            }, 100L);
                        }
                    );
                }

            } catch (Exception e) {
                logger.logError("Error en limpieza de canales Discord", e);
            }
        }, 60L); // 3 segundos de delay
    }

    /**
     * NUEVO: Limpieza forzada de recursos cuando fallan otros métodos
     */
    private static void forceCleanupResources(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            logger.warning("Limpieza Forzada", "Ejecutando limpieza de emergencia");

            // Forzar reset de MatchState
            MatchState.endMatch();

            // Remover sesión de picks
            activeSessions.remove(activeMatch.getMatchId());

            // Intentar limpiar QueueManager
            QueueManager queueManager = RankedMinecraft.getInstance().getDiscordBot().getQueueManager();
            for (PlayerData playerData : activeMatch.getAllPlayers()) {
                try {
                    queueManager.forceRemovePlayer(playerData.getMinecraftUuid());
                } catch (Exception e) {
                    // Ignorar errores en limpieza forzada
                }
            }

            logger.info("Limpieza Forzada", "Limpieza de emergencia completada");

        } catch (Exception e) {
            logger.logError("Error incluso en limpieza forzada", e);
        }
    }

    /**
     * Inicia la partida oficial - CORREGIDO: Sin duplicar comando start
     */
    private static void startOfficialMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            announceToPlayers(activeMatch.getAllPlayers(), "§a§l¡PARTIDA INICIADA! §f¡Buena suerte!");

            // CORREGIDO: NO ejecutar comando start aquí - se ejecuta en MatchManager
            // Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 120"); // ❌ REMOVIDO

            // CORREGIDO: Solo establecer estado si no está ya establecido
            if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) {
                activeMatch.setStatus(ActiveMatch.MatchStatus.IN_PROGRESS);
                logger.matchEvent(activeMatch.getMatchId(), "PARTIDA_INICIADA",
                        "Partida iniciada oficialmente via sistema de picks", activeMatch.getAllPlayers().size());
            } else {
                logger.info("Estado Ya Establecido", "La partida ya estaba marcada como IN_PROGRESS");
            }

        } catch (Exception e) {
            logger.logError("Error iniciando partida oficial", e);
        }
    }

    /**
     * Anuncia un mensaje a todos los jugadores
     */
    private static void announceToPlayers(List<PlayerData> players, String message) {
        for (PlayerData playerData : players) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            } catch (Exception e) {
                // Ignorar errores de envío de mensajes
            }
        }
    }

    /**
     * Maneja la selección de un jugador por parte de un capitán
     */
    public static void handlePlayerPick(String matchId, String captainDiscordId, String pickedPlayerUuid) {
        PickSession session = activeSessions.get(matchId);
        if (session == null) {
            return;
        }

        session.handlePlayerPick(captainDiscordId, pickedPlayerUuid);
    }

    /**
     * Limpia una sesión de picks
     */
    public static void cleanupSession(String matchId) {
        activeSessions.remove(matchId);
    }

    /**
     * NUEVO: Obtiene la lista de jugadores disponibles para pickear en una sesión activa
     */
    public static List<PlayerData> getAvailablePlayers(String matchId) {
        PickSession session = activeSessions.get(matchId);
        if (session == null) {
            return null;
        }
        return session.getAvailablePlayers();
    }

    /**
     * NUEVO: Da el libro de picks a un capitán
     */
    public static void givePickBook(Player captain) {
        ItemStack pickBook = new ItemStack(Material.BOOK);
        ItemMeta meta = pickBook.getItemMeta();

        if (meta != null) {
            meta.setDisplayName("§6§l⚔ Libro de Picks");

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Haz §eclick derecho §7para abrir");
            lore.add("§7el menú de selección de jugadores");
            lore.add("");
            lore.add("§a§l▶ Click Derecho para usar");

            meta.setLore(lore);
            pickBook.setItemMeta(meta);
        }

        captain.getInventory().addItem(pickBook);
        captain.sendMessage("§a§l✓ §fRecibiste el §6Libro de Picks");
        captain.sendMessage("§7Haz click derecho para seleccionar jugadores");
    }

    /**
     * Clase interna para manejar una sesión de picks
     */
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

        // NUEVO: Variables para el modelo de pickeo 1-2-1-1-1-1
        private int consecutivePicksRemaining = 1; // Cuántos picks consecutivos le quedan al capitán actual

        // NUEVO: Canales temporales para picks
        private net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel tempBlueChannel;
        private net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel tempRedChannel;

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
            // Seleccionar dos capitanes aleatoriamente
            // Si la lista de eligibleCaptains fue provista con exactamente 2 elementos
            // (p. ej. seleccionados por MMR), respetar el orden y no mezclar.
            if (eligibleCaptains.size() == 2) {
                captain1 = eligibleCaptains.get(0);
                captain2 = eligibleCaptains.get(1);
            } else {
                Collections.shuffle(eligibleCaptains);
                captain1 = eligibleCaptains.get(0);
                captain2 = eligibleCaptains.get(1);
            }

            // Agregar capitanes a sus equipos
            team1.add(captain1);
            team2.add(captain2);

            // Crear lista de jugadores disponibles (sin capitanes)
            availablePlayers.addAll(allPlayers);
            availablePlayers.remove(captain1);
            availablePlayers.remove(captain2);

            // Decidir quién pickea primero: el de mayor MMR (captain1)
            currentCaptain = captain1;
            consecutivePicksRemaining = 1; // NUEVO: Comienza con 1 pick (patrón 1-2-1-1-1-1)

            // NUEVO: Crear canales temporales para la fase de picks
            createTemporaryPickChannels();

            // Anunciar inicio de picks
            announcePickStart();

            // NUEVO: Dar libro de picks a ambos capitanes
            givePickBooksToCapitans();

            // Iniciar primer pick
            startNextPick();
        }

        /**
         * NUEVO: Da el libro de picks a ambos capitanes
         */
        private void givePickBooksToCapitans() {
            try {
                Player captain1Player = Bukkit.getPlayer(UUID.fromString(captain1.getMinecraftUuid()));
                if (captain1Player != null) {
                    CaptainPickSystem.givePickBook(captain1Player);
                }

                Player captain2Player = Bukkit.getPlayer(UUID.fromString(captain2.getMinecraftUuid()));
                if (captain2Player != null) {
                    CaptainPickSystem.givePickBook(captain2Player);
                }
            } catch (Exception e) {
                logger.warning("Error dando libros de picks", "Error: " + e.getMessage());
            }
        }

        /**
         * NUEVO: Obtiene la lista de jugadores disponibles (para el GUI)
         */
        public List<PlayerData> getAvailablePlayers() {
            return new ArrayList<>(availablePlayers);
        }

        /**
         * NUEVO: Crea canales temporales para la fase de picks y distribuye jugadores aleatoriamente
         */
        private void createTemporaryPickChannels() {
            try {
                String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH-mm"));

                // Contador para saber cuándo ambos canales temporales están listos
                final java.util.concurrent.atomic.AtomicInteger channelsCreated = new java.util.concurrent.atomic.AtomicInteger(0);
                final Object lock = new Object();

                // Crear canal temporal azul
                activeMatch.getGuild().createVoiceChannel("🔵 Picks Azul " + timestamp)
                    .setParent(activeMatch.getGuild().getCategoryById("1412199394536898631")) // Categoría de canales de equipo
                    .queue(channel -> {
                        tempBlueChannel = channel;
                        logger.info("Canal Temporal Creado", "Canal temporal azul creado: " + channel.getName());

                        synchronized (lock) {
                            if (channelsCreated.incrementAndGet() == 2) {
                                // Ambos canales creados, distribuir jugadores aleatoriamente
                                distributePlayersRandomly();
                            }
                        }
                    }, error -> logger.logError("Error creando canal temporal azul", error));

                // Crear canal temporal rojo
                activeMatch.getGuild().createVoiceChannel("🔴 Picks Rojo " + timestamp)
                    .setParent(activeMatch.getGuild().getCategoryById("1412199394536898631"))
                    .queue(channel -> {
                        tempRedChannel = channel;
                        logger.info("Canal Temporal Creado", "Canal temporal rojo creado: " + channel.getName());

                        synchronized (lock) {
                            if (channelsCreated.incrementAndGet() == 2) {
                                // Ambos canales creados, distribuir jugadores aleatoriamente
                                distributePlayersRandomly();
                            }
                        }
                    }, error -> logger.logError("Error creando canal temporal rojo", error));

            } catch (Exception e) {
                logger.logError("Error creando canales temporales para picks", e);
            }
        }

        /**
         * NUEVO: Distribuye jugadores aleatoriamente en los canales temporales (5vs5)
         */
        private void distributePlayersRandomly() {
            try {
                // Crear una lista aleatoria de todos los jugadores
                List<PlayerData> shuffledPlayers = new ArrayList<>(allPlayers);
                Collections.shuffle(shuffledPlayers);

                // Dividir en dos grupos de 5
                List<PlayerData> tempTeam1 = shuffledPlayers.subList(0, 5);
                List<PlayerData> tempTeam2 = shuffledPlayers.subList(5, 10);

                logger.info("Distribución Temporal",
                    String.format("Distribuyendo jugadores: Azul=%d, Rojo=%d", tempTeam1.size(), tempTeam2.size()));

                // Mover jugadores a canales temporales
                movePlayersToTemporaryChannels(tempTeam1, tempTeam2);

                // Anunciar distribución temporal
                announceTemporaryDistribution(tempTeam1, tempTeam2);

            } catch (Exception e) {
                logger.logError("Error distribuyendo jugadores aleatoriamente", e);
            }
        }

        /**
         * NUEVO: Mueve jugadores a los canales temporales
         */
        private void movePlayersToTemporaryChannels(List<PlayerData> tempTeam1, List<PlayerData> tempTeam2) {
            // Mover equipo 1 al canal azul temporal
            for (PlayerData player : tempTeam1) {
                try {
                    net.dv8tion.jda.api.entities.Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                    if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                        activeMatch.getGuild().moveVoiceMember(member, tempBlueChannel).queue(
                            success -> logger.debug("Jugador Movido", "Movido " + member.getEffectiveName() + " a canal azul temporal"),
                            error -> logger.debug("Error Moviendo", "Error moviendo " + member.getEffectiveName() + ": " + error.getMessage())
                        );
                    }
                } catch (Exception e) {
                    logger.debug("Error moviendo jugador a canal temporal azul", e.getMessage());
                }
            }

            // Mover equipo 2 al canal rojo temporal
            for (PlayerData player : tempTeam2) {
                try {
                    net.dv8tion.jda.api.entities.Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                    if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                        activeMatch.getGuild().moveVoiceMember(member, tempRedChannel).queue(
                            success -> logger.debug("Jugador Movido", "Movido " + member.getEffectiveName() + " a canal rojo temporal"),
                            error -> logger.debug("Error Moviendo", "Error moviendo " + member.getEffectiveName() + ": " + error.getMessage())
                        );
                    }
                } catch (Exception e) {
                    logger.debug("Error moviendo jugador a canal temporal rojo", e.getMessage());
                }
            }
        }

        /**
         * NUEVO: Anuncia la distribución temporal de jugadores
         */
        private void announceTemporaryDistribution(List<PlayerData> tempTeam1, List<PlayerData> tempTeam2) {
            StringBuilder announcement = new StringBuilder();
            announcement.append("§e§l=== DISTRIBUCIÓN TEMPORAL PARA PICKS ===\n");
            announcement.append("§7Los jugadores han sido distribuidos aleatoriamente para la fase de picks\n\n");

            announcement.append("§9§lCanal Azul Temporal:\n");
            for (PlayerData player : tempTeam1) {
                announcement.append("§b• ").append(getPlayerName(player)).append("\n");
            }

            announcement.append("\n§c§lCanal Rojo Temporal:\n");
            for (PlayerData player : tempTeam2) {
                announcement.append("§c• ").append(getPlayerName(player)).append("\n");
            }

            announcement.append("\n§a§lLos capitanes procederán a hacer picks!");

            announceToPlayers(allPlayers, announcement.toString());
        }

        private void announcePickStart() {
            String announcement = String.format("§e§l=== FASE DE PICKS ===\n§aCapitán Azul: §b%s\n§aCapitán Rojo: §c%s\n§fTiempo límite por pick: §e%d segundos\n§fUsa §a/pick <jugador> §fpara seleccionar\n§f¡%s pickea primero!",
                    getPlayerName(captain1), getPlayerName(captain2), PICK_TIMEOUT_SECONDS, getPlayerName(currentCaptain));

            announceToPlayers(allPlayers, announcement);

            logger.info("Picks Iniciados",
                    String.format("Capitanes: %s vs %s. Pickea primero: %s",
                            getPlayerName(captain1), getPlayerName(captain2), getPlayerName(currentCaptain)));
        }

        private void startNextPick() {
            if (availablePlayers.isEmpty()) {
                finishPicks();
                return;
            }

            // NUEVO: Si solo queda 1 jugador, asignarlo automáticamente sin pick
            if (availablePlayers.size() == 1) {
                PlayerData lastPlayer = availablePlayers.get(0);

                announceToPlayers(allPlayers,
                    String.format("§e⚡ Último jugador disponible: §a%s §eserá asignado automáticamente a §b%s",
                        getPlayerName(lastPlayer), getPlayerName(currentCaptain)));

                logger.info("Auto-pick",
                    String.format("Último jugador %s asignado automáticamente a %s",
                        getPlayerName(lastPlayer), getPlayerName(currentCaptain)));

                // Asignar directamente sin esperar pick
                performPick(lastPlayer);
                return;
            }

            // CRÍTICO: Validar que el capitán actual sigue conectado
            if (!isCaptainConnected(currentCaptain)) {
                handleCaptainDisconnection();
                return;
            }

            // Cancelar timeout anterior si existe
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }

            // Anunciar turno actual
            String pickAnnouncement = String.format("§e§lTURNO %d - %s debe pickear!\n§fJugadores disponibles: %s\n§fUsa: §a/pick <jugador>",
                    pickNumber, getPlayerName(currentCaptain), getAvailablePlayersString());

            announceToPlayers(allPlayers, pickAnnouncement);

            // Notificar específicamente al capitán
            try {
                Player captainPlayer = Bukkit.getPlayer(UUID.fromString(currentCaptain.getMinecraftUuid()));
                if (captainPlayer != null) {
                    captainPlayer.sendMessage("§a§l¡ES TU TURNO! §fUsa /pick <jugador> para seleccionar");
                }
            } catch (Exception e) {
                // Ignorar error
            }

            // Programar timeout
            schedulePickTimeout();
        }

        private String getAvailablePlayersString() {
            StringBuilder sb = new StringBuilder();

            // Salto de línea inicial para que se vea debajo de "Jugadores disponibles:"
            sb.append("\n");

            for (PlayerData playerData : availablePlayers) {
                sb.append("§e- ").append(getPlayerName(playerData)).append("\n");
            }

            return sb.toString();
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

            // CRÍTICO: Verificar primero si el capitán sigue conectado
            if (!isCaptainConnected(currentCaptain)) {
                handleCaptainDisconnection();
                return;
            }

            // Pick automático aleatorio solo si el capitán sigue conectado
            if (!availablePlayers.isEmpty()) {
                PlayerData randomPick = availablePlayers.get(
                        ThreadLocalRandom.current().nextInt(availablePlayers.size())
                );

                announceToPlayers(allPlayers,
                        String.format("§c⏰ Tiempo agotado! %s fue asignado automáticamente a %s",
                                getPlayerName(randomPick), getPlayerName(currentCaptain)));

                performPick(randomPick);
            }
        }

        /**
         * CRÍTICO: Verifica si un capitán sigue conectado
         */
        private boolean isCaptainConnected(PlayerData captain) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(captain.getMinecraftUuid()));
                return player != null && player.isOnline();
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * CRÍTICO: Maneja la desconexión de un capitán durante picks
         */
        private void handleCaptainDisconnection() {
            logger.warning("Capitán Desconectado",
                String.format("El capitán %s se desconectó durante la fase de picks",
                    getPlayerName(currentCaptain)));

            announceToPlayers(allPlayers,
                String.format("§c§l¡CAPITÁN DESCONECTADO! §f%s se desconectó durante los picks.",
                    getPlayerName(currentCaptain)));

            // Verificar si el otro capitán sigue conectado
            PlayerData otherCaptain = (currentCaptain == captain1) ? captain2 : captain1;

            if (!isCaptainConnected(otherCaptain)) {
                // Ambos capitanes desconectados - cancelar partida
                announceToPlayers(allPlayers,
                    "§c§l¡PARTIDA CANCELADA! §fAmbos capitanes se desconectaron.");

                logger.warning("Picks Cancelados", "Ambos capitanes desconectados - cancelando partida");
                cancelPickSession();
                return;
            }

            // Buscar capitán de reemplazo
            PlayerData replacementCaptain = findReplacementCaptain();

            if (replacementCaptain != null) {
                // Reemplazar capitán desconectado
                replaceDisconnectedCaptain(replacementCaptain);
            } else {
                // No hay reemplazo disponible - usar balanceo automático
                fallbackToAutomaticFromPicks();
            }
        }

        /**
         * Busca un capitán de reemplazo entre los jugadores disponibles
         */
        private PlayerData findReplacementCaptain() {
            // Buscar en jugadores disponibles (que no han sido pickeados)
            for (PlayerData player : availablePlayers) {
                if (isEligibleCaptain(player) && isCaptainConnected(player)) {
                    return player;
                }
            }

            // Buscar en equipos ya formados (menos ideal)
            PlayerData otherCaptain = (currentCaptain == captain1) ? captain2 : captain1;
            List<PlayerData> otherTeam = (currentCaptain == captain1) ? team2 : team1;

            for (PlayerData player : otherTeam) {
                if (!player.equals(otherCaptain) && isEligibleCaptain(player) && isCaptainConnected(player)) {
                    return player;
                }
            }

            return null;
        }

        /**
         * Verifica si un jugador es elegible para ser capitán
         */
        private boolean isEligibleCaptain(PlayerData player) {
            try {
                Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                return member != null && hasSponsorRole(member);
            } catch (Exception e) {
                return false;
            }
        }

        /**
         * Reemplaza al capitán desconectado con uno nuevo
         */
        private void replaceDisconnectedCaptain(PlayerData replacementCaptain) {
            announceToPlayers(allPlayers,
                String.format("§a§l¡NUEVO CAPITÁN! §f%s será el nuevo capitán.",
                    getPlayerName(replacementCaptain)));

            // Remover jugador de reemplazo de donde esté
            availablePlayers.remove(replacementCaptain);
            team1.remove(replacementCaptain);
            team2.remove(replacementCaptain);

            // Reemplazar capitán
            if (currentCaptain == captain1) {
                // Remover capitán desconectado del equipo
                team1.remove(captain1);
                // Agregar nuevo capitán
                team1.add(replacementCaptain);
                captain1 = replacementCaptain;
                currentCaptain = captain1;
            } else {
                team2.remove(captain2);
                team2.add(replacementCaptain);
                captain2 = replacementCaptain;
                currentCaptain = captain2;
            }

            logger.info("Capitán Reemplazado",
                String.format("Capitán reemplazado con %s", getPlayerName(replacementCaptain)));

            // Continuar con el pick
            startNextPick();
        }

        /**
         * Cancela la sesión de picks por problemas críticos
         */
        private void cancelPickSession() {
            finished = true;

            // Cancelar timeout
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }

            // CRÍTICO: Limpiar canales temporales también en cancelación
            cleanupTemporaryChannels();

            // Usar la lógica de cancelación existente
            handlePlayerDisconnectionDuringCountdown(activeMatch, logger);

            // Limpiar sesión
            cleanupSession(matchId);
        }

        /**
         * Fallback a balanceo automático desde picks
         */
        private void fallbackToAutomaticFromPicks() {
            finished = true;

            // Cancelar timeout
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }

            // CRÍTICO: Limpiar canales temporales antes del fallback
            cleanupTemporaryChannels();

            announceToPlayers(allPlayers,
                "§e§l¡CAMBIANDO A BALANCEO AUTOMÁTICO! §fNo hay capitanes de reemplazo disponibles.");

            logger.info("Fallback Automático",
                "Cambiando a balanceo automático por falta de capitanes");

            // Limpiar sesión de picks
            cleanupSession(matchId);

            // Usar balanceo automático
            fallbackToAutomaticBalancing(activeMatch, logger);
        }

        public void handlePlayerPick(String captainDiscordId, String pickedPlayerUuid) {
            if (finished) return;

            // Verificar que sea el turno del capitán correcto
            if (!currentCaptain.getDiscordId().equals(captainDiscordId)) {
                try {
                    PlayerData requestingPlayer = findPlayerByDiscordId(captainDiscordId);
                    if (requestingPlayer != null) {
                        Player player = Bukkit.getPlayer(UUID.fromString(requestingPlayer.getMinecraftUuid()));
                        if (player != null) {
                          player.sendMessage("§c¡No es tu turno! Espera a que " + getPlayerName(currentCaptain) + " pickee.");
                        }
                    }
                } catch (Exception e) {
                    // Ignorar error
                }
                return;
            }

            // Encontrar jugador seleccionado
            PlayerData pickedPlayer = null;
            for (PlayerData player : availablePlayers) {
                if (player.getMinecraftUuid().equals(pickedPlayerUuid)) {
                    pickedPlayer = player;
                    break;
                }
            }

            if (pickedPlayer == null) {
                try {
                    Player captainPlayer = Bukkit.getPlayer(UUID.fromString(currentCaptain.getMinecraftUuid()));
                    if (captainPlayer != null) {
                        captainPlayer.sendMessage("§cEse jugador no está disponible para pickear.");
                    }
                } catch (Exception e) {
                    // Ignorar error
                }
                return;
            }

            // Realizar el pick
            performPick(pickedPlayer);
        }

        private PlayerData findPlayerByDiscordId(String discordId) {
            for (PlayerData player : allPlayers) {
                if (Objects.equals(player.getDiscordId(), discordId)) {
                    return player;
                }
            }
            return null;
        }

        private void performPick(PlayerData pickedPlayer) {
            // Cancelar timeout
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }

            // Agregar al equipo correspondiente
            if (currentCaptain == captain1) {
                team1.add(pickedPlayer);
            } else {
                team2.add(pickedPlayer);
            }

            // Remover de disponibles
            availablePlayers.remove(pickedPlayer);

            // Anunciar pick
            announceToPlayers(allPlayers,
                    String.format("§a✓ %s ha seleccionado a %s",
                            getPlayerName(currentCaptain), getPlayerName(pickedPlayer)));

            // NUEVO: Decrementar picks consecutivos restantes
            consecutivePicksRemaining--;

            // NUEVO: Cambiar turno solo si el capitán actual completó sus picks consecutivos
            if (consecutivePicksRemaining <= 0) {
                // Cambiar de capitán
                currentCaptain = (currentCaptain == captain1) ? captain2 : captain1;

                // NUEVO: Determinar cuántos picks consecutivos según patrón 1-2-1-1-1-1
                // Patrón: Cap1(1) -> Cap2(2) -> Cap1(1) -> Cap2(1) -> Cap1(1) -> Cap2(1)
                // Total de 8 jugadores disponibles (10 - 2 capitanes)

                int totalPicksMade = 8 - availablePlayers.size(); // Cuántos picks se han hecho hasta ahora

                if (totalPicksMade == 0) {
                    // Después del primer pick (1), el siguiente capitán pickea 2
                    consecutivePicksRemaining = 2;
                } else if (totalPicksMade == 2 || totalPicksMade == 4 || totalPicksMade == 6) {
                    // Después de 2, 4 o 6 picks (1+2, 1+2+1, 1+2+1+1), el siguiente pickea 1
                    consecutivePicksRemaining = 1;
                } else {
                    // Fallback (no debería pasar)
                    consecutivePicksRemaining = 1;
                }

                logger.debug("Pick Pattern",
                    String.format("Turno cambiado a %s - Picks consecutivos: %d (Total picks hechos: %d)",
                        getPlayerName(currentCaptain), consecutivePicksRemaining, totalPicksMade));
            } else {
                // El mismo capitán sigue pickeando
                logger.debug("Pick Pattern",
                    String.format("%s tiene %d pick(s) más consecutivo(s)",
                        getPlayerName(currentCaptain), consecutivePicksRemaining));
            }

            pickNumber++;

            // Continuar o finalizar
            if (availablePlayers.isEmpty()) {
                finishPicks();
            } else {
                // Breve pausa antes del siguiente pick
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        startNextPick();
                    }
                }.runTaskLater(RankedMinecraft.getInstance(), 60L); // 3 segundos
            }
        }

        private void finishPicks() {
            finished = true;

            // Cancelar timeout
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }

            //Agregar estas 3 lineas aqui:
            activeMatch.setPicksMatch(true);
            activeMatch.setBlueCaptain(captain1);
            activeMatch.setRedCaptain(captain2);
            // Asignar equipos al ActiveMatch usando la lógica existente
            Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
            teams.clear(); // Limpiar equipos previos
            teams.put(Team.BLUE, new ArrayList<>(team1));
            teams.put(Team.RED, new ArrayList<>(team2));

            // Anunciar equipos finales
            announceToPlayers(allPlayers, generateTeamsAnnouncement());

            logger.info("Picks Completados",
                    String.format("Equipos formados - Azul: %d, Rojo: %d", team1.size(), team2.size()));

            // CRÍTICO: Crear canales finales PRIMERO y mover jugadores
            createFinalChannelsAndMovePlayersThenCleanup();
        }

        /**
         * CRÍTICO: Crea canales finales, mueve jugadores y luego limpia canales temporales
         */
        private void createFinalChannelsAndMovePlayersThenCleanup() {
            try {
                logger.info("Proceso Final", "Iniciando creación de canales finales y movimiento de jugadores");

                // 1. Crear canales de equipo finales
                activeMatch.createTeamChannels();

                // 2. Esperar un momento para que Discord procese la creación de canales
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        try {
                            // 3. Asignar equipos en Minecraft (esto incluye mover a canales Discord)
                            activeMatch.assignPlayersInMinecraft();

                            // 4. Esperar otro momento para que los movimientos se procesen
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    // 5. AHORA sí limpiar los canales temporales
                                    cleanupTemporaryChannels();

                                    // 6. Continuar con cuenta regresiva final
                                    startFinalCountdown(activeMatch, logger);

                                    // 7. Limpiar sesión
                                    cleanupSession(matchId);
                                }
                            }.runTaskLater(RankedMinecraft.getInstance(), 40L); // 2 segundos adicionales

                        } catch (Exception e) {
                            logger.logError("Error en asignación de equipos", e);
                            // En caso de error, limpiar y cancelar
                            cleanupTemporaryChannels();
                            activeMatch.cleanup();
                        }
                    }
                }.runTaskLater(RankedMinecraft.getInstance(), 60L); // 3 segundos para crear canales

            } catch (Exception e) {
                logger.logError("Error en proceso final de picks", e);
                // Fallback: limpiar todo
                cleanupTemporaryChannels();
                activeMatch.cleanup();
            }
        }

        /**
         * CRÍTICO: Limpia los canales temporales de picks
         * FLUJO CORREGIDO: Mover jugadores a canales finales antes de borrar temporales
         */
        private void cleanupTemporaryChannels() {
            logger.info("Limpieza Canales", "Iniciando limpieza de canales temporales de picks");

            // CORREGIDO: Mover jugadores a canales FINALES DE PARTIDA, no al canal de espera
            movePlayersToFinalChannels();

            // Esperar un momento para que Discord procese los movimientos antes de borrar canales temporales
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Borrar canal temporal azul
                    if (tempBlueChannel != null) {
                        try {
                            tempBlueChannel.delete().queue(
                                success -> {
                                    logger.info("Canal Borrado", "Canal temporal azul borrado exitosamente");
                                    tempBlueChannel = null; // Limpiar referencia inmediatamente después del éxito
                                },
                                error -> {
                                    logger.warning("Error Borrando", "Error borrando canal temporal azul: " + error.toString());
                                    // Retry después de 3 segundos
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (tempBlueChannel != null) {
                                                    tempBlueChannel.delete().queue(
                                                        retrySuccess -> {
                                                            logger.info("Canal Borrado Retry", "Canal temporal azul borrado en segundo intento");
                                                            tempBlueChannel = null;
                                                        },
                                                        retryError -> {
                                                            logger.warning("Retry Fallido", "Retry fallido para canal temporal azul");
                                                            tempBlueChannel = null; // Limpiar referencia incluso si falla
                                                        }
                                                    );
                                                }
                                            } catch (Exception retryException) {
                                                logger.warning("Retry Exception", "Excepción en retry de canal temporal azul: " + retryException.getMessage());
                                                tempBlueChannel = null;
                                            }
                                        }
                                    }.runTaskLater(RankedMinecraft.getInstance(), 60L);
                                }
                            );
                        } catch (Exception e) {
                            logger.logError("Error borrando canal temporal azul", e);
                            tempBlueChannel = null;
                        }
                    }

                    // Borrar canal temporal rojo
                    if (tempRedChannel != null) {
                        try {
                            tempRedChannel.delete().queue(
                                success -> {
                                    logger.info("Canal Borrado", "Canal temporal rojo borrado exitosamente");
                                    tempRedChannel = null; // Limpiar referencia inmediatamente después del éxito
                                },
                                error -> {
                                    logger.warning("Error Borrando", "Error borrando canal temporal rojo: " + error.toString());
                                    // Retry después de 3 segundos
                                    new BukkitRunnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                if (tempRedChannel != null) {
                                                    tempRedChannel.delete().queue(
                                                        retrySuccess -> {
                                                            logger.info("Canal Borrado Retry", "Canal temporal rojo borrado en segundo intento");
                                                            tempRedChannel = null;
                                                        },
                                                        retryError -> {
                                                            logger.warning("Retry Fallido", "Retry fallido para canal temporal rojo");
                                                            tempRedChannel = null; // Limpiar referencia incluso si falla
                                                        }
                                                    );
                                                }
                                            } catch (Exception retryException) {
                                                logger.warning("Retry Exception", "Excepción en retry de canal temporal rojo: " + retryException.getMessage());
                                                tempRedChannel = null;
                                            }
                                        }
                                    }.runTaskLater(RankedMinecraft.getInstance(), 60L);
                                }
                            );
                        } catch (Exception e) {
                            logger.logError("Error borrando canal temporal rojo", e);
                            tempRedChannel = null;
                        }
                    }
                }
            }.runTaskLater(RankedMinecraft.getInstance(), 60L); // AUMENTADO: 3 segundos de delay para asegurar que los movimientos se procesen

            logger.info("Limpieza Completada", "Proceso de limpieza de canales temporales iniciado");
        }

        /**
         * NUEVO: Mueve jugadores de canales temporales de picks a canales finales de partida
         */
        private void movePlayersToFinalChannels() {
            try {
                // Verificar que los canales finales existan
                VoiceChannel blueTeamChannel = activeMatch.getBlueTeamChannel();
                VoiceChannel redTeamChannel = activeMatch.getRedTeamChannel();

                if (blueTeamChannel == null || redTeamChannel == null) {
                    logger.warning("Canales Finales", "Los canales finales de partida no están disponibles");
                    // Fallback: mover al canal de espera si no hay canales finales
                    movePlayersToWaitingChannelFallback();
                    return;
                }

                logger.info("Moviendo Jugadores", "Moviendo jugadores de canales temporales a canales finales de partida");

                // Mover equipo azul a canal final azul
                for (PlayerData player : team1) {
                    try {
                        Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                        if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                            // Solo mover si está en canal temporal azul
                            String currentChannelId = member.getVoiceState().getChannel().getId();
                            boolean isInTempBlueChannel = (tempBlueChannel != null && tempBlueChannel.getId().equals(currentChannelId));

                            if (isInTempBlueChannel) {
                                activeMatch.getGuild().moveVoiceMember(member, blueTeamChannel).queue(
                                    success -> logger.debug("Jugador Movido Final", "Movido " + member.getEffectiveName() + " a canal azul final"),
                                    error -> logger.debug("Error Movimiento Final", "Error moviendo " + member.getEffectiveName() + " a canal azul final: " + error.getMessage())
                                );
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Error moviendo jugador azul individual", e.getMessage());
                    }
                }

                // Mover equipo rojo a canal final rojo
                for (PlayerData player : team2) {
                    try {
                        Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                        if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                            // Solo mover si está en canal temporal rojo
                            String currentChannelId = member.getVoiceState().getChannel().getId();
                            boolean isInTempRedChannel = (tempRedChannel != null && tempRedChannel.getId().equals(currentChannelId));

                            if (isInTempRedChannel) {
                                activeMatch.getGuild().moveVoiceMember(member, redTeamChannel).queue(
                                    success -> logger.debug("Jugador Movido Final", "Movido " + member.getEffectiveName() + " a canal rojo final"),
                                    error -> logger.debug("Error Movimiento Final", "Error moviendo " + member.getEffectiveName() + " a canal rojo final: " + error.getMessage())
                                );
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Error moviendo jugador rojo individual", e.getMessage());
                    }
                }

                logger.info("Movimiento Completado", "Jugadores movidos a canales finales de partida");

            } catch (Exception e) {
                logger.logError("Error moviendo jugadores a canales finales", e);
                // Fallback: mover al canal de espera
                movePlayersToWaitingChannelFallback();
            }
        }

        /**
         * FALLBACK: Mueve jugadores al canal de espera si fallan los canales finales
         */
        private void movePlayersToWaitingChannelFallback() {
            try {
                VoiceChannel waitingChannel = activeMatch.getGuild().getVoiceChannelById(VoiceChannelConfig.WAITING_ROOM_CHANNEL_ID);
                if (waitingChannel == null) {
                    logger.warning("Canal Espera", "No se pudo encontrar el canal de espera para fallback");
                    return;
                }

                logger.warning("Fallback Activado", "Moviendo jugadores a canal de espera como fallback");

                for (PlayerData player : allPlayers) {
                    try {
                        Member member = activeMatch.getGuild().getMemberById(player.getDiscordId());
                        if (member != null && member.getVoiceState() != null && member.getVoiceState().getChannel() != null) {
                            activeMatch.getGuild().moveVoiceMember(member, waitingChannel).queue(
                                success -> logger.debug("Jugador Movido Fallback", "Movido " + member.getEffectiveName() + " a canal de espera"),
                                error -> logger.debug("Error Movimiento Fallback", "Error moviendo " + member.getEffectiveName() + " a espera: " + error.getMessage())
                            );
                        }
                    } catch (Exception e) {
                        logger.debug("Error moviendo jugador individual a espera", e.getMessage());
                    }
                }

                logger.info("Fallback Completado", "Jugadores movidos a canal de espera");

            } catch (Exception e) {
                logger.logError("Error en fallback de movimiento a canal de espera", e);
            }
        }

        /**
         * Obtiene el nombre de Minecraft de un jugador
         */
        private String getPlayerName(PlayerData playerData) {
            try {
                Player player = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (player != null) {
                    return player.getName();
                }

                // Si no está online, intentar obtener el nombre desde OfflinePlayer
                org.bukkit.OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (offlinePlayer.getName() != null) {
                    return offlinePlayer.getName();
                }

                // Fallback: usar primeros 8 caracteres del UUID
                return "Player_" + playerData.getMinecraftUuid().substring(0, 8);
            } catch (Exception e) {
                return "Unknown";
            }
        }

        /**
         * Genera el anuncio de equipos finales
         */
        private String generateTeamsAnnouncement() {
            StringBuilder announcement = new StringBuilder();
            announcement.append("§a§l=== EQUIPOS FINALES ===\n\n");

            // Equipo Azul
            announcement.append("§9§l🔵 EQUIPO AZUL (Capitán: ").append(getPlayerName(captain1)).append("):\n");
            for (PlayerData player : team1) {
                String prefix = player.equals(captain1) ? "§b★ " : "§b• ";
                announcement.append(prefix).append(getPlayerName(player)).append("\n");
            }

            announcement.append("\n");

            // Equipo Rojo
            announcement.append("§c§l🔴 EQUIPO ROJO (Capitán: ").append(getPlayerName(captain2)).append("):\n");
            for (PlayerData player : team2) {
                String prefix = player.equals(captain2) ? "§c★ " : "§c• ";
                announcement.append(prefix).append(getPlayerName(player)).append("\n");
            }

            announcement.append("\n§e§l¡Buena suerte a ambos equipos!");

            return announcement.toString();
        }
    }
}
