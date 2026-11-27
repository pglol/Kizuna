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
 * Forward alone is a slow, paced walk. Double-tapping forward is a normal walk, and it costs nothing.
 * Holding sprint is a full sprint, twice a normal walk, and it burns stamina. Run dry and you can only
 * walk until you've got a quarter back. Stamina comes back while you're not running, after a short
 * breath.
 */
public final class Move {
    private Move() {}

    public enum Mode { WALK, FAST, RUN }

    /**
     * Ground speeds against Minecraft's walking speed (4.3 m/s):
     *  - W: a slow, paced walk
     *  - double-tap W: a normal walk
     *  - holding sprint: a full sprint, twice a normal walk
     * Minecraft's own sprint is 1.3 times the walk; the factors below take that out.
     */
    private static final float WALK_SPEED = 0.72f, FAST_SPEED = 1.0f, RUN_SPEED = 2.0f, VANILLA_SPRINT = 1.3f;
    private static final float DRAIN = 1 / (12f * 20), REGEN = 1 / (7f * 20), FAST_REGEN = 1 / (14f * 20);

    public static Mode mode = Mode.WALK;
    /** 0..1 */
    public static float stamina = 1, shownStamina = 1;
    private static boolean winded;
    private static int rest;

    /** Spent breath on something (a strike, a dodge, getting up): it waits two seconds before coming back. */
    static void exert() { rest = 25; }

    /** Crouched by a tap of sneak (stays down until tapped again, or you run or jump into the air). */
    public static boolean crouchOn;
    /** Sneak held down while moving: the crouch run (quicker, costs breath). */
    public static boolean crouchRun;
    private static long sneakDownAt;
    private static boolean sneakWas;

    /**
     * Sneak: a tap crouches you (and another stands you up), a slow quiet crouch walk, the same speed
     * whichever way you go; held down, a crouch run, quicker but it costs breath. Taps in the air are
     * the landing catch, not a crouch.
     */
    private static void crouch(MinecraftClient mc, ClientPlayerEntity p, boolean sprintHeld) {
        boolean key = mc.currentScreen == null && mc.options.sneakKey.isPressed();
        long now = System.nanoTime();
        if (key && !sneakWas) sneakDownAt = now;
        if (!key && sneakWas && now - sneakDownAt < 250_000_000L && p.isOnGround()) crouchOn = !crouchOn;
        sneakWas = key;
        if (sprintHeld || p.getAbilities().flying || p.isTouchingWater() || p.hasVehicle() || down(p)) crouchOn = false;
        boolean holding = key && now - sneakDownAt >= 250_000_000L;
        boolean moving = Math.abs(p.input.movementForward) + Math.abs(p.input.movementSideways) > 0.1f;
        crouchRun = holding && moving && p.isOnGround() && !winded && (Fight.myInjuries() & 3) == 0;
    }

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            stamina = 1;
            mode = Mode.WALK;
            crouchOn = crouchRun = false;
            return;
        }
        crouch(mc, p, mc.currentScreen == null && physicallyHeld(mc));
        // The sprint key physically held down (with "Sprint: Toggle" the key reads as held after one
        // press, which would make double-tapping forward a run).
        boolean held = mc.currentScreen == null && physicallyHeld(mc);
        boolean free = p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isTouchingWater();
        // Knocked flat (or splatting into a landing): you aren't running any more, whatever is held.
        if (down(p)) p.setSprinting(false);
        // Holding sprint and moving forward always sprints (whatever Minecraft made of the key).
        if (held && !winded && !free && !p.isSneaking() && p.input.movementForward > 0.5f && !down(p)) p.setSprinting(true);
        // Winded: no running and no fast walk either, just walking until you've got your breath back.
        if (winded) p.setSprinting(false);
        // A broken leg: no running, you limp.
        if ((Fight.myInjuries() & 3) != 0) p.setSprinting(false);
        // Locked on to someone, guarding or charging: you're fighting, not running. You step and walk.
        if (Fight.locked() || Fight.guarding()) p.setSprinting(false);
        if (!p.isSprinting() || free) mode = Mode.WALK;
        else mode = held ? Mode.RUN : Mode.FAST;

        // Running costs breath only while you're really running: on your feet and moving.
        boolean running = (mode == Mode.RUN || crouchRun) && p.isOnGround() && Parkour.view(p).mode() == Parkour.Mode.NONE
            && p.getVelocity().horizontalLengthSquared() > (crouchRun ? 0.003 : 0.01);
        if (running && !p.isCreative()) {
            stamina = Math.max(0, stamina - (crouchRun ? DRAIN * 0.6f : DRAIN));
            rest = 25;
            if (stamina <= 0) winded = true;
        } else if (rest > 0) rest--;
        else stamina = Math.min(1, stamina + (mode == Mode.FAST ? FAST_REGEN : REGEN) * ((Fight.myInjuries() & 16) != 0 ? 0.55f : 1f)); // cracked ribs: breath comes back slower
        if (stamina <= 0.005f) winded = true; // however it went: running, strikes, dodges, getting up
        if (winded && stamina >= 0.3f) winded = false;
    }

    private static boolean down(PlayerEntity p) {
        Parkour.Mode m = Parkour.view(p).mode();
        return m == Parkour.Mode.RAGDOLL || m == Parkour.Mode.FACEPLANT || m == Parkour.Mode.LAND;
    }

    public static boolean winded() {
        return winded;
    }

    /** Spend breath on something other than running (a quick shuffle, a run-up): counts as effort. */
    public static void spend(float amount) {
        stamina = Math.max(0, stamina - amount);
        rest = 20;
        if (stamina <= 0) winded = true;
    }

    /** Is the sprint key physically down (for moves other than running that it speeds up). */
    public static boolean sprintHeld(MinecraftClient mc) {
        return mc.currentScreen == null && physicallyHeld(mc);
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
     *  holding sneak down is the crouch run (see crouch()). */
    private static final float VANILLA_SNEAK = 0.3f, CROUCH_WALK = 0.42f, CROUCH_RUN = 0.95f;

    /** Multiplier on the local player's ground speed. */
    public static float speedFactor(PlayerEntity p) {
        return Parkour.speedScale() * baseFactor(p) * Fight.speedScale(p);
    }

    private static float baseFactor(PlayerEntity p) {
        if (p.isInSneakingPose() && p instanceof net.minecraft.client.network.ClientPlayerEntity cp && !p.getAbilities().flying) {
            // The same pace whichever way you go (sideways and back aren't a way round it).
            return (crouchRun ? CROUCH_RUN : CROUCH_WALK) / VANILLA_SNEAK;
        }
        if (!p.isSprinting()) return p.getAbilities().flying ? 1 : WALK_SPEED;
        // Locked on or guarding: walking pace even if the game still has you sprinting this tick.
        if (p == MinecraftClient.getInstance().player && (Fight.locked() || Fight.guarding()) && !p.getAbilities().flying) return WALK_SPEED / VANILLA_SPRINT;
        return switch (mode) {
            case RUN -> RUN_SPEED / VANILLA_SPRINT;
            case FAST -> FAST_SPEED / VANILLA_SPRINT;
            default -> WALK_SPEED;
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
