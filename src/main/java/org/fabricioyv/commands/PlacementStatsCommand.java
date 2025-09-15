package org.fabricioyv.commands;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.fabricioyv.database.DatabaseManager;
import org.fabricioyv.model.PlayerData;

import java.awt.*;
import java.time.Instant;

/**
 * Comando para mostrar estadísticas del sistema de placement matches
 */
public class PlacementStatsCommand extends ListenerAdapter {

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("placement-stats")) return;

        // Verificar permisos de administrador
        if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
            event.reply("❌ No tienes permisos para usar este comando.")
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        try {
            // Obtener estadísticas de placement
            DatabaseManager.PlacementStats stats = DatabaseManager.getPlacementStats();
            
            // Crear embed con estadísticas
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("📊 Estadísticas del Sistema de Placement Matches")
                    .setColor(Color.CYAN)
                    .setTimestamp(Instant.now());

            // Estadísticas generales
            embed.addField("👥 Jugadores Totales", 
                    String.valueOf(stats.totalPlayers), true);
            
            embed.addField("🎯 En Placement Matches", 
                    String.format("%d (%.1f%%)", 
                            stats.placementPlayers,
                            stats.totalPlayers > 0 ? (stats.placementPlayers * 100.0 / stats.totalPlayers) : 0),
                    true);
            
            embed.addField("📈 Progreso Promedio", 
                    String.format("%.1f/8 partidas", stats.avgPlacementProgress), true);

            // Información del sistema
            embed.addField("⚙️ Configuración",
                    String.format("• **Partidas requeridas:** %d\n" +
                                 "• **MMR inicial:** 1000\n" +
                                 "• **Cambios amplificados:** Sí\n" +
                                 "• **MMR promedio establecido:** %.0f",
                            PlayerData.getPlacementMatchesRequired(),
                            stats.avgEstablishedMMR),
                    false);

            // Estado del sistema
            String systemStatus;
            if (stats.placementPlayers > stats.totalPlayers * 0.7) {
                systemStatus = "🟡 **Alta actividad** - Muchos jugadores en placement";
            } else if (stats.placementPlayers > stats.totalPlayers * 0.3) {
                systemStatus = "🟢 **Normal** - Balance saludable de jugadores";
            } else {
                systemStatus = "🔵 **Estable** - Mayoría de jugadores con MMR establecido";
            }

            embed.addField("📊 Estado del Sistema", systemStatus, false);

            // Información adicional
            embed.setFooter("Sistema de Placement Matches v2.0 • Actualizado automáticamente");

            event.getHook().editOriginalEmbeds(embed.build()).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("❌ Error obteniendo estadísticas: " + e.getMessage()).queue();
            e.printStackTrace();
        }
    }
}
