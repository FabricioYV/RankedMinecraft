package org.fabricioyv.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.fabricioyv.database.MatchLogsManager;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Comando para obtener detalles completos de una partida específica
 */
public class MatchDetailsCommand extends ListenerAdapter {

    /**
     * Método estático para obtener la definición del comando slash
     */
    public static CommandData getSlashCommand() {
        return Commands.slash("matchdetails", "Muestra detalles completos de una partida específica")
                .addOption(OptionType.STRING, "match_id", "ID de la partida a consultar", true);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("matchdetails")) return;

        if (event.getOption("match_id") == null) {
            event.reply("❌ Debes proporcionar un ID de partida.").setEphemeral(true).queue();
            return;
        }

        String matchId = event.getOption("match_id").getAsString();
        event.deferReply().queue();

        MatchLogsManager.getMatchDetails(matchId).thenAccept(matchSummary -> {
            if (matchSummary == null) {
                event.getHook().editOriginal("❌ No se encontró una partida con ID: `" + matchId + "`").queue();
                return;
            }

            // Crear embed principal con información de la partida
            EmbedBuilder mainEmbed = createMainMatchEmbed(matchSummary);

            // Crear embed con estadísticas de jugadores
            EmbedBuilder statsEmbed = createPlayerStatsEmbed(matchSummary);

            // Crear embed con top performers
            EmbedBuilder topEmbed = createTopPerformersEmbed(matchSummary);

            event.getHook().editOriginalEmbeds(
                mainEmbed.build(),
                statsEmbed.build(),
                topEmbed.build()
            ).queue();

        }).exceptionally(throwable -> {
            event.getHook().editOriginal("❌ Error obteniendo detalles de la partida: " + throwable.getMessage()).queue();
            throwable.printStackTrace();
            return null;
        });
    }

    private EmbedBuilder createMainMatchEmbed(MatchLogsManager.MatchSummary match) {
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("🏆 Detalles de Partida")
            .setColor(Color.GREEN)
            .addField("🆔 Match ID", "`" + match.getMatchId() + "`", true)
            .addField("🎮 Tipo", match.getMatchType(), true)
            .addField("🗺️ Mapa", match.getMapName(), true)
            .addField("🏆 Ganador", getWinnerEmoji(match.getWinnerTeam()) + " **" + match.getWinnerTeam() + "**", true)
            .addField("⏰ Inicio", match.getStartTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")), true)
            .addField("⏱️ Duración", formatDuration(match.getDurationSeconds()), true);

        // Agregar información de equipos
        Map<String, MatchLogsManager.PlayerMatchStats> playerStats = match.getPlayerStats();
        long blueTeamCount = playerStats.values().stream()
            .filter(p -> "BLUE".equalsIgnoreCase(p.getTeam()) || "AZUL".equalsIgnoreCase(p.getTeam()))
            .count();
        long redTeamCount = playerStats.values().stream()
            .filter(p -> "RED".equalsIgnoreCase(p.getTeam()) || "ROJO".equalsIgnoreCase(p.getTeam()))
            .count();

        embed.addField("👥 Jugadores",
            String.format("🔵 Azul: %d | 🔴 Rojo: %d", blueTeamCount, redTeamCount), true);

        return embed;
    }

    private EmbedBuilder createPlayerStatsEmbed(MatchLogsManager.MatchSummary match) {
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("📊 Estadísticas de Jugadores")
            .setColor(Color.ORANGE);

        Map<String, MatchLogsManager.PlayerMatchStats> playerStats = match.getPlayerStats();

        // Separar por equipos
        List<MatchLogsManager.PlayerMatchStats> blueTeam = new ArrayList<>();
        List<MatchLogsManager.PlayerMatchStats> redTeam = new ArrayList<>();

        for (MatchLogsManager.PlayerMatchStats stats : playerStats.values()) {
            if ("BLUE".equalsIgnoreCase(stats.getTeam()) || "AZUL".equalsIgnoreCase(stats.getTeam())) {
                blueTeam.add(stats);
            } else {
                redTeam.add(stats);
            }
        }

        // Ordenar por kills descendente
        blueTeam.sort((a, b) -> Integer.compare(b.getKills(), a.getKills()));
        redTeam.sort((a, b) -> Integer.compare(b.getKills(), a.getKills()));

        // Agregar estadísticas del equipo azul
        StringBuilder blueStats = new StringBuilder();
        for (MatchLogsManager.PlayerMatchStats stats : blueTeam) {
            blueStats.append(String.format(
                "**%s** %s\n" +
                "🗡️ %d K | 💀 %d D | 🏹 %.1f%% acc\n" +
                "📈 %+d ELO (%.1f → %.1f MMR)\n\n",
                stats.getPlayerName(),
                stats.isWon() ? "🏆" : "💔",
                stats.getKills(),
                stats.getDeaths(),
                stats.getArrowAccuracy(),
                stats.getEloChange(),
                stats.getOldMmr(),
                stats.getNewMmr()
            ));
        }

        // Agregar estadísticas del equipo rojo
        StringBuilder redStats = new StringBuilder();
        for (MatchLogsManager.PlayerMatchStats stats : redTeam) {
            redStats.append(String.format(
                "**%s** %s\n" +
                "🗡️ %d K | 💀 %d D | 🏹 %.1f%% acc\n" +
                "📈 %+d ELO (%.1f → %.1f MMR)\n\n",
                stats.getPlayerName(),
                stats.isWon() ? "🏆" : "💔",
                stats.getKills(),
                stats.getDeaths(),
                stats.getArrowAccuracy(),
                stats.getEloChange(),
                stats.getOldMmr(),
                stats.getNewMmr()
            ));
        }

        if (blueStats.length() > 0) {
            embed.addField("🔵 Equipo Azul", blueStats.toString(), true);
        }

        if (redStats.length() > 0) {
            embed.addField("🔴 Equipo Rojo", redStats.toString(), true);
        }

        return embed;
    }

    private EmbedBuilder createTopPerformersEmbed(MatchLogsManager.MatchSummary match) {
        EmbedBuilder embed = new EmbedBuilder()
            .setTitle("🏅 Top Performers")
            .setColor(Color.YELLOW);

        List<MatchLogsManager.PlayerMatchStats> allPlayers = new ArrayList<>(match.getPlayerStats().values());

        // Top 3 Kills
        List<MatchLogsManager.PlayerMatchStats> topKills = allPlayers.stream()
            .sorted((a, b) -> Integer.compare(b.getKills(), a.getKills()))
            .limit(3)
            .toList();

        StringBuilder killsLeaderboard = new StringBuilder();
        for (int i = 0; i < topKills.size(); i++) {
            MatchLogsManager.PlayerMatchStats stats = topKills.get(i);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : "🥉";
            killsLeaderboard.append(String.format("%s **%s**: %d kills\n",
                medal, stats.getPlayerName(), stats.getKills()));
        }
        embed.addField("🗡️ Más Kills", killsLeaderboard.toString(), true);

        // Top 3 Damage Dealt
        List<MatchLogsManager.PlayerMatchStats> topDamage = allPlayers.stream()
            .sorted((a, b) -> Double.compare(b.getDamageDealt(), a.getDamageDealt()))
            .limit(3)
            .toList();

        StringBuilder damageLeaderboard = new StringBuilder();
        for (int i = 0; i < topDamage.size(); i++) {
            MatchLogsManager.PlayerMatchStats stats = topDamage.get(i);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : "🥉";
            damageLeaderboard.append(String.format("%s **%s**: %.1f dmg\n",
                medal, stats.getPlayerName(), stats.getDamageDealt()));
        }
        embed.addField("⚔️ Más Daño", damageLeaderboard.toString(), true);

        // Top 3 Arrow Accuracy (minimum 5 arrows shot)
        List<MatchLogsManager.PlayerMatchStats> topAccuracy = allPlayers.stream()
            .filter(stats -> stats.getArrowsShot() >= 5) // Mínimo 5 flechas disparadas
            .sorted((a, b) -> Double.compare(b.getArrowAccuracy(), a.getArrowAccuracy()))
            .limit(3)
            .toList();

        StringBuilder accuracyLeaderboard = new StringBuilder();
        for (int i = 0; i < topAccuracy.size(); i++) {
            MatchLogsManager.PlayerMatchStats stats = topAccuracy.get(i);
            String medal = i == 0 ? "🥇" : i == 1 ? "🥈" : "🥉";
            accuracyLeaderboard.append(String.format("%s **%s**: %.1f%% (%d/%d)\n",
                medal, stats.getPlayerName(), stats.getArrowAccuracy(),
                stats.getArrowsHit(), stats.getArrowsShot()));
        }
        embed.addField("🏹 Mejor Puntería", accuracyLeaderboard.toString(), true);

        // Estadísticas generales de la partida
        int totalKills = allPlayers.stream().mapToInt(MatchLogsManager.PlayerMatchStats::getKills).sum();
        double totalDamage = allPlayers.stream().mapToDouble(MatchLogsManager.PlayerMatchStats::getDamageDealt).sum();
        int totalArrows = allPlayers.stream().mapToInt(MatchLogsManager.PlayerMatchStats::getArrowsShot).sum();
        int totalHits = allPlayers.stream().mapToInt(MatchLogsManager.PlayerMatchStats::getArrowsHit).sum();
        double overallAccuracy = totalArrows > 0 ? ((double) totalHits / totalArrows) * 100 : 0;

        embed.addField("📈 Estadísticas Generales",
            String.format("**Total Kills:** %d\n**Total Daño:** %.1f\n**Precisión General:** %.1f%% (%d/%d)",
                totalKills, totalDamage, overallAccuracy, totalHits, totalArrows), false);

        return embed;
    }

    private String getWinnerEmoji(String team) {
        return switch (team.toLowerCase()) {
            case "blue", "azul" -> "🔵";
            case "red", "rojo" -> "🔴";
            default -> "⚪";
        };
    }

    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        if (minutes >= 60) {
            long hours = minutes / 60;
            minutes = minutes % 60;
            return String.format("%dh %dm %ds", hours, minutes, remainingSeconds);
        } else {
            return String.format("%dm %ds", minutes, remainingSeconds);
        }
    }
}
