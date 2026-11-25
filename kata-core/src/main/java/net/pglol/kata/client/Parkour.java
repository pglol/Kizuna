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
import net.pglol.kata.KataCore;
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
    // Momentum: last tick's velocity (what you hit things with), and how long you've been flying fast.
    private static Vec3d lastVel = Vec3d.ZERO;
    private static boolean lastFlying;
    // A leap cut short in the air carries on as a real flight: for a while the hands still go for the
    // lip it was aimed at, in the direction it was aimed.
    private static int flightTicks;
    private static Direction flightDir = Direction.NORTH;
    private static int fastAir;
    /** Hitting a wall this fast (blocks a tick, ~11 m/s) knocks you flat; landing this fast across the
     *  ground tumbles you; flung through the air this fast you lose control. */
    private static final double SLAM_SPEED = 0.55, TUMBLE_SPEED = 0.62, FLUNG_SPEED = 0.9;
    // Walking in to a ledge before the hop: ticks so far (0 = not), its height and the way.
    private static int stepIn;
    private static double stepRise;
    private static Direction stepDir = Direction.NORTH;

    /** While limp: the feet's height against the pelvis (eases to a hip's height). */
    private static double ragOff = Double.NaN;

    public enum Mode { NONE, VAULT, HANG, RUNUP, PULL, MANTLE, HOP, CORNER, LAND, FACEPLANT, RAGDOLL }

    /** What the animator needs: the move, how far into it (0..1), shuffle side, step phase, seconds in. */
    /** rough: how hard a landing was (0..1). */
    public record View(Mode mode, float progress, int side, float phase, float secs, float rough) {
        static final View NONE = new View(Mode.NONE, 0, 0, 0, 0, 0);
    }

    private static final int VAULT_TICKS = 9, PULL_TICKS = 13, MANTLE_TICKS = 9, HOP_TICKS = 7, RUNUP_TICKS = 8;
    private static final float RUNUP_COST = 0.12f;
    /** Hanging: the lip is this far above your feet, so straight-ish arms reach it (shoulders at 1.29). */
    public static final double HANG_H = 1.72;
    private static final int CORNER_TICKS = 11;
    private static Direction cornerTo = Direction.NORTH;
    private static float yawFrom, yawTo;
    private static Vec3d[] handsFrom, handsTo, pullHands;
    private static Vec3d cornerEdge;
    private static int cornerLead;
    private static Vec3d cornerMid;

    // Ledge jumps wind up first (a coil on the arms) for this many ticks.
    private static int hopWind;
    /** The leap's arc height above the straight line, and its length in ticks (both fitted to the gap). */
    private static double hopLift;
    private static int hopTicks = HOP_TICKS;

    // Landings: a crouch pressed just before touching down catches it (rougher the higher you fell);
    // too high, or a high fall without the crouch, and you go down on your face.
    private static final int CATCH_WINDOW = 6, FACEPLANT_TICKS = 28;
    private static final double LAND_MIN = 3.5, FACEPLANT_UNCAUGHT = 7, FACEPLANT_ALWAYS = 13;
    private static Vec3d airVel = Vec3d.ZERO;
    private static boolean stoodUp;
    private static Vec3d[] hopHands;
    /** A ledge jump that turns you round (to the wall behind), or null. */
    private static Direction hopTurn;
    private static double peakY;
    private static int sneakAge = 99, landTicks = 12;
    private static boolean lastSneak;
    private static float landRough;

    /** Movement while landing: slowed through a caught landing, none while face down. */
    public static float speedScale() {
        return mode == Mode.FACEPLANT || mode == Mode.RAGDOLL ? 0 : mode == Mode.LAND ? 0.45f : 1;
    }

    /** How fast a player was going as they hit the ground (blocks per second), for the ragdoll. */
    public static Vec3d impact(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && p.getId() == mc.player.getId()) return airVel.multiply(20);
        Vec3d v = p.getPos().subtract(p.prevX, p.prevY, p.prevZ).multiply(20);
        return new Vec3d(v.x, Math.min(v.y, -9), v.z);
    }

    /** The share of a ledge jump spent winding up (for the animator). */
    public static float hopWindShare() {
        return hopWind / (float) (hopTicks + hopWind);
    }

    // Shuffling, hand over hand: the lead hand reaches out (REACH ticks), then the body follows while the
    // other hand comes in (FOLLOW ticks). Let go of the key and the step in progress finishes, quicker.
    private static final double STEP = 0.3;
    /** Stamina for one quick (sprint-held) shuffle step: about 60 steps from full. */
    private static final float QUICK_SHUFFLE_COST = 1 / 60f;
    private static boolean cycQuick;
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
    /** How far off the wall the marker's lip is (0 when touching it). */
    private static double markerDist;

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
            if (mode == Mode.CORNER) return MathHelper.lerpAngleDegrees(ease((progress(Mode.CORNER, (System.nanoTime() - started) / 1e9f) - 0.15f) / 0.8f), yawFrom, yawTo);
            if (mode == Mode.HOP && hopTurn != null) {
                float k = (progress(Mode.HOP, (System.nanoTime() - started) / 1e9f) - hopWindShare()) / (1 - hopWindShare());
                return MathHelper.lerpAngleDegrees(ease(k), yawFrom, yawTo);
            }
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
            // The hands stay on the lip while winding up for a ledge jump, then let go.
            case HOP -> 1 - MathHelper.clamp((v.progress() - hopWindShare()) / 0.08f, 0, 1);
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
                    double arc = cyc == 3 ? 0 : 0.06 * MathHelper.sin(MathHelper.PI * k);
                    h[cyc == 1 ? lead : 1 - lead] = handFrom.lerp(handTo, e).add(0, arc, 0);
                }
                return h;
            }
            if (mode == Mode.HOP && hopHands != null) return hopHands;
            if (mode == Mode.CORNER && handsFrom != null) {
                // Hand over hand round the corner: the leading hand reaches round onto the next face first,
                // the body follows it round, then the trailing hand lets go and comes round after it.
                float k = progress(Mode.CORNER, (System.nanoTime() - started) / 1e9f);
                int l = cornerLead;
                Vec3d[] h = new Vec3d[2];
                h[l] = round(handsFrom[l], handsTo[l], ease((k - 0.0f) / 0.5f));
                h[1 - l] = round(handsFrom[1 - l], handsTo[1 - l], ease((k - 0.45f) / 0.5f));
                return h;
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
            return new View(mode, progress(mode, secs), side, phase, secs, landRough);
        }
        Remote r = REMOTE.get(p.getId());
        if (r == null) return View.NONE;
        float secs = (System.nanoTime() - r.started) / 1e9f;
        if (r.mode != Mode.HANG && secs > 2) return View.NONE;
        float moved = (float) Math.sqrt(p.squaredDistanceTo(p.prevX, p.prevY, p.prevZ));
        return new View(r.mode, progress(r.mode, secs), r.side, secs * 9 * Math.min(1, moved * 12), secs, 0.5f);
    }

    private static float progress(Mode m, float secs) {
        int t = switch (m) {
            case VAULT -> VAULT_TICKS;
            case PULL -> PULL_TICKS;
            case MANTLE -> MANTLE_TICKS;
            case HOP -> hopTicks + hopWind;
            case LAND -> landTicks;
            case FACEPLANT -> FACEPLANT_TICKS;
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
        sneakAge++;
        if (sneak && !lastSneak) {
            sneakAge = 0;
            // Crouching to catch a landing: tell the server now (it works out fall damage on touchdown).
            if (!ground && p.getVelocity().y < -0.3 && ClientPlayNetworking.canSend(KataNet.Act.ID))
                ClientPlayNetworking.send(new KataNet.Act(KataNet.BRACE, 0));
        }
        lastSneak = sneak;
        if (!ground) airVel = p.getVelocity();

        boolean free = p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isTouchingWater() || p.isFallFlying() || p.isDead();
        // Water ends any move, except going limp: a limp body lands in it and floats (see Ragdoll).
        boolean wetOnly = p.isTouchingWater() && !(p.getAbilities().flying || p.isSpectator() || p.hasVehicle() || p.isFallFlying() || p.isDead());
        if (free && mode != Mode.NONE && !(mode == Mode.RAGDOLL && wetOnly)) set(Mode.NONE);
        // Speed: slamming into a wall, landing fast across the ground, or being flung hard through the
        // air puts you down, with all the speed you had.
        if (!free && (mode == Mode.NONE || mode == Mode.VAULT)) {
            double hs = lastVel.horizontalLength();
            boolean slam = !ground && p.horizontalCollision && hs > SLAM_SPEED;
            boolean tumble = ground && !lastGround && hs > TUMBLE_SPEED;
            fastAir = !ground && p.getVelocity().horizontalLength() > FLUNG_SPEED ? fastAir + 1 : 0;
            // Stopping flight at speed: nothing is holding you up any more and you're still going.
            boolean dropped = lastFlying && !ground && p.getVelocity().length() > 0.6;
            if (slam || tumble || dropped || fastAir >= 2) {
                airVel = slam || tumble ? lastVel : p.getVelocity();
                landRough = 1;
                stoodUp = false;
                p.setSprinting(false);
                stepIn = 0;
                set(Mode.RAGDOLL);
                if (slam && ClientPlayNetworking.canSend(KataNet.Act.ID))
                    ClientPlayNetworking.send(new KataNet.Act(KataNet.IMPACT, (int) Math.min(127, Math.round(hs * 20))));
            }
        }
        // Landings.
        boolean airMode = mode == Mode.NONE || mode == Mode.VAULT;
        if (!airMode || ground) {
            if (ground && !lastGround && airMode && !free) land(p, peakY - p.getY());
            peakY = p.getY();
        } else {
            peakY = Math.max(peakY, p.getY());
            if (mode == Mode.NONE && !free && p.getVelocity().y < -0.5) brace(p);
        }

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
                if (flightTicks > 0 && (ground || --flightTicks <= 0)) flightTicks = 0;
                if (flightTicks > 0) {
                    dir = flightDir;
                    if (wall(p, at, 0.3, 1.7)) {
                        Double l = lip(p, at, at.y, 1.3, 2.3);
                        if (l != null && grab(p, at, l)) {
                            flightTicks = 0;
                            break;
                        }
                    }
                    dir = p.getHorizontalFacing();
                }
                if (!ground && intent != Intent.NONE && wall(p, at, 0.3, 1.7)) reach(p, at, climbAsk);
                else if (!ground && mode == Mode.NONE) reflex(p, sneak, false);
            }
            case VAULT -> {
                ticks++;
                if (ticks > VAULT_TICKS + 4 || (ground && ticks > 3)) set(Mode.NONE);
            }
            case HANG -> {
                Vec3d at = p.getPos();
                ticks++;
                if (sneak) drop(p, 0.08);
                else if (jumpEdge && away) {
                    if (!backJump(p, at)) kickOff(p);
                } else if ((toward(p) && ticks > 4 && cyc == 0) || (climbAsk && !jumpEdge && ticks > 2)) {
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
            case LAND -> {
                if (++ticks >= landTicks) set(Mode.NONE);
            }
            case FACEPLANT -> {
                if (++ticks >= FACEPLANT_TICKS) set(Mode.NONE);
            }
            case RAGDOLL -> {
                ticks++;
                Ragdoll r = Ragdoll.of(p);
                // Still in the air and not yet hit anything: a hand can still find a lip.
                if (!ground && r != null && !r.resting() && ticks > 2 && reflex(p, sneak, true)) {
                    Ragdoll.stop(p);
                    break;
                }
                if (ticks > 4 && (r == null || r.finished())) set(Mode.NONE);
                else if (r != null && r.resting() && !stoodUp) {
                    // Coming round. Get up where the body ended up; afloat with nothing to stand on, just swim.
                    stoodUp = true;
                    Vec3d pel = r.pelvis();
                    if (r.inWater()) {
                        Double floor = null;
                        for (double d = 0; d <= 1.6; d += 0.1) {
                            if (!empty(p, new Box(pel.x - 0.25, pel.y - d - 0.05, pel.z - 0.25, pel.x + 0.25, pel.y - d, pel.z + 0.25))) {
                                floor = pel.y - d;
                                break;
                            }
                        }
                        if (floor == null) {
                            set(Mode.NONE);
                            p.setVelocity(0, 0.05, 0);
                            break;
                        }
                        if (empty(p, column(pel.x, floor, pel.z, 1.8))) p.setPosition(pel.x, floor, pel.z);
                    } else if (empty(p, column(pel.x, p.getY(), pel.z, 1.8))) p.setPosition(pel.x, p.getY(), pel.z);
                }
            }
            default -> { }
        }
        if (ground) {
            groundY = p.getY();
            if (intent != Intent.NONE && !jump && pressAge > DOUBLE) intent = Intent.NONE;
        }
        lastGround = ground;
        lastVel = p.getVelocity();
        lastFlying = p.getAbilities().flying;
        updateMarker(p, ground);
        syncOut();
    }

    /**
     * Falling: how far to the ground below, and whether this fall is a ragdoll. A really high one goes
     * limp before it lands (a block or two up) so the body falls into the impact; a crouch timed
     * just before touching down turns a merely high one into a caught landing instead.
     */
    private static void brace(ClientPlayerEntity p) {
        double below = -1;
        for (double d = 0; d <= 16; d += 0.25) {
            if (!empty(p, new Box(p.getX() - 0.25, p.getY() - d - 0.01, p.getZ() - 0.25, p.getX() + 0.25, p.getY() - d, p.getZ() + 0.25))) {
                below = d;
                break;
            }
        }
        double fallen = peakY - p.getY();
        double total = below < 0 ? fallen + 16 : fallen + below;
        boolean huge = total >= FACEPLANT_ALWAYS, caught = sneakAge <= CATCH_WINDOW;
        // A big drop: the body gives up early, a few blocks into the fall, and you ride it all the way
        // down. A medium one you didn't brace for: limp a block or so before the ground.
        if ((huge && fallen >= 3.5) || (!caught && below >= 0 && total >= FACEPLANT_UNCAUGHT && below <= 1.2)) {
            landRough = 1;
            stoodUp = false;
            p.setSprinting(false);
            set(Mode.RAGDOLL);
        }
    }

    private static void land(ClientPlayerEntity p, double h) {
        boolean caught = sneakAge <= CATCH_WINDOW;
        if (h >= FACEPLANT_ALWAYS || (!caught && h >= FACEPLANT_UNCAUGHT)) {
            // Too high: the body goes limp on impact and lands how it lands.
            landRough = 1;
            stoodUp = false;
            p.setSprinting(false);
            set(Mode.RAGDOLL);
            p.setVelocity(Vec3d.ZERO);
        } else if (caught && h >= LAND_MIN) {
            landRough = (float) MathHelper.clamp((h - LAND_MIN) / (FACEPLANT_ALWAYS - LAND_MIN), 0, 1);
            landTicks = 9 + Math.round(landRough * 10);
            set(Mode.LAND);
        }
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
        hopHands = handPoints(p.getWorld(), at, dir, ledgeY);
        from = at;
        to = hang;
        ledgeY = l;
        hopWind = 4;
        planArc(p);
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
                hopHands = handPoints(p.getWorld(), at, dir, ledgeY);
                from = at;
                to = hang;
                ledgeY = l;
                hopWind = 4;
                planArc(p);
                set(Mode.HOP);
                Parkour.side = side;
                return;
            }
        }
    }

    /** Leap backwards, turning round in the air, to a lip on the wall behind (up to ~3 blocks away). */
    /**
     * Jump pressed on the ground. Facing a lip you can get your hands to, the jump is read from what's
     * between you and it: with floor all the way you walk in to the wall and hop straight up, just as
     * high as the hands need; over a real gap you leap it, just far and high enough to arrive at the
     * lip. Anything else is an ordinary jump (false).
     */
    public static boolean preJump(ClientPlayerEntity p) {
        if (mode != Mode.NONE || cooldown > 0 || !p.isOnGround() || Move.mode == Move.Mode.RUN
            || p.getAbilities().flying || p.isTouchingWater() || p.input.movementForward < -0.3f) return false;
        Direction keep = dir;
        dir = p.getHorizontalFacing();
        try {
            Vec3d at = p.getPos();
            double feet = p.getY();
            double dist = wallDist(p, at, feet);
            if (dist < 0) return false;
            Double l = lip(p, ahead(at, dist), feet, 1.3, 3.5);
            // Low enough to jump onto, vault or climb straight up: those have their own moves.
            if (l == null || l - feet < 1.7) return false;
            double rise = l - (feet + 2.15);
            if (rise > 1.3) return false;
            boolean gap = false;
            for (double s = 0.1; s < dist; s += 0.2) {
                Vec3d c = ahead(at, s);
                if (empty(p, new Box(c.x - 0.15, feet - 1.0, c.z - 0.15, c.x + 0.15, feet - 0.01, c.z + 0.15))) {
                    gap = true;
                    break;
                }
            }
            if (!gap && dist > 0.12) {
                stepIn = 1;
                stepRise = rise;
                stepDir = dir;
                return true;
            }
            leap(p, dist, rise);
            return true;
        } finally {
            dir = keep;
        }
    }

    /** How far you can go straight ahead before touching what's in front (up to head-and-reach height). */
    private static double wallDist(ClientPlayerEntity p, Vec3d at, double feet) {
        for (double s = 0; s <= 1.8; s += 0.04) {
            Vec3d c = ahead(at, s);
            if (!empty(p, column(c.x, feet + 0.1, c.z, 2.3))) return s;
        }
        return -1;
    }

    /** Off the ground towards the lip: just high enough for the hands and just far enough to arrive. */
    private static void leap(ClientPlayerEntity p, double dist, double rise) {
        double g = KataCore.GRAVITY;
        double h = Math.max(0.15, rise + 0.12);
        double vy = Math.sqrt(2 * g * h) * 1.05 + 0.01;
        double apex = vy / g;
        double vx = dist <= 0.05 ? 0.02 : Math.min(0.5, (dist + 0.05) / Math.max(2, apex * 0.9));
        p.setVelocity(dir.getOffsetX() * vx, vy, dir.getOffsetZ() * vx);
        if (intent == Intent.NONE) intent = Intent.GRAB;
        intentAge = 0;
    }

    /** Walking in to the wall under a lip, then the hop up to it. */
    private static boolean stepIn(ClientPlayerEntity p) {
        Direction keep = dir;
        dir = stepDir;
        try {
            if (!p.isOnGround() || p.input.movementForward < -0.3f || ++stepIn > 40) {
                stepIn = 0;
                return false;
            }
            double dist = wallDist(p, p.getPos(), p.getY());
            if (dist < 0) {
                stepIn = 0;
                return false;
            }
            if (dist <= 0.08) {
                stepIn = 0;
                leap(p, dist, stepRise);
                return false; // Minecraft carries out the hop
            }
            double sp = Math.min(0.2, dist);
            Vec3d v = p.getVelocity();
            p.setVelocity(dir.getOffsetX() * sp, v.y, dir.getOffsetZ() * sp);
            return false; // walked by Minecraft (so the legs walk too)
        } finally {
            dir = keep;
        }
    }

    /**
     * Through the air you keep your momentum: real air drag (it grows with the square of the speed) in
     * place of Minecraft's heavy air brake, and your legs can only steer you a little, up to about a
     * running speed. A fast flight you stop, a leap, a launch: the speed carries until something
     * stops it.
     */
    private static boolean air(ClientPlayerEntity p) {
        if (p.isOnGround() || p.getAbilities().flying || p.isFallFlying() || p.isTouchingWater() || p.isInLava()
            || p.isClimbing() || p.hasVehicle() || p.isSpectator()
            || p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.LEVITATION)
            || p.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.SLOW_FALLING)) return false;
        Vec3d v = p.getVelocity();
        double f = p.input.movementForward, s = p.input.movementSideways;
        double len = Math.sqrt(f * f + s * s);
        if (len > 1e-3) {
            if (len > 1) {
                f /= len;
                s /= len;
            }
            double yaw = Math.toRadians(p.getYaw());
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            double wx = s * cos - f * sin, wz = f * cos + s * sin;
            double wl = Math.sqrt(wx * wx + wz * wz);
            double accel = (p.isSprinting() ? 0.026 : 0.02) * Math.min(1, wl);
            double cap = p.isSprinting() ? 0.36 : 0.22;
            wx /= wl;
            wz /= wl;
            double along = v.x * wx + v.z * wz;
            double add = MathHelper.clamp(cap - along, 0, accel);
            v = v.add(wx * add, 0, wz * add);
        }
        double hs = v.horizontalLength();
        double keepShare = Math.max(0, 1 - 0.0032 * hs) * 0.995;
        v = new Vec3d(v.x * keepShare, v.y, v.z * keepShare);
        p.setVelocity(v);
        p.move(MovementType.SELF, v);
        Vec3d a = p.getVelocity();
        p.setVelocity(a.x, (a.y - p.getFinalGravity()) * 0.98, a.z);
        return true;
    }

    /**
     * The catch reflex: falling past a lip (a real fall, not stepping off the edge you were on), the
     * hands go for it, whichever side of you it's on, facing first. Too fast and the grip can't take
     * it; holding crouch means letting yourself drop.
     */
    private static boolean reflex(ClientPlayerEntity p, boolean sneak, boolean limp) {
        double vy = p.getVelocity().y;
        double fallen = peakY - p.getY();
        if (sneak || (!limp && fallen < 2.5) || vy > -0.35 || vy < -1.1 || cooldown > 0) return false;
        Direction keep = dir;
        Direction face = p.getHorizontalFacing();
        Vec3d at = p.getPos();
        for (Direction d : new Direction[] {face, face.rotateYClockwise(), face.rotateYCounterclockwise(), face.getOpposite()}) {
            dir = d;
            if (!wall(p, at, 0.3, 1.7)) continue;
            Double l = lip(p, at, at.y, 1.3, 2.3);
            if (l == null || (!limp && l > peakY + 0.5)) continue; // not one you've just come off
            if (grab(p, at, l)) return true;
        }
        dir = keep;
        return false;
    }

    /** Where a leap from `from` to `to` is at k (0..1): off fast, easing into the catch, lifted by lift. */
    private static Vec3d arc(float k, double lift) {
        float u = 1 - (1 - k) * (1 - k);
        double up = lift * MathHelper.sin(MathHelper.PI * k);
        return new Vec3d(MathHelper.lerp(u, from.x, to.x), MathHelper.lerp(u, from.y, to.y) + up, MathHelper.lerp(u, from.z, to.z));
    }

    /**
     * Fit the leap to the gap: as low an arc as gets the whole body through clear air (checked all the
     * way along), and a flight time that grows with the distance. False if no arc is clear.
     */
    private static boolean planArc(ClientPlayerEntity p) {
        double across = Math.hypot(to.x - from.x, to.z - from.z);
        hopTicks = MathHelper.clamp((int) Math.round(5 + across * 2.2), HOP_TICKS, 14);
        double base = Math.min(0.5, across * 0.25);
        for (double extra : new double[] {0, 0.25, 0.5, 0.8, 1.1}) {
            boolean clear = true;
            for (int i = 1; i < 16 && clear; i++) {
                Vec3d c = arc(i / 16f, base + extra);
                // A touch slimmer than the body: grazing an edge is fine, going through a block isn't.
                clear = empty(p, new Box(c.x - 0.25, c.y + 0.05, c.z - 0.25, c.x + 0.25, c.y + 1.75, c.z + 0.25));
            }
            if (clear) {
                hopLift = base + extra;
                return true;
            }
        }
        hopLift = base;
        return false;
    }

    private static boolean backJump(ClientPlayerEntity p, Vec3d at) {
        Direction keep = dir, back = dir.getOpposite();
        Vec3d b = new Vec3d(back.getOffsetX(), 0, back.getOffsetZ());
        dir = back;
        try {
            Vec3d fbHang = null;
            double fbLip = 0;
            for (double d = 0.6; d <= 4.2; d += 0.12) {
                for (double dy = 0; dy <= 1.5; dy = dy <= 0 ? -dy + 0.25 : -dy) {
                    Vec3d c = at.add(b.multiply(d)).add(0, dy, 0);
                    if (!empty(p, column(c.x, c.y, c.z, 1.8))) continue;
                    Double l = lip(p, c, c.y, HANG_H - 0.2, HANG_H + 0.2);
                    if (l == null) continue;
                    Vec3d hold = snap(c, l - 0.03);
                    Vec3d hang = new Vec3d(hold.x, l - HANG_H, hold.z);
                    if (!empty(p, column(hang.x, hang.y, hang.z, 1.8))) continue;
                    from = at;
                    to = hang;
                    if (!planArc(p)) {
                        // Nothing clear to fly through to this one: remember it, try further on.
                        if (fbHang == null) {
                            fbHang = hang;
                            fbLip = l;
                        }
                        continue;
                    }
                    return backLeap(p, at, keep, back, hang, l);
                }
            }
            // No perfectly clear arc anywhere: go for the nearest lip all the same (a leap that hits
            // something carries on as a free flight and still catches it).
            if (fbHang != null) {
                from = at;
                to = fbHang;
                planArc(p);
                return backLeap(p, at, keep, back, fbHang, fbLip);
            }
            return false;
        } finally {
            if (mode != Mode.HOP) dir = keep;
        }
    }

    private static boolean backLeap(ClientPlayerEntity p, Vec3d at, Direction keep, Direction back, Vec3d hang, double l) {
        dir = keep;
        hopHands = handPoints(p.getWorld(), at, keep, ledgeY);
        hopTurn = back;
        yawFrom = keep.asRotation();
        yawTo = back.asRotation();
        from = at;
        to = hang;
        ledgeY = l;
        hopWind = 4;
        set(Mode.HOP);
        return true;
    }

    private static void kickOff(ClientPlayerEntity p) {
        set(Mode.NONE);
        cooldown = 8;
        intent = Intent.NONE;
        p.setVelocity(-dir.getOffsetX() * 0.38, 0.42, -dir.getOffsetZ() * 0.38);
    }

    /** On a wall the keys are the wall's: W climbs (towards it), S is away, A/D along it; the camera doesn't matter. */
    private static boolean toward(ClientPlayerEntity p) {
        return wallForward(p) > 0.5f;
    }

    private static boolean away(ClientPlayerEntity p) {
        return wallForward(p) < -0.5f;
    }

    /**
     * Forward/back as the wall sees it. Normally that's just W and S, whatever the camera does; but
     * turned right round to look behind you (at a ledge there, say), W means "that way": away from the
     * wall.
     */
    private static float wallForward(ClientPlayerEntity p) {
        float f = p.input.movementForward;
        return lookingBack(p) ? -f : f;
    }

    private static boolean lookingBack(ClientPlayerEntity p) {
        return Math.abs(MathHelper.wrapDegrees(p.getYaw() - dir.asRotation())) > 110;
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
                    markerDist = k;
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
    /**
     * If what you're climbing is less than about half a block deep with open air beyond it, where to
     * go to clear it (just past it, at the top's height: you drop down the far side from there).
     */
    private static Vec3d overThin(ClientPlayerEntity p, Vec3d up, double top) {
        double depth = -1;
        for (double s = 0.36; s <= 1.2; s += 0.04) {
            Vec3d c = ahead(up, s);
            if (empty(p, new Box(c.x - 0.05, top - 0.1, c.z - 0.05, c.x + 0.05, top - 0.01, c.z + 0.05))) {
                depth = s - 0.36;
                break;
            }
        }
        if (depth < 0 || depth > 0.6) return null;
        Vec3d beyond = ahead(up, 0.36 + depth + 0.35);
        if (!empty(p, column(beyond.x, beyond.y, beyond.z, 1.8))) return null;
        return beyond;
    }

    private static boolean startPath(ClientPlayerEntity p, Mode m, double top) {
        Vec3d at = p.getPos();
        Vec3d up = new Vec3d(at.x, top + 0.01, at.z);
        Vec3d end = ahead(up, 0.8);
        if (!empty(p, column(at.x, at.y, at.z, top + 1.8 - at.y))) return false;
        // A thin top (a rail, a fence, a wall's coping): over it and down beside it, not stood on.
        Vec3d over = overThin(p, up, top);
        if (over != null) end = over;
        else {
            if (!empty(p, column(end.x, end.y, end.z, 1.8))) return false;
            if (empty(p, column(end.x, end.y - 0.1, end.z, 0.1))) return false; // nothing to stand on
        }
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
        if (m != Mode.HOP) hopTurn = null;
        if (m == Mode.RAGDOLL && mode != Mode.RAGDOLL) ragOff = Double.NaN;
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
        if (mode == Mode.NONE) {
            if (stepIn > 0) return stepIn(p);
            return air(p);
        }
        switch (mode) {
            case HANG -> {
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                // Shuffle along the lip, hand over hand (left is +sideways), if it carries on that way.
                float s = p.input.movementSideways;
                int want = s > 0.1f ? -1 : s < -0.1f ? 1 : 0;
                Vec3d at = p.getPos();
                if (plant == null) plant = handPoints(p.getWorld(), at, dir, ledgeY);
                if (cyc == 3) {
                    cycK += cycRate;
                    if (cycK >= 1) cyc = 0;
                    return true;
                }
                // Sprint held (with breath to spare): a quick shuffle, longer reaches at a faster beat,
                // costing a little stamina for each reach.
                boolean quick = want != 0 && Move.sprintHeld(MinecraftClient.getInstance()) && !Move.winded();
                if (cyc == 0 && want != 0) {
                    Vec3d r = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(want);
                    Vec3d next = null;
                    for (double d = quick ? STEP * 1.4 : STEP; d > 0.05; d -= 0.1) {
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
                        cycQuick = quick;
                        if (quick && !p.isCreative()) Move.spend(QUICK_SHUFFLE_COST);
                    } else if (corner(p, want)) return true;
                }
                side = cyc != 0 ? cycSide : 0;
                if (cyc != 0) {
                    if (want != cycSide) hurry = true;
                    cycRate = (cyc == 1 ? 1 / REACH : 1 / FOLLOW) * (hurry ? HURRY : 1) * (cycQuick ? 1.4f : 1);
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
            case FACEPLANT -> {
                p.setVelocity(0, Math.min(0, p.getVelocity().y), 0);
                return false;
            }
            case RAGDOLL -> {
                // You are the body: no walking; you go where it goes, up, down and across, so the camera
                // rides the whole fall and the impact with it instead of landing before it.
                Ragdoll r = Ragdoll.of(p);
                p.setSprinting(false);
                if (r != null && !r.resting()) {
                    Vec3d pel = r.pelvis();
                    double dx = MathHelper.clamp(pel.x - p.getX(), -2.5, 2.5);
                    double dz = MathHelper.clamp(pel.z - p.getZ(), -2.5, 2.5);
                    // Feet a hip's height under the pelvis (the ground stops them when it's lying down).
                    // Eased in from wherever they were when it went limp, so the camera never jerks.
                    if (Double.isNaN(ragOff)) ragOff = p.getY() - pel.y;
                    ragOff += (-0.70 - ragOff) * 0.15;
                    double dy = MathHelper.clamp(pel.y + ragOff - p.getY(), -3.0, 0.3);
                    p.setVelocity(0, dy, 0);
                    p.move(MovementType.SELF, new Vec3d(dx, dy, dz));
                    return true;
                }
                double vy = p.isOnGround() ? 0 : (p.getVelocity().y - KataCore.GRAVITY) * 0.98;
                p.setVelocity(0, vy, 0);
                p.move(MovementType.SELF, new Vec3d(0, vy, 0));
                return true;
            }
            case CORNER -> {
                ticks++;
                float k = ease((ticks / (float) CORNER_TICKS - 0.15f) / 0.8f); // after the leading hand has reached
                // Two legs round the outside of the corner (a straight line would cut through the block).
                Vec3d target = k < 0.5f ? from.lerp(cornerMid, k * 2) : cornerMid.lerp(to, (k - 0.5f) * 2);
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                p.move(MovementType.SELF, target.subtract(p.getPos()));
                if (ticks >= CORNER_TICKS) {
                    p.setPosition(to);
                    dir = cornerTo;
                    set(Mode.HANG);
                    ticks = 3;
                }
                return true;
            }
            case PULL, MANTLE, HOP -> {
                int total = mode == Mode.PULL ? PULL_TICKS : mode == Mode.MANTLE ? MANTLE_TICKS : hopTicks;
                ticks++;
                if (mode == Mode.HOP && ticks <= hopWind) {
                    // The wind-up: hold on and coil before the leap.
                    p.setVelocity(Vec3d.ZERO);
                    p.fallDistance = 0;
                    return true;
                }
                float k = Math.min(1, (ticks - (mode == Mode.HOP ? hopWind : 0)) / (float) total);
                Vec3d target;
                if (mode == Mode.HOP) {
                    // A spring straight up, fast off the hands, easing into the catch.
                    target = arc(k, hopLift);
                } else if (k < 0.62f) {
                    // Up first (most of it), then over onto the top.
                    float u = ease(k / 0.62f);
                    target = new Vec3d(from.x, MathHelper.lerp(u, from.y, to.y), from.z);
                } else {
                    float u = ease((k - 0.62f) / 0.38f);
                    target = new Vec3d(MathHelper.lerp(u, from.x, to.x), to.y, MathHelper.lerp(u, from.z, to.z));
                }
                p.fallDistance = 0;
                Vec3d was = p.getPos();
                p.move(MovementType.SELF, target.subtract(was));
                // The body really moves at the speed of the path, so whatever happens next, it carries.
                p.setVelocity(p.getPos().subtract(was));
                if (p.getPos().squaredDistanceTo(target) > 0.04) {
                    // Brushed something on the way: a leap slides past an edge it only grazes (the path
                    // was checked clear before it started); a real block in the way turns it into a free
                    // flight that keeps its speed and still catches the lip it was aimed at.
                    if (mode == Mode.HOP && empty(p, column(target.x, target.y + 0.05, target.z, 1.7))) {
                        p.setPosition(target);
                        p.setVelocity(target.subtract(was));
                    } else if (mode == Mode.HOP) {
                        flightDir = hopTurn != null ? hopTurn : dir;
                        flightTicks = 20;
                        p.setVelocity(target.subtract(was).multiply(0.8));
                        set(Mode.NONE);
                    } else set(Mode.NONE);
                }
                else if (k >= 1) {
                    if (mode == Mode.HOP) {
                        int s = side;
                        if (hopTurn != null) dir = hopTurn;
                        p.setPosition(to);
                        set(Mode.HANG);
                        side = s; // the animator swings the catch on in the direction of the leap
                        ticks = 0;
                        if (s != 0) {
                            // The leading hand has it; the other comes up to the lip a beat later.
                            plant = handPoints(p.getWorld(), to, dir, ledgeY);
                            lead = s > 0 ? 0 : 1;
                            Vec3d r = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(s);
                            handFrom = plant[1 - lead].add(0, -0.55, 0).subtract(r.multiply(0.2));
                            handTo = plant[1 - lead];
                            cyc = 3;
                            cycK = 0;
                            cycRate = 1 / 4f;
                        }
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
                return startCorner(p, keep, inner, at, hang, side);
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
                return startCorner(p, keep, outer, at, hang, side);
            }
        }
        dir = keep;
        return false;
    }

    /** Hand over hand round the corner: the body moves and turns there over a few ticks. */
    private static boolean startCorner(ClientPlayerEntity p, Direction old, Direction now, Vec3d at, Vec3d hang, int side) {
        net.minecraft.world.World w = p.getWorld();
        handsFrom = handPoints(w, at, old, ledgeY);
        handsTo = handPoints(w, hang, now, ledgeY);
        cornerLead = side > 0 ? 0 : 1;
        // Where the two faces' lips meet: both hands go round by it, never through the block or the air.
        Vec3d a = handsFrom[0], b = handsTo[0];
        cornerEdge = new Vec3d(old.getOffsetX() != 0 ? a.x : b.x, ledgeY, old.getOffsetZ() != 0 ? a.z : b.z);
        yawFrom = old.asRotation();
        yawTo = now.asRotation();
        dir = old;
        cornerTo = now;
        from = at;
        to = hang;
        // The way round: via the outside point that's clear (the middle if neither is).
        Vec3d m1 = new Vec3d(hang.x, at.y, at.z), m2 = new Vec3d(at.x, at.y, hang.z);
        cornerMid = empty(p, column(m1.x, m1.y, m1.z, 1.8)) ? m1 : empty(p, column(m2.x, m2.y, m2.z, 1.8)) ? m2 : at.lerp(hang, 0.5);
        set(Mode.CORNER);
        return true;
    }

    /** A hand's way round a corner: along the lip, round its edge, onto the other face, lifted a touch. */
    private static Vec3d round(Vec3d from, Vec3d to, float k) {
        if (k <= 0) return from;
        if (k >= 1) return to;
        Vec3d c = cornerEdge == null ? from.lerp(to, 0.5) : cornerEdge;
        double u = 1 - k;
        Vec3d q = from.multiply(u * u).add(c.multiply(2 * u * k)).add(to.multiply(k * k));
        return q.add(0, 0.07 * MathHelper.sin(MathHelper.PI * k), 0);
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
