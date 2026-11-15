package de.peregrine.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/** Freelook: Kamera frei drehen, während die Spielfigur weiter in ihre Richtung schaut. */
public final class FreelookState {
    private FreelookState() {
    }

    public static boolean active;
    public static float yaw, pitch;
    private static CameraType previous;

    public static void update(Minecraft mc, boolean keyDown) {
        boolean want = keyDown && Modules.FREELOOK.enabled && mc.player != null && mc.mouseHandler.isMouseGrabbed();
        if (want && !active) {
            active = true;
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
            previous = mc.options.getCameraType();
            if (Modules.FREELOOK.thirdPerson.on() && previous == CameraType.FIRST_PERSON) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        } else if (!want && active) {
            active = false;
            if (previous != null) mc.options.setCameraType(previous);
            previous = null;
        }
    }

    /** Mausbewegung umleiten (gleiche Empfindlichkeit wie Entity.turn). */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15f;
        pitch += (float) (Modules.FREELOOK.invert.on() ? -dy : dy) * 0.15f;
        pitch = Math.max(-90f, Math.min(90f, pitch));
    }
}
