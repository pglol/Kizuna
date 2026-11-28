package net.pglol.kata.rig;

/**
 * A pose: for each bone, a rotation in degrees (pitch about X, yaw about Y, roll about Z), added on
 * top of whatever Minecraft is already doing (walking, swinging). Elbows and knees use pitch as their
 * bend. Shared by client and server (the server will use the same numbers for hitboxes).
 */
public final class Pose {
    public static final int PITCH = 0, YAW = 1, ROLL = 2;
    private final float[][] deg = new float[Bone.values().length][3];
    /** The whole body's offset in model pixels: x sideways, y down, z back. */
    public final float[] offset = new float[3];
    /** True when this pose drives the walk cycle itself (Minecraft's stick-leg swing is taken out). */
    public boolean gait;

    public float get(Bone b, int axis) {
        return deg[b.ordinal()][axis];
    }

    public Pose set(Bone b, int axis, float degrees) {
        deg[b.ordinal()][axis] = degrees;
        return this;
    }

    public Pose set(Bone b, float pitch, float yaw, float roll) {
        float[] d = deg[b.ordinal()];
        d[0] = pitch;
        d[1] = yaw;
        d[2] = roll;
        return this;
    }

    public float rad(Bone b, int axis) {
        return (float) Math.toRadians(deg[b.ordinal()][axis]);
    }

    public boolean shifted() {
        return offset[0] != 0 || offset[1] != 0 || offset[2] != 0 || moves(Bone.ROOT);
    }

    public boolean moves(Bone b) {
        float[] d = deg[b.ordinal()];
        return d[0] != 0 || d[1] != 0 || d[2] != 0;
    }

    public void clear() {
        for (float[] d : deg) java.util.Arrays.fill(d, 0);
        java.util.Arrays.fill(offset, 0);
        gait = false;
    }

    public Pose copy() {
        Pose p = new Pose();
        for (int i = 0; i < deg.length; i++) System.arraycopy(deg[i], 0, p.deg[i], 0, 3);
        System.arraycopy(offset, 0, p.offset, 0, 3);
        p.gait = gait;
        return p;
    }

    /** In place: this pose eased back towards before, keeping t (0..1) of what changed since. */
    public void keep(Pose before, float t) {
        for (int i = 0; i < deg.length; i++) for (int k = 0; k < 3; k++) deg[i][k] = before.deg[i][k] + (deg[i][k] - before.deg[i][k]) * t;
        for (int k = 0; k < 3; k++) offset[k] = before.offset[k] + (offset[k] - before.offset[k]) * t;
    }

    /** Blends a towards b by t (0..1), per bone and axis. */
    public static Pose lerp(Pose a, Pose b, float t) {
        Pose p = new Pose();
        for (int i = 0; i < p.deg.length; i++) for (int k = 0; k < 3; k++) p.deg[i][k] = a.deg[i][k] + (b.deg[i][k] - a.deg[i][k]) * t;
        for (int k = 0; k < 3; k++) p.offset[k] = a.offset[k] + (b.offset[k] - a.offset[k]) * t;
        p.gait = t < 0.5f ? a.gait : b.gait;
        return p;
    }
}
