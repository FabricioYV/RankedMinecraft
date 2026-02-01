package org.fabricioyv.logging;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.entity.Player;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;
import org.fabricioyv.rating.Rank;

import java.awt.*;
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

    /**
     * Log detallado de finalización de partida con estadísticas completas
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, long durationSeconds) {

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Partida Finalizada");
        embed.setColor(winnerTeam == Team.BLUE ? Color.BLUE : Color.RED);

        // Información básica
        embed.addField("🆔 Match ID", matchId, true);
        embed.addField("⚔️ Tipo", matchType, true);
        embed.addField("🗺️ Mapa", mapName, true);
        embed.addField("👑 Ganador", winnerTeam.getFormattedName(), true);
        embed.addField("⏱️ Duración", formatDuration(durationSeconds), true);
        embed.addField("📊 Jugadores", String.valueOf(teams.values().stream().mapToInt(List::size).sum()), true);

        // Equipos y estadísticas
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

                // Placement
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

        // Estadísticas de ELO (solo NO-placement)
        Map<String, Integer> rankedEloChanges = new HashMap<>();
        for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
            PlayerData player = findPlayerByUuid(teams, entry.getKey());
            if (player != null && !player.isInPlacement()) {
                rankedEloChanges.put(entry.getKey(), entry.getValue());
            }
        }

        int totalEloGained = rankedEloChanges.values().stream()
                .filter(change -> change > 0)
                .mapToInt(Integer::intValue)
                .sum();

        int totalEloLost = Math.abs(rankedEloChanges.values().stream()
                .filter(change -> change < 0)
                .mapToInt(Integer::intValue)
                .sum());

        embed.addField("📈 ELO Ganado", String.valueOf(totalEloGained), true);
        embed.addField("📉 ELO Perdido", String.valueOf(totalEloLost), true);
        embed.addField("⚖️ ELO Neto", "0", true);

        if (placementPlayersCount > 0) {
            embed.addField("🔍 En Evaluación",
                    placementPlayersCount + " jugador" + (placementPlayersCount == 1 ? "" : "es") +
                            " en período de evaluación", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Partida completada");

        sendEmbedToResults(embed);

        updateDiscordNicknames(eloChanges, teams);
    }

    /**
     * Log detallado de finalización de partida CON INFORMACIÓN DE CAPITANES
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, long durationSeconds,
                              boolean isPicksMatch, PlayerData blueCaptain, PlayerData redCaptain) {

        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Partida Finalizada");
        embed.setColor(winnerTeam == Team.BLUE ? Color.BLUE : Color.RED);

        // Información básica
        embed.addField("🆔 Match ID", matchId, true);
        embed.addField("⚔️ Tipo", matchType, true);
        embed.addField("🗺️ Mapa", mapName, true);
        embed.addField("👑 Ganador", winnerTeam.getFormattedName(), true);
        embed.addField("⏱️ Duración", formatDuration(durationSeconds), true);
        embed.addField("📊 Jugadores", String.valueOf(teams.values().stream().mapToInt(List::size).sum()), true);

        String teamFormation = isPicksMatch ? "🎯 Sistema de Picks" : "⚖️ Balanceo Automático";
        embed.addField("🔄 Formación", teamFormation, true);

        if (isPicksMatch && blueCaptain != null && redCaptain != null) {
            String captainsInfo = String.format("🔵 **%s**\n🔴 **%s**",
                    getPlayerDisplayName(blueCaptain),
                    getPlayerDisplayName(redCaptain));
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
                if (isPicksMatch && ((team == Team.BLUE && player.equals(blueCaptain)) ||
                        (team == Team.RED && player.equals(redCaptain)))) {
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

        // Estadísticas de ELO (solo NO-placement)
        Map<String, Integer> rankedEloChanges = new HashMap<>();
        for (Map.Entry<String, Integer> entry : eloChanges.entrySet()) {
            PlayerData player = findPlayerByUuid(teams, entry.getKey());
            if (player != null && !player.isInPlacement()) {
                rankedEloChanges.put(entry.getKey(), entry.getValue());
            }
        }

        int totalEloGained = rankedEloChanges.values().stream()
                .filter(change -> change > 0)
                .mapToInt(Integer::intValue)
                .sum();

        int totalEloLost = Math.abs(rankedEloChanges.values().stream()
                .filter(change -> change < 0)
                .mapToInt(Integer::intValue)
                .sum());

        embed.addField("📈 ELO Ganado", String.valueOf(totalEloGained), true);
        embed.addField("📉 ELO Perdido", String.valueOf(totalEloLost), true);
        embed.addField("⚖️ ELO Neto", "0", true);

        if (placementPlayersCount > 0) {
            embed.addField("🔍 En Evaluación",
                    placementPlayersCount + " jugador" + (placementPlayersCount == 1 ? "" : "es") +
                            " en período de evaluación", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        embed.setFooter("Partida completada");

        sendEmbedToResults(embed);

        updateDiscordNicknames(eloChanges, teams);
    }

    /**
     * Log específico para cambios de ELO
     */
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

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return (minutes > 0) ? String.format("%dm %ds", minutes, remainingSeconds) : String.format("%ds", seconds);
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
        return "UUID:" + uuid.substring(0, 8);
    }

    public void info(String title, String description) { sendLog(LogLevel.INFO, title, description, Color.BLUE); }
    public void warning(String title, String description) { sendLog(LogLevel.WARNING, title, description, Color.ORANGE); }
    public void error(String title, String description) { sendLog(LogLevel.ERROR, title, description, Color.RED); }
    public void success(String title, String description) { sendLog(LogLevel.SUCCESS, title, description, Color.GREEN); }

    public void queueEvent(String playerName, String discordId, String action, String details) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🎮 Evento de Cola");
        embed.setColor(Color.CYAN);
        embed.addField("👤 Jugador", playerName, true);
        embed.addField("🔗 Discord ID", discordId, true);
        embed.addField("⚡ Acción", action, true);
        embed.addField("📝 Detalles", details, false);
        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

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

                    // Rango real desde tu enum
                    Rank rank = Rank.getRankByElo(currentElo);
                    String rankName = (rank != null) ? rank.getDisplayName() : "Cobre III";

                    String desiredNick = truncateDiscordNick(String.format("[%s] %s", rankName, minecraftName));

                    String currentNick = member.getNickname();
                    if (currentNick == null) currentNick = member.getEffectiveName();

                    if (desiredNick.equals(currentNick)) continue;

                    member.modifyNickname(desiredNick).queue(
                            ok -> {}, // sin spam de logs
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

    public void debug(String balanceDetallado, String balanceReport) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🔍 Debug Detalles");
        embed.setColor(Color.GRAY);
        embed.addField("📝 Balance Detallado", "```\n" + balanceDetallado + "\n```", false);
        embed.addField("📊 Balance Report", "```\n" + balanceReport + "\n```", false);
        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    public void matchDraw(String matchId, String matchType, String selectedMap, Map<Team, List<PlayerData>> teams, long durationSeconds) {
        // pendiente si quieres implementar
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

    public void logError(String message, Throwable throwable) {
        String errorDetails = message != null ? message : "Error";

        if (throwable != null) {
            errorDetails += "\nError: " + throwable.getMessage();

            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTrace = sw.toString();

            if (stackTrace.length() > 800) {
                stackTrace = stackTrace.substring(0, 800) + "...";
            }

            errorDetails += "\nStack Trace:\n```\n" + stackTrace + "\n```";
        }

        error("Error del Sistema", errorDetails);
    }
}