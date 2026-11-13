package de.peregrine.client;

/** Zoom (wie OptiFine), solange die Zoom-Taste gedrueckt ist – optional weich. */
public final class Zoom {
    private static boolean held;
    private static double current = 1.0;
    private static long lastNanos = System.nanoTime();

    public static void setHeld(boolean h) {
        held = h;
    }

    /** Faktor, durch den das Sichtfeld geteilt wird (1 = kein Zoom). */
    public static double factor() {
        var z = Modules.ZOOM;
        double target = (held && z.enabled) ? Math.max(1.0, z.factor.d()) : 1.0;
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (!z.smooth.on()) current = target;
        else current += (target - current) * Math.min(1.0, dt * 14.0);
        if (Math.abs(current - target) < 0.001) current = target;
        return current;
    }
}
