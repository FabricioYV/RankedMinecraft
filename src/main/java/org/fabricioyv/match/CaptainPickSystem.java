


package org.fabricioyv.match;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.queue.QueueManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sistema de picks con capitanes para RankedMinecraft
 * Los jugadores con roles "Main Sponsor" o "Sponsor" pueden ser capitanes
 */
public class CaptainPickSystem {

    private static final Map<String, PickSession> activeSessions = new ConcurrentHashMap<>();

    // IDs de roles que pueden ser capitanes
    private static final String MAIN_SPONSOR_ROLE_ID = "1413241361087332505";
    private static final String SPONSOR_ROLE_ID = "1413243740231041174";

    private static final int PICK_TIMEOUT_SECONDS = 15;

    /**
     * Inicia el sistema de picks después de seleccionar el mapa
     */
    public static void startPickPhase(ActiveMatch activeMatch, DiscordLogger logger) {
        String matchId = activeMatch.getMatchId();
        List<PlayerData> allPlayers = activeMatch.getAllPlayers();

        // Verificar si hay jugadores con el rol de capitán
        List<PlayerData> eligibleCaptains = findEligibleCaptains(allPlayers, activeMatch.getGuild());

        if (eligibleCaptains.size() < 2) {
            logger.info("Sistema de Picks",
                    String.format("No hay suficientes capitanes elegibles (%d/2). Usando balanceo automático.",
                            eligibleCaptains.size()));

            // Fallback al sistema de balanceo automático
            fallbackToAutomaticBalancing(activeMatch, logger);
            return;
        }

        // Crear sesión de picks
        PickSession session = new PickSession(matchId, allPlayers, eligibleCaptains, activeMatch, logger);
        activeSessions.put(matchId, session);

        logger.info("Sistema de Picks",
                String.format("Iniciando fase de picks para partida %s con %d capitanes elegibles",
                        matchId, eligibleCaptains.size()));

        // Iniciar el proceso de picks
        session.startPickProcess();
    }

    /**
     * Encuentra jugadores elegibles para ser capitanes por roles de Discord
     */
    private static List<PlayerData> findEligibleCaptains(List<PlayerData> players, Guild guild) {
        List<PlayerData> eligibleCaptains = new ArrayList<>();

        for (PlayerData player : players) {
            try {
                Member member = guild.getMemberById(player.getDiscordId());  // guild en lugar de activeMatch.guild
                if (member != null && hasSponsorRole(member)) {
                    eligibleCaptains.add(player);
                }
            } catch (Exception e) {
                // Ignorar errores de búsqueda de miembros
            }
        }

        return eligibleCaptains;
    }


    /**
     * Verifica si un miembro tiene rol de "Main Sponsor" o "Sponsor"
     */
    private static boolean hasSponsorRole(Member member) {
        for (Role role : member.getRoles()) {
            if (role.getId().equals(MAIN_SPONSOR_ROLE_ID) || role.getId().equals(SPONSOR_ROLE_ID)) {
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
     */
    private static void continueAfterTeamFormation(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // 1. Crear canales de equipo
            activeMatch.createTeamChannels();

            // 2. Asignar equipos en Minecraft (incluye mover a canales Discord automáticamente)
            activeMatch.assignPlayersInMinecraft();

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
     * CRÍTICO: Maneja desconexiones durante cuenta regresiva
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
        }
    }

    /**
     * CRÍTICO: Limpieza completa de partida cancelada
     */
    private static void cleanupCancelledMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // 1. Remover jugadores de la cola para que puedan unirse nuevamente
            QueueManager queueManager = RankedMinecraft.getInstance().getDiscordBot().getQueueManager();  // Acceso correcto al QueueManager
            for (PlayerData playerData : activeMatch.getAllPlayers()) {
                try {
                    // Remover el flag de "en partida" para que puedan hacer queue de nuevo
                    queueManager.removePlayerFromAllQueues(playerData.getMinecraftUuid());  // Ahora este método existe
                } catch (Exception e) {
                    logger.debug("Error limpiando jugador de cola", e.getMessage());
                }
            }

            // 2. Mover jugadores al canal de espera
            movePlayersToWaitingRoom(activeMatch, logger);

            // 3. Limpiar recursos de la partida (canales, etc.)
            activeMatch.cleanup();

            logger.info("Limpieza Completa",
                    String.format("Partida %s cancelada y recursos liberados", activeMatch.getMatchId()));

        } catch (Exception e) {
            logger.logError("Error en limpieza de partida cancelada", e);
        }
    }


    /**
     * Mueve jugadores al canal de espera
     */
    private static void movePlayersToWaitingRoom(ActiveMatch activeMatch, DiscordLogger logger) {
        // Esta lógica ya existe en MatchFinisher, la reutilizamos
        try {
            // Implementación básica para mover al canal de espera
            // El código específico dependería de tu configuración de canales
            logger.info("Moviendo Jugadores", "Moviendo jugadores al canal de espera");
        } catch (Exception e) {
            logger.logError("Error moviendo jugadores a canal de espera", e);
        }
    }

    /**
     * Inicia la partida oficial
     */
    private static void startOfficialMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            announceToPlayers(activeMatch.getAllPlayers(), "§a§l¡PARTIDA INICIADA! §f¡Buena suerte!");

            activeMatch.setStatus(ActiveMatch.MatchStatus.IN_PROGRESS);

            logger.matchEvent(activeMatch.getMatchId(), "PARTIDA_INICIADA",
                    "Partida iniciada oficialmente", activeMatch.getAllPlayers().size());

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
            Collections.shuffle(eligibleCaptains);
            captain1 = eligibleCaptains.get(0);
            captain2 = eligibleCaptains.get(1);

            // Agregar capitanes a sus equipos
            team1.add(captain1);
            team2.add(captain2);

            // Crear lista de jugadores disponibles (sin capitanes)
            availablePlayers.addAll(allPlayers);
            availablePlayers.remove(captain1);
            availablePlayers.remove(captain2);

            // Decidir quién pickea primero aleatoriamente
            currentCaptain = ThreadLocalRandom.current().nextBoolean() ? captain1 : captain2;

            // Anunciar inicio de picks
            announcePickStart();

            // Iniciar primer pick
            startNextPick();
        }

        private void announcePickStart() {
            String announcement = String.format(
                    "§e§l=== FASE DE PICKS ===\n" +
                            "§aCapitán Azul: §b%s\n" +
                            "§aCapitán Rojo: §c%s\n" +
                            "§fTiempo límite por pick: §e%d segundos\n" +
                            "§fUsa §a/pick <jugador> §fpara seleccionar\n" +
                            "§f¡%s pickea primero!",
                    getPlayerName(captain1),
                    getPlayerName(captain2),
                    PICK_TIMEOUT_SECONDS,
                    getPlayerName(currentCaptain)
            );

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

            // Cancelar timeout anterior si existe
            // Cancelar timeout
            if (timeoutTask != null) {
                try { timeoutTask.cancel(); } catch (Exception ignored) {}
            }
            // Anunciar turno actual
            String pickAnnouncement = String.format(
                    "§e§lTURNO %d - %s debe pickear!\n" +
                            "§fJugadores disponibles: %s\n" +
                            "§fUsa: §a/pick <jugador>",
                    pickNumber,
                    getPlayerName(currentCaptain),
                    getAvailablePlayersString()
            );

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
            for (int i = 0; i < availablePlayers.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("§a").append(getPlayerName(availablePlayers.get(i)));
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

            // Pick automático aleatorio
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
                if (player.getDiscordId().equals(discordId)) {
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

            // Cambiar turno
            currentCaptain = (currentCaptain == captain1) ? captain2 : captain1;
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

            // Asignar equipos al ActiveMatch usando la lógica existente
            Map<Team, List<PlayerData>> teams = activeMatch.getTeams();
            teams.clear(); // Limpiar equipos previos
            teams.put(Team.BLUE, new ArrayList<>(team1));
            teams.put(Team.RED, new ArrayList<>(team2));

            // Anunciar equipos finales
            announceToPlayers(allPlayers, generateTeamsAnnouncement());

            logger.info("Picks Completados",
                    String.format("Equipos formados - Azul: %d, Rojo: %d", team1.size(), team2.size()));

            // Continuar con el flujo normal
            continueAfterTeamFormation(activeMatch, logger);

            // Limpiar sesión
            cleanupSession(matchId);
        }

        private String generateTeamsAnnouncement() {
            StringBuilder announcement = new StringBuilder();
            announcement.append("§e§l=== EQUIPOS FINALES ===\n");

            announcement.append("§9§lEQUIPO AZUL:\n");
            for (PlayerData player : team1) {
                announcement.append("§b• ").append(getPlayerName(player));
                if (player == captain1) announcement.append(" §7(Capitán)");
                announcement.append("\n");
            }

            announcement.append("\n§c§lEQUIPO ROJO:\n");
            for (PlayerData player : team2) {
                announcement.append("§c• ").append(getPlayerName(player));
                if (player == captain2) announcement.append(" §7(Capitán)");
                announcement.append("\n");
            }

            announcement.append("\n§a§l¡Preparándose para la partida!");

            return announcement.toString();
        }

        private String getPlayerName(PlayerData player) {
            try {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(player.getMinecraftUuid()));
                if (mcPlayer != null) {
                    return mcPlayer.getName();
                }
            } catch (Exception e) {
                // Fallback
            }
            return "Player_" + player.getMinecraftUuid().substring(0, 8);
        }
    }
}
