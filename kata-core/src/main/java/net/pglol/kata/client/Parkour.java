package net.pglol.kata.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.KataNet;

import java.util.HashMap;
import java.util.Map;

/**
 * Basic parkour, grounded in what a person can do: no sticking to flat walls. You get up things by
 * jumping, running a couple of steps up a wall, catching lips with your hands, pulling yourself up and
 * springing from one ledge to the next. Worked out on your own game (that's where Minecraft moves you)
 * and shown to everyone.
 *
 * Space decides how far you go: a tap near a ledge just catches it (hang); holding space or tapping it
 * twice climbs all the way. A small marker shows where your hands will go (white: a jump away, green:
 * grab now).
 *
 *  - Vault: running or fast walking at something low and thin (up to 1.5 blocks), press jump: over it
 *    with one hand planted, keeping your speed.
 *  - Climb onto: hold or double-tap jump at something up to about 2.3 blocks high: pull up onto it.
 *  - Ledge hang: tap jump near a lip you can reach. A/D shuffle along it, sneak drops.
 *  - Up from a hang: hold or double-tap jump. Room on top: pull up onto it. No room (a window sill
 *    with wall above): spring up to the next lip in reach.
 *  - Wall run-up: at a full run into a wall, jump and you run two or three steps up it, catching any
 *    lip your hands reach on the way.
 *  - Kicking off: only jump together with the movement key pointing away from the wall throws you
 *    back off it (whichever way the camera faces). Jump alone never does.
 */
public final class Parkour {
    private Parkour() {}

    /** Synced by ordinal: add new moves at the end. */
    public enum Mode { NONE, VAULT, HANG, RUNUP, PULL, MANTLE, HOP, CORNER }

    /** What the animator needs: the move, how far into it (0..1), shuffle side, step phase, seconds in. */
    public record View(Mode mode, float progress, int side, float phase, float secs) {
        static final View NONE = new View(Mode.NONE, 0, 0, 0, 0);
    }

    private static final int VAULT_TICKS = 9, PULL_TICKS = 13, MANTLE_TICKS = 9, HOP_TICKS = 7, RUNUP_TICKS = 8;
    private static final float RUNUP_COST = 0.12f;
    /** Hanging: the lip is this far above your feet, so straight-ish arms reach it (shoulders at 1.29). */
    public static final double HANG_H = 1.72;
    private static final int CORNER_TICKS = 6;
    private static Direction cornerTo = Direction.NORTH;
    private static float yawFrom, yawTo;
    private static Vec3d[] handsFrom, handsTo, pullHands;

    // Shuffling, hand over hand: the lead hand reaches out (REACH ticks), then the body follows while the
    // other hand comes in (FOLLOW ticks). Let go of the key and the step in progress finishes, quicker.
    private static final double STEP = 0.3;
    private static final float REACH = 4, FOLLOW = 5, HURRY = 2.5f;
    private static int cyc, cycSide, lead;
    private static float cycK, cycRate;
    private static boolean hurry;
    private static Vec3d cycFrom, cycTo, handFrom, handTo;
    private static Vec3d[] plant;

    // ---- your own state ----
    private static Mode mode = Mode.NONE;
    private static int ticks, side, cooldown;
    private static Direction dir = Direction.NORTH;
    private static double ledgeY;
    private static Vec3d from, to;
    private static boolean lastJump, lastGround = true;
    private static double groundY;
    private static float phase;
    private static long started;
    private static Mode sentMode = Mode.NONE;
    private static int sentSide;
    // Space: a tap grabs; holding it (HOLD ticks) or a second tap within DOUBLE ticks climbs.
    private static final int HOLD = 5, DOUBLE = 8, INTENT = 16;
    private enum Intent { NONE, GRAB, CLIMB }
    private static Intent intent = Intent.NONE;
    private static int pressAge = 99, intentAge;

    /** The marker: where your hands would go (the lip's edge), and whether space grabs it right now. */
    public static volatile Vec3d marker;
    public static volatile boolean markerReady;
    public static volatile Direction markerDir = Direction.NORTH;
    /** Where each hand would hold (right, left), for the marker. */
    public static volatile Vec3d[] markerHands;

    // ---- the hands ----

    /** Can a hand hold here: something solid just under the point, a little room above it. */
    private static boolean holdable(net.minecraft.world.World w, Vec3d q) {
        return !w.isSpaceEmpty(new Box(q.x - 0.03, q.y - 0.1, q.z - 0.03, q.x + 0.03, q.y - 0.01, q.z + 0.03))
            && w.isSpaceEmpty(new Box(q.x - 0.03, q.y + 0.01, q.z - 0.03, q.x + 0.03, q.y + 0.25, q.z + 0.03));
    }

    /**
     * Where the two hands go on a lip whose top is at y, in front of a body at 'at' facing d: a hand's
     * width either side of centre, just over the edge. A hand whose side runs out (an end, a corner)
     * slides in towards the middle so it never grips air. Returns {right, left}.
     */
    public static Vec3d[] handPoints(net.minecraft.world.World w, Vec3d at, Direction d, double y) {
        Vec3d face = new Vec3d(at.x + d.getOffsetX() * 0.36, y, at.z + d.getOffsetZ() * 0.36);
        Vec3d r = new Vec3d(-d.getOffsetZ(), 0, d.getOffsetX());
        Vec3d[] out = new Vec3d[2];
        for (int i = 0; i < 2; i++) {
            int s = i == 0 ? 1 : -1;
            out[i] = face;
            for (double o = 0.24; o >= -0.001; o -= 0.04) {
                Vec3d q = face.add(r.multiply(s * o));
                if (holdable(w, q)) {
                    out[i] = q;
                    break;
                }
            }
        }
        return out;
    }

    /** The wall you're on faces this way (yaw), for turning the body to it (turning smoothly round corners). */
    public static float hangYaw(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && p.getId() == mc.player.getId()) {
            if (mode == Mode.CORNER) return MathHelper.lerpAngleDegrees(ease(progress(Mode.CORNER, (System.nanoTime() - started) / 1e9f)), yawFrom, yawTo);
            return dir.asRotation();
        }
        return p.getHorizontalFacing().asRotation();
    }

    /** On a wall (hanging, rounding a corner, pulling up, springing up): the body faces it. */
    public static boolean onWall(PlayerEntity p) {
        Mode m = view(p).mode();
        return m == Mode.HANG || m == Mode.CORNER || m == Mode.PULL || m == Mode.MANTLE || m == Mode.HOP;
    }

    /** Your own hands are placed exactly by parkour (hand over hand); others' are smoothed by the animator. */
    public static boolean exactHands(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && p.getId() == mc.player.getId() && mode != Mode.NONE;
    }

    /** How much the hands are planted (1 hanging; letting go late in a pull-up as the body comes over). */
    public static float handWeight(PlayerEntity p) {
        View v = view(p);
        return switch (v.mode()) {
            case HANG, CORNER -> 1;
            case PULL -> 1 - MathHelper.clamp((v.progress() - 0.6f) / 0.25f, 0, 1);
            case MANTLE -> 1 - MathHelper.clamp((v.progress() - 0.45f) / 0.3f, 0, 1);
            default -> 0;
        };
    }

    /** Where this hanging player's hands hold, or null if they aren't hanging. */
    public static Vec3d[] hands(PlayerEntity p, float td) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d at = p.getLerpedPos(td);
        if (mc.player != null && p.getId() == mc.player.getId()) {
            if (mode == Mode.HANG) {
                if (plant == null) plant = handPoints(p.getWorld(), at, dir, ledgeY);
                Vec3d[] h = {plant[0], plant[1]};
                if (cyc != 0 && handFrom != null) {
                    // The hand on its way: a quick little arc, between ticks too.
                    float k = MathHelper.clamp(cycK + cycRate * td, 0, 1);
                    float e = ease(k);
                    h[cyc == 1 ? lead : 1 - lead] = handFrom.lerp(handTo, e).add(0, 0.06 * MathHelper.sin(MathHelper.PI * k), 0);
                }
                return h;
            }
            if (mode == Mode.CORNER && handsFrom != null) {
                float k = ease(progress(Mode.CORNER, (System.nanoTime() - started) / 1e9f));
                return new Vec3d[] {handsFrom[0].lerp(handsTo[0], k), handsFrom[1].lerp(handsTo[1], k)};
            }
            if ((mode == Mode.PULL || mode == Mode.MANTLE) && pullHands != null) return pullHands;
            return null;
        }
        Remote r = REMOTE.get(p.getId());
        if (r == null || (r.mode != Mode.HANG && r.mode != Mode.CORNER)) return null;
        return handPoints(p.getWorld(), at, p.getHorizontalFacing(), at.y + HANG_H);
    }

    // ---- everyone else, from the server ----
    private record Remote(Mode mode, int side, long started) { }
    private static final Map<Integer, Remote> REMOTE = new HashMap<>();

    public static void remote(int entity, int m, int s) {
        Mode md = m >= 0 && m < Mode.values().length ? Mode.values()[m] : Mode.NONE;
        if (md == Mode.NONE) REMOTE.remove(entity);
        else REMOTE.put(entity, new Remote(md, s, System.nanoTime()));
        if (REMOTE.size() > 512) REMOTE.clear();
    }

    public static View view(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && p.getId() == mc.player.getId()) {
            if (mode == Mode.NONE) return View.NONE;
            float secs = (System.nanoTime() - started) / 1e9f;
            return new View(mode, progress(mode, secs), side, phase, secs);
        }
        Remote r = REMOTE.get(p.getId());
        if (r == null) return View.NONE;
        float secs = (System.nanoTime() - r.started) / 1e9f;
        if (r.mode != Mode.HANG && secs > 2) return View.NONE;
        float moved = (float) Math.sqrt(p.squaredDistanceTo(p.prevX, p.prevY, p.prevZ));
        return new View(r.mode, progress(r.mode, secs), r.side, secs * 9 * Math.min(1, moved * 12), secs);
    }

    private static float progress(Mode m, float secs) {
        int t = switch (m) {
            case VAULT -> VAULT_TICKS;
            case PULL -> PULL_TICKS;
            case MANTLE -> MANTLE_TICKS;
            case HOP -> HOP_TICKS;
            case RUNUP -> RUNUP_TICKS;
            case CORNER -> CORNER_TICKS;
            default -> 0;
        };
        return t == 0 ? 0 : Math.min(1, secs / (t / 20f));
    }

    // ---- geometry ----

    /** A player-wide column at (x, y, z), h tall (a hair narrower than the player, to slide past edges). */
    private static Box column(double x, double y, double z, double h) {
        return new Box(x - 0.29, y, z - 0.29, x + 0.29, y + h, z + 0.29);
    }

    private static boolean empty(ClientPlayerEntity p, Box b) {
        return p.getWorld().isSpaceEmpty(p, b);
    }

    private static Vec3d ahead(Vec3d at, double d) {
        return at.add(dir.getOffsetX() * d, 0, dir.getOffsetZ() * d);
    }

    /** Solid right in front, from y0 to y1 above the feet. */
    private static boolean wall(ClientPlayerEntity p, Vec3d at, double y0, double y1) {
        Vec3d a = ahead(at, 0.36);
        return !empty(p, new Box(a.x - 0.2, at.y + y0, a.z - 0.2, a.x + 0.2, at.y + y1, a.z + 0.2));
    }

    /** Room straight up beside the wall, from the feet (or where you are) to y. */
    private static boolean clearUp(ClientPlayerEntity p, Vec3d at, double feetY, double y) {
        double from = Math.max(feetY, p.getY());
        return y <= from || empty(p, column(at.x, from, at.z, y - from));
    }

    /**
     * The top of whatever is in front where a player could stand on it: the lowest height between lo
     * and hi above feetY, with room straight up beside it to get there. null if there's none.
     */
    private static Double ledge(ClientPlayerEntity p, Vec3d at, double feetY, double lo, double hi) {
        Vec3d top = ahead(at, 0.8);
        for (double dy = lo; dy <= hi + 1e-6; dy += 1 / 16.0) {
            double y = feetY + dy;
            if (!empty(p, column(top.x, y, top.z, 1.8))) continue;
            if (empty(p, column(top.x, y - 0.07, top.z, 0.07))) continue;
            return clearUp(p, at, feetY, y + 1.8) ? y : null;
        }
        return null;
    }

    /** A lip the hands can hold: a top edge in front with a little room above it (no need to stand). */
    private static Double lip(ClientPlayerEntity p, Vec3d at, double feetY, double lo, double hi) {
        Vec3d top = ahead(at, 0.55);
        for (double dy = lo; dy <= hi + 1e-6; dy += 1 / 16.0) {
            double y = feetY + dy;
            Box hands = new Box(top.x - 0.2, y, top.z - 0.2, top.x + 0.2, y + 0.35, top.z + 0.2);
            if (!empty(p, hands)) continue;
            if (empty(p, new Box(top.x - 0.2, y - 0.07, top.z - 0.2, top.x + 0.2, y, top.z + 0.2))) continue;
            return clearUp(p, at, feetY, y + 0.2) ? y : null;
        }
        return null;
    }

    /** Where to hold on: centred 0.31 from the face of the block in front at height y. */
    private static Vec3d snap(Vec3d at, double y) {
        Vec3d a = ahead(at, 0.6);
        BlockPos b = BlockPos.ofFloored(a.x, y, a.z);
        double x = at.x, z = at.z;
        switch (dir) {
            case EAST -> x = b.getX() - 0.31;
            case WEST -> x = b.getX() + 1.31;
            case SOUTH -> z = b.getZ() - 0.31;
            case NORTH -> z = b.getZ() + 1.31;
            default -> { }
        }
        return new Vec3d(x, at.y, z);
    }

    // ---- each tick, after Minecraft has moved you ----

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            mode = Mode.NONE;
            marker = null;
            return;
        }
        boolean jump = mc.options.jumpKey.isPressed() && mc.currentScreen == null;
        boolean jumpEdge = jump && !lastJump;
        lastJump = jump;
        boolean sneak = mc.options.sneakKey.isPressed() && mc.currentScreen == null;
        boolean ground = p.isOnGround();
        float fwd = p.input.movementForward;
        if (cooldown > 0) cooldown--;

        // Tap, hold or double tap.
        pressAge++;
        if (jumpEdge) {
            boolean twice = pressAge <= DOUBLE;
            pressAge = 0;
            intent = twice ? Intent.CLIMB : Intent.GRAB;
            intentAge = 0;
        } else if (jump && pressAge >= HOLD && intent == Intent.GRAB) intent = Intent.CLIMB;
        if (intent != Intent.NONE && ++intentAge > INTENT && !jump) intent = Intent.NONE;
        boolean climbAsk = intent == Intent.CLIMB;
        boolean away = away(p);

        boolean free = p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isTouchingWater() || p.isFallFlying() || p.isDead();
        if (free && mode != Mode.NONE) set(Mode.NONE);

        switch (mode) {
            case NONE -> {
                if (free || cooldown > 0) break;
                dir = p.getHorizontalFacing();
                Vec3d at = p.getPos();
                boolean justJumped = jumpEdge && lastGround && !ground && p.getVelocity().y > 0.3;
                if (justJumped && fwd > 0.1) {
                    double feet = groundY;
                    Double low = ledge(p, at, feet, 0.5, 1.6);
                    if (low != null && Move.mode != Move.Mode.WALK) {
                        double h = low - feet;
                        boolean thin = empty(p, column(ahead(at, 1.9).x, feet, ahead(at, 1.9).z, 1.8))
                            && empty(p, column(ahead(at, 1.05).x, low + 0.05, ahead(at, 1.05).z, 1.8));
                        if (thin) {
                            double speed = Math.max(0.34, p.getVelocity().horizontalLength());
                            p.setVelocity(dir.getOffsetX() * speed, 0.42 + Math.max(0, h - 1.0) * 0.36, dir.getOffsetZ() * speed);
                            set(Mode.VAULT);
                            break;
                        }
                    }
                    // Full run into a wall with nothing low to get onto: run up it.
                    if (low == null && Move.mode == Move.Mode.RUN && wall(p, at, 0.2, 1.8) && (Move.stamina > RUNUP_COST || p.isCreative())) {
                        if (!p.isCreative()) Move.stamina -= RUNUP_COST;
                        from = at;
                        p.setVelocity(Vec3d.ZERO);
                        set(Mode.RUNUP);
                        break;
                    }
                }
                if (!ground && intent != Intent.NONE && wall(p, at, 0.3, 1.7)) reach(p, at, climbAsk);
            }
            case VAULT -> {
                ticks++;
                if (ticks > VAULT_TICKS + 4 || (ground && ticks > 3)) set(Mode.NONE);
            }
            case HANG -> {
                Vec3d at = p.getPos();
                ticks++;
                if (sneak) drop(p, 0.08);
                else if (jumpEdge && away) kickOff(p);
                else if ((toward(p) && ticks > 4 && cyc == 0) || (climbAsk && !jumpEdge && ticks > 2)) {
                    // W (or holding space after the catch): up and over; no room on top: the lip above.
                    intent = Intent.NONE;
                    if (!startPath(p, Mode.PULL, ledgeY)) {
                        Double up = lip(p, at, ledgeY, 0.5, 3.1);
                        if (up != null) hop(p, at, up);
                    }
                } else if (jumpEdge && ticks > 2) {
                    // Space: a ledge jump. With A/D, across to a lip that way; on its own, up to the lip
                    // above (or over the top if there's nothing above).
                    intent = Intent.NONE;
                    float sw = p.input.movementSideways;
                    int sideWant = sw > 0.1f ? -1 : sw < -0.1f ? 1 : 0;
                    if (sideWant != 0) sideJump(p, at, sideWant);
                    else {
                        Double up = lip(p, at, ledgeY, 0.5, 3.1);
                        if (up != null) hop(p, at, up);
                        else startPath(p, Mode.PULL, ledgeY);
                    }
                } else if (lip(p, at, at.y, HANG_H - 0.15, HANG_H + 0.15) == null) drop(p, 0); // the lip went (broken, or slid off the end)
            }
            case RUNUP -> {
                if (sneak) set(Mode.NONE);
                else if (jumpEdge && away) kickOff(p);
            }
            default -> { }
        }
        if (ground) {
            groundY = p.getY();
            if (intent != Intent.NONE && !jump && pressAge > DOUBLE) intent = Intent.NONE;
        }
        lastGround = ground;
        updateMarker(p, ground);
        syncOut();
    }

    /** In the air at a wall with space pressed: climb onto it (hold / double tap) or catch its lip. */
    private static void reach(ClientPlayerEntity p, Vec3d at, boolean climbAsk) {
        if (climbAsk) {
            Double top = ledge(p, at, at.y, 0.3, 2.3);
            if (top != null && startPath(p, Mode.MANTLE, top)) {
                intent = Intent.NONE;
                return;
            }
        }
        Double l = lip(p, at, at.y, 1.3, 2.3);
        if (l != null && grab(p, at, l) && climbAsk) {
            intent = Intent.NONE;
            if (!startPath(p, Mode.PULL, ledgeY)) {
                Double up = lip(p, p.getPos(), ledgeY, 0.5, 3.1);
                if (up != null) hop(p, p.getPos(), up);
            }
        }
    }

    /** Catch the lip whose top is at l: hands on it, body hanging below. */
    private static boolean grab(ClientPlayerEntity p, Vec3d at, double l) {
        Vec3d hold = snap(at, l - 0.03);
        Vec3d hang = new Vec3d(hold.x, l - HANG_H, hold.z);
        if (!empty(p, column(hang.x, hang.y, hang.z, 1.8))) return false;
        ledgeY = l;
        p.setPosition(hang);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        set(Mode.HANG);
        return true;
    }

    /** Spring up from a hang to the lip at l above. */
    private static void hop(ClientPlayerEntity p, Vec3d at, double l) {
        Vec3d hold = snap(at, l - 0.03);
        Vec3d hang = new Vec3d(hold.x, l - HANG_H, hold.z);
        if (!empty(p, column(at.x, at.y, at.z, hang.y + 1.8 - at.y)) || !empty(p, column(hang.x, hang.y, hang.z, 1.8))) return;
        from = at;
        to = hang;
        ledgeY = l;
        set(Mode.HOP);
    }

    /** Leap sideways (1 right, -1 left) to the nearest lip on the same wall, up to ~2.4 blocks across. */
    private static void sideJump(ClientPlayerEntity p, Vec3d at, int side) {
        Vec3d r = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(side);
        for (double d = 0.6; d <= 2.4; d += 0.15) {
            for (double dy = 0; dy <= 1.2; dy = dy <= 0 ? -dy + 0.25 : -dy) {
                if (dy < -1.0) continue;
                Vec3d c = at.add(r.multiply(d)).add(0, dy, 0);
                if (!empty(p, column(c.x, c.y, c.z, 1.8))) continue;
                Double l = lip(p, c, c.y, HANG_H - 0.2, HANG_H + 0.2);
                if (l == null) continue;
                Vec3d hold = snap(c, l - 0.03);
                Vec3d hang = new Vec3d(hold.x, l - HANG_H, hold.z);
                if (!empty(p, column(hang.x, hang.y, hang.z, 1.8))) continue;
                from = at;
                to = hang;
                ledgeY = l;
                set(Mode.HOP);
                return;
            }
        }
    }

    private static void kickOff(ClientPlayerEntity p) {
        set(Mode.NONE);
        cooldown = 8;
        intent = Intent.NONE;
        p.setVelocity(-dir.getOffsetX() * 0.38, 0.42, -dir.getOffsetZ() * 0.38);
    }

    /** The movement keys point at the wall you're on (judged from where the camera faces). */
    private static boolean toward(ClientPlayerEntity p) {
        float f = p.input.movementForward, s = p.input.movementSideways;
        if (f * f + s * s < 0.01f) return false;
        double yaw = Math.toRadians(p.getYaw());
        double x = s * Math.cos(yaw) - f * Math.sin(yaw), z = f * Math.cos(yaw) + s * Math.sin(yaw);
        return (x * dir.getOffsetX() + z * dir.getOffsetZ()) / Math.sqrt(x * x + z * z) > 0.6;
    }

    /** The movement keys point away from the wall you're on (judged from where the camera faces). */
    private static boolean away(ClientPlayerEntity p) {
        float f = p.input.movementForward, s = p.input.movementSideways;
        if (f * f + s * s < 0.01f) return false;
        double yaw = Math.toRadians(p.getYaw());
        double x = s * Math.cos(yaw) - f * Math.sin(yaw), z = f * Math.cos(yaw) + s * Math.sin(yaw);
        double len = Math.sqrt(x * x + z * z);
        return (x * dir.getOffsetX() + z * dir.getOffsetZ()) / len < -0.5;
    }

    /** Where the marker goes: the lip you're holding, or the nearest one in front you could catch. */
    private static void updateMarker(ClientPlayerEntity p, boolean ground) {
        if (mode == Mode.HANG) {
            setMarker(p.getPos(), ledgeY, true, dir);
            return;
        }
        if (mode != Mode.NONE && mode != Mode.RUNUP) {
            marker = null;
            return;
        }
        Direction keep = dir;
        dir = p.getHorizontalFacing();
        Vec3d at = p.getPos();
        try {
            for (double k = 0; k <= 0.9; k += 0.45) {
                Vec3d probe = ahead(at, k);
                if (k > 0 && !empty(p, column(probe.x, at.y, probe.z, 1.8))) break;
                if (!wall(p, probe, 0.3, 2.6)) continue;
                // Hanging reach now (in the air), or with a jump (or a run-up) from the ground.
                Double now = !ground && k == 0 ? lip(p, probe, at.y, 1.3, 2.3) : null;
                Double reach = now != null ? now : lip(p, probe, at.y, 1.3, ground ? (Move.mode == Move.Mode.RUN ? 4.4 : 3.3) : 2.3);
                if (reach != null) {
                    setMarker(probe, reach, now != null, dir);
                    return;
                }
                break;
            }
            marker = null;
        } finally {
            dir = keep;
        }
    }

    private static void setMarker(Vec3d at, double y, boolean ready, Direction d) {
        Direction keep = dir;
        dir = d;
        Vec3d c = snap(at, y - 0.03);
        dir = keep;
        marker = new Vec3d(c.x + d.getOffsetX() * 0.31, y, c.z + d.getOffsetZ() * 0.31);
        markerHands = handPoints(MinecraftClient.getInstance().world, c, d, y);
        markerDir = d;
        markerReady = ready;
    }

    private static void drop(ClientPlayerEntity p, double push) {
        set(Mode.NONE);
        cooldown = 10;
        intent = Intent.NONE;
        p.setVelocity(-dir.getOffsetX() * push, 0, -dir.getOffsetZ() * push);
    }

    /** Up beside the wall to top, then over onto it. false if there isn't room. */
    private static boolean startPath(ClientPlayerEntity p, Mode m, double top) {
        Vec3d at = p.getPos();
        Vec3d up = new Vec3d(at.x, top + 0.01, at.z);
        Vec3d end = ahead(up, 0.8);
        if (!empty(p, column(at.x, at.y, at.z, top + 1.8 - at.y)) || !empty(p, column(end.x, end.y, end.z, 1.8))) return false;
        if (empty(p, column(end.x, end.y - 0.1, end.z, 0.1))) return false; // nothing to stand on
        from = at;
        to = end;
        ledgeY = top;
        pullHands = handPoints(p.getWorld(), snap(at, top - 0.03), dir, top);
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        set(m);
        return true;
    }

    private static void set(Mode m) {
        mode = m;
        cyc = 0;
        plant = null;
        ticks = 0;
        side = 0;
        started = System.nanoTime();
    }

    private static void syncOut() {
        if (mode == sentMode && side == sentSide) return;
        sentMode = mode;
        sentSide = side;
        if (ClientPlayNetworking.canSend(KataNet.Act.ID)) ClientPlayNetworking.send(new KataNet.Act(mode.ordinal(), side));
    }

    // ---- during Minecraft's own movement step: true = parkour moved you this tick ----

    public static boolean travel(ClientPlayerEntity p) {
        switch (mode) {
            case HANG -> {
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                // Shuffle along the lip, hand over hand (left is +sideways), if it carries on that way.
                float s = p.input.movementSideways;
                int want = s > 0.1f ? -1 : s < -0.1f ? 1 : 0;
                Vec3d at = p.getPos();
                if (plant == null) plant = handPoints(p.getWorld(), at, dir, ledgeY);
                if (cyc == 0 && want != 0) {
                    Vec3d r = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(want);
                    Vec3d next = null;
                    for (double d = STEP; d > 0.05; d -= 0.1) {
                        Vec3d n = at.add(r.multiply(d));
                        if (empty(p, column(n.x, n.y, n.z, 1.8)) && lip(p, n, n.y, HANG_H - 0.15, HANG_H + 0.15) != null) {
                            next = n;
                            break;
                        }
                    }
                    if (next != null) {
                        cycSide = want;
                        lead = want > 0 ? 0 : 1;
                        cycFrom = at;
                        cycTo = next;
                        handFrom = plant[lead];
                        handTo = handPoints(p.getWorld(), next, dir, ledgeY)[lead];
                        cyc = 1;
                        cycK = 0;
                        hurry = false;
                    } else if (corner(p, want)) return true;
                }
                side = cyc != 0 ? cycSide : 0;
                if (cyc != 0) {
                    if (want != cycSide) hurry = true;
                    cycRate = (cyc == 1 ? 1 / REACH : 1 / FOLLOW) * (hurry ? HURRY : 1);
                    cycK += cycRate;
                    phase += 0.4f;
                    if (cyc == 1 && cycK >= 1) {
                        // Lead hand planted: now the body comes across and the other hand follows it in.
                        plant[lead] = handTo;
                        int trail = 1 - lead;
                        handFrom = plant[trail];
                        handTo = handPoints(p.getWorld(), cycTo, dir, ledgeY)[trail];
                        cyc = 2;
                        cycK = 0;
                    } else if (cyc == 2) {
                        Vec3d target = cycFrom.lerp(cycTo, ease(Math.min(1, cycK)));
                        p.move(MovementType.SELF, target.subtract(at));
                        if (cycK >= 1) {
                            plant[1 - lead] = handTo;
                            cyc = 0;
                        }
                    }
                }
                return true;
            }
            case RUNUP -> {
                // Two or three hard steps up the wall, slowing as they go, catching any lip in reach.
                ticks++;
                double vy = Math.max(0, 0.34 - ticks * 0.04);
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                phase += 0.9f;
                Vec3d at = p.getPos();
                if (intent != Intent.NONE || MinecraftClient.getInstance().options.jumpKey.isPressed()) {
                    Double l = lip(p, at, at.y, 1.3, 2.3);
                    if (l != null) {
                        boolean climb = intent == Intent.CLIMB;
                        if (grab(p, at, l) && climb) {
                            intent = Intent.NONE;
                            startPath(p, Mode.PULL, ledgeY);
                        }
                        return true;
                    }
                }
                if (vy <= 0 || ticks > RUNUP_TICKS || !wall(p, at, 0.2, 1.6) || !empty(p, column(at.x, at.y + vy, at.z, 1.8))) {
                    set(Mode.NONE);
                    p.setVelocity(-dir.getOffsetX() * 0.04, 0.05, -dir.getOffsetZ() * 0.04);
                    return true;
                }
                p.move(MovementType.SELF, new Vec3d(0, vy, 0));
                return true;
            }
            case CORNER -> {
                ticks++;
                float k = ease(Math.min(1, ticks / (float) CORNER_TICKS));
                Vec3d target = from.lerp(to, k);
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                p.move(MovementType.SELF, target.subtract(p.getPos()));
                if (ticks >= CORNER_TICKS) {
                    dir = cornerTo;
                    set(Mode.HANG);
                    ticks = 3;
                }
                return true;
            }
            case PULL, MANTLE, HOP -> {
                int total = mode == Mode.PULL ? PULL_TICKS : mode == Mode.MANTLE ? MANTLE_TICKS : HOP_TICKS;
                ticks++;
                float k = Math.min(1, ticks / (float) total);
                Vec3d target;
                if (mode == Mode.HOP) {
                    // A spring straight up, fast off the hands, easing into the catch.
                    float u = 1 - (1 - k) * (1 - k);
                    double across = Math.hypot(to.x - from.x, to.z - from.z);
                    double lift = Math.min(0.5, across * 0.25) * MathHelper.sin(MathHelper.PI * k);
                    target = new Vec3d(MathHelper.lerp(u, from.x, to.x), MathHelper.lerp(u, from.y, to.y) + lift, MathHelper.lerp(u, from.z, to.z));
                } else if (k < 0.62f) {
                    // Up first (most of it), then over onto the top.
                    float u = ease(k / 0.62f);
                    target = new Vec3d(from.x, MathHelper.lerp(u, from.y, to.y), from.z);
                } else {
                    float u = ease((k - 0.62f) / 0.38f);
                    target = new Vec3d(MathHelper.lerp(u, from.x, to.x), to.y, MathHelper.lerp(u, from.z, to.z));
                }
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                p.move(MovementType.SELF, target.subtract(p.getPos()));
                if (p.getPos().squaredDistanceTo(target) > 0.04) set(Mode.NONE); // something got in the way
                else if (k >= 1) {
                    if (mode == Mode.HOP) {
                        set(Mode.HANG);
                        ticks = 0;
                    } else set(Mode.NONE);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Round a corner while shuffling towards side (1 right, -1 left): an inside corner (a wall that way)
     * turns you to face it; an outside corner (the lip turns away) wraps you round onto the next face.
     */
    private static boolean corner(ClientPlayerEntity p, int side) {
        Direction keep = dir;
        Vec3d at = p.getPos();
        Vec3d move = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(side);
        // Inside corner.
        Direction inner = side > 0 ? keep.rotateYClockwise() : keep.rotateYCounterclockwise();
        dir = inner;
        if (wall(p, at, 0.6, 1.9)) {
            Vec3d hold = snap(at, ledgeY - 0.03);
            Vec3d hang = new Vec3d(hold.x, at.y, hold.z);
            if (empty(p, column(hang.x, hang.y, hang.z, 1.8)) && lip(p, hang, hang.y, HANG_H - 0.15, HANG_H + 0.15) != null) {
                return startCorner(p, keep, inner, at, hang);
            }
        }
        // Outside corner.
        Direction outer = side > 0 ? keep.rotateYCounterclockwise() : keep.rotateYClockwise();
        Vec3d fwd = new Vec3d(keep.getOffsetX(), 0, keep.getOffsetZ());
        for (double k = 0.35; k <= 0.75; k += 0.1) {
            Vec3d probe = at.add(fwd.multiply(0.62)).add(move.multiply(k));
            dir = outer;
            if (!empty(p, column(probe.x, probe.y, probe.z, 1.8)) || !wall(p, probe, 1.2, 1.9)) continue;
            Vec3d hold = snap(probe, ledgeY - 0.03);
            Vec3d hang = new Vec3d(hold.x, at.y, hold.z);
            if (empty(p, column(hang.x, hang.y, hang.z, 1.8)) && lip(p, hang, hang.y, HANG_H - 0.15, HANG_H + 0.15) != null) {
                return startCorner(p, keep, outer, at, hang);
            }
        }
        dir = keep;
        return false;
    }

    /** Hand over hand round the corner: the body moves and turns there over a few ticks. */
    private static boolean startCorner(ClientPlayerEntity p, Direction old, Direction now, Vec3d at, Vec3d hang) {
        net.minecraft.world.World w = p.getWorld();
        handsFrom = handPoints(w, at, old, ledgeY);
        handsTo = handPoints(w, hang, now, ledgeY);
        yawFrom = old.asRotation();
        yawTo = now.asRotation();
        dir = old;
        cornerTo = now;
        from = at;
        to = hang;
        set(Mode.CORNER);
        return true;
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
