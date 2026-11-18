package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;

/**
 * Marleyan troops fighting with their bodies: both blades drawn back over the shoulder as a cut
 * winds up (the longer the wind-up, the further back), a full sweeping slash across the body
 * from one hand or the other, blades crossed in front on guard, a two-handed shove, and the APG
 * shouldered and sighted while aiming.
 */
public final class TroopPoses {
    private TroopPoses() {}

    private record Move(int kind, long at, int ms, int hand) { }

    private static final Map<Integer, Move> moves = new HashMap<>();

    public static void on(Net.TroopAnim a) {
        if (a.kind() == 0) {
            moves.remove(a.entity());
            return;
        }
        int ms = switch (a.kind()) {
            case 2 -> 260;
            case 5 -> 320;
            case 7 -> 240;
            case 9 -> 300;
            default -> Math.max(100, a.ticks() * 50);
        };
        moves.put(a.entity(), new Move(a.kind(), Util.getMeasuringTimeMs(), ms, a.hand()));
        if (moves.size() > 256) moves.values().removeIf(m -> Util.getMeasuringTimeMs() - m.at > 5000);
    }

    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }

    /** After the model's own animation: act out the current move, if any. */
    public static void apply(PlayerEntityModel<VillagerEntity> m, VillagerEntity e, float headPitchDeg) {
        Move mv = moves.get(e.getId());
        if (mv == null) return;
        long now = Util.getMeasuringTimeMs();
        float t = (now - mv.at) / (float) mv.ms;
        // Each move eases back to rest over a short tail.
        float tail = 180f / mv.ms;
        if (t > 1 + tail) {
            moves.remove(e.getId());
            return;
        }
        float fade = t <= 1 ? 1 : 1 - ease((t - 1) / tail);
        t = MathHelper.clamp(t, 0, 1);
        boolean left = mv.hand == 1;
        float s = left ? -1 : 1;
        float rp = m.rightArm.pitch, ry = m.rightArm.yaw, rr = m.rightArm.roll;
        float lp = m.leftArm.pitch, ly = m.leftArm.yaw, lr = m.leftArm.roll;
        float by = m.body.yaw;
        switch (mv.kind) {
            case 1 -> {
                // Winding up: both blades drawn up and back over the leading shoulder, body coiling.
                float k = ease(t);
                float shake = t > 0.75f ? (float) Math.sin(now / 22.0) * 0.04f : 0;
                rp = MathHelper.lerp(k, rp, -2.7f + shake);
                ry = MathHelper.lerp(k, ry, 0.55f);
                rr = MathHelper.lerp(k, rr, -0.35f);
                lp = MathHelper.lerp(k, lp, -2.35f + shake);
                ly = MathHelper.lerp(k, ly, -0.15f);
                lr = MathHelper.lerp(k, lr, 0.45f);
                by = MathHelper.lerp(k, by, 0.4f * s);
            }
            case 2 -> {
                // The cut: from high behind the shoulder, sweeping down and across the body.
                float k = ease(Math.min(1, t * 1.25f));
                float lead = MathHelper.lerp(k, -2.7f, -0.35f), across = MathHelper.lerp(k, 0.6f, -0.75f);
                float trail = MathHelper.lerp(k, -2.3f, -0.9f);
                if (!left) {
                    rp = lead;
                    ry = across;
                    rr = MathHelper.lerp(k, -0.35f, 0.25f);
                    lp = trail;
                    ly = MathHelper.lerp(k, -0.15f, 0.35f);
                    lr = 0.2f;
                } else {
                    lp = lead;
                    ly = -across;
                    lr = MathHelper.lerp(k, 0.35f, -0.25f);
                    rp = trail;
                    ry = MathHelper.lerp(k, 0.15f, -0.35f);
                    rr = -0.2f;
                }
                by = MathHelper.lerp(k, 0.4f * s, -0.5f * s);
            }
            case 3 -> {
                // Guard: blades crossed in front of the chest.
                float k = ease(Math.min(1, t * 5));
                rp = MathHelper.lerp(k, rp, -1.45f);
                ry = MathHelper.lerp(k, ry, -0.65f);
                rr = MathHelper.lerp(k, rr, 0);
                lp = MathHelper.lerp(k, lp, -1.35f);
                ly = MathHelper.lerp(k, ly, 0.7f);
                lr = MathHelper.lerp(k, lr, 0);
                by = MathHelper.lerp(k, by, 0);
            }
            case 4 -> {
                // Aiming the APG: shouldered and sighted along the head, the other hand steadying it.
                float k = ease(Math.min(1, t * 4));
                float aim = -1.5708f + headPitchDeg * MathHelper.RADIANS_PER_DEGREE;
                rp = MathHelper.lerp(k, rp, aim);
                ry = MathHelper.lerp(k, ry, -0.1f);
                lp = MathHelper.lerp(k, lp, aim + 0.1f);
                ly = MathHelper.lerp(k, ly, 0.55f);
            }
            case 5 -> {
                // A shove: both arms driven straight out.
                float k = t < 0.35f ? ease(t / 0.35f) : 1;
                rp = MathHelper.lerp(k, rp, -1.55f);
                lp = MathHelper.lerp(k, lp, -1.55f);
                ry = MathHelper.lerp(k, ry, -0.2f);
                ly = MathHelper.lerp(k, ly, 0.2f);
                by = MathHelper.lerp(k, by, 0);
            }
            case 6 -> {
                float k = ease(Math.min(1, t * 2.5f));
                if (mv.hand == 1) {
                    // Both fists up over the head (a lift, or a leap coming down).
                    rp = MathHelper.lerp(k, rp, -3.0f);
                    lp = MathHelper.lerp(k, lp, -3.0f);
                    ry = MathHelper.lerp(k, ry, -0.25f);
                    ly = MathHelper.lerp(k, ly, 0.25f);
                    rr = MathHelper.lerp(k, rr, 0.15f);
                    lr = MathHelper.lerp(k, lr, -0.15f);
                    by = MathHelper.lerp(k, by, 0);
                } else {
                    // The haymaker cocked: right fist drawn far back, the body wound round, the left out to measure.
                    float shake = t > 0.7f ? (float) Math.sin(now / 25.0) * 0.05f : 0;
                    rp = MathHelper.lerp(k, rp, 0.9f + shake);
                    ry = MathHelper.lerp(k, ry, 0.3f);
                    rr = MathHelper.lerp(k, rr, 0.5f);
                    lp = MathHelper.lerp(k, lp, -1.4f);
                    ly = MathHelper.lerp(k, ly, 0.3f);
                    by = MathHelper.lerp(k, by, 0.7f);
                }
            }
            case 7 -> {
                // A blow: driven straight out (0/1), or down at the ground (2 both, 2/3 a hand).
                float k = ease(Math.min(1, t * 2.2f));
                boolean down = mv.hand >= 2;
                boolean right = mv.hand == 0 || mv.hand == 2;
                float pitch = down ? -0.55f : -1.6f;
                if (down && mv.hand == 2 && t < 0.05f) {
                    rp = lp = pitch;
                }
                if (right || mv.hand == 2) {
                    rp = MathHelper.lerp(k, -2.6f, pitch);
                    ry = MathHelper.lerp(k, 0.3f, -0.1f);
                }
                if (!right || mv.hand == 2) {
                    lp = MathHelper.lerp(k, -2.6f, pitch);
                    ly = MathHelper.lerp(k, -0.3f, 0.1f);
                }
                by = down ? 0 : MathHelper.lerp(k, 0.7f, -0.35f);
            }
            case 8 -> {
                // A headsman coiling: the blade low and back, the body turned away, the other hand forward.
                float k = ease(Math.min(1, t * 3));
                float shake = t > 0.6f ? (float) Math.sin(now / 18.0) * 0.03f : 0;
                rp = MathHelper.lerp(k, rp, 0.5f + shake);
                ry = MathHelper.lerp(k, ry, 1.0f);
                rr = MathHelper.lerp(k, rr, 1.2f);
                lp = MathHelper.lerp(k, lp, -1.2f);
                ly = MathHelper.lerp(k, ly, 0.5f);
                by = MathHelper.lerp(k, by, 0.9f);
            }
            case 9 -> {
                // The head cut: flat across at neck height, the body unwinding behind it.
                float k = ease(Math.min(1, t * 1.6f));
                rp = -1.55f;
                ry = MathHelper.lerp(k, 1.1f, -1.2f);
                rr = MathHelper.lerp(k, 1.2f, -0.2f);
                lp = MathHelper.lerp(k, -1.2f, 0.4f);
                ly = 0.3f;
                by = MathHelper.lerp(k, 0.9f, -0.8f);
            }
            case 10 -> {
                // Reaching for you: both arms out, hands open.
                float k = ease(Math.min(1, t * 2));
                rp = MathHelper.lerp(k, rp, -1.45f);
                lp = MathHelper.lerp(k, lp, -1.45f);
                ry = MathHelper.lerp(k, ry, 0.35f);
                ly = MathHelper.lerp(k, ly, -0.35f);
                rr = MathHelper.lerp(k, rr, 0);
                lr = MathHelper.lerp(k, lr, 0);
                by = MathHelper.lerp(k, by, 0);
            }
            default -> { }
        }
        m.rightArm.pitch = MathHelper.lerp(fade, m.rightArm.pitch, rp);
        m.rightArm.yaw = MathHelper.lerp(fade, m.rightArm.yaw, ry);
        m.rightArm.roll = MathHelper.lerp(fade, m.rightArm.roll, rr);
        m.leftArm.pitch = MathHelper.lerp(fade, m.leftArm.pitch, lp);
        m.leftArm.yaw = MathHelper.lerp(fade, m.leftArm.yaw, ly);
        m.leftArm.roll = MathHelper.lerp(fade, m.leftArm.roll, lr);
        m.body.yaw = MathHelper.lerp(fade, m.body.yaw, by);
        // The shoulders turn with the body.
        m.rightArm.pivotZ = MathHelper.sin(m.body.yaw) * 5;
        m.rightArm.pivotX = -MathHelper.cos(m.body.yaw) * 5;
        m.leftArm.pivotZ = -MathHelper.sin(m.body.yaw) * 5;
        m.leftArm.pivotX = MathHelper.cos(m.body.yaw) * 5;
        m.rightSleeve.copyTransform(m.rightArm);
        m.leftSleeve.copyTransform(m.leftArm);
        m.jacket.copyTransform(m.body);
    }
}
