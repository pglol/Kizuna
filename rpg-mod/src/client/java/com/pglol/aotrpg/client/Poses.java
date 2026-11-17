package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * How bodies move on top of the ordinary animation: everyone's chosen run style (blended in as
 * they break into a sprint and out as they slow), and the slam slash (blades raised overhead on
 * the way down).
 *
 * Run styles: Ordinary; Shinobi (leaning hard, arms swept straight back); Blade Runner (blades
 * held low and out to the sides); Scout (compact and quick, a short lean); Charger (big pumping
 * arms, chest up).
 */
public final class Poses {
    private Poses() {}

    public static final String[] STYLES = {"", "shinobi", "blade_runner", "scout", "charger"};
    public static final String[] NAMES = {"Ordinary", "Shinobi", "Blade Runner", "Scout", "Charger"};

    private static final Map<UUID, String> styles = new HashMap<>();
    /** How far into the run style each body is (0 .. 1), and when it was last drawn. */
    private static final Map<Integer, float[]> blend = new HashMap<>();

    public static void on(Net.RunStyle s) {
        styles.put(s.player(), s.style());
    }

    /** Tells the server (and so everyone) which style is mine. */
    public static void announce() {
        if (ClientPlayNetworking.canSend(Net.RunStyleSet.ID)) ClientPlayNetworking.send(new Net.RunStyleSet(ShoulderCam.cfg.runStyle));
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) styles.put(mc.player.getUuid(), ShoulderCam.cfg.runStyle);
    }

    public static String name(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return NAMES[i];
        return NAMES[0];
    }

    public static String next(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return STYLES[(i + 1) % STYLES.length];
        return STYLES[1];
    }

    public static void apply(PlayerEntityModel<?> m, LivingEntity e, float limbAngle, float limbDistance, float age) {
        if (!(e instanceof PlayerEntity pl)) return;
        long now = Util.getMeasuringTimeMs();
        // Slammed into a wall: limp, arms and legs thrown wide, head lolling.
        if (AdrenalineClient.pinned(e.getId())) {
            m.rightArm.pitch = -0.4f;
            m.leftArm.pitch = -0.3f;
            m.rightArm.roll = 1.9f;
            m.leftArm.roll = -1.7f;
            m.rightArm.yaw = 0;
            m.leftArm.yaw = 0;
            m.rightLeg.pitch = 0.15f;
            m.leftLeg.pitch = -0.1f;
            m.rightLeg.roll = 0.35f;
            m.leftLeg.roll = -0.3f;
            m.head.pitch = 0.45f;
            m.head.roll = 0.3f;
            m.body.pitch = -0.15f;
            sync(m);
            return;
        }
        // The slam: blades high overhead coming down, then driven down at the end.
        OdmMoves.Anim a = OdmMoves.anim(e.getId());
        if (a != null && a.kind() >= OdmMoves.SLAM) {
            float k = OdmMoves.progress(a);
            float up = k < 0.7f ? 1 : 1 - (k - 0.7f) / 0.3f;
            float armP = MathHelper.lerp(up, -0.4f, -3.0f);
            m.rightArm.pitch = armP;
            m.leftArm.pitch = armP;
            m.rightArm.yaw = -0.15f * up;
            m.leftArm.yaw = 0.15f * up;
            m.rightArm.roll = 0;
            m.leftArm.roll = 0;
            sync(m);
            return;
        }
        String style = styles.getOrDefault(pl.getUuid(), "");
        float[] b = blend.computeIfAbsent(e.getId(), k -> new float[] {0, now});
        float dt = MathHelper.clamp((now - b[1]) / 1000f, 0, 0.1f);
        b[1] = now;
        boolean running = !style.isEmpty() && pl.isSprinting() && !pl.isSwimming() && !pl.isFallFlying() && !pl.hasVehicle() && !pl.isUsingItem()
            && limbDistance > 0.3f;
        b[0] = MathHelper.clamp(b[0] + (running ? dt / 0.22f : -dt / 0.18f), 0, 1);
        if (blend.size() > 256) blend.clear();
        float k = b[0] * b[0] * (3 - 2 * b[0]);
        if (k <= 0.001f || style.isEmpty()) return;
        float swing = MathHelper.cos(limbAngle * 0.6662f) * Math.min(1, limbDistance);
        float lean;
        float rp, lp, rr, lr, ry = 0, ly = 0;
        switch (style) {
            case "shinobi" -> {
                lean = 0.55f;
                rp = 1.45f + swing * 0.05f;
                lp = 1.45f - swing * 0.05f;
                rr = 0.12f;
                lr = -0.12f;
            }
            case "blade_runner" -> {
                lean = 0.35f;
                rp = 0.75f + swing * 0.25f;
                lp = 0.75f - swing * 0.25f;
                rr = 0.75f;
                lr = -0.75f;
            }
            case "scout" -> {
                lean = 0.22f;
                rp = -swing * 0.9f - 0.15f;
                lp = swing * 0.9f - 0.15f;
                rr = 0.05f;
                lr = -0.05f;
                ry = -0.2f;
                ly = 0.2f;
            }
            default -> { // charger
                lean = 0.1f;
                rp = -swing * 1.7f;
                lp = swing * 1.7f;
                rr = 0;
                lr = 0;
            }
        }
        lean(m, lean * k);
        m.rightArm.pitch = MathHelper.lerp(k, m.rightArm.pitch, rp);
        m.leftArm.pitch = MathHelper.lerp(k, m.leftArm.pitch, lp);
        m.rightArm.roll = MathHelper.lerp(k, m.rightArm.roll, rr);
        m.leftArm.roll = MathHelper.lerp(k, m.leftArm.roll, lr);
        m.rightArm.yaw = MathHelper.lerp(k, m.rightArm.yaw, ry);
        m.leftArm.yaw = MathHelper.lerp(k, m.leftArm.yaw, ly);
        // Longer strides for the hard leans.
        if (style.equals("shinobi") || style.equals("blade_runner")) {
            m.rightLeg.pitch *= 1 + 0.35f * k;
            m.leftLeg.pitch *= 1 + 0.35f * k;
        }
        sync(m);
    }

    /** Leans the whole upper body forward about the hips (the way crouching does), keeping the eyes up. */
    private static void lean(PlayerEntityModel<?> m, float lean) {
        float s = lean / 0.5f;
        m.body.pitch = lean;
        m.head.pivotY = 4.2f * s;
        m.body.pivotY = 3.2f * s;
        m.rightArm.pivotY = 2 + 3.2f * s;
        m.leftArm.pivotY = 2 + 3.2f * s;
        m.rightLeg.pivotZ = 0.1f + 3.9f * s;
        m.leftLeg.pivotZ = 0.1f + 3.9f * s;
        m.rightLeg.pivotY = 12 + 0.2f * s;
        m.leftLeg.pivotY = 12 + 0.2f * s;
        m.head.pitch -= lean * 0.6f;
    }

    private static void sync(PlayerEntityModel<?> m) {
        m.hat.copyTransform(m.head);
        m.jacket.copyTransform(m.body);
        m.rightSleeve.copyTransform(m.rightArm);
        m.leftSleeve.copyTransform(m.leftArm);
        m.rightPants.copyTransform(m.rightLeg);
        m.leftPants.copyTransform(m.leftLeg);
    }

    @SuppressWarnings("unused")
    private static void unused(ModelPart p) { }
}
