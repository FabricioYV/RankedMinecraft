package org.fabricioyv.logging;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.entity.Player;
import org.fabricioyv.config.VoiceChannelConfig;
import org.fabricioyv.database.MatchLogsManager;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;

import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;



public class DiscordLogger {
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
     * OPTIMIZADO: NO guarda en base de datos automáticamente - solo envía a Discord
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, long durationSeconds) {


        // Continuar con el logging a Discord existente
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

        for (Map.Entry<Team, List<PlayerData>> entry : teams.entrySet()) {
            Team team = entry.getKey();
            List<PlayerData> players = entry.getValue();
            boolean isWinner = team == winnerTeam;

            teamsInfo.append(team == Team.BLUE ? "🔵" : "🔴")
                    .append(" **").append(team.getDisplayName()).append("**")
                    .append(isWinner ? " 🏆" : " 💔").append("\n");

            for (PlayerData player : players) {
                String playerName = getPlayerDisplayName(player);
                Integer eloChange = eloChanges.get(player.getMinecraftUuid());
                String eloText = "";

                if (eloChange != null) {
                    eloText = " (" + (eloChange > 0 ? "+" : "") + eloChange + ")";
                }

                teamsInfo.append("• ").append(playerName).append(eloText).append("\n");
            }
            teamsInfo.append("\n");
        }

        embed.addField("👥 Equipos", teamsInfo.toString(), false);

        // Estadísticas de ELO
        int totalEloGained = eloChanges.values().stream().filter(change -> change > 0).mapToInt(Integer::intValue).sum();
        int totalEloLost = Math.abs(eloChanges.values().stream().filter(change -> change < 0).mapToInt(Integer::intValue).sum());

        embed.addField("📈 ELO Ganado", String.valueOf(totalEloGained), true);
        embed.addField("📉 ELO Perdido", String.valueOf(totalEloLost), true);
        embed.addField("⚖️ ELO Neto", "0", true);

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

        if (winners.length() > 0) {
            embed.addField("📈 Ganadores", winners.toString(), true);
        }
        if (losers.length() > 0) {
            embed.addField("📉 Perdedores", losers.toString(), true);
        }

        embed.setTimestamp(java.time.Instant.now());
        sendEmbedToResults(embed);
    }

    /**
     */
    private void sendEmbedToResults(EmbedBuilder embed) {
        if (resultsChannel == null) {
            System.err.println("❌ No se puede enviar resultado: Canal de resultados no disponible");
            return;
        }

        // Enviar de forma asíncrona para no bloquear el hilo principal
        CompletableFuture.runAsync(() -> {
            try {
                resultsChannel.sendMessageEmbeds(embed.build()).queue(
                        success -> {
                            // Resultado enviado exitosamente
                        },
                        error -> {
                            System.err.println("❌ Error enviando resultado a Discord: " + error.getMessage());
                        }
                );
            } catch (Exception e) {
                System.err.println("❌ Error inesperado enviando resultado: " + e.getMessage());
            }
        });
    }
    /**
     * Formatea la duración en formato legible
     */
    private String formatDuration(long seconds) {
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;

        if (minutes > 0) {
            return String.format("%dm %ds", minutes, remainingSeconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
    /**
     * Obtiene el nombre de display de un jugador
     */
    private String getPlayerDisplayName(PlayerData player) {
        try {
            org.bukkit.entity.Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                    java.util.UUID.fromString(player.getMinecraftUuid())
            );
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }

            net.dv8tion.jda.api.entities.Member discordMember =
                    jda.getGuilds().get(0).getMemberById(player.getDiscordId());
            if (discordMember != null) {
                return discordMember.getEffectiveName();
            }
        } catch (Exception e) {
            // Fallback
        }

        return "UUID:" + player.getMinecraftUuid().substring(0, 8);
    }

    /**
     * Obtiene el nombre de un jugador por UUID
     */
    private String getPlayerNameByUuid(String uuid) {
        try {
            org.bukkit.entity.Player mcPlayer = org.bukkit.Bukkit.getPlayer(java.util.UUID.fromString(uuid));
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }
        } catch (Exception e) {
            // Fallback
        }
        return "UUID:" + uuid.substring(0, 8);
    }


    /**
     * Log de información general
     */
    public void info(String title, String description) {
        sendLog(LogLevel.INFO, title, description, Color.BLUE);
    }

    /**
     * Log de advertencias
     */
    public void warning(String title, String description) {
        sendLog(LogLevel.WARNING, title, description, Color.ORANGE);
    }

    /**
     * Log de errores
     */
    public void error(String title, String description) {
        sendLog(LogLevel.ERROR, title, description, Color.RED);
    }

    /**
     * Log de éxito
     */
    public void success(String title, String description) {
        sendLog(LogLevel.SUCCESS, title, description, Color.GREEN);
    }

    /**
     * Log específico para eventos de cola
     */
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

    /**
     * Log específico para eventos de partidas
     */
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

    /**
     * Log específico para errores de sistema
     */
    public void systemError(String component, String error, String stackTrace) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🚨 Error del Sistema");
        embed.setColor(Color.RED);
        embed.addField("🔧 Componente", component, true);
        embed.addField("❌ Error", error, false);

        if (stackTrace != null && stackTrace.length() > 0) {
            String truncatedStack = stackTrace.length() > 1000 ?
                    stackTrace.substring(0, 1000) + "..." : stackTrace;
            embed.addField("📋 Stack Trace", "```\n" + truncatedStack + "\n```", false);
        }

        embed.setTimestamp(java.time.Instant.now());
        sendEmbed(embed);
    }

    /**
     * Log de inicio del sistema
     */
    public void systemStart() {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle("🚀 Sistema Iniciado");
        embed.setDescription("RankedMinecraft ha sido iniciado exitosamente");
        embed.setColor(Color.GREEN);
        embed.addField("⏰ Tiempo", DATE_FORMAT.format(LocalDateTime.now()), true);
        embed.addField("🔧 Estado", "Operativo", true);
        embed.setTimestamp(java.time.Instant.now());

        sendEmbed(embed);
    }


    /**
     * Metodo generico para enviar logs.
     */
    private void sendLog(LogLevel level, String title, String description, Color color) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(level.getEmoji() + " " + title);
        embed.setDescription(description);
        embed.setColor(color);
        embed.addField("⏰ Hora", TIME_FORMAT.format(LocalDateTime.now()), true);
        embed.addField("📊 Nivel", level.name(), true);
        embed.setTimestamp(java.time.Instant.now());

        sendEmbed(embed);
    }

    /**
     */
    private void sendEmbed(EmbedBuilder embed) {
        if (logsChannel == null) {
            System.err.println("❌ No se puede enviar log: Canal de logs no disponible");
            return;
        }

        // Enviar de forma asíncrona para no bloquear el hilo principal
        CompletableFuture.runAsync(() -> {
            try {
                logsChannel.sendMessageEmbeds(embed.build()).queue(
                        success -> {
                            // Log enviado exitosamente
                        },
                        error -> {
                            System.err.println("❌ Error enviando log a Discord: " + error.getMessage());
                        }
                );
            } catch (Exception e) {
                System.err.println("❌ Error inesperado enviando log: " + e.getMessage());
            }
        });
    }
    public void updateDiscordNicknames(Map<String, Integer> eloChanges, Map<Team, List<PlayerData>> teams) {
        if (eloChanges == null || teams == null || jda == null) {
            return;
        }

        Guild guild = jda.getGuilds().isEmpty() ? null : jda.getGuilds().get(0);
        if (guild == null) {
            return;
        }

        for (Map.Entry<Team, List<PlayerData>> teamEntry : teams.entrySet()) {
            List<PlayerData> players = teamEntry.getValue();
            if (players == null) continue;

            for (PlayerData player : players) {
                try {
                    Member member = guild.getMemberById(player.getDiscordId());
                    if (member == null) continue;

                    // Obtener cambio de ELO
                    Integer eloChange = eloChanges.get(player.getMinecraftUuid());
                    if (eloChange == null) continue;

                    // Calcular nuevo ELO
                    int newElo = player.getElo() + eloChange;

                    // Obtener nombre de Minecraft del jugador
                    String minecraftName = getMinecraftPlayerName(player.getMinecraftUuid());
                    if (minecraftName == null || minecraftName.isEmpty()) continue;

                    // Crear nuevo apodo con formato: minecraft_name (ELO)
                    String newNickname = String.format("%s [%d]", minecraftName, newElo);

                    // Actualizar apodo si es diferente
                    String currentNickname = member.getNickname();
                    if (!newNickname.equals(currentNickname)) {
                        member.modifyNickname(newNickname).queue(
                                success -> info("Apodo Actualizado",
                                        String.format("Apodo de %s actualizado a: %s", minecraftName, newNickname)),
                                error -> warning("Error Actualizando Apodo",
                                        String.format("No se pudo actualizar apodo de %s: %s", minecraftName, error.getMessage()))
                        );
                    }

                } catch (Exception e) {
                    systemError("DiscordLogger",
                            "Error actualizando apodo de jugador", e.getMessage());
                }
            }
        }
    }

    private String getMinecraftPlayerName(String minecraftUuid) {
        try {
            Player mcPlayer = org.bukkit.Bukkit.getPlayer(
                    java.util.UUID.fromString(minecraftUuid)
            );
            if (mcPlayer != null) {
                return mcPlayer.getName();
            }
        } catch (Exception e) {
            // Fallback to UUID if player not found
        }
        return null;
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

    }


    /**
     * Versión extendida del método matchComplete con más datos para la base de datos
     */
    public void matchComplete(String matchId, String matchType, String mapName,
                              Team winnerTeam, Map<Team, List<PlayerData>> teams,
                              Map<String, Integer> eloChanges, Map<String, Double> mmrChanges,
                              Map<String, Integer> oldElos, Map<String, Double> oldMMRs,
                              Map<String, Integer> kills, Map<String, Integer> deaths,
                              long durationSeconds, LocalDateTime startTime, LocalDateTime endTime) {

        // Guardar en base de datos de logs
        CompletableFuture.runAsync(() -> {
            try {
                boolean saved = MatchLogsManager.saveMatchLog(
                    matchId, matchType, mapName, winnerTeam, teams,
                    eloChanges, mmrChanges, oldElos, oldMMRs, kills, deaths,
                    durationSeconds, startTime, endTime
                );

                if (saved) {
                    System.out.println("✅ Match data guardado en base de datos: " + matchId);
                } else {
                    System.err.println("⚠️ No se pudo guardar match data en base de datos: " + matchId);
                }
            } catch (Exception e) {
                System.err.println("❌ Error guardando match data: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // Llamar al método original para Discord
        matchComplete(matchId, matchType, mapName, winnerTeam, teams, eloChanges, durationSeconds);
    }

    /**
     * Guarda un match en la base de datos de logs (método auxiliar)
     */
    private void saveMatchToDatabase(String matchId, String matchType, String mapName,
                                   Team winnerTeam, Map<Team, List<PlayerData>> teams,
                                   Map<String, Integer> eloChanges, long durationSeconds) {
        try {
            // Crear mapas con datos por defecto si no están disponibles
            Map<String, Double> mmrChanges = new HashMap<>();
            Map<String, Integer> oldElos = new HashMap<>();
            Map<String, Double> oldMMRs = new HashMap<>();
            Map<String, Integer> kills = new HashMap<>();
            Map<String, Integer> deaths = new HashMap<>();

            // Llenar los mapas con datos disponibles
            for (Map.Entry<Team, List<PlayerData>> teamEntry : teams.entrySet()) {
                for (PlayerData player : teamEntry.getValue()) {
                    String uuid = player.getMinecraftUuid();

                    // Estimar valores anteriores basados en el cambio de ELO
                    Integer eloChange = eloChanges.getOrDefault(uuid, 0);
                    oldElos.put(uuid, player.getElo() - eloChange);
                    oldMMRs.put(uuid, player.getMmr()); // Asumir que MMR no cambió mucho
                    mmrChanges.put(uuid, 0.0); // Por defecto
                    kills.put(uuid, 0); // Por defecto
                    deaths.put(uuid, 0); // Por defecto
                }
            }

            LocalDateTime now = LocalDateTime.now();
            LocalDateTime startTime = now.minusSeconds(durationSeconds);

            MatchLogsManager.saveMatchLog(
                matchId, matchType, mapName, winnerTeam, teams,
                eloChanges, mmrChanges, oldElos, oldMMRs, kills, deaths,
                durationSeconds, startTime, now
            );

        } catch (Exception e) {
            System.err.println("❌ Error guardando match básico en base de datos: " + e.getMessage());
        }
    }

    /**
     * Guarda un evento específico del match en la base de datos
     */
    public void logMatchEvent(String matchId, String eventType, String playerUuid, String eventData) {
        CompletableFuture.runAsync(() -> {
            try {
                MatchLogsManager.saveMatchEvent(matchId, eventType, playerUuid, eventData, LocalDateTime.now());
            } catch (Exception e) {
                System.err.println("❌ Error guardando evento de match: " + e.getMessage());
            }
        });
    }


    /**
     * Enum para niveles de log
     */
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
}
