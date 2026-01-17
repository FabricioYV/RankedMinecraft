package org.fabricioyv.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.fabricioyv.RankedMinecraft;
import org.fabricioyv.rating.EloDecaySystem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Comando administrativo para gestionar el sistema de ELO Decay
 * Uso: /elodecay <reload|force|info>
 */
public class EloDecayCommand implements CommandExecutor, TabCompleter {

    private final EloDecaySystem decaySystem;

    public EloDecayCommand(EloDecaySystem decaySystem) {
        this.decaySystem = decaySystem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Verificar permisos
        if (!sender.hasPermission("rankedmc.admin")) {
            sender.sendMessage("§c✗ No tienes permiso para usar este comando");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                decaySystem.reloadConfig();
                sender.sendMessage("§a✓ Configuración de ELO Decay recargada");
                break;

            case "force":
                sender.sendMessage("§e⚡ Ejecutando proceso de ELO Decay manualmente...");
                decaySystem.forceDecayNow().thenAccept(result -> {
                    sender.sendMessage(result);
                });
                break;

            case "info":
                sendInfoMessage(sender);
                break;

            default:
                sendHelpMessage(sender);
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§6§l⚡ ELO Decay System - Comandos");
        sender.sendMessage("§e/elodecay reload §7- Recarga la configuración");
        sender.sendMessage("§e/elodecay force §7- Fuerza ejecución inmediata del decay");
        sender.sendMessage("§e/elodecay info §7- Muestra información del sistema");
    }

    private void sendInfoMessage(CommandSender sender) {
        sender.sendMessage("§6§l⚡ ELO Decay System - Información");
        sender.sendMessage("§7El sistema se ejecuta diariamente a las 03:00 AM");
        sender.sendMessage("§7Los jugadores pierden ELO después de 14 días de inactividad");
        sender.sendMessage("§7Pérdida: 5 ELO por día adicional de inactividad");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "force", "info");
        }
        return new ArrayList<>();
    }
}