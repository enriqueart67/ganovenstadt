package de.peregrine.client.feature;

import de.peregrine.client.Waypoints;
import de.peregrine.client.gui.Px;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * World → screen projection for overlays (same camera maths as the waypoint markers). Built once per frame.
 * Works in camera space, so lines that reach behind the camera are clipped at a near plane instead of flipping.
 */
public final class Projector {
    private static final double NEAR = 0.05;
    private final Vec3 c;
    private final double fx, fy, fz, rx, rz, ux, uy, uz, focal;
    public final int w, h;

    private Projector(Camera cam, int w, int h) {
        this.c = cam.position();
        double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
        fx = -Math.sin(yaw) * Math.cos(pitch);
        fy = -Math.sin(pitch);
        fz = Math.cos(yaw) * Math.cos(pitch);
        rx = -Math.cos(yaw);
        rz = -Math.sin(yaw);
        ux = -Math.sin(yaw) * Math.sin(pitch);
        uy = Math.cos(pitch);
        uz = Math.cos(yaw) * Math.sin(pitch);
        this.w = w;
        this.h = h;
        focal = (h / 2.0) / Math.tan(Math.toRadians(cam.getFov()) / 2.0);
    }

    /** Null when there is no usable camera yet. */
    public static Projector of(Minecraft mc, GuiGraphicsExtractor g) {
        Camera cam = Waypoints.camera(mc);
        if (cam == null || !cam.isInitialized()) return null;
        return new Projector(cam, g.guiWidth(), g.guiHeight());
    }

    public Vec3 camera() {
        return c;
    }

    /** Camera space: x right, y up, z forward. */
    public double[] toCam(double x, double y, double z) {
        double dx = x - c.x, dy = y - c.y, dz = z - c.z;
        return new double[]{dx * rx + dz * rz, dx * ux + dy * uy + dz * uz, dx * fx + dy * fy + dz * fz};
    }

    /** Screen position of a camera-space point (must be in front). */
    public float[] toScreen(double[] p) {
        return new float[]{(float) (w / 2.0 + p[0] / p[2] * focal), (float) (h / 2.0 - p[1] / p[2] * focal)};
    }

    /** Screen position of a world point, or null if it is behind the camera. */
    public float[] project(double x, double y, double z) {
        double[] p = toCam(x, y, z);
        return p[2] > NEAR ? toScreen(p) : null;
    }

    /** A world-space segment, clipped at the near plane. */
    public void line(GuiGraphicsExtractor g, double[] a, double[] b, float width, int argb) {
        if (a[2] <= NEAR && b[2] <= NEAR) return;
        if (a[2] <= NEAR || b[2] <= NEAR) {
            double t = (NEAR - a[2]) / (b[2] - a[2]);
            double[] m = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, NEAR};
            if (a[2] <= NEAR) a = m;
            else b = m;
        }
        float[] s = toScreen(a), e = toScreen(b);
        Px.line(g, s[0], s[1], e[0], e[1], width, argb);
    }

    private static final int[][] EDGES = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    // corners per face: -X, +X, -Y, +Y, -Z, +Z (corner index = x*4 + y*2 + z)
    private static final int[][] FACES = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};

    /**
     * Outline of only the faces in {@code faces} (bit i = face i above). Neighbouring highlighted blocks hide the
     * shared faces, so a whole ore vein gets one clean outline instead of a grid. Returns screen bounds or null.
     */
    public float[] faces(GuiGraphicsExtractor g, AABB b, int faces, float width, int lineArgb) {
        double[][] p = new double[8][];
        int k = 0;
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int l = 0; l < 2; l++)
                    p[k++] = toCam(i == 0 ? b.minX : b.maxX, j == 0 ? b.minY : b.maxY, l == 0 ? b.minZ : b.maxZ);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (double[] q : p) {
            if (q[2] <= NEAR) continue;
            float[] s = toScreen(q);
            minX = Math.min(minX, s[0]);
            minY = Math.min(minY, s[1]);
            maxX = Math.max(maxX, s[0]);
            maxY = Math.max(maxY, s[1]);
            any = true;
        }
        if (!any) return null;
        long drawn = 0; // one bit per corner pair, so shared edges are drawn once
        for (int f = 0; f < 6; f++) {
            if ((faces & (1 << f)) == 0) continue;
            int[] c = FACES[f];
            for (int e = 0; e < 4; e++) {
                int a = c[e], z = c[(e + 1) % 4];
                int lo = Math.min(a, z), hi = Math.max(a, z);
                long bit = 1L << (lo * 8 + hi);
                if ((drawn & bit) != 0) continue;
                drawn |= bit;
                line(g, p[a], p[z], width, lineArgb);
            }
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /**
     * Box outline (12 edges) plus a soft glow over its screen area. Returns the screen bounds {minX, minY, maxX, maxY}
     * or null when it is completely behind the camera.
     */
    public float[] box(GuiGraphicsExtractor g, AABB b, float width, int lineArgb) {
        double[][] p = new double[8][];
        int k = 0;
        for (int i = 0; i < 2; i++)
            for (int j = 0; j < 2; j++)
                for (int l = 0; l < 2; l++)
                    p[k++] = toCam(i == 0 ? b.minX : b.maxX, j == 0 ? b.minY : b.maxY, l == 0 ? b.minZ : b.maxZ);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (double[] q : p) {
            if (q[2] <= NEAR) continue;
            float[] s = toScreen(q);
            minX = Math.min(minX, s[0]);
            minY = Math.min(minY, s[1]);
            maxX = Math.max(maxX, s[0]);
            maxY = Math.max(maxY, s[1]);
            any = true;
        }
        if (!any) return null;
        for (int[] e : EDGES) line(g, p[e[0]], p[e[1]], width, lineArgb);
        return new float[]{minX, minY, maxX, maxY};
    }
}
