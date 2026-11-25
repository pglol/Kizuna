package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

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
    private static final float FAST_SPEED = 0.885f, RUN_SPEED = 0.95f;
    private static final float DRAIN = 1 / (7f * 20), REGEN = 1 / (5f * 20), FAST_REGEN = 1 / (12f * 20);

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
        boolean held = mc.options.sprintKey.isPressed() && mc.currentScreen == null;
        boolean free = p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isTouchingWater();
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

    /** Multiplier on the local player's ground speed. */
    public static float speedFactor(PlayerEntity p) {
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
        return speed > 5.25f ? Mode.RUN : Mode.FAST;
    }
}
