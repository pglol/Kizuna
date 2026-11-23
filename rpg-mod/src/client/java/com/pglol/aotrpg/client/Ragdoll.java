package com.pglol.aotrpg.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.Map;

/**
 * A body knocked limp and flying: it tumbles head over heels the way it was hit, limbs trailing
 * and flailing, slowing as it loses force, and comes round upright as it ends (so it lands or
 * sticks in the next wall without a snap).
 */
public final class Ragdoll {
    private Ragdoll() {}

    /** start, end (ms), the way it flies (yaw), spin direction and a little wobble per body. */
    private record R(long at, long until, float yaw, float side, float seed) { }

    private static final Map<Integer, R> active = new HashMap<>();

    public static void start(int id, float yaw, int ticks) {
        long now = Util.getMeasuringTimeMs();
        if (active.size() > 64) active.values().removeIf(r -> r.until() < now);
        active.put(id, new R(now, now + Math.max(300, ticks * 50L), yaw, Math.random() < 0.5 ? -1 : 1, (float) Math.random() * 10));
    }

    public static void stop(int id) {
        active.remove(id);
    }

    public static boolean active(int id) {
        R r = active.get(id);
        return r != null && Util.getMeasuringTimeMs() < r.until();
    }

    private static float progress(R r) {
        return MathHelper.clamp((Util.getMeasuringTimeMs() - r.at()) / (float) (r.until() - r.at()), 0, 1);
    }

    /** Whole-body tumble. True if it took the body over. */
    public static boolean transforms(LivingEntity e, MatrixStack ms, float bodyYaw) {
        R r = active.get(e.getId());
        if (r == null || Util.getMeasuringTimeMs() >= r.until()) return false;
        float k = progress(r);
        float secs = (r.until() - r.at()) / 1000f;
        // Whole turns, fast at first and easing off, ending upright.
        int turns = Math.max(1, Math.round(secs * 1.7f));
        float ease = 1 - (1 - k) * (1 - k) * (1 - k);
        float flip = turns * 360f * ease;
        float h = e.getHeight() * 0.5f;
        ms.translate(0, h, 0);
        // Into the direction it's flying (the model is already turned to the body's yaw).
        float rel = MathHelper.wrapDegrees(r.yaw() - bodyYaw);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-rel));
        // Head over heels backward, with a corkscrew wobble.
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(flip));
        float wob = (1 - k) * 28 * MathHelper.sin(k * 20 + r.seed());
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(r.side() * wob));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(rel));
        ms.translate(0, -h, 0);
        return true;
    }

    /** Limbs flung out and flailing, the head thrown back. True if it posed the body. */
    public static boolean pose(BipedEntityModel<?> m, LivingEntity e, float age) {
        R r = active.get(e.getId());
        if (r == null || Util.getMeasuringTimeMs() >= r.until()) return false;
        float k = progress(r), t = (Util.getMeasuringTimeMs() - r.at()) / 1000f;
        float flail = 1 - k * 0.6f;
        float a = MathHelper.sin(t * 17 + r.seed()), b = MathHelper.sin(t * 13 + r.seed() * 2), c = MathHelper.sin(t * 11 + r.seed() * 3);
        m.rightArm.pitch = -2.2f + 0.9f * a * flail;
        m.leftArm.pitch = -1.8f + 0.9f * b * flail;
        m.rightArm.roll = 1.2f + 0.5f * c * flail;
        m.leftArm.roll = -1.2f - 0.5f * a * flail;
        m.rightArm.yaw = 0.3f * b;
        m.leftArm.yaw = -0.3f * c;
        m.rightLeg.pitch = 0.7f + 0.6f * b * flail;
        m.leftLeg.pitch = -0.4f + 0.6f * c * flail;
        m.rightLeg.roll = 0.45f + 0.2f * a;
        m.leftLeg.roll = -0.45f - 0.2f * b;
        m.head.pitch = -0.6f + 0.3f * c * flail;
        m.head.yaw = 0.3f * a * flail;
        m.head.roll = 0.25f * b;
        m.body.pitch = 0.2f * a * flail;
        m.hat.copyTransform(m.head);
        if (m instanceof PlayerEntityModel<?> pm) {
            pm.jacket.copyTransform(pm.body);
            pm.rightSleeve.copyTransform(pm.rightArm);
            pm.leftSleeve.copyTransform(pm.leftArm);
            pm.rightPants.copyTransform(pm.rightLeg);
            pm.leftPants.copyTransform(pm.leftLeg);
        }
        return true;
    }
}
