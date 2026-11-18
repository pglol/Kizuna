package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

/**
 * Emotes, seen by everyone around: a wave, the Survey Corps salute (fist to the heart), a bow,
 * a cheer, pointing ahead, and sitting down. They ease in and out; moving ends them.
 */
public final class Emotes {
    private Emotes() {}

    public static final int WAVE = 1, SALUTE = 2, BOW = 3, CHEER = 4, POINT = 5, SIT = 6, CLAP = 7, SHRUG = 8, LAUGH = 9, THINK = 10,
        FLEX = 11, TAUNT = 12, FACEPALM = 13, DANCE = 14, KNEEL = 15, STRETCH = 16;
    public static final String[] NAMES = {"", "Wave", "Salute", "Bow", "Cheer", "Point", "Sit", "Clap", "Shrug", "Laugh", "Think",
        "Flex", "Taunt", "Facepalm", "Dance", "Kneel", "Stretch"};

    private record Playing(int kind, long at, Vec3d where) { }

    private static final Map<Integer, Playing> playing = new HashMap<>();

    public static void on(Net.Emote e) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        var ent = mc.world == null ? null : mc.world.getEntityById(e.entity());
        if (e.kind() == 0 || ent == null) playing.remove(e.entity());
        else playing.put(e.entity(), new Playing(e.kind(), Util.getMeasuringTimeMs(), ent.getPos()));
    }

    private static long length(int kind) {
        return switch (kind) {
            case SIT, KNEEL -> 60_000;
            case SALUTE -> 3500;
            case SHRUG -> 1800;
            case THINK -> 4200;
            case DANCE -> 8000;
            case STRETCH -> 3600;
            case FLEX -> 3000;
            default -> 2800;
        };
    }

    /** How far in (0..1) an emote is for this body, or -1; ends it if they've moved or it's over. */
    private static float weight(LivingEntity e, Playing p) {
        long t = Util.getMeasuringTimeMs() - p.at(), len = length(p.kind());
        if (t > len || e.getPos().squaredDistanceTo(p.where()) > 0.04 || e.deathTime > 0) {
            playing.remove(e.getId());
            return -1;
        }
        return Math.min(1, Math.min(t / 220f, (len - t) / 260f));
    }

    public static boolean active(int id) {
        return playing.containsKey(id);
    }

    public static boolean transforms(LivingEntity e, MatrixStack ms) {
        Playing p = playing.get(e.getId());
        if (p == null) return false;
        float k = weight(e, p);
        if (k < 0) return false;
        float h = e.getHeight() * 0.5f;
        if (p.kind() == BOW) {
            ms.translate(0, h, 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-38 * ease(k)));
            ms.translate(0, -h, 0);
        } else if (p.kind() == SIT) ms.translate(0, -0.62f * ease(k), 0);
        else if (p.kind() == KNEEL) ms.translate(0, -0.55f * ease(k), 0);
        else if (p.kind() == DANCE) {
            float t = (Util.getMeasuringTimeMs() - p.at()) / 1000f, q = ease(k);
            ms.translate(0, Math.abs(MathHelper.sin(t * 7)) * 0.08f * q, 0);
            ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.sin(t * 3.5f) * 22 * q));
            ms.translate(0, h, 0);
            ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(t * 7) * 5 * q));
            ms.translate(0, -h, 0);
        } else if (p.kind() == LAUGH || p.kind() == STRETCH || p.kind() == FACEPALM) {
            float t = (Util.getMeasuringTimeMs() - p.at()) / 1000f;
            float deg = p.kind() == FACEPALM ? -10 : p.kind() == STRETCH ? 7 : 6 + MathHelper.sin(t * 16) * 2;
            ms.translate(0, h, 0);
            ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(deg * ease(k)));
            ms.translate(0, -h, 0);
        }
        return false;
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /** The limbs, blended in by how far in the emote is. */
    public static boolean pose(BipedEntityModel<?> m, LivingEntity e, float age) {
        Playing p = playing.get(e.getId());
        if (p == null) return false;
        float k = weight(e, p);
        if (k < 0) return false;
        k = ease(k);
        float t = (Util.getMeasuringTimeMs() - p.at()) / 1000f;
        switch (p.kind()) {
            case WAVE -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -2.7f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, -0.25f + MathHelper.sin(t * 11) * 0.35f);
                m.rightArm.yaw = 0;
            }
            case SALUTE -> {
                // Right fist over the heart, left hand behind the back, standing straight.
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -1.25f);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.95f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, -0.1f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, 0.45f);
                m.leftArm.yaw = MathHelper.lerp(k, m.leftArm.yaw, 0.4f);
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, 0);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, 0);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.08f);
            }
            case BOW -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, 0.25f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, 0.25f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, 0.5f);
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, 0);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, 0);
            }
            case CHEER -> {
                float bob = MathHelper.sin(t * 9) * 0.18f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -2.9f + bob);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -2.9f - bob);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, -0.3f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, 0.3f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.3f);
            }
            case POINT -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -1.55f + m.head.pitch);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, m.head.yaw);
            }
            case SIT -> {
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, -1.45f);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, -1.45f);
                m.rightLeg.yaw = MathHelper.lerp(k, m.rightLeg.yaw, 0.25f);
                m.leftLeg.yaw = MathHelper.lerp(k, m.leftLeg.yaw, -0.25f);
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -0.5f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.5f);
            }
            case CLAP -> {
                float c = MathHelper.sin(t * 16) * 0.2f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -1.3f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -1.3f);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.5f + c);
                m.leftArm.yaw = MathHelper.lerp(k, m.leftArm.yaw, -0.5f - c);
            }
            case SHRUG -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -0.85f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.85f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, 0.6f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, -0.6f);
                m.head.roll = MathHelper.lerp(k, m.head.roll, 0.22f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.1f);
            }
            case LAUGH -> {
                float b = MathHelper.sin(t * 18) * 0.08f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -0.8f + b);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.8f - b);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.6f);
                m.leftArm.yaw = MathHelper.lerp(k, m.leftArm.yaw, -0.6f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.45f + b);
            }
            case THINK -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -2.0f);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.55f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.9f);
                m.leftArm.yaw = MathHelper.lerp(k, m.leftArm.yaw, -0.8f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, 0.15f);
                m.head.roll = MathHelper.lerp(k, m.head.roll, 0.12f);
            }
            case FLEX -> {
                float pump = MathHelper.sin(t * 6) * 0.12f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -0.2f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.2f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, 2.3f + pump);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, -2.3f - pump);
                m.head.yaw = MathHelper.lerp(k, m.head.yaw, t < 1.5f ? 0.5f : -0.5f);
            }
            case TAUNT -> {
                float b = MathHelper.sin(t * 12) * 0.28f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -1.5f + b);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, -0.1f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -0.3f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, -0.35f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.18f);
            }
            case FACEPALM -> {
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -2.3f);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.5f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, 0.5f);
                m.head.yaw = MathHelper.lerp(k, m.head.yaw, MathHelper.sin(t * 4) * 0.15f);
            }
            case DANCE -> {
                float a = MathHelper.sin(t * 7), b = MathHelper.cos(t * 7);
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -1.6f + a * 1.2f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, -1.6f - a * 1.2f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, 0.4f + b * 0.3f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, -0.4f + b * 0.3f);
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, a * 0.35f);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, -a * 0.35f);
                m.head.roll = MathHelper.lerp(k, m.head.roll, b * 0.2f);
            }
            case KNEEL -> {
                m.rightLeg.pitch = MathHelper.lerp(k, m.rightLeg.pitch, -1.55f);
                m.leftLeg.pitch = MathHelper.lerp(k, m.leftLeg.pitch, 0.35f);
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, -0.65f);
                m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, 0.1f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, 0.05f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, 0.4f);
            }
            case STRETCH -> {
                boolean up = t < 1.9f;
                m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, up ? -3.0f : -0.2f);
                m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, up ? -3.0f : -0.2f);
                m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, up ? -0.22f : 1.6f);
                m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, up ? 0.22f : -1.6f);
                m.head.pitch = MathHelper.lerp(k, m.head.pitch, -0.35f);
            }
            default -> { }
        }
        m.hat.copyTransform(m.head);
        if (m instanceof net.minecraft.client.render.entity.model.PlayerEntityModel<?> pm) {
            pm.rightSleeve.copyTransform(pm.rightArm);
            pm.leftSleeve.copyTransform(pm.leftArm);
            pm.rightPants.copyTransform(pm.rightLeg);
            pm.leftPants.copyTransform(pm.leftLeg);
        }
        return true;
    }
}
