package de.peregrine.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/** Emotes: Animationen der eigenen Spielfigur (sichtbar in der Ansicht von außen, F5). */
public final class Emotes {
    private Emotes() {
    }

    /** Eine Animation: Name, Symbol, Dauer in Sekunden (0 = bis man sich bewegt). */
    public record Emote(String name, String icon, float seconds, Pose pose) {
    }

    @FunctionalInterface
    public interface Pose {
        void apply(PlayerModel m, float t);
    }

    private static float s(float t, float speed) {
        return (float) Math.sin(t * speed);
    }

    public static final Emote[] ALL = {
            new Emote("Winken", "✋", 2.5f, (m, t) -> {
                m.rightArm.xRot = -2.9f;
                m.rightArm.zRot = 0.35f * s(t, 11f);
                m.head.zRot = 0.08f * s(t, 4f);
            }),
            new Emote("Dab", "➚", 1.6f, (m, t) -> {
                m.head.xRot = 0.65f;
                m.head.yRot = 0.55f;
                m.rightArm.xRot = -2.35f;
                m.rightArm.zRot = 0.45f;
                m.leftArm.xRot = -1.55f;
                m.leftArm.yRot = 0.95f;
            }),
            new Emote("Klatschen", "👏", 3f, (m, t) -> {
                float c = 0.35f + 0.25f * Math.abs(s(t, 9f));
                m.rightArm.xRot = -1.35f;
                m.leftArm.xRot = -1.35f;
                m.rightArm.yRot = -c;
                m.leftArm.yRot = c;
            }),
            new Emote("Tanzen", "♪", 0f, (m, t) -> {
                float w = s(t, 6f);
                m.body.yRot = 0.3f * w;
                m.head.zRot = 0.2f * w;
                m.rightArm.zRot = 0.6f + 0.5f * w;
                m.leftArm.zRot = -0.6f + 0.5f * w;
                m.rightLeg.xRot = 0.35f * w;
                m.leftLeg.xRot = -0.35f * w;
            }),
            new Emote("Salutieren", "✦", 2.2f, (m, t) -> {
                m.rightArm.xRot = -2.25f;
                m.rightArm.yRot = -0.75f;
                m.rightArm.zRot = 0.35f;
                m.head.xRot = -0.1f;
            }),
            new Emote("Facepalm", "☹", 2.5f, (m, t) -> {
                m.head.xRot = 0.55f;
                m.rightArm.xRot = -2.1f;
                m.rightArm.yRot = -0.55f;
            }),
            new Emote("Jubeln", "★", 3f, (m, t) -> {
                float w = s(t, 12f);
                m.rightArm.xRot = -3.0f;
                m.leftArm.xRot = -3.0f;
                m.rightArm.zRot = 0.35f + 0.2f * w;
                m.leftArm.zRot = -0.35f - 0.2f * w;
                m.head.xRot = -0.3f;
            }),
            new Emote("Nicken", "✓", 2f, (m, t) -> m.head.xRot = 0.45f + 0.35f * s(t, 8f)),
    };

    private static Emote active;
    private static long startNanos;
    private static CameraType previousCamera;

    public static Emote active() {
        return active;
    }

    public static void play(Emote e) {
        Minecraft mc = Minecraft.getInstance();
        if (!Modules.EMOTES.enabled || mc.player == null) return;
        if (active == null && Modules.EMOTES.camera.on()) {
            previousCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        }
        active = e;
        startNanos = System.nanoTime();
    }

    public static void stop() {
        if (active == null) return;
        active = null;
        if (previousCamera != null) {
            Minecraft.getInstance().options.setCameraType(previousCamera);
            previousCamera = null;
        }
    }

    private static float time() {
        return (System.nanoTime() - startNanos) / 1_000_000_000f;
    }

    public static void tick(Minecraft mc) {
        if (active == null) return;
        if (!Modules.EMOTES.enabled || mc.player == null) {
            stop();
            return;
        }
        boolean moving = mc.options.keyUp.isDown() || mc.options.keyDown.isDown() || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown() || mc.options.keyJump.isDown();
        if ((active.seconds() > 0 && time() > active.seconds()) || (moving && Modules.EMOTES.stopOnMove.on() && time() > 0.3f)) {
            stop();
        }
    }

    /** Vom PlayerModel-Mixin aufgerufen: Pose auf das Modell des eigenen Spielers legen. */
    public static void apply(PlayerModel m) {
        Emote e = active;
        if (e == null) return;
        // sanft ein- und ausblenden
        float t = time();
        e.pose().apply(m, t);
        float in = Math.min(1f, t / 0.15f);
        if (in < 1f) scale(m, in);
    }

    private static void scale(PlayerModel m, float f) {
        for (ModelPart p : new ModelPart[]{m.head, m.body, m.rightArm, m.leftArm, m.rightLeg, m.leftLeg}) {
            p.xRot *= f;
            p.yRot *= f;
            p.zRot *= f;
        }
    }
}
