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

    public static final String[] STYLES = {"", "shinobi", "blade_runner", "scout", "charger", "reverse_grip", "feral", "glide", "drill", "sprinter", "stalker", "carefree", "captain", "heavy"};
    public static final String[] NAMES = {"Ordinary", "Shinobi", "Blade Runner", "Scout", "Charger", "Reverse Grip", "Feral", "Glide", "Drill", "Sprinter", "Stalker", "Carefree", "Captain", "Heavy"};

    /** Who else is aiming down a gun's sights. */
    private static final java.util.Set<Integer> aimers = new java.util.HashSet<>();

    public static void on(Net.AimSeen a) {
        if (a.on()) aimers.add(a.entity());
        else aimers.remove(a.entity());
    }

    private static boolean lastAim;

    /** Tells the server when I start or stop aiming (so others see the stance). */
    public static void tickAim() {
        boolean now = ShoulderCam.aimingDown();
        if (now != lastAim && ClientPlayNetworking.canSend(Net.AimSet.ID)) ClientPlayNetworking.send(new Net.AimSet(now));
        lastAim = now;
    }

    /** How far into the Reverse Grip run a body is (0 .. 1): its blades turned back while it lasts. */
    public static float reverseGrip(net.minecraft.entity.LivingEntity e) {
        if (!(e instanceof PlayerEntity pl) || !"reverse_grip".equals(runOf(pl.getUuid()))) return 0;
        float[] b = blend.get(e.getId());
        if (b == null) return 0;
        return b[0] * b[0] * (3 - 2 * b[0]);
    }

    /** In the air on the gear (not just a hop): well clear of the ground. */
    public static boolean airborne(PlayerEntity p) {
        if (p.isOnGround() || p.isTouchingWater() || p.hasVehicle() || p.isFallFlying() || p.isClimbing() || p.getAbilities().flying) return false;
        var w = p.getWorld();
        var b = p.getBlockPos();
        return w.getBlockState(b.down()).isAir() && w.getBlockState(b.down(2)).isAir();
    }

    /** No running in mid-air: whatever animates the legs (resource packs too) sees them still. */
    public static void stillLegs(net.minecraft.client.MinecraftClient mc) {
        if (mc.world == null) return;
        for (PlayerEntity p : mc.world.getPlayers()) if (airborne(p)) p.limbAnimator.setSpeed(p.limbAnimator.getSpeed() * 0.3f);
    }

    private static final Map<UUID, String> styles = new HashMap<>();
    /** How far into the run style each body is (0 .. 1), and when it was last drawn. */
    private static final Map<Integer, float[]> blend = new HashMap<>();

    public static void on(Net.RunStyle s) {
        styles.put(s.player(), s.style());
    }

    /** A player's run style ("style;idle" as announced). */
    public static String runOf(UUID id) {
        String s = styles.getOrDefault(id, "");
        int i = s.indexOf(';');
        return i < 0 ? s : s.substring(0, i);
    }

    /** A player's idle style (see Gestures). */
    public static String idleOf(UUID id) {
        String s = styles.getOrDefault(id, "");
        int i = s.indexOf(';');
        return i < 0 ? "" : s.substring(i + 1);
    }

    private static String mine() {
        String idle = ShoulderCam.cfg.idleStyle == null ? "" : ShoulderCam.cfg.idleStyle;
        return ShoulderCam.cfg.runStyle + (idle.isEmpty() ? "" : ";" + idle);
    }

    /** Tells the server (and so everyone) which style is mine. */
    public static void announce() {
        if (ClientPlayNetworking.canSend(Net.RunStyleSet.ID)) ClientPlayNetworking.send(new Net.RunStyleSet(mine()));
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) styles.put(mc.player.getUuid(), mine());
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
        // Face down in the dirt: limbs flung out flat, cheek to the ground.
        if (AdrenalineClient.faceplant(e.getId()) > 0) {
            m.rightArm.pitch = -0.15f;
            m.leftArm.pitch = 0.1f;
            m.rightArm.roll = 0.9f;
            m.leftArm.roll = -0.75f;
            m.rightArm.yaw = 0;
            m.leftArm.yaw = 0;
            m.rightLeg.pitch = 0.05f;
            m.leftLeg.pitch = -0.05f;
            m.rightLeg.roll = 0.22f;
            m.leftLeg.roll = -0.18f;
            m.head.pitch = -0.2f;
            m.head.yaw = 1.1f;
            m.body.pitch = 0;
            sync(m);
            return;
        }
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
        String style = runOf(pl.getUuid());
        float[] b = blend.computeIfAbsent(e.getId(), k -> new float[] {0, now, 0, 0});
        float dt = MathHelper.clamp((now - b[1]) / 1000f, 0, 0.1f);
        b[1] = now;
        boolean running = !style.isEmpty() && pl.isSprinting() && !pl.isSwimming() && !pl.isFallFlying() && !pl.hasVehicle() && !pl.isUsingItem()
            && limbDistance > 0.3f;
        b[0] = MathHelper.clamp(b[0] + (running ? dt / 0.22f : -dt / 0.18f), 0, 1);
        b[2] = MathHelper.clamp(b[2] + (airborne(pl) ? dt / 0.25f : -dt / 0.15f), 0, 1);
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        boolean aim = pl == mc.player ? ShoulderCam.aimingDown() : aimers.contains(pl.getId());
        b[3] = MathHelper.clamp(b[3] + (aim ? dt / 0.12f : -dt / 0.15f), 0, 1);
        if (blend.size() > 256) blend.clear();
        float k = b[0] * b[0] * (3 - 2 * b[0]);
        if (k > 0.001f && !style.isEmpty()) run(m, style, k, limbAngle, limbDistance);
        // On the gear: legs together and trailing, not running on air.
        float fk = b[2] * b[2] * (3 - 2 * b[2]);
        if (fk > 0.001f) {
            float sway = MathHelper.sin(age * 0.12f) * 0.05f;
            m.rightLeg.pitch = MathHelper.lerp(fk, m.rightLeg.pitch, 0.18f + sway);
            m.leftLeg.pitch = MathHelper.lerp(fk, m.leftLeg.pitch, 0.42f - sway);
            m.rightLeg.roll = MathHelper.lerp(fk, m.rightLeg.roll, 0.06f);
            m.leftLeg.roll = MathHelper.lerp(fk, m.leftLeg.roll, -0.04f);
            m.rightLeg.yaw = MathHelper.lerp(fk, m.rightLeg.yaw, 0);
            m.leftLeg.yaw = MathHelper.lerp(fk, m.leftLeg.yaw, 0);
        }
        // Down the sights: the gun up at the shoulder, the other hand bracing it.
        float ak = b[3] * b[3] * (3 - 2 * b[3]);
        if (ak > 0.001f) {
            float hp = m.head.pitch, hy = m.head.yaw;
            m.rightArm.pitch = MathHelper.lerp(ak, m.rightArm.pitch, hp - MathHelper.HALF_PI);
            m.rightArm.yaw = MathHelper.lerp(ak, m.rightArm.yaw, hy - 0.1f);
            m.rightArm.roll = MathHelper.lerp(ak, m.rightArm.roll, 0);
            m.leftArm.pitch = MathHelper.lerp(ak, m.leftArm.pitch, hp - MathHelper.HALF_PI + 0.08f);
            m.leftArm.yaw = MathHelper.lerp(ak, m.leftArm.yaw, hy + 0.55f);
            m.leftArm.roll = MathHelper.lerp(ak, m.leftArm.roll, 0);
        }
        sync(m);
    }

    private static void run(PlayerEntityModel<?> m, String style, float k, float limbAngle, float limbDistance) {
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
            case "reverse_grip" -> {
                // Blades reversed and swept back along the forearms, low and fast.
                lean = 0.5f;
                rp = 1.15f + swing * 0.08f;
                lp = 1.15f - swing * 0.08f;
                rr = 0.5f;
                lr = -0.5f;
                ry = 0.35f;
                ly = -0.35f;
            }
            case "feral" -> {
                // Hunched right over, arms hanging and swinging loose, like something hunting.
                lean = 0.75f;
                rp = -0.55f - swing * 0.7f;
                lp = -0.55f + swing * 0.7f;
                rr = 0.25f;
                lr = -0.25f;
            }
            case "glide" -> {
                // Arms flung out wide like wings.
                lean = 0.4f;
                rp = 0.3f + swing * 0.05f;
                lp = 0.3f - swing * 0.05f;
                rr = 1.25f;
                lr = -1.25f;
            }
            case "drill" -> {
                // A soldier's run: upright, fists tight, short hard swings.
                lean = 0.06f;
                rp = -swing * 0.85f - 0.25f;
                lp = swing * 0.85f - 0.25f;
                rr = 0.08f;
                lr = -0.08f;
                ry = 0.1f;
                ly = -0.1f;
            }
            case "sprinter" -> {
                // A track sprinter: driving arms, bent hard at the elbow, tall and forward.
                lean = 0.2f;
                rp = -swing * 2.0f - 0.3f;
                lp = swing * 2.0f - 0.3f;
                rr = 0.05f;
                lr = -0.05f;
            }
            case "stalker" -> {
                // Low and hunting: one blade out ahead, the other drawn back ready.
                lean = 0.65f;
                rp = -1.25f + swing * 0.1f;
                lp = 0.7f - swing * 0.1f;
                rr = 0.1f;
                lr = -0.2f;
                ry = -0.15f;
            }
            case "carefree" -> {
                // Upright and easy, arms swinging loose and wide.
                lean = 0.02f;
                rp = -swing * 1.25f;
                lp = swing * 1.25f;
                rr = 0.35f;
                lr = -0.35f;
            }
            case "captain" -> {
                // One hand resting on the blade grip at the hip, the other pumping.
                lean = 0.25f;
                rp = 0.15f;
                lp = swing * 1.4f - 0.1f;
                rr = 0.2f;
                lr = -0.05f;
                ry = 0.4f;
            }
            case "heavy" -> {
                // Big and heavy-footed: shoulders rolling, arms out wide.
                lean = 0.3f;
                rp = -swing * 0.9f;
                lp = swing * 0.9f;
                rr = 0.5f;
                lr = -0.5f;
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
        if (style.equals("shinobi") || style.equals("blade_runner") || style.equals("reverse_grip") || style.equals("feral")
            || style.equals("sprinter") || style.equals("stalker") || style.equals("heavy")) {
            m.rightLeg.pitch *= 1 + 0.35f * k;
            m.leftLeg.pitch *= 1 + 0.35f * k;
        }
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
