package net.pglol.kata.client;

import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

/**
 * The starting poses, as set in the pose editor. Each is laid over the footwork, not instead of it.
 *
 * Crouch is what sneaking looks like. Hang is the ledge hold; when shuffling along a ledge the arms'
 * and legs' Out angles open towards the way you're going (the lead hand reaches, the trailing one
 * follows), which is how the ledge moves will build on it.
 */
public final class Presets {
    private Presets() {}

    public static final Pose GUARD = new Pose(), KICK = new Pose(), CROUCH = new Pose(), HANG = new Pose();

    static {
        GUARD.set(Bone.HEAD, 10, -15, 0);
        GUARD.set(Bone.CHEST, 5, 20, 0);
        GUARD.set(Bone.RIGHT_ARM, -36, -25, 7).set(Bone.RIGHT_FOREARM, 89, 0, 0);
        GUARD.set(Bone.LEFT_ARM, -57, -6, 1).set(Bone.LEFT_FOREARM, 102, 0, 0);
        GUARD.set(Bone.RIGHT_LEG, -4, 0, 11).set(Bone.RIGHT_SHIN, 37, 0, 0);
        GUARD.set(Bone.LEFT_LEG, -21, 0, -12).set(Bone.LEFT_SHIN, 15, 0, 0);

        KICK.set(Bone.CHEST, -15, 0, 8);
        KICK.set(Bone.RIGHT_ARM, -54, 8, 22).set(Bone.RIGHT_FOREARM, 90, 0, 0);
        KICK.set(Bone.LEFT_ARM, -29, 0, -24).set(Bone.LEFT_FOREARM, 96, 0, 0);
        KICK.set(Bone.RIGHT_LEG, -95, 0, 32).set(Bone.RIGHT_SHIN, 10, 0, 0);
        KICK.set(Bone.LEFT_SHIN, 18, 0, 0);

        CROUCH.set(Bone.CHEST, 45, 1, 8);
        CROUCH.set(Bone.RIGHT_ARM, -13, 0, 0).set(Bone.RIGHT_FOREARM, 28, 0, 0);
        CROUCH.set(Bone.LEFT_ARM, -26, 0, -4).set(Bone.LEFT_FOREARM, 25, 0, 0);
        CROUCH.set(Bone.RIGHT_LEG, -20, 0, 6).set(Bone.RIGHT_SHIN, 77, 0, 0);
        CROUCH.set(Bone.LEFT_LEG, -52, 0, -6).set(Bone.LEFT_SHIN, 90, 0, 0);

        HANG.set(Bone.HEAD, -30, 0, 0);
        HANG.set(Bone.CHEST, -12, 0, 0);
        HANG.set(Bone.RIGHT_ARM, -149, 17, -10).set(Bone.RIGHT_FOREARM, 22, 0, 0);
        HANG.set(Bone.LEFT_ARM, -149, -12, 10).set(Bone.LEFT_FOREARM, 15, 0, 0);
        HANG.set(Bone.RIGHT_LEG, 10, 0, 0).set(Bone.RIGHT_SHIN, 35, 0, 0);
        HANG.set(Bone.LEFT_LEG, -5, 0, 0).set(Bone.LEFT_SHIN, 20, 0, 0);
    }

    /** Parkour keyframes: halfway up a pull (elbows bent, chest over the hands), a knee up onto the
     *  top (pushing down on it), and a vault (one hand planted, legs tucked and swung to the side). */
    public static final Pose PULL = new Pose(), KNEE = new Pose(), VAULT = new Pose();

    static {
        PULL.set(Bone.HEAD, 10, 0, 0);
        PULL.set(Bone.CHEST, 25, 0, 0);
        PULL.set(Bone.RIGHT_ARM, -70, 0, 10).set(Bone.RIGHT_FOREARM, 110, 0, 0);
        PULL.set(Bone.LEFT_ARM, -70, 0, -10).set(Bone.LEFT_FOREARM, 110, 0, 0);
        PULL.set(Bone.RIGHT_LEG, -30, 0, 0).set(Bone.RIGHT_SHIN, 50, 0, 0);
        PULL.set(Bone.LEFT_LEG, -5, 0, 0).set(Bone.LEFT_SHIN, 30, 0, 0);

        KNEE.set(Bone.HEAD, 15, 0, 0);
        KNEE.set(Bone.CHEST, 45, 0, 0);
        KNEE.set(Bone.RIGHT_ARM, -15, 0, 15).set(Bone.RIGHT_FOREARM, 25, 0, 0);
        KNEE.set(Bone.LEFT_ARM, -15, 0, -15).set(Bone.LEFT_FOREARM, 25, 0, 0);
        KNEE.set(Bone.RIGHT_LEG, -95, 0, 0).set(Bone.RIGHT_SHIN, 110, 0, 0);
        KNEE.set(Bone.LEFT_LEG, 15, 0, 0).set(Bone.LEFT_SHIN, 35, 0, 0);

        VAULT.set(Bone.ROOT, 0, 0, -20);
        VAULT.set(Bone.CHEST, 20, 0, -8);
        VAULT.set(Bone.LEFT_ARM, -45, 0, -35).set(Bone.LEFT_FOREARM, 5, 0, 0);
        VAULT.set(Bone.RIGHT_ARM, -80, 0, 70).set(Bone.RIGHT_FOREARM, 20, 0, 0);
        VAULT.set(Bone.RIGHT_LEG, -75, 0, 30).set(Bone.RIGHT_SHIN, 85, 0, 0);
        VAULT.set(Bone.LEFT_LEG, -70, 0, 30).set(Bone.LEFT_SHIN, 90, 0, 0);
    }

    /** Gripping a wall: hands and feet reaching up in turn as you climb (phase advances as you move). */
    public static Pose climb(float phase) {
        float s = (float) Math.sin(phase);
        Pose p = new Pose();
        p.set(Bone.HEAD, -20, 0, 0);
        p.set(Bone.CHEST, 8, 0, 0);
        p.set(Bone.RIGHT_ARM, -130 + 25 * s, 0, 12).set(Bone.RIGHT_FOREARM, 40 - 20 * s, 0, 0);
        p.set(Bone.LEFT_ARM, -130 - 25 * s, 0, -12).set(Bone.LEFT_FOREARM, 40 + 20 * s, 0, 0);
        p.set(Bone.RIGHT_LEG, -45 - 20 * s, 0, 6).set(Bone.RIGHT_SHIN, 70 + 25 * s, 0, 0);
        p.set(Bone.LEFT_LEG, -45 + 20 * s, 0, -6).set(Bone.LEFT_SHIN, 70 - 25 * s, 0, 0);
        return p;
    }

    /** Running up a wall: high driving knees, arms reaching up for the top in turn. */
    public static Pose runUp(float phase) {
        float s = (float) Math.sin(phase);
        Pose p = new Pose();
        p.set(Bone.HEAD, -30, 0, 0);
        p.set(Bone.CHEST, -6, 0, 0);
        p.set(Bone.RIGHT_ARM, -120 - 45 * s, 0, 14).set(Bone.RIGHT_FOREARM, 30 + 25 * s, 0, 0);
        p.set(Bone.LEFT_ARM, -120 + 45 * s, 0, -14).set(Bone.LEFT_FOREARM, 30 - 25 * s, 0, 0);
        p.set(Bone.RIGHT_LEG, -70 - 30 * s, 0, 4).set(Bone.RIGHT_SHIN, 80 + 30 * s, 0, 0);
        p.set(Bone.LEFT_LEG, -70 + 30 * s, 0, -4).set(Bone.LEFT_SHIN, 80 - 30 * s, 0, 0);
        return p;
    }

    /** Springing up for the next ledge: fully stretched, arms high, legs long beneath. */
    public static final Pose REACH = new Pose();

    static {
        REACH.set(Bone.HEAD, -40, 0, 0);
        REACH.set(Bone.CHEST, -10, 0, 0);
        REACH.set(Bone.RIGHT_ARM, -172, 8, 6).set(Bone.RIGHT_FOREARM, 6, 0, 0);
        REACH.set(Bone.LEFT_ARM, -172, -8, -6).set(Bone.LEFT_FOREARM, 6, 0, 0);
        REACH.set(Bone.RIGHT_LEG, 14, 0, 2).set(Bone.RIGHT_SHIN, 8, 0, 0);
        REACH.set(Bone.LEFT_LEG, -6, 0, -2).set(Bone.LEFT_SHIN, 18, 0, 0);
    }

    /** Coiling for a ledge jump: elbows pull in, knees tuck, leaning away from where you'll go (side). */
    public static Pose coil(int side) {
        Pose p = HANG.copy();
        p.set(Bone.RIGHT_FOREARM, 70, 0, 0).set(Bone.LEFT_FOREARM, 70, 0, 0);
        p.set(Bone.RIGHT_ARM, -120, 0, 6).set(Bone.LEFT_ARM, -120, 0, -6);
        p.set(Bone.RIGHT_LEG, -45, 0, 0).set(Bone.RIGHT_SHIN, 75, 0, 0);
        p.set(Bone.LEFT_LEG, -35, 0, 0).set(Bone.LEFT_SHIN, 65, 0, 0);
        p.set(Bone.CHEST, 12, 0, side * 8);
        p.set(Bone.ROOT, 0, 0, side * 10);
        return p;
    }

    /** A caught landing, rough by r (0..1): deep staggered crouch, a hand down, the other out (both down
     *  when it's rough), leaning into it. sway adds the wobble. */
    public static Pose caught(float r, float sway) {
        Pose p = new Pose();
        p.set(Bone.HEAD, 15 - 10 * r, 0, 0);
        p.set(Bone.CHEST, 32 + 16 * r, 4 * sway, 6 * sway);
        p.set(Bone.ROOT, 8 + 8 * r, 0, 5 * sway * r);
        p.set(Bone.RIGHT_ARM, -38 - 8 * r, 0, 12).set(Bone.RIGHT_FOREARM, 18, 0, 0);
        p.set(Bone.LEFT_ARM, -60 + 25 * r, 0, -50 + 35 * r).set(Bone.LEFT_FOREARM, 20, 0, 0);
        p.set(Bone.RIGHT_LEG, -72 - 10 * r, 0, 8).set(Bone.RIGHT_SHIN, 112 + 12 * r, 0, 0);
        p.set(Bone.LEFT_LEG, -28 + 6 * sway, 0, -10).set(Bone.LEFT_SHIN, 82 + 10 * r, 0, 0);
        p.offset[1] = 5.5f + 3 * r;
        return p;
    }

    /** Face down on the ground, arms out in front where they broke the fall. */
    public static final Pose SPLAT = new Pose(), KNEEL = new Pose();

    static {
        SPLAT.set(Bone.ROOT, 84, 0, 4);
        SPLAT.set(Bone.HEAD, -45, 0, 0);
        SPLAT.set(Bone.RIGHT_ARM, -150, -10, 30).set(Bone.RIGHT_FOREARM, 35, 0, 0);
        SPLAT.set(Bone.LEFT_ARM, -140, 10, -35).set(Bone.LEFT_FOREARM, 45, 0, 0);
        SPLAT.set(Bone.RIGHT_LEG, 6, 0, 6).set(Bone.RIGHT_SHIN, 10, 0, 0);
        SPLAT.set(Bone.LEFT_LEG, -4, 0, -8).set(Bone.LEFT_SHIN, 25, 0, 0);
        SPLAT.offset[1] = 1.5f;

        KNEEL.set(Bone.ROOT, 35, 0, 0);
        KNEEL.set(Bone.CHEST, 25, 0, 0);
        KNEEL.set(Bone.RIGHT_ARM, -55, 0, 10).set(Bone.RIGHT_FOREARM, 15, 0, 0);
        KNEEL.set(Bone.LEFT_ARM, -50, 0, -10).set(Bone.LEFT_FOREARM, 15, 0, 0);
        KNEEL.set(Bone.RIGHT_LEG, -85, 0, 4).set(Bone.RIGHT_SHIN, 115, 0, 0);
        KNEEL.set(Bone.LEFT_LEG, 20, 0, -4).set(Bone.LEFT_SHIN, 100, 0, 0);
        KNEEL.offset[1] = 6;
    }

    /** Leaping sideways towards side (1 right): that hand reaching out and up for the lip, the other
     *  lower, legs trailing the other way. */
    public static Pose sideReach(int side) {
        Pose p = new Pose();
        Bone lead = side > 0 ? Bone.RIGHT_ARM : Bone.LEFT_ARM, trail = side > 0 ? Bone.LEFT_ARM : Bone.RIGHT_ARM;
        Bone leadF = side > 0 ? Bone.RIGHT_FOREARM : Bone.LEFT_FOREARM, trailF = side > 0 ? Bone.LEFT_FOREARM : Bone.RIGHT_FOREARM;
        p.set(Bone.HEAD, -20, 0, 0);
        p.set(Bone.CHEST, -4, 0, side * 10);
        p.set(lead, -118, 0, side * 52).set(leadF, 8, 0, 0);
        p.set(trail, -60, 0, -side * 8).set(trailF, 35, 0, 0);
        p.set(Bone.RIGHT_LEG, -10, 0, -side * 16).set(Bone.RIGHT_SHIN, 30, 0, 0);
        p.set(Bone.LEFT_LEG, -25, 0, -side * 16).set(Bone.LEFT_SHIN, 45, 0, 0);
        p.set(Bone.ROOT, 0, 0, side * 8);
        return p;
    }

    public static Pose of(String name) {
        return switch (name) {
            case "Guard" -> GUARD;
            case "Kick" -> KICK;
            case "Crouch" -> CROUCH;
            case "Hang" -> HANG;
            default -> null;
        };
    }

    /** How far the hips drop for the crouch so the feet stay on the ground (model pixels), on top of
     *  the 2 Minecraft already lowers a sneaking player. */
    public static final float CROUCH_DROP = 1.4f;
}
