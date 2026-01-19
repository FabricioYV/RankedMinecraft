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
import java.util.concurrent.atomic.AtomicInteger;

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
    private Team winnerTeam;

    // Picks
    private boolean isPicksMatch = false;
    private PlayerData blueCaptain = null;
    private PlayerData redCaptain = null;

    // =========================================================
    // Forfeit inteligente / abandono
    // =========================================================

    /**
     * Momento "real" desde el cual se cuentan los 3 minutos.
     * IMPORTANT: NO debe iniciarse en PREPARING, sino cuando empieza la partida (IN_PROGRESS).
     */
    private volatile long startedAtMillis = -1L;

    /**
     * Early disconnects por equipo (conteo).
     */
    private final Map<Team, Integer> earlyDisconnects = new ConcurrentHashMap<>();

    /**
     * Para no contar 2 veces el mismo jugador como early disconnect.
     */
    private final Set<String> earlyDisconnectMarked = ConcurrentHashMap.newKeySet();

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

        // Inicializar mapa earlyDisconnects (por si lo usas sin haber metido merge aún)
        earlyDisconnects.put(Team.BLUE, 0);
        earlyDisconnects.put(Team.RED, 0);

        // Registrar partida activa
        activeMatches.put(matchId, this);

        // Registrar jugadores en partidas activas
        for (PlayerData player : players) {
            player.setInMatch(true);
            player.setCurrentMatchId(matchId);
        }
    }

    // =========================
    // Inicio de partida (lock 3 min)
    // =========================

    /**
     * Marca el inicio real del match: desde aquí se cuentan los 3 minutos.
     * Llamar cuando cambias a IN_PROGRESS (idealmente en el momento exacto que PGM inicia).
     */
    public void markMatchStartedNow() {
        this.startedAtMillis = System.currentTimeMillis();
    }

    /**
     * Getter usado por ForfeitManager / AbandonmentDetectionSystem
     * Si aún no se marcó el inicio real, hace fallback a "ahora" para evitar negativos.
     */
    public long getStartedAtMillis() {
        if (startedAtMillis <= 0L) {
            // Fallback defensivo: si alguien olvidó marcar inicio, no rompas todo.
            return System.currentTimeMillis();
        }
        return startedAtMillis;
    }

    /**
     * Si quieres setearlo manualmente (opcional).
     */
    public void setStartedAtMillis(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    // =========================
    // Early disconnects (para early FF por desconexión)
    // =========================

    public int getEarlyDisconnects(Team team) {
        return earlyDisconnects.getOrDefault(team, 0);
    }

    public void markEarlyDisconnect(Team team, String playerUuid) {
        if (team == null || playerUuid == null) return;

        // Solo cuenta 1 vez por jugador
        if (earlyDisconnectMarked.add(playerUuid)) {
            earlyDisconnects.merge(team, 1, Integer::sum);
        }
    }

    /**
     * ✅ NUEVO: desmarca early disconnect si el jugador reconecta dentro del grace period.
     * Esto evita que quede "pegado" habilitando early /ff fantasma.
     */
    public void unmarkEarlyDisconnect(Team team, String playerUuid) {
        if (team == null || playerUuid == null) return;

        // Si nunca estuvo marcado, no hagas nada
        if (!earlyDisconnectMarked.remove(playerUuid)) return;

        earlyDisconnects.compute(team, (t, val) -> {
            int current = (val == null ? 0 : val);
            // Nunca bajar de 0
            return Math.max(0, current - 1);
        });
    }

    /**
     * Útil si en algún punto quieres "resetear" estado de early disconnect al final del match o por rejoin masivo.
     */
    public void clearEarlyDisconnects() {
        earlyDisconnectMarked.clear();
        earlyDisconnects.put(Team.BLUE, 0);
        earlyDisconnects.put(Team.RED, 0);
    }

    // =========================================================
    // Balanceo y Teams (tu código intacto)
    // =========================================================

    public void balanceTeams() {
        logger.info("Iniciando Balance", "Iniciando balanceo inteligente con predicción de MMR para " + allPlayers.size() + " jugadores");

        try {
            long placementCount = allPlayers.stream().filter(PlayerData::isInPlacement).count();

            if (placementCount > 0) {
                logger.info("Placement Detectado",
                        String.format("Detectados %d jugadores en partidas de prueba - Aplicando algoritmo de predicción de MMR", placementCount));

                SmartPlacementMatchmaking.BalancedTeams balancedTeams =
                        SmartPlacementMatchmaking.balanceTeamsWithPrediction(allPlayers);

                teams.put(Team.BLUE, new ArrayList<>(balancedTeams.team1));
                teams.put(Team.RED, new ArrayList<>(balancedTeams.team2));

                String smartReport = SmartPlacementMatchmaking.generateSmartMatchmakingReport(balancedTeams);
                logger.success("Balance Inteligente", smartReport);

            } else {
                TeamBalancer.BalanceResult result = TeamBalancer.balanceTeams(allPlayers);

                teams.put(Team.BLUE, new ArrayList<>(result.getTeam1()));
                teams.put(Team.RED, new ArrayList<>(result.getTeam2()));

                String balanceReport = TeamBalancer.generateBalanceReport(result);
                logger.info("Balance Completado", balanceReport);

                String statusMessage = result.isBalanced() ? "✅ BALANCEADO" : "⚠️ MEJOR INTENTO";
                logger.success("Equipos Formados",
                        String.format("%s - Diferencia: %.1f\nEquipo Azul: %.1f promedio vs Equipo Rojo: %.1f promedio",
                                statusMessage, result.getDifference(), result.getTeam1Average(), result.getTeam2Average()));
            }

        } catch (Exception e) {
            logger.systemError("TeamBalancer", "Error en balanceo inteligente", e.getMessage());

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
            if (i % 2 == 0) blueTeam.add(sortedPlayers.get(i));
            else redTeam.add(sortedPlayers.get(i));
        }

        teams.put(Team.BLUE, blueTeam);
        teams.put(Team.RED, redTeam);
    }

    public void createTeamChannels() {
        if (blueTeamChannel != null || redTeamChannel != null) {
            logger.info("Canales Existentes", "Los canales de equipo ya existen, saltando creación");
            if (blueTeamChannel != null && redTeamChannel != null) {
                movePlayersToTeamChannels();
            }
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm"));

            final AtomicInteger channelsCreated = new AtomicInteger(0);
            final Object lock = new Object();

            logger.info("Creando Canales", "Iniciando creación de canales de equipo finales");

            guild.createVoiceChannel("🔵 Equipo Azul " + timestamp)
                    .setParent(guild.getCategoryById(VoiceChannelConfig.TEAM_CHANNELS_CATEGORY_ID))
                    .queue(channel -> {
                        this.blueTeamChannel = channel;
                        logger.info("Canal Creado", "Canal azul creado: " + channel.getName());

                        setupChannelPermissions(channel, Team.BLUE);

                        synchronized (lock) {
                            if (channelsCreated.incrementAndGet() == 2) {
                                logger.info("Canales Completos", "Ambos canales creados, moviendo jugadores");
                                movePlayersToTeamChannels();
                            }
                        }
                    }, error -> logger.error("Error Canal Azul", "No se pudo crear canal azul: " + error.getMessage()));

            guild.createVoiceChannel("🔴 Equipo Rojo " + timestamp)
                    .setParent(guild.getCategoryById(VoiceChannelConfig.TEAM_CHANNELS_CATEGORY_ID))
                    .queue(channel -> {
                        this.redTeamChannel = channel;
                        logger.info("Canal Creado", "Canal rojo creado: " + channel.getName());

                        setupChannelPermissions(channel, Team.RED);

                        synchronized (lock) {
                            if (channelsCreated.incrementAndGet() == 2) {
                                logger.info("Canales Completos", "Ambos canales creados, moviendo jugadores");
                                movePlayersToTeamChannels();
                            }
                        }
                    }, error -> logger.error("Error Canal Rojo", "No se pudo crear canal rojo: " + error.getMessage()));

        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error creando canales de equipos", e.getMessage());
        }
    }

    public void movePlayersToTeamChannels() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            moveTeamToChannel(Team.BLUE, blueTeamChannel);
            moveTeamToChannel(Team.RED, redTeamChannel);
        }, 40L);
    }

    private void moveTeamToChannel(Team team, VoiceChannel channel) {
        if (channel == null) {
            logger.error("Canal No Disponible", "Canal del equipo " + team.getDisplayName() + " no está disponible");
            return;
        }

        List<PlayerData> teamPlayers = teams.get(team);
        if (teamPlayers == null) return;

        for (PlayerData playerData : teamPlayers) {
            try {
                Member member = guild.getMemberById(playerData.getDiscordId());
                if (member != null && member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
                    guild.moveVoiceMember(member, channel).queue(
                            success -> logger.info("Jugador Movido",
                                    member.getEffectiveName() + " movido al equipo " + team.getDisplayName()),
                            error -> logger.error("Error Moviendo Jugador",
                                    "No se pudo mover " + member.getEffectiveName() + ": " + error.getMessage())
                    );
                }
            } catch (Exception e) {
                logger.systemError("ActiveMatch", "Error moviendo jugador a canal de equipo", e.getMessage());
            }
        }
    }

    public void assignPlayersInMinecraft() {
        logger.info("Asignando Equipos", "Asignando jugadores a equipos en Minecraft");

        assignTeamInMinecraft(Team.BLUE);
        assignTeamInMinecraft(Team.RED);

        logger.info("Moviendo a Canales", "Moviendo jugadores a canales de Discord después de asignación en Minecraft");
        movePlayersToTeamChannels();
    }

    private void assignTeamInMinecraft(Team team) {
        List<PlayerData> teamPlayers = teams.get(team);
        if (teamPlayers == null) return;

        for (PlayerData playerData : teamPlayers) {
            Player mcPlayer = Bukkit.getPlayer(UUID.fromString(playerData.getMinecraftUuid()));
            if (mcPlayer != null && mcPlayer.isOnline()) {
                String command = "team force " + mcPlayer.getName() + " " + team.getPgmName();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);

                mcPlayer.sendMessage("§aFuiste asignado al " + team.getFormattedName() + " §aequipo!");
                logger.info("Jugador Asignado", mcPlayer.getName() + " asignado al equipo " + team.getDisplayName());
            }
        }
    }

    public void cleanup() {
        logger.info("Limpieza de Partida", "Limpiando partida " + matchId);

        for (PlayerData player : allPlayers) {
            player.setInMatch(false);
            player.setCurrentMatchId(null);
        }

        List<String> discordIds = allPlayers.stream()
                .map(PlayerData::getDiscordId)
                .collect(java.util.stream.Collectors.toList());

        org.fabricioyv.discord.VoiceChannelListener.invalidatePlayersCache(discordIds);
        logger.info("Cache Invalidado", "Cache de Discord invalidado para " + discordIds.size() + " jugadores - pueden volver a entrar a colas inmediatamente");

        if (blueTeamChannel != null) {
            blueTeamChannel.delete().queue(
                    success -> logger.info("Canal Limpiado", "Canal azul borrado durante cleanup"),
                    error -> logger.warning("Error Limpieza Canal", "Error borrando canal azul en cleanup: " + error.getMessage())
            );
        }

        if (redTeamChannel != null) {
            redTeamChannel.delete().queue(
                    success -> logger.info("Canal Limpiado", "Canal rojo borrado durante cleanup"),
                    error -> logger.warning("Error Limpieza Canal", "Error borrando canal rojo en cleanup: " + error.getMessage())
            );
        }

        // ✅ Limpiar estado early disconnect
        clearEarlyDisconnects();

        activeMatches.remove(matchId);

        logger.success("Partida Limpiada", "Partida " + matchId + " limpiada exitosamente - canales borrados");
    }

    // ---------------------------
    // Permisos Discord (tu código)
    // ---------------------------

    private void setupChannelPermissions(VoiceChannel channel, Team team) {
        try {
            List<PlayerData> teamPlayers = teams.get(team);
            if (teamPlayers == null || teamPlayers.isEmpty()) {
                logger.warning("No Team Players", "No hay jugadores para configurar permisos en " + team.getDisplayName());
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    logger.info("Setting Permissions", "Configurando permisos para canal " + team.getDisplayName());

                    channel.getManager()
                            .putPermissionOverride(guild.getPublicRole(),
                                    EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_SPEAK),
                                    EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS,
                                            Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS))
                            .queue(
                                    success -> {
                                        logger.debug("Permissions Set", "Permisos @everyone configurados en " + channel.getName() + " - Pueden VER y HABLAR, pero NO CONECTAR");
                                        setupQueueRolePermissions(channel, team, teamPlayers);
                                    },
                                    error -> {
                                        logger.error("Permission Error", "Error configurando permisos a @everyone: " + error.getMessage());
                                        setupQueueRolePermissions(channel, team, teamPlayers);
                                    }
                            );

                } catch (Exception e) {
                    logger.systemError("ActiveMatch", "Error en configuración inicial de permisos", e.getMessage());
                    setupBasicPermissions(channel, team, teamPlayers);
                }
            }, 20L);

        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos del canal", e.getMessage());
        }
    }

    private void setupQueueRolePermissions(VoiceChannel channel, Team team, List<PlayerData> teamPlayers) {
        try {
            Role queueRole = guild.getRoleById(VoiceChannelConfig.QUEUE_ROLE_ID);
            if (queueRole != null) {
                channel.getManager()
                        .putPermissionOverride(queueRole,
                                EnumSet.of(Permission.VIEW_CHANNEL),
                                EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK,
                                        Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS,
                                        Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS))
                        .queue(
                                success -> {
                                    logger.debug("Queue Role Permissions Set", "Permisos del rol @Queue configurados en " + channel.getName() + " - Solo VER canal");
                                    setupTeamMemberPermissions(channel, team, teamPlayers, 0);
                                },
                                error -> {
                                    logger.warning("Queue Role Permission Error", "Error configurando permisos para rol @Queue: " + error.getMessage());
                                    setupTeamMemberPermissions(channel, team, teamPlayers, 0);
                                }
                        );
            } else {
                logger.warning("Queue Role Not Found", "No se encontró el rol @Queue con ID: " + VoiceChannelConfig.QUEUE_ROLE_ID);
                setupTeamMemberPermissions(channel, team, teamPlayers, 0);
            }
        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos del rol @Queue", e.getMessage());
            setupTeamMemberPermissions(channel, team, teamPlayers, 0);
        }
    }

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
                channel.getManager()
                        .putPermissionOverride(member,
                                EnumSet.of(
                                        Permission.VIEW_CHANNEL,
                                        Permission.VOICE_CONNECT,
                                        Permission.VOICE_SPEAK,
                                        Permission.VOICE_USE_VAD,
                                        Permission.VOICE_STREAM
                                ),
                                EnumSet.of(
                                        Permission.VOICE_MUTE_OTHERS,
                                        Permission.VOICE_DEAF_OTHERS
                                ))
                        .queue(
                                success -> {
                                    logger.debug("Member Permission Set",
                                            String.format("✅ Permisos COMPLETOS otorgados a %s en canal %s (ver, conectar, hablar, PTT)",
                                                    member.getEffectiveName(), team.getDisplayName()));
                                    setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
                                },
                                error -> {
                                    logger.warning("Member Permission Error",
                                            String.format("⚠️ Error configurando permisos para %s: %s",
                                                    member.getEffectiveName(), error.getMessage()));
                                    setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
                                }
                        );
            } else {
                logger.warning("Member Not Found", "No se encontró miembro Discord ID: " + playerData.getDiscordId());
                setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
            }
        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error configurando permisos para jugador individual", e.getMessage());
            setupTeamMemberPermissions(channel, team, teamPlayers, memberIndex + 1);
        }
    }

    private void setupBasicPermissions(VoiceChannel channel, Team team, List<PlayerData> teamPlayers) {
        logger.info("Basic Permissions", "Aplicando configuración básica de permisos para " + team.getDisplayName());

        try {
            channel.getManager()
                    .putPermissionOverride(guild.getPublicRole(),
                            EnumSet.of(Permission.VIEW_CHANNEL),
                            EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
                    .queue();

            for (PlayerData playerData : teamPlayers) {
                try {
                    Member member = guild.getMemberById(playerData.getDiscordId());
                    if (member != null) {
                        channel.getManager()
                                .putPermissionOverride(member,
                                        EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK,
                                                Permission.VIEW_CHANNEL, Permission.VOICE_USE_VAD),
                                        Collections.emptyList())
                                .queue();
                    }
                    Thread.sleep(150);
                } catch (Exception e) {
                    logger.warning("Basic Permission Error", "Error configurando permisos básicos para jugador: " + e.getMessage());
                }
            }

            logger.success("Basic Permissions Applied", "Configuración básica aplicada para canal " + team.getDisplayName());

        } catch (Exception e) {
            logger.systemError("ActiveMatch", "Error aplicando configuración básica de permisos", e.getMessage());
        }
    }

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

    // =========================================================
    // Getters / helpers
    // =========================================================

    public String getMatchId() {
        return matchId;
    }

    public Map<Team, List<PlayerData>> getTeams() {
        return teams;
    }

    public static ActiveMatch findActiveMatchForPlayer(String playerUuid) {
        for (ActiveMatch match : activeMatches.values()) {
            for (List<PlayerData> team : match.getTeams().values()) {
                for (PlayerData player : team) {
                    if (player.getMinecraftUuid().equals(playerUuid)) {
                        return match;
                    }
                }
            }
        }
        return null;
    }

    public static ActiveMatch getActiveMatch(String matchId) {
        return activeMatches.get(matchId);
    }

    public static Map<String, ActiveMatch> getActiveMatches() {
        return Collections.unmodifiableMap(activeMatches);
    }

    public static Collection<ActiveMatch> getAllActiveMatches() {
        return activeMatches.values();
    }

    public static ActiveMatch getPlayerActiveMatch(String playerUuid) {
        for (ActiveMatch match : activeMatches.values()) {
            for (PlayerData player : match.getAllPlayers()) {
                if (player.getMinecraftUuid().equals(playerUuid)) return match;
            }
        }
        return null;
    }

    public List<PlayerData> getAllPlayers() {
        return new ArrayList<>(allPlayers);
    }

    public PlayerData getPlayerByUUID(UUID playerUUID) {
        return allPlayers.stream()
                .filter(player -> UUID.fromString(player.getMinecraftUuid()).equals(playerUUID))
                .findFirst()
                .orElse(null);
    }

    public boolean isFinishedByForfeit() {
        return finishedByForfeit;
    }

    public void setFinishedByForfeit(boolean finishedByForfeit) {
        this.finishedByForfeit = finishedByForfeit;
    }

    public Team getWinnerTeam() {
        return winnerTeam;
    }

    public void setWinnerTeam(Team winnerTeam) {
        this.winnerTeam = winnerTeam;
    }

    public MatchStatus getStatus() {
        return status;
    }

    public void setStatus(MatchStatus status) {
        this.status = status;

        // ✅ Si pasas a IN_PROGRESS y aún no se marcó, marca inicio real ahora.
        if (status == MatchStatus.IN_PROGRESS && this.startedAtMillis <= 0L) {
            markMatchStartedNow();
        }
    }

    public String getSelectedMap() {
        return selectedMap;
    }

    public void setSelectedMap(String selectedMap) {
        this.selectedMap = selectedMap;
    }

    public MapVoting getMapVoting() {
        return mapVoting;
    }

    public void setMapVoting(MapVoting mapVoting) {
        this.mapVoting = mapVoting;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public ProgressiveEloCalculator.MatchType getMatchTypeEnum() {
        return matchTypeEnum;
    }

    public String getMatchType() {
        return matchType;
    }

    public VoiceChannel getBlueTeamChannel() {
        return blueTeamChannel;
    }

    public VoiceChannel getRedTeamChannel() {
        return redTeamChannel;
    }

    public Guild getGuild() {
        return this.guild;
    }

    public boolean isPicksMatch() {
        return isPicksMatch;
    }

    public void setPicksMatch(boolean picksMatch) {
        isPicksMatch = picksMatch;
    }

    public PlayerData getBlueCaptain() {
        return blueCaptain;
    }

    public void setBlueCaptain(PlayerData blueCaptain) {
        this.blueCaptain = blueCaptain;
    }

    public PlayerData getRedCaptain() {
        return redCaptain;
    }

    public void setRedCaptain(PlayerData redCaptain) {
        this.redCaptain = redCaptain;
    }

    public PlayerData getCaptainForTeam(Team team) {
        return team == Team.BLUE ? blueCaptain : redCaptain;
    }

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