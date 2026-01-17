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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Comando para obtener detalles completos de una partida específica
 */
public class MatchDetailsCommand extends ListenerAdapter {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

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

        // Usar Optional para manejo seguro de null
        String matchId = Optional.ofNullable(event.getOption("match_id"))
                .map(option -> option.getAsString())
                .filter(id -> !id.trim().isEmpty())
                .orElse(null);

        if (matchId == null) {
            event.reply("❌ Debes proporcionar un ID de partida válido.").setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        // Crear un CompletableFuture mock para obtener detalles de la partida
        getMatchDetails(matchId).thenAccept(matchSummary -> {
            if (matchSummary == null) {
                event.getHook().editOriginal("❌ No se encontró una partida con ID: `" + matchId + "`").queue();
                return;
            }

            try {
                // Crear embed principal con información de la partida
                EmbedBuilder mainEmbed = createMainMatchEmbed(matchSummary);

                // Crear embed con estadísticas de jugadores
                EmbedBuilder statsEmbed = createPlayerStatsEmbed(matchSummary);

                // Crear embed con top performers
                EmbedBuilder topEmbed = createTopPerformersEmbed(matchSummary);

                // Enviar todos los embeds
                event.getHook().editOriginalEmbeds(
                        mainEmbed.build(),
                        statsEmbed.build(),
                        topEmbed.build()
                ).queue();

            } catch (Exception e) {
                event.getHook().editOriginal("❌ Error creando detalles de la partida: " + e.getMessage()).queue();
            }

        }).exceptionally(throwable -> {
            event.getHook().editOriginal("❌ Error obteniendo detalles de la partida: " + throwable.getMessage()).queue();
            return null;
        });
    }

    /**
     * Obtiene los detalles de una partida (método temporal hasta que se implemente en MatchLogsManager)
     */
    private CompletableFuture<MatchLogsManager.MatchSummary> getMatchDetails(String matchId) {
        return CompletableFuture.supplyAsync(() -> {
            // TODO: Implementar método getMatchDetails en MatchLogsManager
            // Por ahora retornamos null para evitar errores de compilación
            // El matchId se usará cuando se implemente la funcionalidad real
            System.out.println("Buscando detalles para partida: " + matchId);
            return null;
        });
    }

    /**
     * Crea el embed principal con información de la partida
     */
    private EmbedBuilder createMainMatchEmbed(MatchLogsManager.MatchSummary matchSummary) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🏆 Detalles de Partida: " + matchSummary.getMatchId());
        embed.setColor(matchSummary.isCompleted() ? Color.GREEN : Color.ORANGE);

        // Información básica
        embed.addField("📅 Fecha de Inicio",
                matchSummary.getStartTime().format(DATE_FORMATTER), true);

        if (matchSummary.getEndTime() != null) {
            embed.addField("📅 Fecha de Fin",
                    matchSummary.getEndTime().format(DATE_FORMATTER), true);
        }

        embed.addField("⏱️ Duración",
                formatDuration(matchSummary.getDurationSeconds()), true);

        embed.addField("🗺️ Mapa", matchSummary.getMapName(), true);
        embed.addField("🎮 Tipo", matchSummary.getMatchType(), true);

        if (matchSummary.isCompleted()) {
            embed.addField("🥇 Ganador", getWinnerDisplay(matchSummary.getWinnerTeam()), true);
        } else {
            embed.addField("📊 Estado", "En progreso", true);
        }

        embed.setFooter("Partida ID: " + matchSummary.getMatchId());
        embed.setTimestamp(matchSummary.getStartTime());

        return embed;
    }

    /**
     * Crea el embed con estadísticas de jugadores por equipos
     */
    private EmbedBuilder createPlayerStatsEmbed(MatchLogsManager.MatchSummary matchSummary) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("📊 Estadísticas de Jugadores");
        embed.setColor(Color.CYAN);

        StringBuilder blueStats = new StringBuilder();
        StringBuilder redStats = new StringBuilder();

        for (MatchLogsManager.PlayerMatchStats playerStats : matchSummary.getPlayerStats().values()) {
            String playerLine = """
                **%s** %s
                K/D: %d/%d | Daño: %.1f | Precisión: %.1f%%
                """.formatted(
                    playerStats.getPlayerName(),
                    playerStats.isWon() ? "🥇" : "🥈",
                    playerStats.getKills(),
                    playerStats.getDeaths(),
                    playerStats.getDamageDealt(),
                    playerStats.getArrowAccuracy()
            );

            if ("blue".equalsIgnoreCase(playerStats.getTeam()) || "azul".equalsIgnoreCase(playerStats.getTeam())) {
                blueStats.append(playerLine);
            } else if ("red".equalsIgnoreCase(playerStats.getTeam()) || "rojo".equalsIgnoreCase(playerStats.getTeam())) {
                redStats.append(playerLine);
            }
        }

        if (!blueStats.isEmpty()) {
            embed.addField("🔵 Equipo Azul", blueStats.toString(), false);
        }

        if (!redStats.isEmpty()) {
            embed.addField("🔴 Equipo Rojo", redStats.toString(), false);
        }

        return embed;
    }

    /**
     * Crea el embed con los mejores performers de la partida
     */
    private EmbedBuilder createTopPerformersEmbed(MatchLogsManager.MatchSummary matchSummary) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🌟 Mejores Jugadores");
        embed.setColor(new Color(255, 215, 0)); // Gold color as RGB

        List<MatchLogsManager.PlayerMatchStats> allPlayers = new ArrayList<>(matchSummary.getPlayerStats().values());

        // Top Killer
        allPlayers.stream()
                .max(Comparator.comparingInt(MatchLogsManager.PlayerMatchStats::getKills))
                .ifPresent(player -> embed.addField("⚔️ Más Kills",
                        String.format("%s - %d kills", player.getPlayerName(), player.getKills()), true));

        // Menos muertes
        allPlayers.stream()
                .min(Comparator.comparingInt(MatchLogsManager.PlayerMatchStats::getDeaths))
                .ifPresent(player -> embed.addField("🛡️ Menos Muertes",
                        String.format("%s - %d muertes", player.getPlayerName(), player.getDeaths()), true));

        // Más daño
        allPlayers.stream()
                .max(Comparator.comparingDouble(MatchLogsManager.PlayerMatchStats::getDamageDealt))
                .ifPresent(player -> embed.addField("💥 Más Daño",
                        String.format("%s - %.1f daño", player.getPlayerName(), player.getDamageDealt()), true));

        // Mejor precisión (solo si disparó flechas)
        allPlayers.stream()
                .filter(p -> p.getArrowsShot() > 0)
                .max(Comparator.comparingDouble(MatchLogsManager.PlayerMatchStats::getArrowAccuracy))
                .ifPresent(player -> embed.addField("🎯 Mejor Precisión",
                        String.format("%s - %.1f%% (%d/%d)",
                                player.getPlayerName(),
                                player.getArrowAccuracy(),
                                player.getArrowsHit(),
                                player.getArrowsShot()), true));

        // MVP (más kills + menos muertes)
        allPlayers.stream()
                .max(Comparator.comparingDouble(p -> (double) p.getKills() - (double) p.getDeaths() * 0.5))
                .ifPresent(player -> embed.addField("👑 MVP",
                        String.format("%s - K/D: %d/%d",
                                player.getPlayerName(),
                                player.getKills(),
                                player.getDeaths()), true));

        return embed;
    }

    /**
     * Formatea la duración en segundos a un formato legible
     */
    private String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + " segundos";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long remainingSeconds = seconds % 60;
            return String.format("%d:%02d min", minutes, remainingSeconds);
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return String.format("%d:%02d horas", hours, minutes);
        }
    }

    /**
     * Obtiene el display del equipo ganador
     */
    private String getWinnerDisplay(String winnerTeam) {
        if (winnerTeam == null) return "Sin determinar";

        return switch (winnerTeam.toLowerCase()) {
            case "blue", "azul" -> "🔵 Equipo Azul";
            case "red", "rojo" -> "🔴 Equipo Rojo";
            default -> winnerTeam;
        };
    }
}