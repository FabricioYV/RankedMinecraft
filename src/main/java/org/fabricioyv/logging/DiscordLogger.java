package org.fabricioyv.logging;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.entity.Player;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.Rank;

import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class DiscordLogger {

    // Zona horaria de Perú (GMT-5) para timestamps consistentes
    private static final ZoneId PERU_ZONE = ZoneId.of("America/Lima");

    // Discord nickname max length
    private static final int DISCORD_NICK_MAX = 32;

    // Link público de stats (usa match_id de 5 caracteres de BD2)
    private static final String STATS_BASE_URL = "https://kekelive.netlify.app/match/";

    // Para evitar wraps feos de nombres (en fields)
    private static final int MAX_NAME_CHARS = 18;

    private final JDA jda;
    private final TextChannel logsChannel;
    private final TextChannel resultsChannel;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public DiscordLogger(JDA jda) {
        this.jda = jda;
        this.logsChannel = jda.getTextChannelById(VoiceChannelConfig.LOGS_CHANNEL_ID);
        this.resultsChannel = jda.getTextChannelById(VoiceChannelConfig.MATCH_RESULTS_CHANNEL_ID);

        if (this.logsChannel == null) {
            System.err.println("⚠️ Canal de logs no encontrado: " + VoiceChannelConfig.LOGS_CHANNEL_ID);
        }
        if (this.resultsChannel == null) {
            System.err.println("⚠️ Canal de resultados no encontrado: " + VoiceChannelConfig.MATCH_RESULTS_CHANNEL_ID);
        }
    }

    // =========================================================
    //  STRATUS STYLE (START / END) — SIN CODEBLOCK
    //  Columnas: usando FIELDS INLINE (como Stratus)
    // =========================================================

    /**
     * Embed estilo Stratus al iniciar: Map/Server + equipos (ELO actual).
     * SIN "pizarra" (sin ```), usa 2 fields inline.
     */
    public void matchStartedStratusStyle(ActiveMatch activeMatch, String serverName) {
        if (activeMatch == null) return;

        int playerCount = 0;
        try { playerCount = activeMatch.getAllPlayers() != null ? activeMatch.getAllPlayers().size() : 0; } catch (Exception ignored) {}

        String queueLabel = toQueueLabel(activeMatch.getMatchType(), playerCount);
        String map = safe(activeMatch.getSelectedMap(), "Unknown");
        String server = safe(serverName, "Ranked");

        String redLines = buildStartTeamLines(activeMatch.getTeams(), Team.RED);
        String blueLines = buildStartTeamLines(activeMatch.getTeams(), Team.BLUE);

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Match Started – " + queueLabel);
        embed.setColor(Color.GREEN);

        // Texto normal (sin codeblock)
        embed.setDescription(
                "**Map** - " + map + "\n" +
                        "**Server** - " + server
        );

        // Columnas reales sin fondo negro: fields inline
        embed.addField("Red", limitFieldValue(redLines), true);
        embed.addField("Blue", limitFieldValue(blueLines), true);

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Keke Ranked (" + safe(activeMatch.getMatchId(), "?") + ")");

        sendEmbedToResults(embed);
    }

    /**
     * Embed estilo Stratus al terminar: muestra ELO inicial→final (delta) y link a la web.
     * SIN "pizarra" (sin ```), usa 2 fields inline.
     * @param publicMatchId  match_id público de 5 caracteres (BD2.matches.match_id). Si es null, no pone link.
     */
    public void matchEndedStratusStyle(ActiveMatch activeMatch,
                                       Team winnerTeam,
                                       Map<String, Integer> eloChanges,
                                       long durationSeconds,
                                       String publicMatchId) {
        if (activeMatch == null) return;
        if (winnerTeam == null) winnerTeam = Team.BLUE;

        int playerCount = 0;
        try { playerCount = activeMatch.getAllPlayers() != null ? activeMatch.getAllPlayers().size() : 0; } catch (Exception ignored) {}

        String queueLabel = toQueueLabel(activeMatch.getMatchType(), playerCount);
        String map = safe(activeMatch.getSelectedMap(), "Unknown");

        String statsUrl = null;
        if (publicMatchId != null && !publicMatchId.trim().isEmpty()) {
            statsUrl = STATS_BASE_URL + publicMatchId.trim();
        }

        Team loserTeam = (winnerTeam == Team.BLUE) ? Team.RED : Team.BLUE;

        String winnersLines = buildEndTeamLines(activeMatch.getTeams(), winnerTeam, eloChanges);
        String losersLines = buildEndTeamLines(activeMatch.getTeams(), loserTeam, eloChanges);

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("Match Ended – Keke Ranked");
        embed.setColor(Color.RED);

        StringBuilder desc = new StringBuilder();
        desc.append("**Mode** - ").append(queueLabel)
                .append(" | **Map** - ").append(map)
                .append(" | **Duration** - ").append(formatDuration(durationSeconds));

        if (statsUrl != null) {
            desc.append("\n").append("**Stats** - ").append(statsUrl);
        }

        embed.setDescription(desc.toString());

        // Columnas sin pizarra
        embed.addField("Winning Team", limitFieldValue(winnersLines), true);
        embed.addField("Losing Team", limitFieldValue(losersLines), true);

        // Capitanes (si es picks)
        try {
            if (activeMatch.isPicksMatch()) {
                String blueCap = (activeMatch.getBlueCaptain() != null) ? getPlayerDisplayName(activeMatch.getBlueCaptain()) : "N/A";
                String redCap = (activeMatch.getRedCaptain() != null) ? getPlayerDisplayName(activeMatch.getRedCaptain()) : "N/A";
                embed.addField("Captains", "🔵 " + shortName(blueCap) + " | 🔴 " + shortName(redCap), false);
            }
        } catch (Exception ignored) {}

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Keke Ranked (" + safe(activeMatch.getMatchId(), "?") + ")");

        sendEmbedToResults(embed);

        // Mantener update de nicknames (solo rankeds con eloChanges)
        try {
            if (eloChanges != null && !eloChanges.isEmpty()) {
                updateDiscordNicknames(eloChanges, activeMatch.getTeams());
            }
        } catch (Exception ignored) {}
    }

    // =========================================================
    //  DRAW (Empate) — SIN CODEBLOCK, EN 2 COLUMNAS INLINE
    // =========================================================

    public void matchDraw(String matchId,
                          String matchType,
                          String selectedMap,
                          java.util.Map<org.fabricioyv.match.Team, java.util.List<org.fabricioyv.model.PlayerData>> teams,
                          long durationSeconds) {
        try {
            int playerCount = 0;
            if (teams != null) {
                for (java.util.List<org.fabricioyv.model.PlayerData> list : teams.values()) {
                    if (list != null) playerCount += list.size();
                }
            }

            String queueLabel = toQueueLabel(matchType, playerCount);
            String map = (selectedMap != null && !selectedMap.trim().isEmpty()) ? selectedMap.trim() : "Unknown";

            // Link SOLO si matchId ya es el público de 5 chars (BD2.matches.match_id)
            String statsUrl = null;
            if (matchId != null && matchId.trim().length() == 5) {
                statsUrl = STATS_BASE_URL + matchId.trim();
            }

            String redLines = buildDrawTeamLines(teams, org.fabricioyv.match.Team.RED);
            String blueLines = buildDrawTeamLines(teams, org.fabricioyv.match.Team.BLUE);

            EmbedBuilder embed = new EmbedBuilder();
            embed.setTitle("Match Draw – Keke Ranked");
            embed.setColor(Color.GRAY);

            StringBuilder desc = new StringBuilder();
            desc.append("**Mode** - ").append(queueLabel)
                    .append(" | **Map** - ").append(map)
                    .append(" | **Duration** - ").append(formatDuration(durationSeconds));
            if (statsUrl != null) desc.append("\n").append("**Stats** - ").append(statsUrl);

            embed.setDescription(desc.toString());

            embed.addField("Red", limitFieldValue(redLines), true);
            embed.addField("Blue", limitFieldValue(blueLines), true);

            embed.setTimestamp(java.time.Instant.now());
            sendEmbedToResults(embed);
        } catch (Exception ignored) {}
    }

    private String buildDrawTeamLines(
            java.util.Map<org.fabricioyv.match.Team, java.util.List<org.fabricioyv.model.PlayerData>> teams,
            org.fabricioyv.match.Team team
    ) {
        if (teams == null) return "(empty)";
        java.util.List<org.fabricioyv.model.PlayerData> list = teams.get(team);
        if (list == null || list.isEmpty()) return "(empty)";

        StringBuilder sb = new StringBuilder();
        for (org.fabricioyv.model.PlayerData p : list) {
            if (p == null) continue;
            String name = shortName(getPlayerDisplayName(p));
            int elo = p.getElo();
            sb.append(name).append(" — ").append(elo).append("\n");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(empty)" : out;
    }

    // =========================================================
    //  LÍNEAS DE EQUIPO
    // =========================================================

    private String buildStartTeamLines(Map<Team, List<PlayerData>> teams, Team team) {
        if (teams == null) return "(empty)";
        List<PlayerData> list = teams.get(team);
        if (list == null || list.isEmpty()) return "(empty)";

        StringBuilder sb = new StringBuilder();
        for (PlayerData p : list) {
            if (p == null) continue;
            String name = shortName(getPlayerDisplayName(p));
            int elo = 0;
            try { elo = p.getElo(); } catch (Exception ignored) {}
            sb.append(name).append(" — ").append(elo).append("\n");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "(empty)" : out;
    }

    private String buildEndTeamLines(Map<Team, List<PlayerData>> teams, Team team, Map<String, Integer> eloChanges) {
        if (teams == null) return "(empty)";
        List<PlayerData> list = teams.get(team);
        if (list == null || list.isEmpty()) return "(empty)";

        StringBuilder sb = new StringBuilder();
        for (PlayerData p : list) {
            if (p == null) continue;

            String name = shortName(getPlayerDisplayName(p));

            int endElo = 0;
            try { endElo = p.getElo(); } catch (Exception ignored) {}

            int delta = 0;
            if (eloChanges != null) {
                try {
                    Integer d = eloChanges.get(p.getMinecraftUuid());
                    if (d != null) delta = d;
                } catch (Exception ignored) {}
            }

            int startElo = endElo - delta;

            // Placement info
            String placementSuffix = "";
            try {
                if (p.isInPlacement()) {
                    int played = p.getPlacementMatchesPlayed();
                    int req = PlayerData.getPlacementMatchesRequired();
                    placementSuffix = " [" + played + "/" + req + "]";
                }
            } catch (Exception ignored) {}

            sb.append(name)
                    .append(" — ")
                    .append(startElo)
                    .append(" → ")
                    .append(endElo)
                    .append(" (")
                    .append(delta >= 0 ? "+" : "")
                    .append(delta)
                    .append(")")
                    .append(placementSuffix)
                    .append("\n");
        }

        String out = sb.toString().trim();
        return out.isEmpty() ? "(empty)" : out;
    }

    private String toQueueLabel(String matchType, int playerCount) {
        // Por tamaño de jugadores (más fiable)
        if (playerCount == 4) return "2v2 Queue";
        if (playerCount == 10) return "Conquest Queue";
        if (playerCount == 16) return "8v8 Queue";

        if (matchType == null) return "Queue";
        String mt = matchType.trim().toLowerCase();
        if (mt.contains("2v2")) return "2v2 Queue";
        if (mt.contains("8v8")) return "8v8 Queue";
        if (mt.contains("5v5") || mt.contains("conquest")) return "Conquest Queue";
        return "Queue";
    }

    private String shortName(String name) {
        if (name == null) return "Player";
        String n = name.trim();
        if (n.length() <= MAX_NAME_CHARS) return n;
        return n.substring(0, MAX_NAME_CHARS - 1) + "…";
    }

    private String safe(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.isEmpty() ? def : t;
    }

    private String limitFieldValue(String s) {
        // Discord embed field value max = 1024 chars
        if (s == null || s.trim().isEmpty()) return "(empty)";
        String t = s.trim();
        if (t.length() <= 1024) return t;
        return t.substring(0, 1021) + "...";
    }

    // =========================================================
    //  EXISTING METHODS (matchComplete, logs, etc.)
    // =========================================================

    /**
     * Log detallado de finalización de partida con estadísticas completas (legacy)
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, long durationSeconds) {

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Partida Finalizada");
        embed.setColor(winnerTeam == Team.BLUE ? Color.BLUE : Color.RED);

        embed.addField("🆔 Match ID", matchId, true);
        embed.addField("⚔️ Tipo", matchType, true);
        embed.addField("🗺️ Mapa", mapName, true);
        embed.addField("👑 Ganador", winnerTeam.getFormattedName(), true);
        embed.addField("⏱️ Duración", formatDuration(durationSeconds), true);
        embed.addField("📊 Jugadores", String.valueOf(teams.values().stream().mapToInt(List::size).sum()), true);

        StringBuilder teamsInfo = new StringBuilder();
        int placementPlayersCount = 0;

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean isWinner = team == winnerTeam;

            teamsInfo.append(team == Team.BLUE ? "🔵" : "🔴")
                    .append(" **").append(team.getDisplayName()).append("**")
                    .append(isWinner ? " 🏆" : " 💔")
                    .append("\n");

            for (PlayerData player : players) {
                String playerName = getPlayerDisplayName(player);
                Integer eloChange = eloChanges.get(player.getMinecraftUuid());
                String statusText;

                if (player.isInPlacement()) {
                    placementPlayersCount++;
                    int matchesPlayed = player.getPlacementMatchesPlayed();
                    int totalRequired = PlayerData.getPlacementMatchesRequired();
                    statusText = String.format(" 🔍 [%d/%d Evaluación]", matchesPlayed, totalRequired);
                } else if (eloChange != null) {
                    statusText = " (" + (eloChange > 0 ? "+" : "") + eloChange + ")";
                } else {
                    statusText = " (Sin cambios)";
                }

                teamsInfo.append("• ").append(playerName).append(statusText).append("\n");
            }
            teamsInfo.append("\n");
        }

        embed.addField("👥 Equipos", teamsInfo.toString(), false);

        Map<String, Integer> rankedEloChanges = new HashMap<>();
        for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
            PlayerData player = findPlayerByUuid(teams, entry.getKey());
            if (player != null && !player.isInPlacement()) {
                rankedEloChanges.put(entry.getKey(), entry.getValue());
            }
        }

        int totalEloGained = rankedEloChanges.values().stream().filter(change -> change > 0).mapToInt(Integer::intValue).sum();
        int totalEloLost = Math.abs(rankedEloChanges.values().stream().filter(change -> change < 0).mapToInt(Integer::intValue).sum());

        embed.addField("📈 ELO Ganado", String.valueOf(totalEloGained), true);
        embed.addField("📉 ELO Perdido", String.valueOf(totalEloLost), true);
        embed.addField("⚖️ ELO Neto", "0", true);

        if (placementPlayersCount > 0) {
            embed.addField("🔍 En Evaluación", placementPlayersCount + " jugador" + (placementPlayersCount == 1 ? "" : "es") + " en período de evaluación", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Partida completada");

        sendEmbedToResults(embed);

        updateDiscordNicknames(eloChanges, teams);
    }

    /**
     * Log detallado de finalización de partida CON INFORMACIÓN DE CAPITANES (legacy)
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, long durationSeconds,
                              boolean isPicksMatch, PlayerData blueCaptain, PlayerData redCaptain) {

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Partida Finalizada");
        embed.setColor(winnerTeam == Team.BLUE ? Color.BLUE : Color.RED);

        embed.addField("🆔 Match ID", matchId, true);
        embed.addField("⚔️ Tipo", matchType, true);
        embed.addField("🗺️ Mapa", mapName, true);
        embed.addField("👑 Ganador", winnerTeam.getFormattedName(), true);
        embed.addField("⏱️ Duración", formatDuration(durationSeconds), true);
        embed.addField("📊 Jugadores", String.valueOf(teams.values().stream().mapToInt(List::size).sum()), true);

        String teamFormation = isPicksMatch ? "🎯 Sistema de Picks" : "⚖️ Balanceo Automático";
        embed.addField("🔄 Formación", teamFormation, true);

        if (isPicksMatch && blueCaptain != null && redCaptain != null) {
            String captainsInfo = String.format("🔵 **%s**\n🔴 **%s**", getPlayerDisplayName(blueCaptain), getPlayerDisplayName(redCaptain));
            embed.addField("👨‍✈️ Capitanes", captainsInfo, true);
        } else {
            embed.addField("", "", true);
        }

        StringBuilder teamsInfo = new StringBuilder();
        int placementPlayersCount = 0;

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean isWinner = team == winnerTeam;

            String teamHeader = (team == Team.BLUE ? "🔵" : "🔴") + " **" + team.getDisplayName() + "**";

            if (isPicksMatch) {
                PlayerData captain = team == Team.BLUE ? blueCaptain : redCaptain;
                if (captain != null) {
                    teamHeader += " (Cap: " + getPlayerDisplayName(captain) + ")";
                }
            }

            teamHeader += (isWinner ? " 🏆" : " 💔") + "\n";
            teamsInfo.append(teamHeader);

            for (PlayerData player : players) {
                String playerName = getPlayerDisplayName(player);
                Integer eloChange = eloChanges.get(player.getMinecraftUuid());

                String captainIndicator = "";
                if (isPicksMatch && ((team == Team.BLUE && player.equals(blueCaptain)) || (team == Team.RED && player.equals(redCaptain)))) {
                    captainIndicator = " 👨‍✈️";
                }

                String statusText;
                if (player.isInPlacement()) {
                    placementPlayersCount++;
                    int matchesPlayed = player.getPlacementMatchesPlayed();
                    int totalRequired = PlayerData.getPlacementMatchesRequired();
                    statusText = String.format(" 🔍 [%d/%d Evaluación]", matchesPlayed, totalRequired);
                } else if (eloChange != null) {
                    statusText = " (" + (eloChange > 0 ? "+" : "") + eloChange + ")";
                } else {
                    statusText = " (Sin cambios)";
                }

                teamsInfo.append("• ").append(playerName).append(captainIndicator).append(statusText).append("\n");
            }
            teamsInfo.append("\n");
        }

        embed.addField("👥 Equipos", teamsInfo.toString(), false);

        Map<String, Integer> rankedEloChanges = new HashMap<>();
        for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
            PlayerData player = findPlayerByUuid(teams, entry.getKey());
            if (player != null && !player.isInPlacement()) {
                rankedEloChanges.put(entry.getKey(), entry.getValue());
            }
        }

        int totalEloGained = rankedEloChanges.values().stream().filter(change -> change > 0).mapToInt(Integer::intValue).sum();
        int totalEloLost = Math.abs(rankedEloChanges.values().stream().filter(change -> change < 0).mapToInt(Integer::intValue).sum());

        embed.addField("📈 ELO Ganado", String.valueOf(totalEloGained), true);
        embed.addField("📉 ELO Perdido", String.valueOf(totalEloLost), true);
        embed.addField("⚖️ ELO Neto", "0", true);

        if (placementPlayersCount > 0) {
            embed.addField("🔍 En Evaluación", placementPlayersCount + " jugador" + (placementPlayersCount == 1 ? "" : "es") + " en período de evaluación", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Partida completada");

        sendEmbedToResults(embed);

        updateDiscordNicknames(eloChanges, teams);
    }

    /** Log específico para cambios de ELO (legacy) */
    public void eloChanges(String matchId, Map<String, Integer> eloChanges, Team winnerTeam) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📊 Cambios de ELO");
        embed.setColor(Color.ORANGE);
        embed.addField("🆔 Match ID", matchId, true);
        embed.addField("👑 Ganador", winnerTeam.getDiscordFormattedName(), true);

        StringBuilder winners = new StringBuilder();
        StringBuilder losers = new StringBuilder();

        for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
            String playerName = getPlayerNameByUuid(entry.getKey());
            int change = entry.getValue();

            if (change > 0) {
                winners.append("• ").append(playerName).append(" +").append(change).append("\n");
            } else {
                losers.append("• ").append(playerName).append(" ").append(change).append("\n");
            }
        }

        if (winners.length() > 0) embed.addField("📈 Ganadores", winners.toString(), true);
        if (losers.length() > 0) embed.addField("📉 Perdedores", losers.toString(), true);

        embed.setTimestamp(java.time.Instant.now());
        sendEmbedToResults(embed);
    }

    private void sendEmbedToResults(EmbedBuilder embed) {
        if (resultsChannel == null) {
            System.err.println("❌ No se puede enviar resultado: Canal de resultados no disponible");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                resultsChannel.sendMessageEmbeds(embed.build()).queue(
                        success -> {},
                        error -> System.err.println("❌ Error enviando resultado a Discord: " + error.getMessage())
                );
            } catch (Exception e) {
                System.err.println("❌ Error inesperado enviando resultado: " + e.getMessage());
            }
        });
    }

    private void sendEmbed(EmbedBuilder embed) {
        if (logsChannel == null) {
            System.err.println("❌ No se puede enviar log: Canal de logs no disponible");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                logsChannel.sendMessageEmbeds(embed.build()).queue(
                        success -> {},
                        error -> System.err.println("❌ Error enviando log a Discord: " + error.getMessage())
                );
            } catch (Exception e) {
                System.err.println("❌ Error inesperado enviando log: " + e.getMessage());
            }
        });
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return (minutes > 0)
                ? String.format("%dm %ds", minutes, remainingSeconds)
                : String.format("%ds", seconds);
    }

    private String getPlayerDisplayName(PlayerData player) {
        try {
            Player mcPlayer = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(player.getMinecraftUuid()));
            if (mcPlayer != null) return mcPlayer.getName();

            Guild guild = getPrimaryGuild();
            if (guild != null) {
                Member discordMember = guild.getMemberById(player.getDiscordId());
                if (discordMember != null) return discordMember.getEffectiveName();
            }
        } catch (Exception ignored) {}

        return "UUID:" + player.getMinecraftUuid().substring(0, 8);
    }

    private String getPlayerNameByUuid(String uuid) {
        try {
            Player mcPlayer = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(uuid));
            if (mcPlayer != null) return mcPlayer.getName();
        } catch (Exception ignored) {}

        if (uuid == null || uuid.length() < 8) return "UUID:unknown";
        return "UUID:" + uuid.substring(0, 8);
    }

    public void info(String title, String description) { sendLog(LogLevel.INFO, title, description, Color.BLUE); }
    public void warning(String title, String description) { sendLog(LogLevel.WARNING, title, description, Color.ORANGE); }
    public void error(String title, String description) { sendLog(LogLevel.ERROR, title, description, Color.RED); }
    public void success(String title, String description) { sendLog(LogLevel.SUCCESS, title, description, Color.GREEN); }

    public void matchEvent(String matchId, String event, String details, int playerCount) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("⚔️ Evento de Partida");
        embed.setColor(Color.MAGENTA);
        embed.addField("🆔 Match ID", matchId != null ? matchId : "N/A", true);
        embed.addField("📊 Jugadores", String.valueOf(playerCount), true);
        embed.addField("🎯 Evento", event, true);
        embed.addField("📝 Detalles", details, false);
        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    public void systemError(String component, String error, String stackTrace) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🚨 Error del Sistema");
        embed.setColor(Color.RED);
        embed.addField("🔧 Componente", component, true);
        embed.addField("❌ Error", error, false);

        if (stackTrace != null && !stackTrace.isEmpty()) {
            String truncatedStack = stackTrace.length() > 1000 ? stackTrace.substring(0, 1000) + "..." : stackTrace;
            embed.addField("📋 Stack Trace", "```\n" + truncatedStack + "\n```", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    public void systemStart() {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🚀 Sistema Iniciado");
        embed.setDescription("RankedMinecraft ha sido iniciado exitosamente");
        embed.setColor(Color.GREEN);
        embed.addField("⏰ Tiempo", DATE_FORMAT.format(LocalDateTime.now(PERU_ZONE)), true);
        embed.addField("🔧 Estado", "Operativo", true);
        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    private void sendLog(LogLevel level, String title, String description, Color color) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(level.getEmoji() + " " + title);
        embed.setDescription(description);
        embed.setColor(color);
        embed.addField("⏰ Hora", TIME_FORMAT.format(LocalDateTime.now(PERU_ZONE)), true);
        embed.addField("📊 Nivel", level.name(), true);
        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    /**
     * Actualiza apodos Discord:
     * Formato: [Rango] Nickmc
     */
    public void updateDiscordNicknames(Map<String, Integer> eloChanges, Map<Team, List<PlayerData>> teams) {
        if (eloChanges == null || teams == null || jda == null) return;

        Guild guild = getPrimaryGuild();
        if (guild == null) return;

        for (Map.Entry<Team, List<PlayerData>> teamEntry : teams.entrySet()) {
            List<PlayerData> players = teamEntry.getValue();
            if (players == null) continue;

            for (PlayerData player : players) {
                try {
                    if (player == null) continue;

                    // No renombrar placements
                    if (player.isInPlacement()) continue;

                    String discordId = player.getDiscordId();
                    if (discordId == null || discordId.isEmpty()) continue;

                    // Solo si participó / tiene delta
                    Integer eloChange = eloChanges.get(player.getMinecraftUuid());
                    if (eloChange == null) continue;

                    Member member = guild.getMemberById(discordId);
                    if (member == null) continue;

                    String minecraftName = getMinecraftPlayerName(player.getMinecraftUuid());
                    if (minecraftName == null || minecraftName.isEmpty()) continue;

                    int currentElo = player.getElo();

                    Rank rank = Rank.getRankByElo(currentElo);
                    String rankName = (rank != null) ? rank.getDisplayName() : "Cobre III";

                    String desiredNick = truncateDiscordNick(String.format("[%s] %s", rankName, minecraftName));

                    String currentNick = member.getNickname();
                    if (currentNick == null) currentNick = member.getEffectiveName();

                    if (desiredNick.equals(currentNick)) continue;

                    member.modifyNickname(desiredNick).queue(
                            ok -> {},
                            err -> warning("Error Actualizando Apodo",
                                    String.format("No se pudo actualizar apodo de %s: %s", minecraftName, err.getMessage()))
                    );

                } catch (Exception e) {
                    systemError("DiscordLogger", "Error actualizando apodo de jugador", e.getMessage());
                }
            }
        }
    }

    private String truncateDiscordNick(String nick) {
        if (nick == null) return null;
        return nick.length() > DISCORD_NICK_MAX ? nick.substring(0, DISCORD_NICK_MAX) : nick;
    }

    private String getMinecraftPlayerName(String minecraftUuid) {
        try {
            Player mcPlayer = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(minecraftUuid));
            if (mcPlayer != null) return mcPlayer.getName();
        } catch (Exception ignored) {}
        return null;
    }

    private Guild getPrimaryGuild() {
        try {
            return (jda != null && !jda.getGuilds().isEmpty()) ? jda.getGuilds().get(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private PlayerData findPlayerByUuid(Map<Team, List<PlayerData>> teams, String uuid) {
        if (teams == null || uuid == null) return null;

        for (List<PlayerData> teamPlayers : teams.values()) {
            if (teamPlayers == null) continue;

            for (PlayerData player : teamPlayers) {
                if (player != null && uuid.equals(player.getMinecraftUuid())) {
                    return player;
                }
            }
        }
        return null;
    }

    public enum LogLevel {
        INFO("ℹ️"),
        WARNING("⚠️"),
        ERROR("❌"),
        SUCCESS("✅");

        private final String emoji;

        LogLevel(String emoji) {
            this.emoji = emoji;
        }

        public String getEmoji() {
            return emoji;
        }
    }

    public void debug(String title, String description) {
        // Debug silencioso: a consola para no spamear Discord
        try {
            org.bukkit.Bukkit.getConsoleSender().sendMessage("[DEBUG] " + title + " | " + description);
        } catch (Exception ignored) {}
    }

    // =========================================================
    //  COMPAT METHODS (para que compile con el resto del proyecto)
    // =========================================================

    /** Compat: usado en CaptainPickSystem, MatchManager, etc. */
    public void logError(String message, Exception ex) {
        logError(message, (Throwable) ex);
    }

    /** Compat: usado en varios listeners */
    public void logError(String message, Throwable t) {
        if (t == null) {
            error("Error", message != null ? message : "Error desconocido");
            return;
        }
        String component = "Runtime";
        String err = (message != null ? message : "Error") + " | " + t.getClass().getSimpleName() + ": " + safe(t.getMessage(), "sin mensaje");
        systemError(component, err, toStackTrace(t));
    }

    /**
     * Compat: usado en VoiceChannelListener.
     * Firma esperada: queueEvent(String, String, String, String)
     */
    public void queueEvent(String type, String event, String actor, String details) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📥 Queue Event");
        embed.setColor(Color.CYAN);

        embed.addField("Tipo", safe(type, "N/A"), true);
        embed.addField("Evento", safe(event, "N/A"), true);
        embed.addField("Actor", safe(actor, "N/A"), true);

        if (details != null && !details.trim().isEmpty()) {
            // Evita exceder límites feos
            String d = details.length() > 1500 ? details.substring(0, 1500) + "..." : details;
            embed.addField("Detalles", d, false);
        }

        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed); // logsChannel
    }

    private String toStackTrace(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String out = sw.toString();
            return out.length() > 2000 ? out.substring(0, 2000) + "..." : out;
        } catch (Exception e) {
            return "No se pudo generar stacktrace: " + e.getMessage();
        }
    }
}