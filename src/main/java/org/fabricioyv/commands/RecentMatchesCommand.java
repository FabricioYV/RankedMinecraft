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
import java.util.List;

/**
 * Comando para obtener lista de partidas recientes
 */
public class RecentMatchesCommand extends ListenerAdapter {

    /**
     * Método estático para obtener la definición del comando slash
     */
    public static CommandData getSlashCommand() {
        return Commands.slash("recentmatches", "Muestra las partidas recientes")
                .addOption(OptionType.INTEGER, "limit", "Número de partidas a mostrar (máximo 20)", false);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("recentmatches")) return;

        event.deferReply().queue();

        // Obtener número de partidas (por defecto 10, máximo 20)
        int limit = 10; // valor por defecto
        if (event.getOption("limit") != null) {
            limit = Math.min(event.getOption("limit").getAsInt(), 20);
        }

        MatchLogsManager.getRecentMatches(limit).thenAccept(matches -> {
            if (matches.isEmpty()) {
                event.getHook().editOriginal("❌ No se encontraron partidas recientes.").queue();
                return;
            }

            EmbedBuilder embed = new EmbedBuilder()
                .setTitle("🏆 Partidas Recientes")
                .setDescription("Últimas " + matches.size() + " partidas jugadas")
                .setColor(Color.BLUE)
                .setFooter("Usa /matchdetails <match_id> para ver detalles completos");

            for (int i = 0; i < matches.size(); i++) {
                MatchLogsManager.MatchBasicInfo match = matches.get(i);

                String matchInfo = String.format(
                    "**ID:** `%s`\n" +
                    "**Tipo:** %s\n" +
                    "**Mapa:** %s\n" +
                    "**Ganador:** %s\n" +
                    "**Fecha:** %s\n" +
                    "**Duración:** %s",
                    match.getMatchId(), // Mostrar ID completo
                    match.getMatchType(),
                    match.getMapName(),
                    getWinnerEmoji(match.getWinnerTeam()) + " " + match.getWinnerTeam(),
                    match.getStartTime().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")),
                    formatDuration(match.getDurationSeconds())
                );

                embed.addField(
                    String.format("#%d - %s", i + 1, match.getMatchId()), // Mostrar ID completo en título
                    matchInfo,
                    false // Cambiar a false para mejor legibilidad con IDs largos
                );

                // Agregar separador entre partidas para mejor formato
                if (i < matches.size() - 1) {
                    embed.addBlankField(false);
                }
            }

            event.getHook().editOriginalEmbeds(embed.build()).queue();
        }).exceptionally(throwable -> {
            event.getHook().editOriginal("❌ Error obteniendo partidas recientes: " + throwable.getMessage()).queue();
            throwable.printStackTrace();
            return null;
        });
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
