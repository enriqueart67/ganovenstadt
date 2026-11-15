package de.peregrine.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;

/** Emotes: Animationen der eigenen Spielfigur (sichtbar in der Ansicht von außen, F5). */
public final class Emotes {
    private Emotes() {
    }

    /** One animation: name, length in seconds (0 = until you move), pose function. */
    public record Emote(String name, float seconds, Pose pose) {
    }

    @FunctionalInterface
    public interface Pose {
        void apply(PlayerModel m, float t);
    }

    private static float s(float t, float speed) {
        return (float) Math.sin(t * speed);
    }

    public static final Emote[] ALL = {
            new Emote("Wave", 2.5f, (m, t) -> {
                m.rightArm.xRot = -2.9f;
                m.rightArm.zRot = 0.35f * s(t, 11f);
                m.head.zRot = 0.08f * s(t, 4f);
            }),
            new Emote("Dab", 1.6f, (m, t) -> {
                m.head.xRot = 0.65f;
                m.head.yRot = 0.55f;
                m.rightArm.xRot = -2.35f;
                m.rightArm.zRot = 0.45f;
                m.leftArm.xRot = -1.55f;
                m.leftArm.yRot = 0.95f;
            }),
            new Emote("Clap", 3f, (m, t) -> {
                float c = 0.35f + 0.25f * Math.abs(s(t, 9f));
                m.rightArm.xRot = -1.35f;
                m.leftArm.xRot = -1.35f;
                m.rightArm.yRot = -c;
                m.leftArm.yRot = c;
            }),
            new Emote("Dance", 0f, (m, t) -> {
                float w = s(t, 6f);
                m.body.yRot = 0.3f * w;
                m.head.zRot = 0.2f * w;
                m.rightArm.zRot = 0.6f + 0.5f * w;
                m.leftArm.zRot = -0.6f + 0.5f * w;
                m.rightLeg.xRot = 0.35f * w;
                m.leftLeg.xRot = -0.35f * w;
            }),
            new Emote("Salute", 2.2f, (m, t) -> {
                m.rightArm.xRot = -2.25f;
                m.rightArm.yRot = -0.75f;
                m.rightArm.zRot = 0.35f;
                m.head.xRot = -0.1f;
            }),
            new Emote("Facepalm", 2.5f, (m, t) -> {
                m.head.xRot = 0.55f;
                m.rightArm.xRot = -2.1f;
                m.rightArm.yRot = -0.55f;
            }),
            new Emote("Cheer", 3f, (m, t) -> {
                float w = s(t, 12f);
                m.rightArm.xRot = -3.0f;
                m.leftArm.xRot = -3.0f;
                m.rightArm.zRot = 0.35f + 0.2f * w;
                m.leftArm.zRot = -0.35f - 0.2f * w;
                m.head.xRot = -0.3f;
            }),
            new Emote("Nod", 2f, (m, t) -> m.head.xRot = 0.45f + 0.35f * s(t, 8f)),
            // ---- second page
            new Emote("Shrug", 2f, (m, t) -> {
                float lift = 0.5f + 0.5f * Math.min(1f, t * 4f);
                m.rightArm.zRot = 0.75f * lift;
                m.leftArm.zRot = -0.75f * lift;
                m.rightArm.xRot = -0.5f * lift;
                m.leftArm.xRot = -0.5f * lift;
                m.head.zRot = 0.18f;
            }),
            new Emote("Point", 2f, (m, t) -> {
                m.rightArm.xRot = -1.57f;
                m.rightArm.yRot = -0.1f;
                m.head.xRot = 0.05f;
            }),
            new Emote("T-Pose", 3f, (m, t) -> {
                m.rightArm.xRot = 0f;
                m.leftArm.xRot = 0f;
                m.rightArm.zRot = 1.57f;
                m.leftArm.zRot = -1.57f;
                m.rightLeg.xRot = 0f;
                m.leftLeg.xRot = 0f;
            }),
            new Emote("Bow", 2f, (m, t) -> {
                float b = 0.7f * (float) Math.sin(Math.min(1f, t / 2f) * Math.PI);
                m.body.xRot = b;
                m.head.xRot = b * 1.3f;
                m.head.y += b * 3f;
                m.head.z -= b * 5f;
                m.rightArm.z -= b * 5f;
                m.leftArm.z -= b * 5f;
                m.rightArm.xRot = b * 0.6f;
                m.leftArm.xRot = b * 0.6f;
            }),
            new Emote("Zombie", 0f, (m, t) -> {
                m.rightArm.xRot = -1.5f + 0.08f * s(t, 3f);
                m.leftArm.xRot = -1.5f - 0.08f * s(t, 3f);
                m.head.zRot = 0.25f * s(t, 1.5f);
            }),
            new Emote("Flex", 2.5f, (m, t) -> {
                float p = 0.12f * s(t, 8f);
                m.rightArm.xRot = -0.2f;
                m.leftArm.xRot = -0.2f;
                m.rightArm.zRot = 2.2f + p;
                m.leftArm.zRot = -2.2f - p;
            }),
            new Emote("Think", 3f, (m, t) -> {
                m.rightArm.xRot = -1.9f;
                m.rightArm.yRot = -0.45f;
                m.head.xRot = 0.25f;
                m.head.zRot = 0.15f + 0.04f * s(t, 2f);
            }),
            new Emote("No", 2f, (m, t) -> m.head.yRot = 0.55f * s(t, 9f)),
    };

    /** Emotes per wheel page. */
    public static final int PAGE = 8;

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

    // ---- preview in the emote wheel (the figure in the hub acts out the hovered emote)
    private static Emote preview;
    private static long previewNanos;

    public static void setPreview(Emote e) {
        if (e != preview) {
            preview = e;
            previewNanos = System.nanoTime();
        }
    }

    /** True while something should be applied to the player model. */
    public static boolean posing() {
        return active != null || preview != null;
    }

    /** Called from the PlayerModel mixin: put the pose on the local player's model. */
    public static void apply(PlayerModel m) {
        Emote e = preview != null ? preview : active;
        if (e == null) return;
        float t = ((preview != null ? System.nanoTime() - previewNanos : System.nanoTime() - startNanos)) / 1_000_000_000f;
        if (preview != null && e.seconds() > 0) t = t % e.seconds(); // loop in the preview
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
