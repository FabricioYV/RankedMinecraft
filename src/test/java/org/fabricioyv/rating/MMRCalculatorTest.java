package org.fabricioyv.rating;

import org.fabricioyv.model.PlayerData;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MMRCalculatorTest {

    private static PlayerData newPlayer(double mmr, int gamesPlayed) {
        PlayerData player = new PlayerData(
                "uuid-test", "discord-test", 500, false, null,
                mmr, 0, 0, gamesPlayed, 0, 0
        );
        player.setInPlacement(false);
        return player;
    }

    @Test
    void averageMmrOfEmptyListDefaultsTo1000() {
        assertEquals(1000.0, MMRCalculator.calculateAverageMMR(Collections.emptyList()));
        assertEquals(1000.0, MMRCalculator.calculateAverageMMR(null));
    }

    @Test
    void averageMmrIsPlainMean() {
        List<PlayerData> team = List.of(newPlayer(1000, 50), newPlayer(1200, 50));
        assertEquals(1100.0, MMRCalculator.calculateAverageMMR(team));
    }

    @Test
    void winningAgainstStrongerTeamIncreasesMmr() {
        PlayerData player = newPlayer(1000, 50);
        player.setCurrentMatchKills(5);
        player.setCurrentMatchDeaths(2);
        player.setCurrentMatchDamage(1200);

        MMRCalculator.MMRChange result = MMRCalculator.calculateMMRChange(
                player, true, /*teamAvgMMR*/ 1000, /*opponentAvgMMR*/ 1300);

        assertTrue(result.getNewMMR() > 1000, "Ganarle a un equipo más fuerte debería subir el MMR");
    }

    @Test
    void mmrChangeIsAlwaysBoundedByMaxAndMinPerMatch() {
        // Estadísticas extremas para intentar forzar un cambio fuera de los límites documentados
        PlayerData dominant = newPlayer(1000, 50);
        dominant.setCurrentMatchKills(30);
        dominant.setCurrentMatchDeaths(0);
        dominant.setCurrentMatchDamage(5000);

        MMRCalculator.MMRChange result = MMRCalculator.calculateMMRChange(
                dominant, true, 1000, 600);

        double change = result.getNewMMR() - 1000;
        assertTrue(change <= 85.0, "El cambio no debería exceder MAX_MMR_CHANGE (85.0), fue " + change);
    }

    @Test
    void mmrNeverDropsBelowMinimumAllowedFloor() {
        PlayerData strugglingPlayer = newPlayer(600, 100);
        strugglingPlayer.setCurrentMatchKills(0);
        strugglingPlayer.setCurrentMatchDeaths(10);
        strugglingPlayer.setCurrentMatchDamage(50);

        MMRCalculator.MMRChange result = MMRCalculator.calculateMMRChange(
                strugglingPlayer, false, 600, 1400);

        assertTrue(result.getNewMMR() >= 600.0, "El MMR no debería bajar del piso de 600.0");
    }
}
