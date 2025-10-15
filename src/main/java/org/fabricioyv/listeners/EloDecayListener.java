package org.fabricioyv.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.rating.EloDecaySystem;

/**
 * Listener para manejar notificaciones de ELO Decay al entrar al servidor
 */
public class EloDecayListener implements Listener {

    private final EloDecaySystem decaySystem;

    public EloDecayListener(EloDecaySystem decaySystem) {
        this.decaySystem = decaySystem;
    }

    /**
     * Notifica al jugador si perdió ELO por inactividad
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Notificar decay (si aplica)
        decaySystem.notifyPlayerOnLogin(player.getUniqueId());
    }
}

