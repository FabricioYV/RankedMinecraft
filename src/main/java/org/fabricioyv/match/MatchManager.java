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

    private static final RankedMinecraft plugin = RankedMinecraft.getPlugin(RankedMinecraft.class);

    /**
     * Inicia una partida con los jugadores especificados
     * @param players Lista de jugadores que participarán en la partida
     */
    public static void startMatch(List<PlayerData> players) {
        try {
            // Obtener instancias necesarias
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

            // CORREGIDO: NO crear canales aquí - se crearán después de la selección de mapa
            // activeMatch.createTeamChannels(); // ❌ REMOVIDO - causaba duplicados

            // Fase 3: Iniciar votación de mapas directamente
            startMapVoting(activeMatch, logger);

        } catch (Exception e) {
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
                plugin,
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
        activeMatch.setSelectedMap(selectedMap);

        logger.matchEvent(activeMatch.getMatchId(), "MAPA_SELECCIONADO",
                "Mapa: " + selectedMap, activeMatch.getAllPlayers().size());

        announceToPlayers(activeMatch.getAllPlayers(),
                "§a§lMapa seleccionado: §e" + selectedMap);

        // Ciclar el mapa en PGM
        cycleMap(selectedMap, activeMatch, logger);
    }

    /**
     * Ejecuta el ciclo de mapa en PGM
     */
    private static void cycleMap(String mapName, ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // Ejecutar comando de ciclo de mapa
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sn " + mapName);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cycle 1");
            });

            logger.info("Ciclo de Mapa", "Ciclando a mapa: " + mapName);

            // NUEVA LÓGICA: Iniciar fase de picks después del ciclo
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Dar tiempo para que se cargue el mapa
                startPickPhaseOrBalance(activeMatch, logger);
            }, 120L); // 3 segundos de espera

        } catch (Exception e) {
            logger.logError("Error ciclando mapa: " + mapName, e);
            cancelMatch(activeMatch, "Error ciclando mapa");
        }
    }
    /**
     * NUEVO MÉTODO: Decide entre sistema de picks o balanceo automático
     */
    private static void startPickPhaseOrBalance(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // Intentar iniciar sistema de picks
            CaptainPickSystem.startPickPhase(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error en sistema de picks, usando balanceo automático", e);

            // Fallback al balanceo automático
            activeMatch.balanceTeams();
            continueWithNormalFlow(activeMatch, logger);
        }
    }

    /**
     * Continúa con el flujo normal después del balanceo/picks
     * CORREGIDO: NO crear canales duplicados si ya existen
     */
    private static void continueWithNormalFlow(ActiveMatch activeMatch, DiscordLogger logger) {
        try {
            // CORREGIDO: Solo crear canales si NO existen ya (evitar duplicados en matchmaking normal)
            if (activeMatch.getBlueTeamChannel() == null && activeMatch.getRedTeamChannel() == null) {
                // Crear canales de equipo solo si no existen
                logger.info("Creando Canales", "Creando canales de equipo para matchmaking normal");
                activeMatch.createTeamChannels();

                // Mover jugadores a canales después de crearlos
                activeMatch.movePlayersToTeamChannels();
            } else {
                // Los canales ya existen, solo mover jugadores
                logger.info("Canales Existentes", "Canales ya existen - solo moviendo jugadores");
                activeMatch.movePlayersToTeamChannels();
            }

            // Asignar equipos en Minecraft
            activeMatch.assignPlayersInMinecraft();

            // Iniciar cuenta regresiva final
            startFinalCountdown(activeMatch, logger);

        } catch (Exception e) {
            logger.logError("Error en flujo normal", e);
            cancelMatch(activeMatch, "Error técnico");
        }
    }

    /**
     * Inicia la cuenta regresiva final antes de comenzar la partida
     */
    private static void startFinalCountdown(ActiveMatch activeMatch, DiscordLogger logger) {
        logger.info("Cuenta Regresiva Final",
                "Iniciando cuenta regresiva final de 2 minutos para partida " + activeMatch.getMatchId());

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

                    // CORREGIDO: Usar start 120 en lugar de start 60
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "start 120");

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
     * CORREGIDO: Limpieza completa incluyendo canales de Discord
     */
    private static void cancelMatch(ActiveMatch activeMatch, String reason) {
        DiscordLogger logger = plugin.getDiscordBot().getLogger();

        activeMatch.setStatus(ActiveMatch.MatchStatus.CANCELLED);

        logger.matchEvent(activeMatch.getMatchId(), "Partida Cancelada", reason,
                activeMatch.getAllPlayers().size());

        // Anunciar cancelación a jugadores
        announceToPlayers(activeMatch.getAllPlayers(),
                "§c❌ Partida cancelada: " + reason);

        // Actualizar estado en base de datos INMEDIATAMENTE
        updatePlayersMatchStatus(activeMatch.getAllPlayers(), null, false);

        // Cancelar votación si está activa
        if (activeMatch.getMapVoting() != null) {
            activeMatch.getMapVoting().cancelVoting();
        }

        // CRÍTICO: Borrar canales de Discord explícitamente antes de cleanup general
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                // Borrar canales si existen
                if (activeMatch.getBlueTeamChannel() != null) {
                    activeMatch.getBlueTeamChannel().delete().queue(
                        success -> logger.info("Canal Cancelado", "Canal azul eliminado tras cancelación en matchmaking"),
                        error -> logger.warning("Error Borrando Canal", "Error borrando canal azul en cancelación: " + error.getMessage())
                    );
                }

                if (activeMatch.getRedTeamChannel() != null) {
                    activeMatch.getRedTeamChannel().delete().queue(
                        success -> logger.info("Canal Cancelado", "Canal rojo eliminado tras cancelación en matchmaking"),
                        error -> logger.warning("Error Borrando Canal", "Error borrando canal rojo en cancelación: " + error.getMessage())
                    );
                }

                logger.info("Limpieza Cancelación",
                        String.format("Partida %s cancelada - canales borrados explícitamente", activeMatch.getMatchId()));

            } catch (Exception e) {
                logger.logError("Error en borrado explícito de canales durante cancelación", e);
            }
        }, 20L); // 1 segundo para que Discord procese

        // Limpiar recursos (esto también borrará canales como respaldo)
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
    /**
     * Actualiza el estado de partida de los jugadores
     * OPTIMIZADO: BD asíncrona para no bloquear el servidor
     */
    private static void updatePlayersMatchStatus(List<PlayerData> players, String matchId, boolean inMatch) {
        // 1. INMEDIATO: Actualizar memoria (instantáneo, no bloquea)
        for (PlayerData player : players) {
            player.setInMatch(inMatch);
            player.setCurrentMatchId(matchId);
        }

        // 2. ASÍNCRONO: Actualizar BD en segundo plano (no bloquea servidor)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (PlayerData player : players) {
                try {
                    DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), inMatch, matchId);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error actualizando estado BD para " +
                        player.getMinecraftUuid().substring(0, 8) + ": " + e.getMessage());
                }
            }
        });
    }





}
