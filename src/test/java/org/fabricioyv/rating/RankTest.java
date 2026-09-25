package org.fabricioyv.rating;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RankTest {

    @Test
    void eloAtBottomOfRangeMapsToCorrectRank() {
        assertEquals(Rank.COBRE_3, Rank.getRankByElo(0));
        assertEquals(Rank.HIERRO_3, Rank.getRankByElo(300));
        assertEquals(Rank.ORO_3, Rank.getRankByElo(600));
        assertEquals(Rank.DIAMANTE_3, Rank.getRankByElo(900));
        assertEquals(Rank.ESMERALDA, Rank.getRankByElo(1200));
    }

    @Test
    void eloAtTopOfRangeMapsToCorrectRank() {
        assertEquals(Rank.COBRE_3, Rank.getRankByElo(99));
        assertEquals(Rank.COBRE_1, Rank.getRankByElo(299));
        assertEquals(Rank.DIAMANTE_1, Rank.getRankByElo(1199));
    }

    @Test
    void veryHighEloStaysEsmeralda() {
        assertEquals(Rank.ESMERALDA, Rank.getRankByElo(50_000));
    }

    @Test
    void negativeEloBelowPlacementSentinelFallsBackToCobre3() {
        // getRankByElo no tiene un rango que cubra elo negativo salvo -1 (reservado
        // para PLACEMENT). Cualquier otro negativo cae al fallback explícito del método.
        assertEquals(Rank.COBRE_3, Rank.getRankByElo(-5));
    }

    @Test
    void negativeOneCollidesWithPlacementSentinel() {
        // OJO: -1 es el sentinel de PLACEMENT (minElo=maxElo=-1), no un elo real.
        // Si algún caller llamara aquí con elo=-1 por error, obtendría PLACEMENT
        // en vez del fallback -- documentado por este test, no necesariamente deseado.
        assertEquals(Rank.PLACEMENT, Rank.getRankByElo(-1));
    }

    @Test
    void esmeraldaCannotBePromoted() {
        assertEquals(false, Rank.ESMERALDA.isPromotionPossible());
    }
}
