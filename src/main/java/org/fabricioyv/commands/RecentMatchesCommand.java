package org.fabricioyv.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.bukkit.Bukkit;
import org.fabricioyv.database.MatchLogsManager;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

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
            limit = Math.min(Objects.requireNonNull(event.getOption("limit")).getAsInt(), 20);
        }

        MatchLogsManager.getRecentMatches(limit).thenAccept(matches -> {
            if (matches.isEmpty()) {
                event.getHook().editOriginal("❌ No se encontraron partidas recientes.").queue();
                return;
            }

            // Usamos StringBuilder para armar un mensaje de texto normal (sin Embed)
            StringBuilder sb = new StringBuilder();
            sb.append("🏆 **Partidas Recientes**\n\n");

            for (int i = 0; i < matches.size(); i++) {
                MatchLogsManager.MatchSummary match = matches.get(i);

                String winner = getWinnerEmoji(match.getWinnerTeam()) + " " + match.getWinnerTeam();
                String duration = formatDuration(match.getDurationSeconds());

                // Resolver el ID público de 5 caracteres para generar el link
                String publicId = MatchLogsManager.resolvePublicMatchId(
                        match.getMapName(),
                        match.getWinnerTeam(),
                        match.getDurationSeconds()
                );

                String linkStats = (publicId != null)
                        ? "https://kekelive.netlify.app/match/" + publicId
                        : "*(Procesando link...)*";

                // Formato textual simple
                sb.append(String.format("**#%d** | %s | 🗺️ %s | 👑 %s | ⏱️ %s\n",
                        i + 1,
                        match.getMatchType(),
                        match.getMapName(),
                        winner,
                        duration
                ));
                sb.append("📊 Stats: ").append(linkStats).append("\n\n");
            }

            // Enviamos el mensaje como texto plano usando editOriginal() en lugar de editOriginalEmbeds()
            event.getHook().editOriginal(sb.toString().trim()).queue();

        }).exceptionally(throwable -> {
            event.getHook().editOriginal("❌ Error obteniendo partidas recientes: " + throwable.getMessage()).queue();
            Bukkit.getLogger().severe("Error obteniendo partidas recientes: " + throwable.getMessage());
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
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }
}