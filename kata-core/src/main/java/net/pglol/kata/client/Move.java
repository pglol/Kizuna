package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * How you're moving and how much you have left.
 *
 * Double-tapping forward is a fast walk: quicker than walking, rough and pushy, and it costs nothing.
 * Holding sprint is a real run: faster (a touch slower than Minecraft's sprint), and it burns stamina.
 * Run dry and you drop to a fast walk until you've got a quarter back. Stamina comes back while you're
 * not running, after a short breath.
 */
public final class Move {
    private Move() {}

    public enum Mode { WALK, FAST, RUN }

    /** Ground speed against Minecraft's sprint speed (walking is 0.77 of it). */
    private static final float FAST_SPEED = 0.82f, RUN_SPEED = 1.6f; // a full run is about twice a fast walk
    private static final float DRAIN = 1 / (12f * 20), REGEN = 1 / (5f * 20), FAST_REGEN = 1 / (12f * 20);

    public static Mode mode = Mode.WALK;
    /** 0..1 */
    public static float stamina = 1, shownStamina = 1;
    private static boolean winded;
    private static int rest;

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            stamina = 1;
            mode = Mode.WALK;
            return;
        }
        // The sprint key physically held down (with "Sprint: Toggle" the key reads as held after one
        // press, which would make double-tapping forward a run).
        boolean held = mc.currentScreen == null && physicallyHeld(mc);
        boolean free = p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isTouchingWater();
        // Knocked flat (or splatting into a landing): you aren't running any more, whatever is held.
        if (down(p)) p.setSprinting(false);
        if (!p.isSprinting() || free) mode = Mode.WALK;
        else mode = held && !winded ? Mode.RUN : Mode.FAST;

        if (mode == Mode.RUN && !p.isCreative()) {
            stamina = Math.max(0, stamina - DRAIN);
            rest = 20;
            if (stamina <= 0) winded = true;
        } else if (rest > 0) rest--;
        else stamina = Math.min(1, stamina + (mode == Mode.FAST ? FAST_REGEN : REGEN));
        if (winded && stamina >= 0.25f) winded = false;
    }

    private static boolean down(PlayerEntity p) {
        Parkour.Mode m = Parkour.view(p).mode();
        return m == Parkour.Mode.RAGDOLL || m == Parkour.Mode.FACEPLANT || m == Parkour.Mode.LAND;
    }

    private static boolean physicallyHeld(MinecraftClient mc) {
        InputUtil.Key k = KeyBindingHelper.getBoundKeyOf(mc.options.sprintKey);
        long win = mc.getWindow().getHandle();
        if (k.getCategory() == InputUtil.Type.MOUSE) return GLFW.glfwGetMouseButton(win, k.getCode()) == GLFW.GLFW_PRESS;
        if (k.getCode() < 0) return false;
        return InputUtil.isKeyPressed(win, k.getCode());
    }

    /** Field of view: a fast walk barely widens it; a run pushes it out a little more than a sprint. */
    public static float fov(PlayerEntity p, float vanilla) {
        if (down(p)) return 1;
        if (!p.isSprinting() || vanilla <= 1) return vanilla;
        return switch (mode) {
            case FAST -> 1 + (vanilla - 1) * 0.25f;
            case RUN -> 1 + (vanilla - 1) * 1.35f;
            default -> vanilla;
        };
    }

    /**
     * Where your next footstep sound falls (Minecraft's distance units, 0.6 a block): one per footfall of
     * the animation, which steps once every half cycle of the limb swing, instead of Minecraft's fixed
     * one per 1.67 blocks that falls behind the feet when running.
     */
    public static float nextStep(PlayerEntity p, float traveled) {
        double perTick = p.getVelocity().horizontalLength();
        float limb = Math.max(0.3f, p.limbAnimator.getSpeed());
        float ticksPerStep = (float) Math.PI / (0.6662f * limb);
        float units = (float) (perTick * ticksPerStep * 0.6);
        return traveled + Math.max(0.35f, Math.min(1, units));
    }

    /** Sneaking: Minecraft's own sneak is 0.3 of walking. Kata's crouch walk is quicker than that, and
     *  stepping sideways or back while crouched is quick footwork, a bit faster than a plain walk. */
    private static final float VANILLA_SNEAK = 0.3f, CROUCH_WALK = 0.6f, CROUCH_SHUFFLE = 1.12f;

    /** Multiplier on the local player's ground speed. */
    public static float speedFactor(PlayerEntity p) {
        return Parkour.speedScale() * baseFactor(p);
    }

    private static float baseFactor(PlayerEntity p) {
        if (p.isInSneakingPose() && p instanceof net.minecraft.client.network.ClientPlayerEntity cp && !p.getAbilities().flying) {
            boolean shuffle = cp.input.movementForward < 0 || (cp.input.movementForward == 0 && cp.input.movementSideways != 0);
            return (shuffle ? CROUCH_SHUFFLE : CROUCH_WALK) / VANILLA_SNEAK;
        }
        if (!p.isSprinting()) return 1;
        return switch (mode) {
            case RUN -> RUN_SPEED;
            case FAST -> FAST_SPEED;
            default -> 1;
        };
    }

    /** How a player is moving: exact for you, judged by speed for everyone else (blocks per second). */
    public static Mode of(PlayerEntity p, float speed) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && p.getId() == mc.player.getId()) return mode;
        if (!p.isSprinting()) return Mode.WALK;
        return speed > 6.4f ? Mode.RUN : Mode.FAST;
    }
}
