package org.fabricioyv.match;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
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
import java.util.stream.Collectors;

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

    private volatile long startedAtMillis = -1L;
    private final Map<Team, Integer> earlyDisconnects = new ConcurrentHashMap<>();
    private final Set<String> earlyDisconnectMarked = ConcurrentHashMap.newKeySet();

    public ActiveMatch(String matchId, List<PlayerData> players, JDA jda, Guild guild,
                       RankedMinecraft plugin, DiscordLogger logger) {

        this.matchId = matchId;
        this.allPlayers = new ArrayList<>(players == null ? Collections.emptyList() : players);

        int count = this.allPlayers.size();

        if (count == 4) {
            this.matchTypeEnum = ProgressiveEloCalculator.MatchType.RANKED_2V2;
        } else if (count == 10) {
            this.matchTypeEnum = ProgressiveEloCalculator.MatchType.RANKED_5V5;
        } else if (count == 16) {
            this.matchTypeEnum = ProgressiveEloCalculator.MatchType.RANKED_8V8;
        } else {
            this.matchTypeEnum = ProgressiveEloCalculator.MatchType.RANKED_5V5;
        }

        this.matchType = detectMatchType(count);

        this.teams = new HashMap<>();
        this.jda = jda;
        this.guild = guild;
        this.plugin = plugin;
        this.logger = logger;

        this.status = MatchStatus.PREPARING;
        this.startTime = LocalDateTime.now();

        earlyDisconnects.put(Team.BLUE, 0);
        earlyDisconnects.put(Team.RED, 0);

        activeMatches.put(matchId, this);

        for (PlayerData player : this.allPlayers) {
            try {
                player.setInMatch(true);
                player.setCurrentMatchId(matchId);
            } catch (Exception ignored) {}
        }
    }

    private static String detectMatchType(int playerCount) {
        if (playerCount == 4) return "2v2";
        if (playerCount == 10) return "5v5";
        if (playerCount == 16) return "8v8";
        int perTeam = Math.max(1, playerCount / 2);
        return perTeam + "v" + perTeam;
    }

    public boolean isUnrankedMatch() {
        return "2v2".equalsIgnoreCase(matchType);
    }

    public boolean isRankedMatch() {
        return !isUnrankedMatch();
    }

    public void markMatchStartedNow() {
        this.startedAtMillis = System.currentTimeMillis();
    }

    public long getStartedAtMillis() {
        if (startedAtMillis <= 0L) return System.currentTimeMillis();
        return startedAtMillis;
    }

    public int getEarlyDisconnects(Team team) {
        return earlyDisconnects.getOrDefault(team, 0);
    }

    public void markEarlyDisconnect(Team team, String playerUuid) {
        if (team == null || playerUuid == null) return;
        if (earlyDisconnectMarked.add(playerUuid)) {
            earlyDisconnects.merge(team, 1, Integer::sum);
        }
    }

    public void clearEarlyDisconnects() {
        earlyDisconnectMarked.clear();
        earlyDisconnects.put(Team.BLUE, 0);
        earlyDisconnects.put(Team.RED, 0);
    }

    // =========================================================
    // Balanceo (tu código igual)
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

    // =========================================================
    // Discord voice channels
    // =========================================================

    public void createTeamChannels() {
        if (blueTeamChannel != null || redTeamChannel != null) {
            logger.info("Canales Existentes", "Los canales de equipo ya existen, saltando creación");
            if (blueTeamChannel != null && redTeamChannel != null) movePlayersToTeamChannels();
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm"));

            final AtomicInteger ready = new AtomicInteger(0);
            final Runnable onOneReady = new Runnable() {
                @Override public void run() {
                    if (ready.incrementAndGet() == 2) {
                        logger.info("Discord", "Canales + permisos listos. Moviendo jugadores...");
                        movePlayersToTeamChannels();
                    }
                }
            };

            Category parentCategory = guild.getCategoryById(VoiceChannelConfig.TEAM_CHANNELS_CATEGORY_ID);
            if (parentCategory == null) {
                logger.warning("Discord", "TEAM_CHANNELS_CATEGORY_ID no válido o categoría no encontrada. Se crearán canales sin parent.");
            }

            ChannelAction<VoiceChannel> blueCreate = guild.createVoiceChannel("🔵 Equipo Azul " + timestamp);
            if (parentCategory != null) blueCreate = blueCreate.setParent(parentCategory);

            blueCreate.queue(channel -> {
                this.blueTeamChannel = channel;
                logger.info("Canal Creado", "Canal azul creado: " + channel.getName());
                setupChannelPermissions(channel, Team.BLUE, onOneReady);
            }, error -> logger.error("Error Canal Azul", "No se pudo crear canal azul: " + error.getMessage()));

            ChannelAction<VoiceChannel> redCreate = guild.createVoiceChannel("🔴 Equipo Rojo " + timestamp);
            if (parentCategory != null) redCreate = redCreate.setParent(parentCategory);

            redCreate.queue(channel -> {
                this.redTeamChannel = channel;
                logger.info("Canal Creado", "Canal rojo creado: " + channel.getName());
                setupChannelPermissions(channel, Team.RED, onOneReady);
            }, error -> logger.error("Error Canal Rojo", "No se pudo crear canal rojo: " + error.getMessage()));

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (blueTeamChannel != null && redTeamChannel != null) {
                    movePlayersToTeamChannels();
                }
            }, 100L);

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

    /**
     * ✅ FIX CLAVE:
     * Si getMemberById() da null, hacemos retrieveMemberById() y movemos igual.
     */
    private void moveTeamToChannel(Team team, VoiceChannel channel) {
        if (channel == null) {
            logger.error("Canal No Disponible", "Canal del equipo " + team.getDisplayName() + " no está disponible");
            return;
        }

        List<PlayerData> teamPlayers = teams.get(team);
        if (teamPlayers == null) return;

        for (PlayerData playerData : teamPlayers) {
            try {
                final String discordId = playerData.getDiscordId();
                if (discordId == null || discordId.isEmpty()) continue;

                Member member = guild.getMemberById(discordId);

                if (member == null) {
                    guild.retrieveMemberById(discordId).queue(
                            fetched -> moveMemberIfInVoice(fetched, channel, team),
                            err -> logger.error("Error Retrieve Member",
                                    "No se pudo obtener member " + discordId + ": " + err.getMessage())
                    );
                    continue;
                }

                moveMemberIfInVoice(member, channel, team);

            } catch (Exception e) {
                logger.systemError("ActiveMatch", "Error moviendo jugador a canal de equipo", e.getMessage());
            }
        }
    }

    private void moveMemberIfInVoice(Member member, VoiceChannel channel, Team team) {
        try {
            if (member == null) return;

            if (member.getVoiceState() != null && member.getVoiceState().inAudioChannel()) {
                guild.moveVoiceMember(member, channel).queue(
                        ok -> logger.info("Jugador Movido",
                                member.getEffectiveName() + " movido al equipo " + team.getDisplayName()),
                        err -> logger.error("Error Moviendo Jugador",
                                "No se pudo mover " + member.getEffectiveName() + ": " + err.getMessage())
                );
            } else {
                logger.info("Player Not In Voice",
                        member.getEffectiveName() + " no está en voice al momento de mover");
            }
        } catch (Exception ignored) {}
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
            }
        }
    }

    // ---------------------------
    // Permisos Discord (callback)
    // ---------------------------

    private void setupChannelPermissions(VoiceChannel channel, Team team, Runnable onComplete) {
        try {
            List<PlayerData> teamPlayers = teams.get(team);
            if (teamPlayers == null || teamPlayers.isEmpty()) {
                if (onComplete != null) onComplete.run();
                return;
            }

            channel.getManager()
                    .putPermissionOverride(guild.getPublicRole(),
                            EnumSet.of(Permission.VIEW_CHANNEL),
                            EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
                    .queue(
                            ok -> setupQueueRolePermissions(channel, teamPlayers, onComplete),
                            err -> setupQueueRolePermissions(channel, teamPlayers, onComplete)
                    );

        } catch (Exception e) {
            if (onComplete != null) onComplete.run();
        }
    }

    private void setupQueueRolePermissions(VoiceChannel channel, List<PlayerData> teamPlayers, Runnable onComplete) {
        try {
            Role queueRole = guild.getRoleById(VoiceChannelConfig.QUEUE_ROLE_ID);

            if (queueRole != null) {
                channel.getManager()
                        .putPermissionOverride(queueRole,
                                EnumSet.of(Permission.VIEW_CHANNEL),
                                EnumSet.of(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK))
                        .queue(
                                ok -> setupTeamMemberPermissions(channel, teamPlayers, 0, onComplete),
                                err -> setupTeamMemberPermissions(channel, teamPlayers, 0, onComplete)
                        );
            } else {
                setupTeamMemberPermissions(channel, teamPlayers, 0, onComplete);
            }
        } catch (Exception e) {
            setupTeamMemberPermissions(channel, teamPlayers, 0, onComplete);
        }
    }

    private void setupTeamMemberPermissions(VoiceChannel channel, List<PlayerData> teamPlayers, int idx, Runnable onComplete) {
        if (idx >= teamPlayers.size()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        PlayerData pd = teamPlayers.get(idx);
        try {
            String discordId = pd.getDiscordId();
            if (discordId == null || discordId.isEmpty()) {
                setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete);
                return;
            }

            Member m = guild.getMemberById(discordId);
            if (m == null) {
                guild.retrieveMemberById(discordId).queue(
                        fetched -> {
                            if (fetched != null) {
                                channel.getManager()
                                        .putPermissionOverride(fetched,
                                                EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD),
                                                Collections.emptySet())
                                        .queue(
                                                ok -> setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete),
                                                err -> setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete)
                                        );
                            } else {
                                setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete);
                            }
                        },
                        err -> setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete)
                );
                return;
            }

            channel.getManager()
                    .putPermissionOverride(m,
                            EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK, Permission.VOICE_USE_VAD),
                            Collections.emptySet())
                    .queue(
                            ok -> setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete),
                            err -> setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete)
                    );

        } catch (Exception e) {
            setupTeamMemberPermissions(channel, teamPlayers, idx + 1, onComplete);
        }
    }

    // =========================================================
    // Cleanup
    // =========================================================

    public void cleanup() {
        logger.info("Limpieza de Partida", "Limpiando partida " + matchId);

        for (PlayerData player : allPlayers) {
            try {
                player.setInMatch(false);
                player.setCurrentMatchId(null);
            } catch (Exception ignored) {}
        }

        List<String> discordIds = allPlayers.stream()
                .map(PlayerData::getDiscordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        try {
            org.fabricioyv.discord.VoiceChannelListener.invalidatePlayersCache(discordIds);
        } catch (Exception ignored) {}

        if (blueTeamChannel != null) {
            blueTeamChannel.delete().queue();
        }
        if (redTeamChannel != null) {
            redTeamChannel.delete().queue();
        }

        clearEarlyDisconnects();
        activeMatches.remove(matchId);
    }

    // =========================================================
    // Getters / helpers
    // =========================================================

    public String getMatchId() { return matchId; }
    public JDA getJda() { return jda; }
    public Guild getGuild() { return guild; }
    public RankedMinecraft getPlugin() { return plugin; }
    public DiscordLogger getLogger() { return logger; }
    public Map<Team, List<PlayerData>> getTeams() { return teams; }
    public List<PlayerData> getAllPlayers() { return new ArrayList<>(allPlayers); }

    public MatchStatus getStatus() { return status; }

    public void setStatus(MatchStatus status) {
        this.status = status;
        if (status == MatchStatus.IN_PROGRESS && this.startedAtMillis <= 0L) {
            markMatchStartedNow();
        }
    }

    public String getSelectedMap() { return selectedMap; }
    public void setSelectedMap(String selectedMap) { this.selectedMap = selectedMap; }

    public MapVoting getMapVoting() { return mapVoting; }
    public void setMapVoting(MapVoting mapVoting) { this.mapVoting = mapVoting; }

    public String getMatchType() { return matchType; }

    public VoiceChannel getBlueTeamChannel() { return blueTeamChannel; }
    public VoiceChannel getRedTeamChannel() { return redTeamChannel; }

    public boolean isPicksMatch() { return isPicksMatch; }
    public void setPicksMatch(boolean picksMatch) { isPicksMatch = picksMatch; }

    public PlayerData getBlueCaptain() { return blueCaptain; }
    public void setBlueCaptain(PlayerData blueCaptain) { this.blueCaptain = blueCaptain; }

    public PlayerData getRedCaptain() { return redCaptain; }
    public void setRedCaptain(PlayerData redCaptain) { this.redCaptain = redCaptain; }

    public enum MatchStatus {
        PREPARING("Preparando"),
        MAP_VOTING("Votación de Mapa"),
        STARTING("Iniciando"),
        IN_PROGRESS("En Progreso"),
        FINISHED("Finalizada"),
        CANCELLED("Cancelada");

        private final String displayName;

        MatchStatus(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }
    public static ActiveMatch getActiveMatch(String matchId) {
        if (matchId == null) return null;
        return activeMatches.get(matchId);
    }

    /** Devuelve un map NO modificable de matches activos */
    public static Map<String, ActiveMatch> getActiveMatches() {
        return Collections.unmodifiableMap(activeMatches);
    }

    /** Devuelve todos los matches activos (Collection) */
    public static Collection<ActiveMatch> getAllActiveMatches() {
        return activeMatches.values();
    }

    /** Devuelve el match activo donde está el playerUuid (buscando en allPlayers) */
    public static ActiveMatch getPlayerActiveMatch(String playerUuid) {
        if (playerUuid == null) return null;
        for (ActiveMatch match : activeMatches.values()) {
            for (PlayerData p : match.allPlayers) { // usa campo directo para evitar copias
                if (playerUuid.equalsIgnoreCase(p.getMinecraftUuid())) {
                    return match;
                }
            }
        }
        return null;
    }

    /** Alias que tu código llama en varios lados */
    public static ActiveMatch findActiveMatchForPlayer(String playerUuid) {
        return getPlayerActiveMatch(playerUuid);
    }

    /** Busca PlayerData dentro de ESTE match por UUID */
    public PlayerData getPlayerByUUID(UUID playerUUID) {
        if (playerUUID == null) return null;
        for (PlayerData p : allPlayers) {
            try {
                if (UUID.fromString(p.getMinecraftUuid()).equals(playerUUID)) {
                    return p;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Getter usado por MatchFinisher */
    public ProgressiveEloCalculator.MatchType getMatchTypeEnum() {
        return matchTypeEnum;
    }

    /** Getter usado por MatchLogsIntegration y MatchFinisher */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /** Flags usados por PGMMatchListener / ForfeitManager */
    public boolean isFinishedByForfeit() {
        return finishedByForfeit;
    }

    public void setFinishedByForfeit(boolean finishedByForfeit) {
        this.finishedByForfeit = finishedByForfeit;
    }

    /** Winner usado por MatchFinisher */
    public Team getWinnerTeam() {
        return winnerTeam;
    }

    public void setWinnerTeam(Team winnerTeam) {
        this.winnerTeam = winnerTeam;
    }
}