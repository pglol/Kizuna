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
