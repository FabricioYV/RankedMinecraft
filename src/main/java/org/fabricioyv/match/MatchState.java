package org.fabricioyv.match;

import java.util.concurrent.atomic.AtomicBoolean;

public class MatchState {
    private static final AtomicBoolean matchActive = new AtomicBoolean(false);

    /**
     * Verifica si hay una partida activa actualmente
     * @return true si hay una partida en curso
     */
    public static boolean isMatchActive() {
        return matchActive.get();

    }

    /**
     * Intenta iniciar una nueva partida
     * @return true si se pudo iniciar la partida, false si ya hay una activa
     */
    public static boolean startMatch() {
        return matchActive.compareAndSet(false, true);
    }

    /**
     * Finaliza la partida activa, permitiendo que se inicie una nueva
     */
    public static void endMatch() {
        matchActive.set(false);
    }
}