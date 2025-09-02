package org.fabricioyv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.fabricioyv.database.MatchLogsManager;
import org.fabricioyv.database.MatchLogsManager.MatchSummary;

import java.time.format.DateTimeFormatter;
import java.util.List;

public class ListMatchesCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("listmatches")) {
            return;
        }

        List<MatchSummary> matches = MatchLogsManager.getRecentMatches();
        if (matches.isEmpty()) {
            event.reply("No hay partidas recientes disponibles.").queue();
            return;
        }

        StringBuilder response = new StringBuilder("**Partidas recientes:**\n");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (MatchSummary match : matches) {
            response.append(String.format("ID: %s | Fecha: %s\n", match.matchId, match.startTime.format(formatter)));
        }

        event.reply(response.toString()).queue();
    }
}
