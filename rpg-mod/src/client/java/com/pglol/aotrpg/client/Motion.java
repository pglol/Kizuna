package com.pglol.aotrpg.client;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

import java.util.HashMap;
import java.util.Map;

/**
 * Bodies that move like bodies, for players and people alike: a flinch from every hit, a tumble
 * when thrown, knees tucked on a jump and arms flung up in a long fall, a squash on landing, the
 * whole body leaning into its speed (forward at a run, banking into turns, diving in flight), and
 * a real death: the knees go, and they pitch forward onto their face instead of tipping sideways.
 */
public final class Motion {
    private Motion() {}

    private static final class St {
        float lean, bank, vy, lastVy, land, tumble, dive, diveAngle;
        int fallTicks;
        long at;
        boolean ground = true;
        int hurtWas;
        long thrownUntil;
    }

    private static final Map<Integer, St> states = new HashMap<>();

    private static boolean ours(LivingEntity e) {
        return e instanceof PlayerEntity || e instanceof VillagerEntity;
    }

    private static St st(LivingEntity e) {
        if (states.size() > 256) states.clear();
        return states.computeIfAbsent(e.getId(), k -> new St());
    }

    /** Busy with something that sets its own whole pose (pinned, face down, a slam). */
    private static boolean posed(LivingEntity e) {
        return FinishScene.in(e.getId()) || AdrenalineClient.pinned(e.getId()) || AdrenalineClient.faceplant(e.getId()) > 0 || OdmMoves.anim(e.getId()) != null;
    }

    /** Called as each body is drawn: works out how it's moving, eased frame to frame. */
    private static void track(LivingEntity e, St s, float td) {
        long now = Util.getMeasuringTimeMs();
        float dt = s.at == 0 ? 0.016f : MathHelper.clamp((now - s.at) / 1000f, 0.001f, 0.1f);
        if (now - s.at < 4) return;
        s.at = now;
        double dx = e.getX() - e.prevX, dy = e.getY() - e.prevY, dz = e.getZ() - e.prevZ;
        double yr = Math.toRadians(e.bodyYaw);
        double fwd = dx * -Math.sin(yr) + dz * Math.cos(yr);
        double left = dx * Math.cos(yr) + dz * Math.sin(yr);
        boolean air = !e.isOnGround();
        // Leaning into the speed: a run tips you forward, a dive further; strafing banks you over.
        float leanWant = (float) MathHelper.clamp(fwd * (air ? 26 : 20), -10, air ? 38 : 16);
        if (air && dy < -0.3) leanWant += (float) MathHelper.clamp(-dy * 10, 0, 14);
        float bankWant = (float) MathHelper.clamp(left * (air ? 32 : 22), -24, 24);
        float k = 1 - (float) Math.exp(-dt * 9);
        s.lean += (leanWant - s.lean) * k;
        s.bank += (bankWant - s.bank) * k;
        s.lastVy = s.vy;
        s.vy += ((float) dy - s.vy) * (1 - (float) Math.exp(-dt * 14));
        // Landing hard: a squash.
        if (!air && !s.ground && s.lastVy < -0.45f) s.land = Math.min(1, -s.lastVy * 0.9f);
        s.ground = !air;
        s.land = Math.max(0, s.land - dt * 4.5f);
        // Hit and sent flying: a tumble while the throw lasts.
        if (e.hurtTime > s.hurtWas && Math.sqrt(dx * dx + dz * dz) > 0.45) s.thrownUntil = now + 700;
        s.hurtWas = e.hurtTime;
        boolean thrown = now < s.thrownUntil && air;
        // A long fall, or flying fast: the body turns into a dive, head first along the way it's going.
        double hs = Math.sqrt(dx * dx + dz * dz), sp = Math.sqrt(hs * hs + dy * dy);
        if (air && dy < -0.35) s.fallTicks++;
        else if (!air || dy > 0) s.fallTicks = 0;
        boolean diving = air && !thrown && (s.fallTicks > 14 && dy < -0.6 || sp > 1.15);
        s.dive += ((diving ? 1 : 0) - s.dive) * (1 - (float) Math.exp(-dt * 5));
        // How far below level the motion points (0 flat out, 90 straight down).
        float below = (float) Math.toDegrees(Math.atan2(-dy, Math.max(0.05, Math.max(0, fwd) + hs * 0.3)));
        s.diveAngle += (MathHelper.clamp(below, -20, 90) - s.diveAngle) * (1 - (float) Math.exp(-dt * 6));
        s.tumble += ((thrown ? 1 : 0) - s.tumble) * (1 - (float) Math.exp(-dt * 10));
    }

    /** The whole body: lean, bank, squash, tumble, and the death fall. */
    public static void transforms(LivingEntity e, MatrixStack ms, float bodyYaw, float td) {
        if (e.deathTime <= 0 && FinishScene.transforms(e, ms, bodyYaw, td)) return;
        if (!ours(e)) return;
        St s = st(e);
        if (e.deathTime > 0) {
            // The knees go first, then forward onto the face.
            float p = MathHelper.clamp((e.deathTime + td - 1) / 16f, 0, 1);
            float buckle = Math.min(1, p / 0.3f);
            float fall = MathHelper.clamp((p - 0.22f) / 0.78f, 0, 1);
            fall = fall * fall * (3 - 2 * fall);
            ms.translate(0, -0.35f * buckle * (1 - fall), 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-88 * fall));
            ms.translate(0, -0.1f * fall, 0.12f * fall);
            return;
        }
        if (posed(e) || e.hasVehicle() || e.isSwimming() || e.isFallFlying() || e.isSleeping()) return;
        track(e, s, td);
        float h = e.getHeight() * 0.5f;
        if (Math.abs(s.lean) > 0.2f || Math.abs(s.bank) > 0.2f || s.tumble > 0.01f || s.dive > 0.01f) {
            ms.translate(0, h, 0);
            // In a dive the body lies along its motion: flat out at speed, head down in a plunge.
            float lean = MathHelper.lerp(s.dive, s.lean, 90 + s.diveAngle);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-lean + 38 * s.tumble));
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(s.bank));
            ms.translate(0, -h, 0);
        }
        if (s.land > 0.01f) {
            float q = s.land;
            ms.translate(0, -0.18f * q, 0);
            ms.scale(1 + 0.08f * q, 1 - 0.12f * q, 1 + 0.08f * q);
        }
    }

    /** The limbs: flinch, tumble, jump and fall, landing, and going limp in death. */
    public static void pose(BipedEntityModel<?> m, LivingEntity e, float age) {
        if (FinishScene.pose(m, e)) return;
        if (!ours(e)) return;
        St s = st(e);
        if (e.deathTime > 0) {
            float p = MathHelper.clamp(e.deathTime / 16f, 0, 1);
            m.rightArm.pitch = MathHelper.lerp(p, m.rightArm.pitch, -0.3f);
            m.leftArm.pitch = MathHelper.lerp(p, m.leftArm.pitch, -0.2f);
            m.rightArm.roll = MathHelper.lerp(p, m.rightArm.roll, 0.35f);
            m.leftArm.roll = MathHelper.lerp(p, m.leftArm.roll, -0.3f);
            m.rightLeg.pitch = MathHelper.lerp(p, m.rightLeg.pitch, p < 0.35f ? -0.9f : 0.1f);
            m.leftLeg.pitch = MathHelper.lerp(p, m.leftLeg.pitch, p < 0.35f ? -0.7f : -0.05f);
            m.head.pitch = MathHelper.lerp(p, m.head.pitch, 0.5f);
            sync(m);
            return;
        }
        if (posed(e)) return;
        // The flinch: snapped back from the blow, arms thrown out.
        if (e.hurtTime > 0 && e.maxHurtTime > 0) {
            float k = e.hurtTime / (float) e.maxHurtTime;
            k = k * k;
            m.head.pitch -= 0.45f * k;
            m.body.pitch -= 0.2f * k;
            m.rightArm.pitch -= 0.6f * k;
            m.leftArm.pitch -= 0.5f * k;
            m.rightArm.roll += 0.5f * k;
            m.leftArm.roll -= 0.5f * k;
        }
        // Diving: arms swept ahead (or back along the sides), legs straight and together.
        if (s.dive > 0.01f && s.tumble < 0.5f) {
            float d = s.dive, f = MathHelper.sin(age * 0.6f) * 0.04f;
            boolean plunge = s.diveAngle > 55;
            m.rightArm.pitch = MathHelper.lerp(d, m.rightArm.pitch, plunge ? -3.0f : 0.15f);
            m.leftArm.pitch = MathHelper.lerp(d, m.leftArm.pitch, plunge ? -3.0f : 0.15f);
            m.rightArm.roll = MathHelper.lerp(d, m.rightArm.roll, plunge ? -0.15f : 0.25f);
            m.leftArm.roll = MathHelper.lerp(d, m.leftArm.roll, plunge ? 0.15f : -0.25f);
            m.rightLeg.pitch = MathHelper.lerp(d, m.rightLeg.pitch, f);
            m.leftLeg.pitch = MathHelper.lerp(d, m.leftLeg.pitch, -f);
            m.rightLeg.roll = MathHelper.lerp(d, m.rightLeg.roll, 0.03f);
            m.leftLeg.roll = MathHelper.lerp(d, m.leftLeg.roll, -0.03f);
            m.head.pitch = MathHelper.lerp(d, m.head.pitch, -0.9f);
        }
        // Thrown: limbs flailing in the air.
        if (s.tumble > 0.01f) {
            float t = s.tumble, f = MathHelper.sin(age * 0.9f);
            m.rightArm.pitch = MathHelper.lerp(t, m.rightArm.pitch, -2.4f + f * 0.4f);
            m.leftArm.pitch = MathHelper.lerp(t, m.leftArm.pitch, -2.1f - f * 0.4f);
            m.rightArm.roll = MathHelper.lerp(t, m.rightArm.roll, 0.6f);
            m.leftArm.roll = MathHelper.lerp(t, m.leftArm.roll, -0.6f);
            m.rightLeg.pitch = MathHelper.lerp(t, m.rightLeg.pitch, -0.7f + f * 0.3f);
            m.leftLeg.pitch = MathHelper.lerp(t, m.leftLeg.pitch, 0.4f - f * 0.3f);
        } else if (s.dive < 0.3f && !e.isOnGround() && !e.hasVehicle() && !e.isSwimming() && !(e instanceof PlayerEntity p && Poses.airborne(p))) {
            // A jump (knees tucked, arms up on the way) or a short drop (arms out for balance).
            if (s.vy > 0.05f) {
                float k = MathHelper.clamp(s.vy * 3, 0, 1);
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, -0.55f);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, 0.15f);
                m.rightArm.pitch = MathHelper.lerp(k * 0.6f, m.rightArm.pitch, -0.7f);
                m.leftArm.pitch = MathHelper.lerp(k * 0.6f, m.leftArm.pitch, 0.4f);
            } else if (s.vy < -0.15f) {
                float k = MathHelper.clamp(-s.vy * 1.6f, 0, 1);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, 0.9f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, -0.9f);
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, 0.2f);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, -0.25f);
            }
        }
        // Landing: knees bend under it.
        if (s.land > 0.01f) {
            float q = s.land;
            m.rightLeg.pitch = MathHelper.lerp(q, m.rightLeg.pitch, -0.45f);
            m.leftLeg.pitch = MathHelper.lerp(q, m.leftLeg.pitch, -0.45f);
            m.body.pitch += 0.25f * q;
            m.head.pitch -= 0.15f * q;
        }
        sync(m);
    }

    private static void sync(BipedEntityModel<?> m) {
        m.hat.copyTransform(m.head);
        if (m instanceof PlayerEntityModel<?> pm) {
            pm.jacket.copyTransform(pm.body);
            pm.rightSleeve.copyTransform(pm.rightArm);
            pm.leftSleeve.copyTransform(pm.leftArm);
            pm.rightPants.copyTransform(pm.rightLeg);
            pm.leftPants.copyTransform(pm.leftLeg);
        }
    }
}
