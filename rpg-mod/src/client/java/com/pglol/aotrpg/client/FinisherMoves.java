package com.pglol.aotrpg.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The choreography of every finisher (timings live in FinisherStyles, shared with the server).
 * Each is keyframes, eased between:
 *   path: where the one finishing is, relative to the foe (along the line from them, to its side, up);
 *   by / on: the one finishing's and the foe's limbs and body (lean, drop, turn, flip, roll);
 *   foe: where the foe is drawn (a reel-in pulls them), relative to where they stand.
 */
public final class FinisherMoves {
    private FinisherMoves() {}

    /** A path point at tick t: along (NaN = where the scene started), side, up. */
    public record Way(float t, float along, float side, float up) { }

    public static final class Move {
        final List<Way> path = new ArrayList<>(), foe = new ArrayList<>();
        final List<float[]> by = new ArrayList<>(), on = new ArrayList<>();
        /** The one finishing keeps turned to the foe (round them in a whirl). */
        boolean faceFoe;

        Move way(float t, float along, float side, float up) {
            path.add(new Way(t, along, side, up));
            return this;
        }

        Move foe(float t, float along, float side, float up) {
            foe.add(new Way(t, along, side, up));
            return this;
        }

        Move by(float t, float[] p) {
            return by(t, p, 0, 0, 0);
        }

        Move by(float t, float[] p, float yaw, float flip, float roll) {
            by.add(row(t, p, yaw, flip, roll));
            return this;
        }

        Move on(float t, float[] p) {
            on.add(row(t, p, 0, 0, 0));
            return this;
        }

        Move faceFoe() {
            faceFoe = true;
            return this;
        }
    }

    private static float[] row(float t, float[] p, float yaw, float flip, float roll) {
        float[] r = new float[15];
        r[0] = t;
        System.arraycopy(p, 0, r, 1, Math.min(11, p.length));
        r[12] = yaw;
        r[13] = flip;
        r[14] = roll;
        return r;
    }

    // Poses: rArm pitch/yaw/roll, lArm pitch/yaw/roll, rLeg, lLeg, head, lean (deg), drop (blocks).
    static final float[] N = {0, 0, 0.1f, 0, 0, -0.1f, 0, 0, 0, 0, 0};
    static final float[] DASH = {0.9f, 0, 0.5f, 0.9f, 0, -0.5f, -1.0f, 0.7f, -0.3f, 35, 0.1f};
    static final float[] OVERHEAD = {-3.0f, -0.3f, 0.1f, -2.9f, 0.3f, -0.1f, -0.9f, -0.2f, -0.35f, -15, 0};
    static final float[] CUTDOWN = {0.5f, 0.6f, 0, 0.4f, 0.4f, 0, -0.6f, 0.4f, 0.4f, 25, 0};
    static final float[] KNEEL = {0.4f, 0.3f, 1.25f, 0.4f, -0.3f, -1.25f, -1.55f, 0.35f, 0.35f, 12, 0.55f};
    static final float[] WIDE = {-0.2f, 0, 1.5f, -0.2f, 0, -1.5f, -0.5f, -0.3f, 0.2f, 0, 0};
    static final float[] CROSS_UP = {-2.8f, 0.6f, 0, -2.8f, -0.6f, 0, -0.3f, 0.2f, -0.3f, -8, 0};
    static final float[] CROSS_OUT = {-1.2f, -0.4f, 1.4f, -1.2f, 0.4f, -1.4f, -0.6f, 0.5f, 0.2f, 10, 0.1f};
    static final float[] THRUST = {-1.6f, 0, 0, 0.6f, 0, -0.3f, -0.8f, 0.5f, 0, 20, 0.15f};
    static final float[] SALUTE = {-1.25f, 0.95f, -0.1f, 0.45f, 0.4f, 0, 0, 0, -0.08f, 0, 0};
    static final float[] CROUCH = {0.6f, 0, 0.6f, 0.6f, 0, -0.6f, -1.4f, 0.9f, 0.2f, 25, 0.45f};
    static final float[] UPPER = {-2.9f, 0.2f, 0.3f, -1.0f, 0, -0.6f, -0.9f, 0.5f, -0.5f, -20, 0};
    static final float[] FLURRY_A = {-1.8f, 0.8f, 0.3f, 0.3f, 0, -0.3f, -0.5f, 0.4f, 0.1f, 15, 0.1f};
    static final float[] FLURRY_B = {0.3f, 0, 0.3f, -1.8f, -0.8f, -0.3f, 0.4f, -0.5f, 0.1f, 15, 0.1f};
    static final float[] WALK_A = {0.4f, 0, 0.1f, -0.4f, 0, -0.1f, -0.5f, 0.5f, 0, 4, 0};
    static final float[] WALK_B = {-0.4f, 0, 0.1f, 0.4f, 0, -0.1f, 0.5f, -0.5f, 0, 4, 0};
    static final float[] RAISE_ONE = {-3.1f, 0, 0.1f, 0.1f, 0, -0.2f, -0.3f, 0.3f, -0.3f, -8, 0};
    static final float[] CHOP = {0.6f, 0, 0, 0.2f, 0, -0.2f, -0.7f, 0.5f, 0.4f, 30, 0.2f};
    static final float[] SLASH_ACROSS = {-1.4f, 1.0f, 0.3f, 0.2f, 0, -0.3f, -0.4f, 0.3f, 0, 10, 0.05f};
    static final float[] FOLLOW = {-1.2f, -0.8f, 0.5f, 0.2f, 0, -0.3f, -0.4f, 0.3f, 0.1f, 14, 0.1f};
    static final float[] SHEATHE = {0.2f, -0.4f, 0.2f, 0, 0, -0.1f, 0, 0, 0.1f, 0, 0};
    static final float[] POINT_L = {0.2f, 0, 0.1f, -1.6f, 0, 0, -0.3f, 0.3f, 0, 0, 0};
    static final float[] REEL = {0.8f, 0, 0.2f, -0.9f, 0.3f, 0, -0.5f, 0.6f, 0, -10, 0};
    static final float[] SPEAR_WIND = {0.9f, 0, 0.2f, -1.3f, 0, 0, -0.6f, 0.6f, 0, -5, 0.05f};
    static final float[] SPEAR_THRUST = {-1.55f, 0, 0, -1.55f, 0, 0, -0.9f, 0.6f, 0, 25, 0.1f};
    static final float[] COVER = {-1.4f, -0.4f, 0, -1.4f, 0.4f, 0, -0.2f, 0.3f, 0.4f, 8, 0.15f};
    static final float[] DRAW = {0.8f, -0.6f, 0.1f, 0.2f, 0, -0.2f, -0.9f, 0.7f, 0.1f, 20, 0.25f};
    static final float[] DRILL = {-3.0f, 0, 0, -3.0f, 0, 0, 0.05f, -0.05f, -0.3f, 82, 0};
    static final float[] TUCK = {-2.4f, 0.2f, 0.3f, -2.4f, -0.2f, -0.3f, -1.6f, -1.6f, 0.2f, 0, 0};
    // The foe.
    static final float[] GUARD = {-1.6f, 0.2f, -0.3f, -1.5f, -0.2f, 0.3f, 0.2f, -0.3f, 0.1f, -5, 0};
    static final float[] LOOKUP = {-1.2f, 0, -0.2f, -1.1f, 0, 0.2f, 0.1f, -0.2f, -1.0f, -12, 0};
    static final float[] STRUCK = {-0.4f, 0, 0.9f, -0.3f, 0, -0.9f, 0, 0, -0.6f, -18, 0};
    static final float[] FROZEN = {-0.15f, 0, 0.25f, -0.1f, 0, -0.25f, 0.05f, -0.1f, 0.3f, 6, 0.08f};
    static final float[] KNEELING = {0.1f, 0, 0.3f, 0.1f, 0, -0.3f, -1.5f, -1.5f, 0.45f, 8, 0.55f};
    static final float[] KNEEL_STRUCK = {-0.4f, 0, 0.8f, -0.3f, 0, -0.8f, -1.5f, -1.5f, -0.5f, -12, 0.55f};
    static final float[] PULLED = {-1.4f, 0, 0, -1.4f, 0, 0, 0.3f, 0.3f, -0.2f, 22, 0};
    static final float[] CLUTCH = {-0.9f, -0.5f, 0, -0.9f, 0.5f, 0, 0.1f, -0.1f, 0.5f, 18, 0.1f};
    static final float[] BLOWN = {-2.4f, 0, 1.2f, -2.4f, 0, -1.2f, 0.4f, -0.4f, -0.8f, -35, 0};
    static final float[] CRUMPLE = {0.2f, 0, 0.4f, 0.2f, 0, -0.4f, -0.8f, -0.8f, 0.6f, 22, 0.3f};

    static final float NaN = Float.NaN;

    public static final Move[] ALL = new Move[15];

    static {
        // 0 Nape Cleaver: leap and spin over them, drop with an overhead cut, slide through.
        ALL[0] = new Move().way(0, NaN, 0, 0).way(6, -2.3f, 0, 0).way(11, -1.2f, 0, 2.3f).way(15, -0.5f, 0, 2.7f).way(20, -0.9f, 0, 0)
            .way(23, 0.5f, 0.8f, 0).way(26, 1.9f, 0, 0)
            .by(0, N).by(2, DASH).by(6, DASH).by(8, new float[] {0.6f, 0, 1.2f, 0.6f, 0, -1.2f, -1.3f, -1.1f, -0.4f, 10, 0})
            .by(13, WIDE, 360, 0, 0).by(16.5f, OVERHEAD, 360, 0, 0).by(19.5f, CUTDOWN, 360, 0, 0)
            .by(22, new float[] {1.1f, 0, 0.9f, 1.1f, 0, -0.9f, -1.4f, 0.9f, 0.1f, 30, 0.45f}, 360, 0, 0)
            .by(26, KNEEL, 360, 0, 0).by(44, KNEEL, 360, 0, 0).by(50, new float[] {-0.4f, 0, 1.6f, 0.1f, 0, -0.4f, 0, 0, 0, 0, 0}, 360, 0, 0).by(56, N, 360, 0, 0)
            .on(0, N).on(4, GUARD).on(10, LOOKUP).on(18, LOOKUP).on(20.5f, STRUCK).on(24, FROZEN).on(56, FROZEN);
        // 1 Crossing Blades: up, crossed over the head, drawn apart through them.
        ALL[1] = new Move().way(0, NaN, 0, 0).way(5, -1.4f, 0, 0).way(13, -1.2f, 0, 0).way(16, 0.2f, 0, 0).way(19, 1.6f, 0, 0)
            .by(0, N).by(4, DASH).by(9, CROSS_UP).by(14.5f, CROSS_UP).by(16.5f, CROSS_OUT).by(21, KNEEL).by(40, KNEEL).by(44, N)
            .on(0, N).on(6, GUARD).on(15, GUARD).on(17, STRUCK).on(21, FROZEN).on(44, FROZEN);
        // 2 Whirlwind: twice round them, cutting on every pass.
        Move w = new Move().faceFoe().way(0, NaN, 0, 0).way(6, -1.3f, 0, 0);
        for (int i = 0; i <= 16; i++) {
            double a = Math.PI + i * Math.PI * 2 * 2 / 16;
            w.way(10 + i * 28f / 16, (float) Math.cos(a) * 1.35f, (float) Math.sin(a) * 1.35f, 0.15f * (float) Math.sin(i * 0.8));
        }
        ALL[2] = w.way(44, 1.4f, 0, 0)
            .by(0, N).by(5, DASH).by(10, WIDE, 0, 0, 0).by(38, WIDE, 1440, 0, 0).by(41, KNEEL, 1440, 0, 0).by(56, KNEEL, 1440, 0, 0).by(60, N, 1440, 0, 0)
            .on(0, N).on(8, GUARD).on(18, STRUCK).on(22, GUARD).on(26, STRUCK).on(30, GUARD).on(34, STRUCK).on(38, FROZEN).on(60, FROZEN);
        // 3 Rising Dragon: low under their guard, up through them, a backflip, down in front.
        ALL[3] = new Move().way(0, NaN, 0, 0).way(6, -1.0f, 0, 0).way(12, -0.8f, 0, 0).way(16, -0.3f, 0, 3.2f).way(22, -0.6f, 0, 3.6f).way(30, -1.6f, 0, 0)
            .by(0, N).by(5, DASH).by(10, CROUCH).by(13, CROUCH).by(15, UPPER).by(20, UPPER, 0, 0, 0).by(28, TUCK, 0, -360, 0)
            .by(31, KNEEL, 0, -360, 0).by(48, KNEEL, 0, -360, 0).by(52, N, 0, -360, 0)
            .on(0, N).on(6, GUARD).on(14, STRUCK).on(18, LOOKUP).on(22, STRUCK).on(26, FROZEN).on(52, FROZEN);
        // 4 Thousand Cuts: cut after cut, then the last.
        Move f = new Move().way(0, NaN, 0, 0).way(6, -1.2f, 0, 0).by(0, N).by(5, DASH).by(8, N);
        int[] fc = {10, 13, 16, 19, 22, 25};
        for (int i = 0; i < fc.length; i++) f.by(fc[i], i % 2 == 0 ? FLURRY_A : FLURRY_B);
        ALL[4] = f.by(28, OVERHEAD).by(31, CUTDOWN).by(36, new float[] {0.5f, 0, 0.4f, 0.5f, 0, -0.4f, -0.3f, 0.3f, 0.2f, 8, 0.05f}).by(50, N).by(54, N)
            .on(0, N).on(6, GUARD).on(10, STRUCK).on(13, FROZEN).on(16, STRUCK).on(19, FROZEN).on(22, STRUCK).on(25, FROZEN)
            .on(31, new float[] {-0.6f, 0, 1.0f, -0.5f, 0, -1.0f, 0, 0, -0.7f, -25, 0}).on(35, FROZEN).on(54, FROZEN);
        // 5 Executioner: a slow walk up, they sink to their knees, one blade high, down.
        ALL[5] = new Move().way(0, NaN, 0, 0).way(16, -1.1f, 0, 0)
            .by(0, N).by(4, WALK_A).by(8, WALK_B).by(12, WALK_A).by(16, N).by(22, RAISE_ONE).by(34, RAISE_ONE).by(37, CHOP).by(42, CHOP).by(56, CHOP).by(60, N)
            .on(0, N).on(8, GUARD).on(16, KNEELING).on(34, KNEELING).on(37, KNEEL_STRUCK).on(40, KNEELING).on(60, KNEELING);
        // 6 Shadow Step: gone, here, there, behind them; one quiet cut, the blade put away.
        ALL[6] = new Move().way(0, NaN, 0, 0).way(3, NaN, 0, 0).way(4.5f, -2.6f, 1.8f, 0).way(6.5f, -2.6f, 1.8f, 0).way(8, -0.8f, -1.4f, 0)
            .way(9.5f, -0.8f, -1.4f, 0).way(11, 1.4f, 0.4f, 0)
            .by(0, N).by(3, CROUCH).by(10, N).by(12, N, 180, 0, 0).by(14, SLASH_ACROSS, 180, 0, 0).by(16, FOLLOW, 180, 0, 0)
            .by(21, SHEATHE, 180, 0, 0).by(26, SHEATHE, 0, 0, 0).by(50, N, 0, 0, 0)
            .on(0, N).on(5, GUARD).on(12, GUARD).on(14, FROZEN).on(50, FROZEN);
        // 7 Hook and Reel: back off, a hook into them, reeled in onto the blade, pushed off it.
        ALL[7] = new Move().way(0, NaN, 0, 0).way(6, -3.0f, 0, 0).way(20, -2.7f, 0, 0).way(24, -2.5f, 0, 0).way(30, -0.9f, 0, 0)
            .foe(0, 0, 0, 0).foe(10, 0, 0, 0).foe(20, -1.4f, 0, 0.4f).foe(24, -1.4f, 0, 0.3f).foe(30, 0, 0, 0)
            .by(0, N).by(5, N).by(8, POINT_L).by(18, REEL).by(22, new float[] {0.9f, 0, 0.2f, -0.7f, 0, 0, -0.5f, 0.6f, 0, -8, 0}).by(24, THRUST).by(30, THRUST)
            .by(46, N).by(50, N)
            .on(0, N).on(8, GUARD).on(12, PULLED).on(20, PULLED).on(24, STRUCK).on(30, FROZEN).on(50, FROZEN);
        // 8 Falling Star: up, a full flip at the top, straight down blade first.
        ALL[8] = new Move().way(0, NaN, 0, 0).way(6, -2.0f, 0, 0).way(14, -1.2f, 0, 4.5f).way(22, -0.7f, 0, 5.0f).way(28, -0.7f, 0, 0.2f)
            .by(0, N).by(5, DASH).by(8, CROUCH).by(12, UPPER).by(15, TUCK, 0, 0, 0).by(23, TUCK, 0, 360, 0).by(25, OVERHEAD, 0, 360, 0)
            .by(28, CUTDOWN, 0, 360, 0).by(31, KNEEL, 0, 360, 0).by(48, KNEEL, 0, 360, 0).by(54, N, 0, 360, 0)
            .on(0, N).on(8, LOOKUP).on(26, LOOKUP).on(28, STRUCK).on(31, CRUMPLE).on(54, CRUMPLE);
        // 9 Thunder Spear: a spear driven home, a step back behind your arms, the blast.
        ALL[9] = new Move().way(0, NaN, 0, 0).way(6, -1.4f, 0, 0).way(16, -1.0f, 0, 0).way(22, -2.6f, 0, 0)
            .by(0, N).by(5, DASH).by(10, SPEAR_WIND).by(16, SPEAR_THRUST).by(20, SPEAR_THRUST).by(24, COVER).by(34, COVER).by(38, N).by(58, N)
            .on(0, N).on(6, GUARD).on(16, STRUCK).on(20, CLUTCH).on(33, CLUTCH).on(35, BLOWN).on(39, FROZEN).on(58, FROZEN);
        // 10 Shinzou wo Sasageyo: walk up, fist to the heart, a breath, the draw.
        ALL[10] = new Move().way(0, NaN, 0, 0).way(8, -1.6f, 0, 0).way(28, -1.6f, 0, 0).way(30, 0.4f, 0, 0).way(33, 1.5f, 0, 0)
            .by(0, N).by(3, WALK_A).by(6, WALK_B).by(8, N).by(12, SALUTE).by(24, SALUTE).by(27, DRAW).by(30, SLASH_ACROSS).by(34, KNEEL).by(52, KNEEL).by(56, N)
            .on(0, N).on(10, GUARD).on(24, GUARD).on(30, STRUCK).on(34, FROZEN).on(56, FROZEN);
        // 11 Spiral Drill: spinning flat along the line, through them, out the far side.
        ALL[11] = new Move().way(0, NaN, 0, 0).way(8, -3.2f, 0, 0.8f).way(20, 0, 0, 1.0f).way(26, 2.6f, 0, 0)
            .by(0, N).by(5, CROUCH).by(8, DRILL, 0, 0, 0).by(24, DRILL, 0, 0, 1440).by(27, KNEEL, 0, 0, 1440).by(42, KNEEL, 0, 0, 1440).by(46, N, 0, 0, 1440)
            .on(0, N).on(8, GUARD).on(20, STRUCK).on(24, FROZEN).on(46, FROZEN);
        // 12 The Rumbling: three blows that shake the earth, the last from the sky.
        ALL[12] = new Move().faceFoe().way(0, NaN, 0, 0).way(8, -1.5f, 0, 0).way(18, -0.6f, 1.6f, 0).way(20, -0.3f, 0.6f, 0).way(30, 0.8f, -1.6f, 0)
            .way(32, 0.3f, -0.6f, 0).way(42, -0.2f, 0, 3.0f).way(44, 0, 0, 0.3f).way(50, 0, 1.8f, 0)
            .by(0, N).by(6, DASH).by(16, OVERHEAD).by(20, CUTDOWN).by(28, CROSS_UP).by(32, CROSS_OUT).by(38, TUCK, 0, 0, 0).by(42, OVERHEAD, 0, 360, 0)
            .by(44, CUTDOWN, 0, 360, 0).by(48, KNEEL, 0, 360, 0).by(64, KNEEL, 0, 360, 0).by(70, N, 0, 360, 0)
            .on(0, N).on(8, GUARD).on(20, STRUCK).on(24, GUARD).on(32, KNEEL_STRUCK).on(36, KNEELING).on(44, KNEEL_STRUCK).on(48, KNEELING).on(70, KNEELING);
    }

    // Poses for the 1 of 1s.
    static final float[] STROLL_A = {0.18f, 0, 0.08f, -0.18f, 0, -0.08f, -0.35f, 0.35f, 0.05f, 2, 0};
    static final float[] STROLL_B = {-0.18f, 0, 0.08f, 0.18f, 0, -0.08f, 0.35f, -0.35f, 0.05f, 2, 0};
    static final float[] COCK_FIST = {1.05f, 0.35f, 0.35f, -0.5f, -0.3f, -0.2f, -0.45f, 0.35f, 0, -6, 0.05f};
    static final float[] FIST_THROUGH = {-1.6f, 0.05f, 0, 0.35f, 0, -0.35f, -0.7f, 0.55f, 0.02f, 18, 0.1f};
    static final float[] STARE = {-1.55f, 0.05f, 0, 0.3f, 0, -0.3f, -0.65f, 0.5f, 0, 14, 0.08f};
    static final float[] PULL_OUT = {0.25f, 0, 0.25f, 0.1f, 0, -0.15f, -0.2f, 0.2f, -0.02f, 0, 0};
    static final float[] CHARGE = {0.75f, 0.1f, 0.45f, 0.55f, 0.75f, -0.1f, -1.35f, 0.9f, -0.15f, 32, 0.45f};
    static final float[] CHIDORI_RUN = {0.95f, 0.05f, 0.4f, 0.9f, 0, -0.5f, -1.1f, 0.8f, -0.4f, 48, 0.2f};
    static final float[] CHIDORI_HIT = {-1.55f, 0, 0, 0.6f, 0, -0.4f, -0.9f, 0.7f, -0.1f, 30, 0.2f};
    static final float[] LOOK_BACK = {0.15f, 0, 0.1f, 0.1f, 0, -0.1f, 0, 0, 0.05f, 0, 0};
    static final float[] OPPRESSED = {-0.1f, 0, 0.35f, -0.05f, 0, -0.35f, 0.15f, -0.2f, 0.25f, -6, 0.1f};
    static final float[] IMPALED = {-0.6f, 0, 0.6f, -0.55f, 0, -0.6f, 0.05f, -0.1f, -0.25f, -14, 0.05f};

    static {
        // 13 Overwhelming Pressure: they can't move under it. A slow walk, the fist drawn back, through the chest; the stare.
        Move sp = new Move().way(0, NaN, 0, 0).way(5, NaN, 0, 0).way(27, -0.85f, 0, 0).way(31, -0.85f, 0, 0).way(34, -0.6f, 0, 0).way(58, -0.6f, 0, 0)
            .way(62, -0.7f, 0, 0);
        sp.by(0, N).by(5, N);
        for (int i = 0; i < 6; i++) sp.by(7 + i * 3.4f, i % 2 == 0 ? STROLL_A : STROLL_B);
        ALL[13] = sp.by(27, N).by(31, COCK_FIST).by(34, FIST_THROUGH).by(40, STARE).by(56, STARE).by(60, PULL_OUT).by(66, N).by(84, N)
            .on(0, N).on(6, OPPRESSED).on(31, OPPRESSED).on(34, IMPALED).on(56, IMPALED).on(61, CRUMPLE).on(84, CRUMPLE);
        // 14 Chidori: crouched low, the hand charging; the run; through them; out past, and a look back over the shoulder.
        ALL[14] = new Move().way(0, NaN, 0, 0).way(15, NaN, 0, 0).way(24, -0.55f, 0, 0).way(36, -0.55f, 0, 0).way(42, 1.6f, 0.5f, 0)
            .by(0, N).by(4, CHARGE).by(14, CHARGE).by(18, CHIDORI_RUN).by(24, CHIDORI_RUN).by(26, CHIDORI_HIT).by(36, CHIDORI_HIT)
            .by(40, PULL_OUT, 0, 0, 0).by(44, LOOK_BACK, 0, 0, 0).by(50, LOOK_BACK, 30, 0, 0).by(66, LOOK_BACK, 30, 0, 0).by(70, N, 0, 0, 0)
            .on(0, N).on(8, GUARD).on(22, GUARD).on(26, IMPALED).on(36, IMPALED).on(40, CRUMPLE).on(70, CRUMPLE);
    }

    public static Move of(int style) {
        return style >= 0 && style < ALL.length && ALL[style] != null ? ALL[style] : ALL[0];
    }
}
