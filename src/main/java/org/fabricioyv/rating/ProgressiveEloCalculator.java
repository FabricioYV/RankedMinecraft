package org.fabricioyv.rating;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;

public class ProgressiveEloCalculator {

    private static FileConfiguration eloConfig;
    private static boolean configLoaded = false;
    private static JavaPlugin plugin;

    /**
     * Inicializa la configuración ELO con la instancia del plugin
     */
    public static void initialize(JavaPlugin pluginInstance) {
        plugin = pluginInstance;
        loadEloConfig();
        configLoaded = true;
    }

    // ELO inicial para nuevos jugadores
    public static int getStartingElo() {
        loadConfigIfNeeded();
        return eloConfig.getInt("starting_elo", 500);
    }

    private static void loadConfigIfNeeded() {
        if (!configLoaded) {
            loadEloConfig();
            configLoaded = true;
        }
    }

    private static void loadEloConfig() {
        try {
            File configFile = new File(plugin.getDataFolder(), "elo-config.yml");

            if (!configFile.exists()) {
                plugin.saveResource("elo-config.yml", false);
                plugin.getLogger().info("✅ Archivo elo-config.yml creado desde recursos");
            }

            eloConfig = YamlConfiguration.loadConfiguration(configFile);
            plugin.getLogger().info("✅ Configuración ELO cargada correctamente");

        } catch (Exception e) {
            plugin.getLogger().severe("❌ Error cargando elo-config.yml: " + e.getMessage());
            createFallbackConfig();
        }
    }

    private static void createFallbackConfig() {
        eloConfig = new YamlConfiguration();
        eloConfig.set("starting_elo", 500);
        eloConfig.set("k_factors.cobre", 45);
        eloConfig.set("k_factors.hierro", 40);
        eloConfig.set("k_factors.oro", 35);
        eloConfig.set("k_factors.diamante", 30);
        eloConfig.set("k_factors.esmeralda", 25);
    }

    // Tipos de partida con sus modificadores
    public enum MatchType {
        RANKED_5V5("ranked_5v5"),
        RANKED_8V8("ranked_8v8");

        private final String configKey;

        MatchType(String configKey) {
            this.configKey = configKey;
        }

        public double getWinMultiplier() {
            loadConfigIfNeeded();
            return eloConfig.getDouble("match_types." + configKey + ".win_multiplier", 1.0);
        }

        public double getLossMultiplier() {
            loadConfigIfNeeded();
            return eloConfig.getDouble("match_types." + configKey + ".loss_multiplier", 1.0);
        }

        public String getDisplayName() {
            loadConfigIfNeeded();
            return eloConfig.getString("match_types." + configKey + ".display_name", configKey);
        }

        public double getMultiplier() {
            loadConfigIfNeeded();
            return eloConfig.getDouble("match_types." + configKey + ".multiplier", 1.0);
        }
    }

    /**
     * Calcula los cambios de ELO con sistema fijo por rango
     */
    public static EloChange calculateEloChange(int playerElo, double opponentAvgElo,
                                               boolean won, MatchType matchType) {
        loadConfigIfNeeded();

        Rank currentRank = Rank.getRankByElo(playerElo);

        int eloChange;

        // Verificar si usar sistema fijo o dinámico
        if (eloConfig.getBoolean("system.use_fixed_elo", true)) {
            eloChange = calculateFixedEloChange(currentRank, won, matchType);
        } else {
            // Mantener el sistema anterior como respaldo
            int baseChange = calculateBaseChange(playerElo, opponentAvgElo, won);
            int rankModifiedChange = applyRankModifiers(baseChange, currentRank, won);

            if (eloConfig.getBoolean("system.enable_match_type_multipliers", false)) {
                double multiplier = won ? matchType.getWinMultiplier() : matchType.getLossMultiplier();
                rankModifiedChange = (int) Math.round(rankModifiedChange * multiplier);
            }

            eloChange = rankModifiedChange;
        }

        int newElo = Math.max(eloConfig.getInt("system.minimum_elo", 0), playerElo + eloChange);
        Rank newRank = Rank.getRankByElo(newElo);

        boolean promoted = newRank.ordinal() > currentRank.ordinal();
        boolean demoted = newRank.ordinal() < currentRank.ordinal();

        return new EloChange(eloChange, newElo, currentRank, newRank, promoted, demoted);
    }

    /**
     * Calcula el cambio de ELO usando valores fijos por rango
     */
    private static int calculateFixedEloChange(Rank currentRank, boolean won, MatchType matchType) {
        loadConfigIfNeeded();

        String rankKey = getRankConfigKey(currentRank);
        String changeType = won ? "win" : "loss";

        int baseChange = eloConfig.getInt("fixed_elo_changes." + rankKey + "." + changeType,
                won ? 20 : -20); // valores por defecto

        // Aplicar multiplicador de tipo de partida si está habilitado
        if (eloConfig.getBoolean("system.enable_match_type_multipliers", false)) {
            double multiplier = matchType.getMultiplier();
            baseChange = (int) Math.round(baseChange * multiplier);
        }

        return baseChange;
    }

    /**
     * Convierte el rango a la clave de configuración correspondiente
     */
    private static String getRankConfigKey(Rank rank) {
        return switch (rank) {
            case COBRE_3, COBRE_2, COBRE_1 -> "cobre";
            case HIERRO_3, HIERRO_2, HIERRO_1 -> "hierro";
            case ORO_3, ORO_2, ORO_1 -> "oro";
            case DIAMANTE_3, DIAMANTE_2, DIAMANTE_1 -> "diamante";
            case ESMERALDA -> "esmeralda";
            case PLACEMENT -> "cobre"; // Usar configuración base para placement (aunque no se debería usar)
        };
    }



    private static int calculateBaseChange(int playerElo, double opponentAvgElo, boolean won) {
        loadConfigIfNeeded();

        double eloDifference = opponentAvgElo - playerElo;

        // Fórmula ELO estándar con factor de división configurable
        int divisionFactor = eloConfig.getInt("advanced.elo_division_factor", 400);
        double expectedScore = 1.0 / (1.0 + Math.pow(10, eloDifference / divisionFactor));
        double actualScore = won ? 1.0 : 0.0;

        // Factor K desde configuración
        int kFactor = getKFactor(playerElo);

        int baseChange = (int) Math.round(kFactor * (actualScore - expectedScore));

        // Ajustar pérdidas mínimas según el rango (si está habilitado)
        if (!won && eloConfig.getBoolean("advanced.enable_minimum_losses", true)) {
            Rank currentRank = Rank.getRankByElo(playerElo);
            int minLoss = getMinimumLoss(currentRank);

            // Asegurar que la pérdida sea al menos el mínimo para el rango
            if (baseChange > minLoss) {
                baseChange = minLoss;
            }
        }

        return baseChange;
    }

    private static int getKFactor(int elo) {
        loadConfigIfNeeded();

        if (elo < 300) return eloConfig.getInt("k_factors.cobre", 45);      // Cobre
        if (elo < 600) return eloConfig.getInt("k_factors.hierro", 40);     // Hierro
        if (elo < 900) return eloConfig.getInt("k_factors.oro", 35);        // Oro
        if (elo < 1200) return eloConfig.getInt("k_factors.diamante", 30);  // Diamante
        return eloConfig.getInt("k_factors.esmeralda", 25);                 // Esmeralda
    }

    private static int getMinimumLoss(Rank rank) {
        loadConfigIfNeeded();

        return switch (rank) {
            case COBRE_3, COBRE_2, COBRE_1 -> eloConfig.getInt("minimum_losses.cobre", -5);
            case HIERRO_3, HIERRO_2, HIERRO_1 -> eloConfig.getInt("minimum_losses.hierro", -10);
            case ORO_3, ORO_2, ORO_1 -> eloConfig.getInt("minimum_losses.oro", -18);
            case DIAMANTE_3, DIAMANTE_2, DIAMANTE_1 -> eloConfig.getInt("minimum_losses.diamante", -25);
            case ESMERALDA -> eloConfig.getInt("minimum_losses.esmeralda", -30);
            case PLACEMENT -> 0; // Los jugadores en placement no deberían perder ELO
        };
    }

    private static int applyRankModifiers(int baseChange, Rank currentRank, boolean won) {
        loadConfigIfNeeded();

        // Verificar si los modificadores de rango están habilitados
        if (!eloConfig.getBoolean("advanced.enable_rank_multipliers", true)) {
            return baseChange;
        }

        double multiplier = 1.0;
        String winLossKey = won ? "win" : "loss";

        switch (currentRank) {
            case COBRE_3:
            case COBRE_2:
            case COBRE_1:
                multiplier = eloConfig.getDouble("rank_multipliers.cobre." + winLossKey, won ? 1.3 : 0.8);
                break;

            case HIERRO_3:
            case HIERRO_2:
            case HIERRO_1:
                multiplier = eloConfig.getDouble("rank_multipliers.hierro." + winLossKey, won ? 1.1 : 1.2);
                break;

            case ORO_3:
            case ORO_2:
            case ORO_1:
                multiplier = eloConfig.getDouble("rank_multipliers.oro." + winLossKey, won ? 1.0 : 1.5);
                break;

            case DIAMANTE_3:
            case DIAMANTE_2:
            case DIAMANTE_1:
                multiplier = eloConfig.getDouble("rank_multipliers.diamante." + winLossKey, won ? 0.9 : 1.8);
                break;

            case ESMERALDA:
                multiplier = eloConfig.getDouble("rank_multipliers.esmeralda." + winLossKey, won ? 0.8 : 1.6);
                break;

            case PLACEMENT:
                // Los jugadores en placement no deberían usar modificadores, retornar sin cambios
                return baseChange;
        }

        return (int) Math.round(baseChange * multiplier);
    }

    public static class EloChange {
        private final int eloChange;
        private final int newElo;
        private final Rank oldRank;
        private final Rank newRank;
        private final boolean promoted;
        private final boolean demoted;

        public EloChange(int eloChange, int newElo, Rank oldRank, Rank newRank,
                         boolean promoted, boolean demoted) {
            this.eloChange = eloChange;
            this.newElo = newElo;
            this.oldRank = oldRank;
            this.newRank = newRank;
            this.promoted = promoted;
            this.demoted = demoted;
        }

        // Getters
        public int getEloChange() { return eloChange; }
        public int getNewElo() { return newElo; }
        public Rank getOldRank() { return oldRank; }
        public Rank getNewRank() { return newRank; }
        public boolean isPromoted() { return promoted; }
        public boolean isDemoted() { return demoted; }

        public String getChangeMessage() {
            StringBuilder msg = new StringBuilder();

            if (promoted) {
                msg.append("§a🎉 ¡PROMOCIÓN! ");
                msg.append(oldRank.getFormattedName()).append(" → ").append(newRank.getFormattedName());
            } else if (demoted) {
                msg.append("§c📉 Degradación: ");
                msg.append(oldRank.getFormattedName()).append(" → ").append(newRank.getFormattedName());
            } else {
                msg.append("Rango: ").append(newRank.getFormattedName());
            }

            msg.append(" (").append(eloChange > 0 ? "+" : "").append(eloChange).append(" ELO)");
            return msg.toString();
        }
    }
}
