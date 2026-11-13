package de.peregrine.client;

import java.util.ArrayDeque;

/** Zaehlt Mausklicks der letzten Sekunde (CPS). */
public final class Clicks {
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>();
    private static final ArrayDeque<Long> RIGHT = new ArrayDeque<>();

    public static synchronized void click(int button) {
        long now = System.currentTimeMillis();
        if (button == 0) LEFT.addLast(now);
        else if (button == 1) RIGHT.addLast(now);
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
