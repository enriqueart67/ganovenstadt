package de.peregrine.client;

import java.util.ArrayDeque;

/** Zaehlt Mausklicks der letzten Sekunde (CPS). */
public final class Clicks {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

    public static synchronized void click(int button) {
        long now = System.currentTimeMillis();
        // Maustasten-Nummern unterscheiden sich je Version (26.3 nutzt SDL: links 1, rechts 3)
        if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) LEFT.addLast(now);
        else if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT) RIGHT.addLast(now);
    }

    private static int count(ArrayDeque<Long> q) {
        long limit = System.currentTimeMillis() - 1000;
        while (!q.isEmpty() && q.peekFirst() < limit) q.pollFirst();
        return q.size();
    }

    public static synchronized int left() {
        return count(LEFT);
    }

    public static synchronized int right() {
        return count(RIGHT);
    }
}
