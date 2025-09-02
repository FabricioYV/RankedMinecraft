package org.fabricioyv.commands;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.fabricioyv.database.MatchLogsManager;
import org.fabricioyv.database.MatchLogsManager.MatchSummary;

public class MatchDetailsCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("match")) {
            return;
        }

        String matchId = event.getOption("match_id") != null ? event.getOption("match_id").getAsString() : "";
        if (matchId.isEmpty()) {
            event.reply("El ID de la partida no fue proporcionado o es inválido.").queue();
            return;
        }

        MatchSummary match = MatchLogsManager.MatchSummary.getMatchDetails(matchId);

        if (match == null) {
            event.reply("No se encontró información para el ID de partida proporcionado.").queue();
            return;
        }

        String response = """
            **Detalles de la partida:**
            ID: %s
            Mapa: %s
            Ganador: %s
            Duración: %d segundos
            Inicio: %s
            Fin: %s
            Jugadores: %d
            MVP: %s
            Top Kills: %s
            Top Daño: %s
            Top Precisión de Flechas: %s
            """.formatted(
            match.matchId,
            match.mapName,
            match.winnerTeam,
            match.durationSeconds,
            match.startTime,
            match.endTime,
            match.playerCount,
            match.mvpPlayer,
            match.topKillsPlayer,
            match.topDamagePlayer,
            match.topArrowAccuracyPlayer
        );

        event.reply(response).queue();
    }
}
