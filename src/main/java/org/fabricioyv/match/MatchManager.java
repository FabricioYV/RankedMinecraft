package org.fabricioyv.match;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.database.MatchLogsIntegration;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

public class MatchManager {

    /**
     * Inicia una partida con los jugadores especificados
     * @param players Lista de jugadores que participarán en la partida
     */
    public static void startMatch(List<PlayerData> players) {
        try {
            // Obtener instancias necesarias
            RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
            JDA jda = plugin.getDiscordBot().getJda();
            Guild guild = jda.getGuilds().get(0); // Asumiendo un solo servidor
            DiscordLogger logger = plugin.getDiscordBot().getLogger();

            // Generar ID único para la partida
            String matchId = generateMatchId();

            // Detectar tipo de partida por número de jugadores
            String matchType = players.size() == 10 ? "5v5" : "8v8";

            logger.matchEvent(matchId, "Partida Iniciando",
                    "Iniciando proceso de partida " + matchType, players.size());

            // Crear partida activa
            ActiveMatch activeMatch = new ActiveMatch(matchId, players, jda, guild, plugin, logger);

            // Fase 1: Validar que todos los jugadores sigan conectados
            if (!validatePlayersConnection(players, logger)) {
                logger.error("Validación Fallida",
                        "Algunos jugadores se desconectaron, cancelando partida");
                cancelMatch(activeMatch, "Jugadores desconectados durante la inicialización");
                return;
            }

            // Fase 2: Balancear equipos por ELO
            activeMatch.balanceTeams();

            // Fase 3: Crear canales de equipos en Discord
            activeMatch.createTeamChannels();

            // Fase 5: Iniciar votación de mapas
            startMapVoting(activeMatch, logger);

        } catch (Exception e) {
            RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
            if (plugin.getDiscordBot() != null && plugin.getDiscordBot().getLogger() != null) {
                plugin.getDiscordBot().getLogger().systemError("MatchManager",
                        "Error crítico iniciando partida", e.getMessage());
            }
            e.printStackTrace();
        }

    }
    /**
     * Genera un ID único para la partida
     */
    private static String generateMatchId() {
        return "MATCH_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
    /**
     * Valida que todos los jugadores sigan conectados a Minecraft
     */
    private static boolean validatePlayersConnection(List<PlayerData> players, DiscordLogger logger) {
        int connectedCount = 0;

        for (PlayerData playerData : players) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                connectedCount++;
            } else {
                logger.warning("Jugador Desconectado",
                        "Jugador UUID:" + playerData.getMinecraftUuid().substring(0, 8) + " no está conectado");
            }
        }

        logger.info("Validación de Conexión",
                connectedCount + "/" + players.size() + " jugadores conectados");

        return connectedCount == players.size();
    }
    /**
     * Inicia el proceso de votación de mapas
     */
    private static void startMapVoting(ActiveMatch activeMatch, DiscordLogger logger) {
        activeMatch.setStatus(ActiveMatch.MatchStatus.MAP_VOTING);

        // Crear sistema de votación
        MapVoting mapVoting = new MapVoting(
                RankedMinecraft.getPlugin(RankedMinecraft.class),
                logger,
                activeMatch.getAllPlayers(),
                activeMatch.getMatchType()
        );

        activeMatch.setMapVoting(mapVoting);

        // Iniciar votación con callback
        mapVoting.startVoting(selectedMap -> {
            activeMatch.setSelectedMap(selectedMap);
            onMapSelected(activeMatch, selectedMap, logger);
        });
    }
    /**
     * Callback cuando se selecciona un mapa
     */
    private static void onMapSelected(ActiveMatch activeMatch, String selectedMap, DiscordLogger logger) {
        logger.success("Mapa Seleccionado",
                "Mapa '" + selectedMap + "' seleccionado para partida " + activeMatch.getMatchId());

        activeMatch.setStatus(ActiveMatch.MatchStatus.STARTING);

        // Validar jugadores nuevamente antes de continuar
        if (!validatePlayersConnection(activeMatch.getAllPlayers(), logger)) {
            cancelMatch(activeMatch, "Jugadores desconectados durante la votación");
            return;
        }

        // Mover jugadores a canales de equipos
        activeMatch.movePlayersToTeamChannels();

        // Asignar equipos en Minecraft
    //    activeMatch.assignPlayersInMinecraft();

        // Ciclear el mapa
        cycleMap(selectedMap, activeMatch, logger);
    }
    /**
     * Ejecuta el ciclo de mapa en PGM
     */
    private static void cycleMap(String mapName, ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Cargando Mapa",
                "Ejecutando ciclo de mapa: " + mapName + " para partida " + activeMatch.getMatchId());

        // Anunciar a los jugadores
        announceToPlayers(activeMatch.getAllPlayers(),
                "§6🗺️ Cargando mapa: §e" + mapName);

        // Ejecutar comando de ciclo
        String nextmap = "sn " + mapName;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), nextmap);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cycle 1");

        // Esperar 6 segundos y luego asignar jugadores a equipos
        RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
        new BukkitRunnable() {
            @Override
            public void run() {
                // NUEVO: Asignar jugadores a equipos después de 6 segundos
                logger.info("Asignando Equipos Post-Cycle",
                        "Asignando jugadores a equipos después del ciclado de mapa");

                activeMatch.assignPlayersInMinecraft();

                // Anunciar que los equipos han sido asignados
                announceToPlayers(activeMatch.getAllPlayers(),
                        "§a⚔️ Equipos asignados! Preparándose para iniciar...");

                // Ahora iniciar la cuenta regresiva final de 2 minutos
                startFinalCountdown(activeMatch, logger);

            }
        }.runTaskLater(plugin, 120L); // 6 segundos (120 ticks)
    }
    /**
     * Inicia la cuenta regresiva final antes de comenzar la partida
     */
    private static void startFinalCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Cuenta Regresiva Final",
                "Iniciando cuenta regresiva final de 2 minutos para partida " + activeMatch.getMatchId());

        RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
        new BukkitRunnable() {

            // MODIFICADO: Countdown de 2 minutos (120 segundos)
            int countdown = 30;

            @Override
            public void run() {
                if (countdown <= 0) {
                    // Validación final antes de iniciar
                    if (!validatePlayersConnection(activeMatch.getAllPlayers(), logger)) {
                        cancelMatch(activeMatch, "Jugadores desconectados antes del inicio");
                        this.cancel();
                        return;
                    }
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 60");

                    // Ejecutar comando start 120 antes de iniciar oficialmente

                    // Oficialmente iniciar la partida
                    startOfficialMatch(activeMatch, logger);
                    this.cancel();
                    return;
                }

                // Anunciar cuenta regresiva cada 30 segundos, cada 10 segundos en los últimos 30, y cada segundo en los últimos 10
                boolean shouldAnnounce = false;
                String message = "";

                if (countdown > 30) {
                    // Cada 30 segundos cuando quedan más de 30 segundos
                    if (countdown % 30 == 0) {
                        shouldAnnounce = true;
                        int minutes = countdown / 60;
                        int seconds = countdown % 60;
                        if (minutes > 0 && seconds == 0) {
                            message = "§6⚔️ §ePartida iniciando en §c" + minutes + " §eminuto" + (minutes == 1 ? "" : "s") + "...";
                        } else {
                            message = "§6⚔️ §ePartida iniciando en §c" + minutes + ":" + String.format("%02d", seconds) + "...";
                        }
                    }
                } else if (countdown > 10) {
                    // Cada 10 segundos cuando quedan entre 30 y 10 segundos
                    if (countdown % 10 == 0) {
                        shouldAnnounce = true;
                        message = "§6⚔️ §ePartida iniciando en §c" + countdown + " §esegundos...";
                    }
                } else {
                    // Cada segundo en los últimos 10 segundos
                    shouldAnnounce = true;
                    message = "§6⚔️ §ePartida iniciando en §c" + countdown + " §esegundo" +
                            (countdown == 1 ? "" : "s") + "...";
                }

                if (shouldAnnounce) {
                    announceToPlayers(activeMatch.getAllPlayers(), message);
                }

                countdown--;
            }
        }.runTaskTimer(plugin, 0L, 20L); // Cada segundo
    }
    /**
     * Inicia oficialmente la partida
     */
    private static void startOfficialMatch(ActiveMatch activeMatch, DiscordLogger logger) {
        activeMatch.setStatus(ActiveMatch.MatchStatus.IN_PROGRESS);

        // INICIALIZAR SISTEMA DE LOGS DE PARTIDAS
        MatchLogsIntegration.startMatchTracking(activeMatch.getMatchId(), activeMatch.getTeams(),
            activeMatch.getMatchType(), activeMatch.getSelectedMap());
        MatchLogsIntegration.logMatchStart(activeMatch.getMatchId(), activeMatch.getSelectedMap(),
            activeMatch.getAllPlayers().size());

        // Anunciar inicio
        announceToPlayers(activeMatch.getAllPlayers(),
                "§a§l🎉 ¡PARTIDA INICIADA!");
        announceToPlayers(activeMatch.getAllPlayers(),
                "§eMapa: §f" + activeMatch.getSelectedMap());
        announceToPlayers(activeMatch.getAllPlayers(),
                "§e¡Buena suerte y que gane el mejor equipo!");

        // Log del inicio oficial
        logger.matchEvent(activeMatch.getMatchId(), "Partida Iniciada Oficialmente",
                "Partida " + activeMatch.getMatchType() + " en mapa " + activeMatch.getSelectedMap() +
                        " iniciada con " + activeMatch.getAllPlayers().size() + " jugadores",
                activeMatch.getAllPlayers().size());

        // Actualizar estado de jugadores en base de datos
        updatePlayersMatchStatus(activeMatch.getAllPlayers(), activeMatch.getMatchId(), true);


        // TODO: Configurar listeners para eventos de la partida (kills, deaths, objectives, etc.)
        // TODO: Inicializar sistema de estadísticas
        // TODO: Configurar sistema de detección de finalización de partida
    }
    /**
     * Cancela una partida y limpia todo el estado
     */
    private static void cancelMatch(ActiveMatch activeMatch, String reason) {
        RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);
        DiscordLogger logger = plugin.getDiscordBot().getLogger();

        activeMatch.setStatus(ActiveMatch.MatchStatus.CANCELLED);

        logger.matchEvent(activeMatch.getMatchId(), "Partida Cancelada", reason,
                activeMatch.getAllPlayers().size());

        // Anunciar cancelación a jugadores
        announceToPlayers(activeMatch.getAllPlayers(),
                "§c❌ Partida cancelada: " + reason);

        // Actualizar estado en base de datos
        updatePlayersMatchStatus(activeMatch.getAllPlayers(), null, false);

        // Cancelar votación si está activa
        if (activeMatch.getMapVoting() != null) {
            activeMatch.getMapVoting().cancelVoting();
        }

        // Limpiar recursos
        activeMatch.cleanup();
        ReadySystem.clearMatchVotes(activeMatch.getMatchId());
    }
    /**
     * Anuncia un mensaje a todos los jugadores de la partida
     */
    private static void announceToPlayers(List<PlayerData> players, String message) {
        for (PlayerData playerData : players) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                mcPlayer.sendMessage(message);
            }
        }
    }
    private static void updatePlayersMatchStatus(List<PlayerData> players, String matchId, boolean inMatch) {
        for (PlayerData player : players) {
            player.setInMatch(inMatch);
            player.setCurrentMatchId(matchId);
            DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), inMatch, matchId);
        }
    }





}
