package org.fabricioyv.match;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.ProgressiveEloCalculator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActiveMatch {
    private static final Map<String, ActiveMatch> activeMatches = new ConcurrentHashMap<>();

    private final String matchId;
    private final String matchType;
    private final List<PlayerData> allPlayers;
    private final Map<Team, List<PlayerData>> teams;
    private final JDA jda;
    private final Guild guild;
    private final RankedMinecraft plugin;
    private final DiscordLogger logger;

    private String selectedMap;
    private MapVoting mapVoting;
    private VoiceChannel blueTeamChannel;
    private VoiceChannel redTeamChannel;
    private MatchStatus status;
    private final LocalDateTime startTime;
    private final ProgressiveEloCalculator.MatchType matchTypeEnum;
    private boolean finishedByForfeit = false;
    private Team winnerTeam; // Campo para almacenar el equipo ganador

    public ActiveMatch(String matchId, List<PlayerData> players, JDA jda, Guild guild,
                       RankedMinecraft plugin, DiscordLogger logger) {
        this.matchId = matchId;
        this.allPlayers = new ArrayList<>(players);
        // Determinar tipo de partida basado en número de jugadores
        this.matchTypeEnum = players.size() == 10 ?
                ProgressiveEloCalculator.MatchType.RANKED_5V5 :
                ProgressiveEloCalculator.MatchType.RANKED_8V8;

        this.matchType = this.matchTypeEnum.getDisplayName();
        this.teams = new HashMap<>();
        this.jda = jda;
        this.guild = guild;
        this.plugin = plugin;
        this.logger = logger;
        this.status = MatchStatus.PREPARING;
        this.startTime = LocalDateTime.now();

        // Registrar partida activa
        activeMatches.put(matchId, this);

        // Registrar jugadores en partidas activas
        for (PlayerData player : players) {
            player.setInMatch(true);
            player.setCurrentMatchId(matchId);
        }

    }

    /**
     * Balancea los equipos por ELO usando algoritmo "snake pick"
     */
    public void balanceTeams() {
        logger.info("Iniciando Balance", "Iniciando balanceo inteligente de equipos para " + allPlayers.size() + " jugadores");

        try {
            // Usar el nuevo sistema de balanceo
            TeamBalancer.BalanceResult result = TeamBalancer.balanceTeams(allPlayers);

            // Asignar equipos
            teams.put(Team.BLUE, new ArrayList<>(result.getTeam1()));
            teams.put(Team.RED, new ArrayList<>(result.getTeam2()));

            // Generar reporte detallado
            String balanceReport = TeamBalancer.generateBalanceReport(result);
            logger.info("Balance Completado", balanceReport);

            // Log resumido para Discord
            String statusMessage = result.isBalanced() ? "✅ BALANCEADO" : "⚠️ MEJOR INTENTO";
            logger.success("Equipos Formados",
                    String.format("%s - Diferencia: %.1f\nEquipo Azul: %.1f promedio vs Equipo Rojo: %.1f promedio",
                            statusMessage, result.getDifference(), result.getTeam1Average(), result.getTeam2Average()));

            // Log en consola para debugging
            logger.debug("Balance Detallado", balanceReport);

        } catch (Exception e) {
            logger.systemError("TeamBalancer", "Error en balanceo inteligente", e.getMessage());

            // Fallback al balanceo simple
            logger.warning("Fallback", "Usando balanceo simple como respaldo");
            balanceTeamsSimple();
        }
    }
    private void balanceTeamsSimple() {
        List<PlayerData> sortedPlayers = new ArrayList<>(allPlayers);
        sortedPlayers.sort((p1, p2) -> Integer.compare(p2.getElo(), p1.getElo()));

        List<PlayerData> blueTeam = new ArrayList<>();
        List<PlayerData> redTeam = new ArrayList<>();

        for (int i = 0; i < sortedPlayers.size(); i++) {
            if (i % 2 == 0) {
                blueTeam.add(sortedPlayers.get(i));
            } else {
                redTeam.add(sortedPlayers.get(i));
            }
        }

        teams.put(Team.BLUE, blueTeam);
        teams.put(Team.RED, redTeam);
    }
    /**
     * Crea canales de voz para los equipos
     */
    public void createTeamChannels() {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm"));

            // Crear canal para equipo azul
            guild.createVoiceChannel("🔵 Equipo Azul " + timestamp)
                    .setParent(guild.getCategoryById(VoiceChannelConfig.TEAM_CHANNELS_CATEGORY_ID))
                    .queue(channel -> {
                        this.blueTeamChannel = channel;
                        logger.info("Canal Creado", "Canal azul creado: " + channel.getName());

                        setupChannelPermissions(channel, Team.BLUE);
                    });

            // Crear canal para equipo rojo
            guild.createVoiceChannel("🔴 Equipo Rojo " + timestamp)
                    .setParent(guild.getCategoryById(VoiceChannelConfig.TEAM_CHANNELS_CATEGORY_ID))
                    .queue(channel -> {
                        this.redTeamChannel = channel;
                        logger.info("Canal Creado", "Canal rojo creado: " + channel.getName());

                        setupChannelPermissions(channel, Team.RED);
                    });

        } catch (Exception e) {
            logger.systemError("ActiveMatch",
                    "Error creando canales de equipos", e.getMessage());
        }
    }

    /**
     * Mueve jugadores a sus canales de equipo
     */
    public void movePlayersToTeamChannels() {
        // Esperar un poco a que los canales se creen
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            moveTeamToChannel(Team.BLUE, blueTeamChannel);
            moveTeamToChannel(Team.RED, redTeamChannel);
        }, 40L); // 2 segundos
    }

    private void moveTeamToChannel(Team team, VoiceChannel channel) {
        if (channel == null) {
            logger.error("Canal No Disponible",
                    "Canal del equipo " + team.getDisplayName() + " no está disponible");
            return;
        }

        List<PlayerData> teamPlayers = teams.get(team);
        if (teamPlayers == null) return;

        for (PlayerData playerData : teamPlayers) {
            try {
                Member member = guild.getMemberById(playerData.getDiscordId());
                if (member != null && member.getVoiceState().inAudioChannel()) {
                    guild.moveVoiceMember(member, channel).queue(
                            success -> logger.info("Jugador Movido",
                                    member.getEffectiveName() + " movido al equipo " + team.getDisplayName()),
                            error -> logger.error("Error Moviendo Jugador",
                                    "No se pudo mover " + member.getEffectiveName() + ": " + error.getMessage())
                    );
                }
            } catch (Exception e) {
                logger.systemError("ActiveMatch",
                        "Error moviendo jugador a canal de equipo", e.getMessage());
            }
        }
    }

    /**
     * Asigna jugadores a equipos en PGM
     */
    public void assignPlayersInMinecraft() {
        logger.info("Asignando Equipos", "Asignando jugadores a equipos en Minecraft");

        // Asignar equipo azul
        assignTeamInMinecraft(Team.BLUE);

        // Asignar equipo rojo
        assignTeamInMinecraft(Team.RED);
    }

    private void assignTeamInMinecraft(Team team) {
        List<PlayerData> teamPlayers = teams.get(team);
        if (teamPlayers == null) return;

        for (PlayerData playerData : teamPlayers) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                // Comando para asignar jugador al equipo en PGM
                String command = "team force " + mcPlayer.getName() + " " + team.getPgmName();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                // Notificar al jugador
                mcPlayer.sendMessage("§aFuiste asignado al " + team.getFormattedName() + " §aequipo!");
                logger.info("Jugador Asignado",
                        mcPlayer.getName() + " asignado al equipo " + team.getDisplayName());
            }
        }
    }

    /**
     * Limpia la partida y libera recursos
     */
    public void cleanup() {
        logger.info("Limpieza de Partida", "Limpiando partida " + matchId);

        // Marcar jugadores como no en partida
        for (PlayerData player : allPlayers) {
            player.setInMatch(false);
            player.setCurrentMatchId(null);
        }

        // Remover de partidas activas
        activeMatches.remove(matchId);

        logger.success("Partida Limpiada", "Partida " + matchId + " limpiada exitosamente");
    }
    private void setupChannelPermissions(VoiceChannel channel, Team team) {
        try {
            List<PlayerData> teamPlayers = teams.get(team);
            if (teamPlayers == null || teamPlayers.isEmpty()) {
                logger.warning("No Team Players", "No hay jugadores para configurar permisos en " + team.getDisplayName());
                return;
            }

            // Configurar permisos de manera asíncrona pero secuencial para evitar conflictos
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    logger.info("Setting Permissions", "Configurando permisos para canal " + team.getDisplayName());

                    // PASO 1: Configurar permisos para @everyone - PERMITIR VER y hablar pero DENEGAR conectar
                    channel.getManager()
                            .putPermissionOverride(guild.getPublicRole(),
                                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_SPEAK), // Permitir ver el canal y hablar
                                    EnumSet.of(Permission.VOICE_CONNECT, // NO pueden conectarse/entrar
                                             Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS,
                                             Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS))
                            .queue(
                                success -> {
                                    logger.debug("Permissions Set", "Permisos @everyone configurados en " + channel.getName() + " - Pueden VER y HABLAR pero NO conectar");
                                    // PASO 2: Configurar permisos para el rol @Queue
                                    setupQueueRolePermissions(channel, team, teamPlayers);
                                },
                                error -> {
                                    logger.error("Permission Error", "Error configurando permisos a @everyone: " + error.getMessage());
                                    // Intentar configurar permisos del rol Queue de todos modos
                                    setupQueueRolePermissions(channel, team, teamPlayers);
                                }
                            );

                } catch (Exception e) {
                    logger.systemError("ActiveMatch", "Error en configuración inicial de permisos", e.getMessage());
                    // Intentar configurar permisos básicos como fallback
                    setupBasicPermissions(channel, team, teamPlayers);
                }
            }, 20L); // 1 segundo de espera

        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos del canal", e.getMessage());
        }
    }

    /**
     * Configura permisos para el rol @Queue - pueden ver pero NO conectar ni hablar
     */
    private void setupQueueRolePermissions(VoiceChannel channel, Team team, List<PlayerData> teamPlayers) {
        try {
            Role queueRole = guild.getRoleById(VoiceChannelConfig.QUEUE_ROLE_ID);
            if (queueRole != null) {
                channel.getManager()
                        .putPermissionOverride(queueRole,
                                EnumSet.of(Permission.VIEW_CHANNEL), // Solo permitir ver el canal
                                EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK,
                                         Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS,
                                         Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS))
                        .queue(
                            success -> {
                                logger.debug("Queue Role Permissions Set", "Permisos del rol @Queue configurados en " + channel.getName() + " - Solo VER canal");
                                // PASO 3: Configurar permisos para cada miembro del equipo
                                setupTeamMemberPermissions(channel, team, teamPlayers, 0);
                            },
                            error -> {
                                logger.warning("Queue Role Permission Error", "Error configurando permisos para rol @Queue: " + error.getMessage());
                                // Continuar con permisos de miembros del equipo
                                setupTeamMemberPermissions(channel, team, teamPlayers, 0);
                            }
                        );
            } else {
                logger.warning("Queue Role Not Found", "No se encontró el rol @Queue con ID: " + VoiceChannelConfig.QUEUE_ROLE_ID);
                // Continuar con permisos de miembros del equipo
                setupTeamMemberPermissions(channel, team, teamPlayers, 0);
            }
        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos del rol @Queue", e.getMessage());
            // Continuar con permisos de miembros del equipo
            setupTeamMemberPermissions(channel, team, teamPlayers, 0);
        }
    }

    /**
     * Configura permisos para miembros del equipo de forma secuencial
     * Les da permisos COMPLETOS: ver, conectar, hablar, y usar PTT
     */
    private void setupTeamMemberPermissions(VoiceChannel channel, Team team, List<PlayerData> teamPlayers, int memberIndex) {
        if (memberIndex >= teamPlayers.size()) {
            logger.success("Channel Permissions Set",
                    String.format("✅ Permisos configurados exitosamente para canal %s (%d miembros) - Visible para todos, acceso solo para el equipo",
                            team.getDisplayName(), teamPlayers.size()));
            return;
        }

        PlayerData playerData = teamPlayers.get(memberIndex);
        try {
            Member member = guild.getMemberById(playerData.getDiscordId());
            if (member != null) {
                // Dar permisos COMPLETOS al miembro del equipo: ver, conectar, hablar, usar PTT, y desmutearse
                channel.getManager()
                        .putPermissionOverride(member,
                                EnumSet.of(
                                    Permission.VIEW_CHANNEL,           // Ver el canal
                                    Permission.VOICE_CONNECT,          // Conectarse al canal
                                    Permission.VOICE_SPEAK,            // Hablar en el canal
                                    Permission.VOICE_USE_VAD,          // Usar detección de voz automática// Usar actividad de voz
                                    Permission.VOICE_STREAM            // Compartir pantalla/cámara si está disponible
                                ),
                                EnumSet.of(
                                    Permission.VOICE_MUTE_OTHERS,      // No puede mutear a otros
                                    Permission.VOICE_DEAF_OTHERS       // No puede ensordecer a otros
                                )) // Permisos denegados explícitamente
                        .queue(
                            success -> {
                                logger.debug("Member Permission Set",
                                        String.format("✅ Permisos COMPLETOS otorgados a %s en canal %s (ver, conectar, hablar, PTT)",
                                                member.getEffectiveName(), team.getDisplayName()));
                                // Configurar siguiente miembro
                                setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
                            },
                            error -> {
                                logger.warning("Member Permission Error",
                                        String.format("⚠️ Error configurando permisos para %s: %s",
                                                member.getEffectiveName(), error.getMessage()));
                                // Continuar con el siguiente miembro
                                setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
                            }
                        );
            } else {
                logger.warning("Member Not Found", "No se encontró miembro Discord ID: " + playerData.getDiscordId());
                // Continuar con el siguiente miembro
                setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
            }
        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos para jugador individual", e.getMessage());
            // Continuar con el siguiente miembro
            setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
        }
    }

    /**
     * Configuración básica de permisos como fallback
     */
    private void setupBasicPermissions(VoiceChannel channel, Team team, List<PlayerData> teamPlayers) {
        logger.info("Basic Permissions", "Aplicando configuración básica de permisos para " + team.getDisplayName());

        try {
            // Configurar permisos básicos para @everyone (solo ver)
            channel.getManager()
                    .putPermissionOverride(guild.getPublicRole(),
                            EnumSet.of(Permission.VIEW_CHANNEL),
                            EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
                    .queue();

            // Dar permisos básicos a los miembros del equipo
            for (PlayerData playerData : teamPlayers) {
                try {
                    Member member = guild.getMemberById(playerData.getDiscordId());
                    if (member != null) {
                        // Configuración básica: permitir conexión, habla y ver
                        channel.getManager()
                                .putPermissionOverride(member,
                                        EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK,
                                                 Permission.VIEW_CHANNEL, Permission.VOICE_USE_VAD),
                                        Collections.emptyList())
                                .queue(
                                    success -> logger.debug("Basic Permission Set",
                                            "✅ Permisos básicos para " + member.getEffectiveName()),
                                    error -> logger.warning("Basic Permission Failed",
                                            "⚠️ Falló configuración básica para " + member.getEffectiveName())
                                );
                    }

                    // Pequeña pausa entre configuraciones para evitar rate limiting
                    Thread.sleep(150);

                } catch (Exception e) {
                    logger.warning("Basic Permission Error",
                        "Error configurando permisos básicos para jugador: " + e.getMessage());
                }
            }

            logger.success("Basic Permissions Applied",
                "Configuración básica aplicada para canal " + team.getDisplayName());

        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error aplicando configuración básica de permisos", e.getMessage());
        }
    }

    /**
     * Método para mover un jugador específico al canal de su equipo (usado para rejoin)
     */
    public void movePlayerToTeamChannel(PlayerData playerData, Team team) {
        VoiceChannel targetChannel = (team == Team.BLUE) ? blueTeamChannel : redTeamChannel;

        if (targetChannel == null) {
            logger.error("Team Channel Not Available",
                "Canal del equipo " + team.getDisplayName() + " no está disponible para rejoin");
            return;
        }

        try {
            Member member = guild.getMemberById(playerData.getDiscordId());
            if (member != null && member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(member, targetChannel).queue(
                        success -> logger.success("Rejoin Voice Move",
                            "✅ " + member.getEffectiveName() + " movido al canal " + team.getDisplayName() + " (rejoin)"),
                        error -> logger.error("Rejoin Voice Move Failed",
                            "❌ Error moviendo " + member.getEffectiveName() + " al canal " + team.getDisplayName() + ": " + error.getMessage())
                );
            } else {
                logger.info("Player Not In Voice",
                    "Jugador " + playerData.getMinecraftName() + " no está en un canal de voz para mover (rejoin)");
            }
        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error en rejoin voice move", e.getMessage());
        }
    }

    /**
     * Obtiene el ID de la partida
     */
    public String getMatchId() {
        return matchId;
    }

    /**
     * Obtiene los equipos de la partida
     */
    public Map<Team, List<PlayerData>> getTeams() {
        return teams;
    }

    /**
     * Obtiene todas las partidas activas
     */
    public static Map<String, ActiveMatch> getActiveMatches() {
        return activeMatches;
    }

    /**
     * Obtiene todas las partidas activas
     */
    public static Collection<ActiveMatch> getAllActiveMatches() {
        return activeMatches.values();
    }

    /**
     * Encuentra la partida activa de un jugador específico por su UUID
     */
    public static ActiveMatch getPlayerActiveMatch(String playerUuid) {
        for (ActiveMatch match : activeMatches.values()) {
            // Buscar en todos los jugadores de la partida
            for (PlayerData player : match.getAllPlayers()) {
                if (player.getMinecraftUuid().equals(playerUuid)) {
                    return match;
                }
            }
        }
        return null;
    }

    /**
     * Obtiene todos los jugadores de la partida
     */
    public List<PlayerData> getAllPlayers() {
        return new ArrayList<>(allPlayers);
    }

    /**
     * Obtiene un jugador por su UUID
     */
    public PlayerData getPlayerByUUID(UUID playerUUID) {
        return allPlayers.stream()
                .filter(player -> UUID.fromString(player.getMinecraftUuid()).equals(playerUUID))
                .findFirst()
                .orElse(null);
    }

    /**
     * Verifica si la partida fue finalizada por forfeit
     */
    public boolean isFinishedByForfeit() {
        return finishedByForfeit;
    }

    /**
     * Establece que la partida fue finalizada por forfeit
     */
    public void setFinishedByForfeit(boolean finishedByForfeit) {
        this.finishedByForfeit = finishedByForfeit;
    }

    /**
     * Obtiene el equipo ganador
     */
    public Team getWinnerTeam() {
        return winnerTeam;
    }

    /**
     * Establece el equipo ganador
     */
    public void setWinnerTeam(Team winnerTeam) {
        this.winnerTeam = winnerTeam;
    }

    /**
     * Obtiene el estado de la partida
     */
    public MatchStatus getStatus() {
        return status;
    }

    /**
     * Establece el estado de la partida
     */
    public void setStatus(MatchStatus status) {
        this.status = status;
    }

    /**
     * Obtiene el mapa seleccionado
     */
    public String getSelectedMap() {
        return selectedMap;
    }

    /**
     * Establece el mapa seleccionado
     */
    public void setSelectedMap(String selectedMap) {
        this.selectedMap = selectedMap;
    }

    /**
     * Obtiene el sistema de votación de mapas
     */
    public MapVoting getMapVoting() {
        return mapVoting;
    }

    /**
     * Establece el sistema de votación de mapas
     */
    public void setMapVoting(MapVoting mapVoting) {
        this.mapVoting = mapVoting;
    }

    /**
     * Obtiene la hora de inicio de la partida
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * Obtiene el tipo de partida como enum
     */
    public ProgressiveEloCalculator.MatchType getMatchTypeEnum() {
        return matchTypeEnum;
    }

    /**
     * Obtiene el tipo de partida como string
     */
    public String getMatchType() {
        return matchType;
    }

    /**
     * Obtiene el canal de voz del equipo azul
     */
    public VoiceChannel getBlueTeamChannel() {
        return blueTeamChannel;
    }

    /**
     * Obtiene el canal de voz del equipo rojo
     */
    public VoiceChannel getRedTeamChannel() {
        return redTeamChannel;
    }

    /**
     * Enum para los estados de la partida
     */
    public enum MatchStatus {
        PREPARING("Preparando"),
        MAP_VOTING("Votación de Mapa"),
        STARTING("Iniciando"),
        IN_PROGRESS("En Progreso"),
        FINISHED("Finalizada"),
        CANCELLED("Cancelada");

        private final String displayName;

        MatchStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
