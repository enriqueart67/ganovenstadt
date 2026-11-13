package de.peregrine.client;

/** Weicher Zoom (wie OptiFine), solange die Zoom-Taste gedrueckt ist. */
public final class Zoom {
    private static boolean held;
    private static double current = 1.0;
    private static long lastNanos = System.nanoTime();

    public static void setHeld(boolean h) {
        held = h;
    }

    public static boolean isHeld() {
        return held;
    }

    /** Faktor, durch den das Sichtfeld geteilt wird (1 = kein Zoom). */
    public static double factor() {
        Config c = Config.get();
        double target = (held && c.zoom) ? Math.max(1.0, c.zoomFactor) : 1.0;
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        current += (target - current) * Math.min(1.0, dt * 14.0);
        if (Math.abs(current - target) < 0.001) current = target;
        return current;
    }
}
