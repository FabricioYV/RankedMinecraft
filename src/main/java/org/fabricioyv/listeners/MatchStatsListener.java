package org.fabricioyv.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.logging.DiscordLogger;
import org.fabricioyv.match.ActiveMatch;
import org.fabricioyv.match.Team;
import org.fabricioyv.model.PlayerData;

import java.util.List;
import java.util.Map;

public class MatchStatsListener implements Listener {
    private DiscordLogger logger;
    private RankedMinecraft plugin;

    // Modificar el constructor existente
    public MatchStatsListener(RankedMinecraft plugin, DiscordLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Buscar la partida activa
        ActiveMatch activeMatch = findActiveMatchForPlayer(victim);
        if (activeMatch == null) return;

        // Registrar muerte de la víctima
        PlayerData victimData = findPlayerData(activeMatch, victim);
        if (victimData != null) {
            victimData.addDeath();
        }

        // Registrar kill del asesino (si existe)
        if (killer != null) {
            PlayerData killerData = findPlayerData(activeMatch, killer);
            if (killerData != null) {
                killerData.addKill();
            }
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;

        // Buscar la partida activa
        ActiveMatch activeMatch = findActiveMatchForPlayer(attacker);
        if (activeMatch == null) return;

        // Solo rastrear si la partida está en progreso
        if (activeMatch.getStatus() != ActiveMatch.MatchStatus.IN_PROGRESS) return;

        // Registrar daño del atacante
        PlayerData attackerData = findPlayerData(activeMatch, attacker);
        if (attackerData != null) {
            attackerData.addDamage(event.getFinalDamage());
        }
    }

    private ActiveMatch findActiveMatchForPlayer(Player player) {
        return ActiveMatch.getAllActiveMatches().stream()
                .filter(match -> match.getStatus() == ActiveMatch.MatchStatus.IN_PROGRESS)
                .filter(match -> match.getAllPlayers().stream()
                        .anyMatch(p -> p.getMinecraftUuid().equals(player.getUniqueId().toString())))
                .findFirst()
                .orElse(null);
    }

    private PlayerData findPlayerData(ActiveMatch activeMatch, Player player) {
        return activeMatch.getAllPlayers().stream()
                .filter(p -> p.getMinecraftUuid().equals(player.getUniqueId().toString()))
                .findFirst()
                .orElse(null);
    }

}