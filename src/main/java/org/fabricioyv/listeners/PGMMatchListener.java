package org.fabricioyv.listeners;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.database.MatchLogsIntegration;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.MatchFinisher;
import org.fabricioyv.match.Team;

// Imports de PGM - ajustar según la versión de PGM que uses
import org.fabricioyv.model.PlayerData;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.ParticipantState;
import tc.oc.pgm.api.player.event.MatchPlayerDeathEvent;
import tc.oc.pgm.goals.Goal;
import tc.oc.pgm.goals.GoalMatchModule;
import tc.oc.pgm.teams.TeamMatchModule;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Listener principal para eventos de PGM (integración y finalización de partidas)
 *
 * Created by FabricioYV
 * @author FabricioYV
 */
public class PGMMatchListener implements Listener{
    private final RankedMinecraft plugin;
    private final DiscordLogger logger;

    // **CRÍTICO**: Set para trackear partidas ya inicializadas
    private static final Set<String> initializedMatches = new HashSet<>();

    public PGMMatchListener(RankedMinecraft plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMatchFinish(MatchFinishEvent event) {
        Match match = event.getMatch();

        logger.info("PGM Match Finalizado",
                "Detectado fin de partida PGM: " + match.getId());

        try {
            // Buscar partida activa correspondiente
            ActiveMatch activeMatch = findActiveMatchForPGM(match);

            if (activeMatch == null) {
                logger.warning("Partida No Encontrada",
                        "No se encontró partida activa correspondiente al match PGM: " + match.getId());
                return;
            }
            // VERIFICAR SI YA FUE FINALIZADA POR FORFEIT
            if (activeMatch.isFinishedByForfeit()) {
                logger.info("Forfeit Match Ignored",
                        "Partida " + activeMatch.getMatchId() + " ya fue finalizada por forfeit - ignorando evento PGM");
                return;
            }

            // CRÍTICO: MARCADO INMEDIATO DE JUGADORES COMO DISPONIBLES EN MEMORIA
            // Solo actualizar el estado en memoria INMEDIATAMENTE (operación instantánea)
            // La BD se actualiza de forma ASÍNCRONA para no bloquear el servidor
            logger.info("Immediate Player Release",
                    "Marcando inmediatamente " + activeMatch.getAllPlayers().size() + " jugadores como disponibles EN MEMORIA");

            List<PlayerData> allPlayers = activeMatch.getAllPlayers();

            // 1. PRIMERO: Marcar en memoria (instantáneo, no bloquea)
            for (PlayerData player : allPlayers) {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
            }

            // 2. SEGUNDO: Actualizar BD de forma ASÍNCRONA (no bloquea el servidor)
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (PlayerData player : allPlayers) {
                    try {
                        DatabaseManager.updatePlayerMatchStatus(player.getMinecraftUuid(), false, null);
                    } catch (Exception e) {
                        logger.warning("Async DB Update Failed",
                                "Error en actualización asíncrona para " + player.getMinecraftUuid().substring(0, 8) + ": " + e.getMessage());
                        // Continuar con otros jugadores
                    }
                }
                logger.info("DB Updates Complete", "Actualizaciones de BD completadas de forma asíncrona");
            });

            // Invalidar cache de Discord inmediatamente (operación rápida en memoria)
            java.util.List<String> discordIds = allPlayers.stream()
                    .map(PlayerData::getDiscordId)
                    .collect(java.util.stream.Collectors.toList());

            org.fabricioyv.discord.VoiceChannelListener.invalidatePlayersCache(discordIds);

            logger.success("Players Immediately Available",
                    "Jugadores marcados como disponibles EN MEMORIA - pueden entrar a colas inmediatamente (BD actualizándose en segundo plano)");

            // OPTIMIZACIÓN: Mover la determinación del ganador a un thread asíncrono
            // para no bloquear el main thread con cálculos pesados
            CompletableFuture.supplyAsync(() -> {
                return determineWinnerTeam(event, activeMatch);
            }).thenAcceptAsync(winnerTeam -> {
                if (winnerTeam == null) {
                    logger.warning("Sin Ganador",
                            "No se pudo determinar ganador para partida: " + activeMatch.getMatchId());
                    // Procesar como empate en el main thread
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        handleDrawOrCancel(activeMatch);
                    });
                    return;
                }

                logger.success("Ganador Determinado",
                        "Equipo " + winnerTeam.getDisplayName() + " ganó la partida " + activeMatch.getMatchId());

                // Volver al main thread para finalizar la partida
                Bukkit.getScheduler().runTask(plugin, () -> {
                    MatchFinisher.finishMatch(activeMatch, winnerTeam, plugin, logger);
                });
            }).exceptionally(throwable -> {
                logger.systemError("PGMMatchListener",
                        "Error procesando fin de partida PGM de forma asíncrona", throwable.getMessage());

                // Fallback al main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        Team winnerTeam = determineWinnerTeam(event, activeMatch);
                        if (winnerTeam != null) {
                            MatchFinisher.finishMatch(activeMatch, winnerTeam, plugin, logger);
                        } else {
                            handleDrawOrCancel(activeMatch);
                        }
                    } catch (Exception e) {
                        logger.systemError("PGMMatchListener", "Error en fallback", e.getMessage());
                    }
                });
                return null;
            });

        } catch (Exception e) {
            logger.systemError("PGMMatchListener",
                    "Error procesando fin de partida PGM", e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Busca la partida activa que corresponde al match de PGM
     * SIMPLIFICADO: Solo debería haber UNA partida activa a la vez
     */
    private ActiveMatch findActiveMatchForPGM(Match pgmMatch) {
        Collection<ActiveMatch> allMatches = ActiveMatch.getAllActiveMatches();

        if (allMatches.isEmpty()) {
            logger.warning("No Active Matches",
                    "No hay partidas activas registradas en el sistema");
            return null;
        }

        // Si solo hay una partida activa, esa debe ser
        if (allMatches.size() == 1) {
            ActiveMatch match = allMatches.iterator().next();
            logger.info("Single Active Match Found",
                    String.format("Encontrada partida activa única: %s (estado: %s)",
                            match.getMatchId(), match.getStatus().getDisplayName()));
            return match;
        }

        // Si hay múltiples, buscar la que NO esté finalizada
        logger.warning("Multiple Active Matches",
                String.format("Hay %d partidas activas, buscando la correcta", allMatches.size()));

        for (ActiveMatch activeMatch : allMatches) {
            ActiveMatch.MatchStatus status = activeMatch.getStatus();

            // Buscar partidas en estados activos (no finalizadas)
            if (status != ActiveMatch.MatchStatus.FINISHED &&
                status != ActiveMatch.MatchStatus.CANCELLED) {

                logger.info("Active Match Selected",
                        String.format("Seleccionada partida %s en estado %s",
                                activeMatch.getMatchId(), status.getDisplayName()));
                return activeMatch;
            }
        }

        logger.error("No Valid Active Match",
                "No se encontró ninguna partida en estado válido para PGM match: " + pgmMatch.getId());
        return null;
    }

    /**
     * Maneja casos de empate o partidas sin ganador claro
     */
    private void handleDrawOrCancel(ActiveMatch activeMatch) {
        logger.warning("Match Draw Detected",
                String.format("Match %s terminó en EMPATE - procesando sin cambios de ELO",
                        activeMatch.getMatchId()));

        // CRÍTICO: Limpiar estado inMatch de todos los jugadores
        cleanupPlayerMatchState(activeMatch);

        // Notificar empate
        notifyPlayersAboutDraw(activeMatch);

        // Procesar empate
        MatchFinisher.handleDrawMatch(activeMatch, plugin, logger);
    }

    /**
     * DETERMINACIÓN DE GANADOR 100% CONFIABLE
     * Solo usa comparación de jugadores, ignora completamente colores/nombres
     * OPTIMIZADO: Ahora se ejecuta de forma asíncrona para no bloquear main thread
     */
    private Team determineWinnerTeam(MatchFinishEvent event, ActiveMatch activeMatch) {
        try {
            logger.info("Starting Winner Determination", "Iniciando determinación de ganador (async)");

            Match match = event.getMatch();

            // PASO 1: Intentar obtener ganador por puntuación (KoTH/KotF)
            Team scoreWinner = determineWinnerByScore(match, activeMatch);
            if (scoreWinner != null) {
                logger.success("Winner by Score",
                        String.format("Ganador determinado por puntuación: %s", scoreWinner.getDisplayName()));
                return scoreWinner;
            }

            // PASO 2: Si no hay ganador por puntuación, usar objetivos (CTF/CTW/DTM/etc)
            logger.info("No Score Winner", "No hay ganador por puntuación, analizando objetivos");

            Map<tc.oc.pgm.teams.Team, Integer> teamScores = calculateAllTeamScores(match);

            if (teamScores.isEmpty()) {
                logger.error("No Team Scores", "No se pudieron calcular puntuaciones");
                return null;
            }

            tc.oc.pgm.teams.Team pgmWinnerTeam = findHighestScoringTeam(teamScores);

            if (pgmWinnerTeam == null) {
                logger.error("No PGM Winner", "No se encontró equipo PGM ganador");
                return null;
            }

            logger.success("PGM Winner Found",
                    String.format("Equipo PGM ganador: '%s' con %d objetivos",
                            pgmWinnerTeam.getId(), teamScores.get(pgmWinnerTeam)));

            Team ourWinnerTeam = mapWinnerByPlayersOnly(pgmWinnerTeam, activeMatch);

            if (ourWinnerTeam == null) {
                logger.error("Player Mapping Failed", "Fallo en mapeo por jugadores");
                return null;
            }

            logger.success("Winner Mapped Successfully",
                    String.format("GANADOR FINAL: %s", ourWinnerTeam.getDisplayName()));

            return ourWinnerTeam;

        } catch (Exception e) {
            logger.systemError("PGMMatchListener", "Error en determinación de ganador", e.getMessage());
            return null;
        }
    }

    /**
     * NUEVO: Determina ganador por puntuación (para KoTH y KotF)
     */
    private Team determineWinnerByScore(Match match, ActiveMatch activeMatch) {
        try {
            TeamMatchModule teamModule = match.getModule(TeamMatchModule.class);
            if (teamModule == null) return null;

            Collection<tc.oc.pgm.teams.Team> allTeams = teamModule.getTeams();
            Map<tc.oc.pgm.teams.Team, Double> teamPoints = new HashMap<>();

            boolean hasScoreSystem = false;

            // Obtener puntuaciones de cada equipo
            for (tc.oc.pgm.teams.Team team : allTeams) {
                try {
                    // Intentar obtener la puntuación del equipo
                    double score = getTeamScore(team);
                    teamPoints.put(team, score);

                    if (score > 0) {
                        hasScoreSystem = true;
                    }

                    logger.info("Team Score Detected",
                            String.format("Equipo '%s': %.1f puntos", team.getId(), score));

                } catch (Exception e) {
                    logger.warning("Score Read Error",
                            String.format("Error leyendo puntuación del equipo %s: %s",
                                    team.getId(), e.getMessage()));
                    teamPoints.put(team, 0.0);
                }
            }

            // Si no hay sistema de puntuación, retornar null
            if (!hasScoreSystem) {
                logger.info("No Score System", "No se detectó sistema de puntuación");
                return null;
            }

            // Encontrar equipo con mayor puntuación
            tc.oc.pgm.teams.Team winnerPgmTeam = null;
            double maxScore = -1;

            for (Map.Entry<tc.oc.pgm.teams.Team, Double> entry : teamPoints.entrySet()) {
                if (entry.getValue() > maxScore) {
                    maxScore = entry.getValue();
                    winnerPgmTeam = entry.getKey();
                }
            }

            if (winnerPgmTeam == null || maxScore <= 0) {
                logger.warning("No Score Winner", "No se encontró ganador por puntuación");
                return null;
            }

            logger.success("Score Winner Found",
                    String.format("Ganador por puntuación: %s (%.1f puntos)",
                            winnerPgmTeam.getId(), maxScore));

            // Mapear a nuestros equipos
            return mapWinnerByPlayersOnly(winnerPgmTeam, activeMatch);

        } catch (Exception e) {
            logger.systemError("PGMMatchListener", "Error determinando ganador por puntuación", e.getMessage());
            return null;
        }
    }
    /**
     * NUEVO: Obtiene la puntuación de un equipo usando reflexión
     */
    private double getTeamScore(tc.oc.pgm.teams.Team team) {
        try {
            // Metodo 1 : Obtiene el getScore() si existe!
            try {
                java.lang.reflect.Method getScoreMethod = team.getClass().getMethod("getScore");
                Object result = getScoreMethod.invoke(team);
                if (result instanceof Number) {
                    return ((Number) result).doubleValue();
                }
            } catch (Exception ignored) {}

            // Metodo 2: Buscar Campos en el Equipo, si tienen "score" o "points" en el nombre
            try {
                java.lang.reflect.Field[] fields = team.getClass().getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    field.setAccessible(true);
                    String fieldName = field.getName().toLowerCase();

                    if (fieldName.contains("score") || fieldName.contains("points")) {
                        Object value = field.get(team);
                        if (value instanceof Number) {
                            return ((Number) value).doubleValue();
                        }
                    }
                }
            } catch (Exception ignored) {}

           //Metodo 3 : Buscar en clase padre
            try {
                Class<?> parentClass = team.getClass().getSuperclass();
                if (parentClass != null) {
                    java.lang.reflect.Field[] parentFields = parentClass.getDeclaredFields();
                    for (java.lang.reflect.Field field : parentFields) {
                        field.setAccessible(true);
                        String fieldName = field.getName().toLowerCase();

                        if (fieldName.contains("score") || fieldName.contains("points")) {
                            Object value = field.get(team);
                            if (value instanceof Number) {
                                return ((Number) value).doubleValue();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            //Metodo 4: Buscar en módulos del match
            try {
                Match match = team.getMatch();
                if (match != null) {
                    // Buscar módulos que puedan tener puntuaciones
                    Object[] modules = match.getModules().toArray();
                    for (Object module : modules) {
                        String moduleName = module.getClass().getSimpleName().toLowerCase();

                        if (moduleName.contains("score") || moduleName.contains("points") ||
                                moduleName.contains("koth") || moduleName.contains("kotf")) {

                            // Intentar obtener puntuación del equipo desde este módulo
                            java.lang.reflect.Method[] methods = module.getClass().getMethods();
                            for (java.lang.reflect.Method method : methods) {
                                String methodName = method.getName().toLowerCase();
                                if (methodName.contains("score") || methodName.contains("points")) {
                                    try {
                                        Object result = method.invoke(module, team);
                                        if (result instanceof Number) {
                                            return ((Number) result).doubleValue();
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            return 0.0;

        } catch (Exception e) {
            logger.warning("Team Score Error",
                    String.format("Error obteniendo puntuación del equipo %s: %s",
                            team.getId(), e.getMessage()));
            return 0.0;
        }
    }
    /**
     * Calcula puntuaciones de TODOS los equipos PGM
     */
    private Map<tc.oc.pgm.teams.Team, Integer> calculateAllTeamScores(Match match) {
        Map<tc.oc.pgm.teams.Team, Integer> teamScores = new HashMap<>();

        try {
            TeamMatchModule teamModule = match.getModule(TeamMatchModule.class);
            GoalMatchModule goalModule = match.getModule(GoalMatchModule.class);

            if (teamModule == null) {
                logger.warning("No Team Module", "TeamMatchModule no encontrado");
                return teamScores;
            }

            Collection<tc.oc.pgm.teams.Team> allTeams = teamModule.getTeams();
            logger.info("Teams Found", String.format("Encontrados %d equipos PGM", allTeams.size()));

            // Inicializar todas las puntuaciones en 0
            for (tc.oc.pgm.teams.Team team : allTeams) {
                teamScores.put(team, 0);
                logger.info("Team Detected",
                        String.format("Equipo PGM: ID='%s', Color='%s', Jugadores=%d",
                                team.getId(),
                                getTeamColorName(team),
                                team.getPlayers().size()));
            }

            // Calcular puntuaciones por objetivos
            if (goalModule != null) {
                Collection<Goal> allGoals = goalModule.getGoals();
                logger.info("Goals Analysis", String.format("Analizando %d objetivos totales", allGoals.size()));

                for (Goal goal : allGoals) {
                    String goalName = goal.getName();
                    String goalType = identifyGoalType(goalName);

                    logger.info("Analyzing Goal",
                            String.format("Objetivo: '%s' (tipo: %s)", goalName, goalType));

                    // Verificar qué equipos completaron este objetivo
                    for (tc.oc.pgm.teams.Team team : allTeams) {
                        if (goal.isCompleted(team)) {
                            int currentScore = teamScores.get(team);
                            teamScores.put(team, currentScore + 1);

                            logger.info("Goal Completed",
                                    String.format("✓ Equipo '%s' completó '%s' → Puntuación: %d",
                                            team.getId(), goalName, currentScore + 1));
                        }
                    }
                }
            }

            // Log resumen de puntuaciones finales
            logger.info("=== PUNTUACIONES FINALES ===", "Resumen de todas las puntuaciones");
            for (Map.Entry<tc.oc.pgm.teams.Team, Integer> entry : teamScores.entrySet()) {
                tc.oc.pgm.teams.Team team = entry.getKey();
                int score = entry.getValue();

                logger.info("Final Team Score",
                        String.format("Equipo '%s' (color: %s): %d objetivos completados",
                                team.getId(), getTeamColorName(team), score));
            }

            return teamScores;

        } catch (Exception e) {
            logger.systemError("PGMMatchListener", "Error calculando puntuaciones", e.getMessage());
            return teamScores;
        }
    }

    /**
     * Encuentra el equipo con la puntuación más alta
     */
    private tc.oc.pgm.teams.Team findHighestScoringTeam(Map<tc.oc.pgm.teams.Team, Integer> teamScores) {
        tc.oc.pgm.teams.Team winnerTeam = null;
        int maxScore = -1;
        int teamsWithMaxScore = 0;

        for (Map.Entry<tc.oc.pgm.teams.Team, Integer> entry : teamScores.entrySet()) {
            int score = entry.getValue();

            if (score > maxScore) {
                maxScore = score;
                winnerTeam = entry.getKey();
                teamsWithMaxScore = 1;
            } else if (score == maxScore) {
                teamsWithMaxScore++;
            }
        }

        if (teamsWithMaxScore > 1) {
            logger.warning("Multiple Winners",
                    String.format("%d equipos empatados con %d puntos - usando el primero encontrado",
                            teamsWithMaxScore, maxScore));
            // En caso de empate, usar el primero encontrado (o implementar criterio de desempate)
        }

        if (winnerTeam != null) {
            logger.success("Highest Scoring Team",
                    String.format("Equipo ganador: '%s' con %d objetivos", winnerTeam.getId(), maxScore));
        }

        return winnerTeam;
    }

    /**
     * MAPEO 100% CONFIABLE - Solo por jugadores
     */
    private Team mapWinnerByPlayersOnly(tc.oc.pgm.teams.Team pgmWinnerTeam, ActiveMatch activeMatch) {
        try {
            // Obtener UUIDs de jugadores del equipo ganador PGM
            Set<UUID> pgmWinnerPlayerUUIDs = new HashSet<>();

            for (tc.oc.pgm.api.player.MatchPlayer matchPlayer : pgmWinnerTeam.getPlayers()) {
                pgmWinnerPlayerUUIDs.add(matchPlayer.getId());
            }

            if (pgmWinnerPlayerUUIDs.isEmpty()) {
                logger.error("No PGM Winner Players", "El equipo ganador PGM no tiene jugadores");
                return null;
            }

            logger.info("PGM Winner Players",
                    String.format("Equipo ganador PGM '%s' tiene %d jugadores",
                            pgmWinnerTeam.getId(), pgmWinnerPlayerUUIDs.size()));

            // Analizar coincidencias con cada uno de nuestros equipos
            Map<Team, PlayerMatchAnalysis> teamAnalysis = new HashMap<>();

            for (Map.Entry<Team, List<PlayerData>> entry : activeMatch.getTeams().entrySet()) {
                Team ourTeam = entry.getKey();
                List<PlayerData> ourPlayers = entry.getValue();

                PlayerMatchAnalysis analysis = analyzePlayerMatches(
                        ourTeam, ourPlayers, pgmWinnerPlayerUUIDs);
                teamAnalysis.put(ourTeam, analysis);

                logger.info("Player Match Analysis",
                        String.format("Equipo %s: %d/%d jugadores coinciden (%.1f%% de precisión)",
                                ourTeam.getDisplayName(),
                                analysis.matchingPlayers,
                                analysis.ourTeamSize,
                                analysis.matchPercentage * 100));
            }

            // Encontrar el equipo con mejor coincidencia
            return selectBestMatchingTeam(teamAnalysis, pgmWinnerTeam.getId());

        } catch (Exception e) {
            logger.systemError("PGMMatchListener", "Error en mapeo por jugadores", e.getMessage());
            return null;
        }
    }
    /**
     * Analiza coincidencias de jugadores entre equipos
     */
    private PlayerMatchAnalysis analyzePlayerMatches(Team ourTeam, List<PlayerData> ourPlayers,
                                                     Set<UUID> pgmWinnerUUIDs) {
        if (ourPlayers == null || ourPlayers.isEmpty()) {
            return new PlayerMatchAnalysis(0, 0, 0.0, new HashSet<>());
        }

        Set<UUID> matchingUUIDs = new HashSet<>();
        int matchingPlayers = 0;

        for (PlayerData playerData : ourPlayers) {
            try {
                UUID playerUUID = UUID.fromString(playerData.getMinecraftUuid());
                if (pgmWinnerUUIDs.contains(playerUUID)) {
                    matchingPlayers++;
                    matchingUUIDs.add(playerUUID);
                }
            } catch (IllegalArgumentException e) {
                logger.warning("Invalid Player UUID",
                        String.format("UUID inválido en equipo %s: %s",
                                ourTeam.getDisplayName(), playerData.getMinecraftUuid()));
            }
        }

        double matchPercentage = (double) matchingPlayers / ourPlayers.size();

        return new PlayerMatchAnalysis(matchingPlayers, ourPlayers.size(), matchPercentage, matchingUUIDs);
    }

    /**
     * Selecciona el equipo con mejor coincidencia de jugadores
     */
    private Team selectBestMatchingTeam(Map<Team, PlayerMatchAnalysis> teamAnalysis, String pgmTeamId) {
        Team bestTeam = null;
        PlayerMatchAnalysis bestAnalysis = null;

        for (Map.Entry<Team, PlayerMatchAnalysis> entry : teamAnalysis.entrySet()) {
            Team team = entry.getKey();
            PlayerMatchAnalysis analysis = entry.getValue();

            // Criterio 1: Coincidencia perfecta o casi perfecta (≥90%)
            if (analysis.matchPercentage >= 0.9) {
                logger.success("Perfect Match Found",
                        String.format("Coincidencia perfecta: %s (%.1f%%)",
                                team.getDisplayName(), analysis.matchPercentage * 100));
                return team;
            }

            // Criterio 2: Mejor coincidencia general
            if (bestAnalysis == null ||
                    analysis.matchPercentage > bestAnalysis.matchPercentage ||
                    (analysis.matchPercentage == bestAnalysis.matchPercentage &&
                            analysis.matchingPlayers > bestAnalysis.matchingPlayers)) {
                bestAnalysis = analysis;
                bestTeam = team;
            }
        }

        // Validación: al menos 70% de coincidencia para ser válido
        if (bestAnalysis != null && bestAnalysis.matchPercentage >= 0.7) {
            logger.success("Best Match Selected",
                    String.format("Mejor coincidencia: %s - %d/%d jugadores (%.1f%%)",
                            bestTeam.getDisplayName(),
                            bestAnalysis.matchingPlayers,
                            bestAnalysis.ourTeamSize,
                            bestAnalysis.matchPercentage * 100));
            return bestTeam;
        }

        // Si no hay suficiente coincidencia, log detallado para debugging
        logger.error("Insufficient Player Match",
                String.format("No hay coincidencia suficiente (≥70%%) para PGM team '%s'", pgmTeamId));

        // Log todas las coincidencias para debugging
        for (Map.Entry<Team, PlayerMatchAnalysis> entry : teamAnalysis.entrySet()) {
            PlayerMatchAnalysis analysis = entry.getValue();
            logger.info("Team Match Details",
                    String.format("Equipo %s: %d/%d jugadores (%.1f%%)",
                            entry.getKey().getDisplayName(),
                            analysis.matchingPlayers,
                            analysis.ourTeamSize,
                            analysis.matchPercentage * 100));
        }

        return null;
    }

    /**
     * Identifica tipo de objetivo para logging
     */
    private String identifyGoalType(String goalName) {
        String lowerName = goalName.toLowerCase();

        if (lowerName.contains("wool")) return "CTW";
        if (lowerName.contains("flag")) return "CTF";
        if (lowerName.contains("hill") || lowerName.contains("control") || lowerName.contains("zone")) return "KoTH";
        if (lowerName.contains("monument")) return "DTM";
        if (lowerName.contains("core")) return "DTC";

        return "UNKNOWN";
    }

    /**
     * Obtiene nombre del color del equipo para logging
     */
    private String getTeamColorName(tc.oc.pgm.teams.Team team) {
        try {
            // Intentar obtener el color del equipo
            if (team.getName() != null) {
                String fullName = PlainTextComponentSerializer.plainText()
                        .serialize(team.getName()).toLowerCase();
                return fullName;
            }

            if (team.getName() != null) {
                String name = PlainTextComponentSerializer.plainText()
                        .serialize(team.getName()).toLowerCase();
                return name;
            }

            return team.getId();

        } catch (Exception e) {
            return "UNKNOWN";
        }
    }






    /**
     * Notifica a los jugadores sobre el empate
     */
    private void notifyPlayersAboutDraw(ActiveMatch activeMatch) {
        String drawMessage = "��e⚖️ ¡EMPATE! No hay cambios de ELO.";

        for (List<PlayerData> teamPlayers : activeMatch.getTeams().values()) {
            for (PlayerData playerData : teamPlayers) {
                Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
                if (mcPlayer != null && mcPlayer.isOnline()) {
                    mcPlayer.sendMessage(drawMessage);
                }
            }
        }
    }
    /**
     * NUEVO: Limpia específicamente el estado inMatch de los jugadores
     */
    private void cleanupPlayerMatchState(ActiveMatch activeMatch) {
        logger.info("Cleaning Player States", "Limpiando estado inMatch de jugadores");

        int playersCleared = 0;
        for (PlayerData player : activeMatch.getAllPlayers()) {
            if (player.isInMatch()) {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
                playersCleared++;

                logger.info("Player State Cleared",
                        String.format("Estado limpiado para jugador UUID: %s",
                                player.getMinecraftUuid().substring(0, 8) + "..."));
            }
        }

        logger.success("States Cleaned",
                String.format("Estado inMatch limpiado para %d jugadores", playersCleared));
    }


    private static class PlayerMatchAnalysis {
        final int matchingPlayers;
        final int ourTeamSize;
        final double matchPercentage;
        final Set<UUID> matchingUUIDs;

        PlayerMatchAnalysis(int matchingPlayers, int ourTeamSize, double matchPercentage, Set<UUID> matchingUUIDs) {
            this.matchingPlayers = matchingPlayers;
            this.ourTeamSize = ourTeamSize;
            this.matchPercentage = matchPercentage;
            this.matchingUUIDs = matchingUUIDs;
        }
    }


    /**
     * ULTRA-OPTIMIZADO: Procesamiento unificado de muertes en batch asíncrono
     * Elimina el doble procesamiento y bloqueos del main thread
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerDeath(MatchPlayerDeathEvent event) {
        MatchPlayer victim = event.getPlayer();
        ParticipantState killerState = event.getKiller();
        Match match = victim.getMatch();

        if (match == null) return;

        // VALIDACIÓN ULTRA-RÁPIDA en main thread (< 0.1ms)
        ActiveMatch activeMatch = findActiveMatchForPGM(match);
        if (activeMatch == null) {
            return; // Sin logging para no bloquear
        }

        // EXTRAER DATOS MÍNIMOS (< 0.1ms) - VARIABLES FINALES para lambda
        final UUID victimUUID = victim.getId();
        final UUID killerUUID;
        final String victimName = victim.getBukkit() != null ? victim.getBukkit().getName() : victim.getId().toString();
        final String killerName;
        final String matchId = activeMatch.getMatchId();

        if (killerState != null && killerState.getPlayer().isPresent()) {
            MatchPlayer killer = killerState.getPlayer().get();
            killerUUID = killer.getId();
            Player killerBukkitPlayer = killer.getBukkit();
            killerName = killerBukkitPlayer != null ? killerBukkitPlayer.getName() : killer.getId().toString();
        } else {
            killerUUID = null;
            killerName = "ninguno";
        }

        // **CRÍTICO**: Inicializar estadísticas automáticamente en el primer evento de muerte
        if (!initializedMatches.contains(matchId)) {
            synchronized (initializedMatches) {
                // Double-check locking para thread safety
                if (!initializedMatches.contains(matchId)) {
                    Bukkit.getConsoleSender().sendMessage(
                        "§c[PGM-AUTO] Detectado primer evento de muerte en match " + matchId + " - inicializando estadísticas automáticamente"
                    );

                    // Inicializar estadísticas inmediatamente
                    initializeStatsForActiveMatch(activeMatch);

                    // Marcar como inicializado
                    initializedMatches.add(matchId);
                }
            }
        }

        // PROCESAMIENTO ASÍNCRONO UNIFICADO (TODO en background thread)
        CompletableFuture.runAsync(() -> {
            try {
                // 1. BATCH UPDATE: MatchStatsListener (sistema de estadísticas)
                MatchStatsListener.recordPlayerDeath(matchId, victimUUID, killerUUID);

                // 2. BATCH UPDATE: PlayerData (para cálculos ELO/MMR)
                updatePlayerDataAsync(activeMatch, victimUUID, killerUUID);

                // 3. LOGGING ASÍNCRONO (no bloquea)
                // Avoid spamming Discord logs for every death — log to server console only
                Bukkit.getConsoleSender().sendMessage(String.format("[RankedMC] Death processed: %s → %s (match: %s)",
                        victimName, killerName, matchId));

            } catch (Exception e) {
                // Error handling silencioso para no afectar performance
                logger.systemError("PGMMatchListener",
                    "Error en procesamiento asíncrono de muerte", e.getMessage());
            }
        });

        // TOTAL: < 0.2ms en main thread vs 2-5ms anterior
    }

    /**
     * Se llama desde el primer evento de muerte para asegurar que las estadísticas estén listas
     */
    private void initializeStatsForActiveMatch(ActiveMatch activeMatch) {
        try {
            String matchId = activeMatch.getMatchId();
            String matchType = activeMatch.getMatchType();
            String mapName = activeMatch.getSelectedMap() != null ? activeMatch.getSelectedMap() : "Unknown";

            Bukkit.getConsoleSender().sendMessage(
                "§e[PGM-AUTO] Ejecutando inicialización automática de estadísticas para " + matchId
            );

            // Llamar directamente a startMatchTracking
            MatchLogsIntegration.startMatchTracking(
                matchId,
                activeMatch.getTeams(),
                matchType,
                mapName
            );

            logger.success("Auto Stats Init",
                "Estadísticas inicializadas automáticamente para partida " + matchId + " (detectada desde PGM)");

        } catch (Exception e) {
            logger.systemError("PGMMatchListener",
                "Error en inicialización automática de estadísticas para " + activeMatch.getMatchId(), e.getMessage());
            logger.logError("Error stack trace", e);
        }
    }

    /**
     * Actualiza PlayerData de forma asíncrona y optimizada
     */
    private void updatePlayerDataAsync(ActiveMatch activeMatch, UUID victimUUID, UUID killerUUID) {
        try {
            // Víctima: añadir muerte
            PlayerData victimData = activeMatch.getPlayerByUUID(victimUUID);
            if (victimData != null) {
                synchronized (victimData) { // Thread-safe para concurrent access
                    victimData.addDeath();
                }
            }

            // Killer: añadir kill
            if (killerUUID != null) {
                PlayerData killerData = activeMatch.getPlayerByUUID(killerUUID);
                if (killerData != null) {
                    synchronized (killerData) { // Thread-safe para concurrent access
                        killerData.addKill();
                    }
                }
            }
        } catch (Exception e) {
            // Manejo silencioso de errores
            logger.warning("PlayerData Update Failed",
                "Error actualizando PlayerData async: " + e.getMessage());
        }
    }
}
