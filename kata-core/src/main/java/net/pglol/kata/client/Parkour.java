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
 * Basic parkour, worked out on your own game (that's where Minecraft moves you) and shown to everyone.
 *
 *  - Vault: running or fast walking at something low and thin (up to 1.5 blocks), press jump: over it
 *    with one hand planted, keeping your speed.
 *  - Climb onto: jump at something up to about 2.4 blocks high (or too thick to vault): pull up onto it.
 *  - Ledge hang: jump at a taller wall holding jump and forward, and you catch the top if your hands
 *    reach it. A/D shuffle along, W or jump pulls up, sneak drops.
 *  - Wall climb: no ledge in reach, you grip the wall itself. W/S up and down, A/D sideways; it costs
 *    stamina. Reaching the top pulls you up; jump kicks off backwards; sneak lets go.
 *  - Run-up: at a full run into a tall wall, jump to run a couple of blocks up it before grabbing on.
 */
public final class Parkour {
    private Parkour() {}

    public enum Mode { NONE, VAULT, HANG, CLIMB, PULL, MANTLE }

    /** What the animator needs for a player: the move, how far into it (0..1), shuffle side, step phase. */
    public record View(Mode mode, float progress, int side, float phase) {
        static final View NONE = new View(Mode.NONE, 0, 0, 0);
    }

    private static final int VAULT_TICKS = 9, PULL_TICKS = 13, MANTLE_TICKS = 9;
    private static final float CLIMB_COST = 1 / (9f * 20), RUNUP_COST = 0.08f;

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
            return new View(mode, progress(mode, (System.nanoTime() - started) / 1e9f), side, phase);
        }
        Remote r = REMOTE.get(p.getId());
        if (r == null) return View.NONE;
        float secs = (System.nanoTime() - r.started) / 1e9f;
        if ((r.mode == Mode.VAULT || r.mode == Mode.PULL || r.mode == Mode.MANTLE) && secs > 2) return View.NONE;
        float moved = (float) Math.sqrt(p.squaredDistanceTo(p.prevX, p.prevY, p.prevZ));
        return new View(r.mode, progress(r.mode, secs), r.side, secs * 9 * Math.min(1, moved * 12));
    }

    private static float progress(Mode m, float secs) {
        return switch (m) {
            case VAULT -> Math.min(1, secs / (VAULT_TICKS / 20f));
            case PULL -> Math.min(1, secs / (PULL_TICKS / 20f));
            case MANTLE -> Math.min(1, secs / (MANTLE_TICKS / 20f));
            default -> 0;
        };
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

    /**
     * The top of whatever is in front: the lowest height (between lo and hi above feetY) where a player
     * could stand on it, with room straight up beside it to get there. null if there's none.
     */
    private static Double ledge(ClientPlayerEntity p, Vec3d at, double feetY, double lo, double hi) {
        Vec3d top = ahead(at, 0.8);
        for (double dy = lo; dy <= hi + 1e-6; dy += 1 / 16.0) {
            double y = feetY + dy;
            if (!empty(p, column(top.x, y, top.z, 1.8))) continue;
            if (empty(p, column(top.x, y - 0.07, top.z, 0.07))) continue;
            if (!empty(p, column(at.x, Math.max(feetY, p.getY()), at.z, y + 1.8 - Math.max(feetY, p.getY())))) return null;
            return y;
        }
        return null;
    }

    /** Where to hold on: centred 0.31 from the face of the block in front at height y. */
    private static Vec3d snap(ClientPlayerEntity p, Vec3d at, double y) {
        BlockPos b = BlockPos.ofFloored(ahead(at, 0.6).x, y, ahead(at, 0.6).z);
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
            return;
        }
        boolean jump = mc.options.jumpKey.isPressed() && mc.currentScreen == null;
        boolean jumpEdge = jump && !lastJump;
        lastJump = jump;
        boolean sneak = mc.options.sneakKey.isPressed() && mc.currentScreen == null;
        boolean ground = p.isOnGround();
        float fwd = p.input.movementForward;
        if (cooldown > 0) cooldown--;

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
                    if (low != null) {
                        double h = low - feet;
                        boolean thin = empty(p, column(ahead(at, 1.9).x, feet, ahead(at, 1.9).z, 1.8))
                            && empty(p, column(ahead(at, 1.05).x, low + 0.05, ahead(at, 1.05).z, 1.8));
                        if (thin && Move.mode != Move.Mode.WALK) {
                            double speed = Math.max(0.34, p.getVelocity().horizontalLength());
                            p.setVelocity(dir.getOffsetX() * speed, 0.42 + Math.max(0, h - 1.0) * 0.36, dir.getOffsetZ() * speed);
                            set(Mode.VAULT);
                        } else startPath(p, Mode.MANTLE, low);
                        break;
                    }
                    Double mid = ledge(p, at, feet, 1.6, 2.4);
                    if (mid != null) {
                        startPath(p, Mode.MANTLE, mid);
                        break;
                    }
                    if (Move.mode == Move.Mode.RUN && wall(p, at, 0.3, 1.8) && Move.stamina > RUNUP_COST) {
                        // Run up the wall: a strong kick up it, then catch on below.
                        Move.stamina -= RUNUP_COST;
                        p.setVelocity(p.getVelocity().x * 0.3, 0.62, p.getVelocity().z * 0.3);
                    }
                } else if (!ground && jump && fwd > 0.1 && wall(p, at, 0.6, 1.7)) {
                    Double l = ledge(p, at, at.y, 1.3, 2.3);
                    if (l != null) {
                        Vec3d hold = snap(p, at, l - 0.03);
                        Vec3d hang = new Vec3d(hold.x, l - 2.05, hold.z);
                        if (empty(p, column(hang.x, hang.y, hang.z, 1.8))) {
                            ledgeY = l;
                            p.setPosition(hang);
                            p.setVelocity(Vec3d.ZERO);
                            p.fallDistance = 0;
                            set(Mode.HANG);
                        }
                    } else if (p.getVelocity().y < 0.25 && (Move.stamina > 0.1 || p.isCreative())) {
                        Vec3d hold = snap(p, at, at.y + 1.2);
                        if (empty(p, column(hold.x, at.y, hold.z, 1.8))) {
                            p.setPosition(hold.x, at.y, hold.z);
                            p.setVelocity(Vec3d.ZERO);
                            p.fallDistance = 0;
                            set(Mode.CLIMB);
                        }
                    }
                }
            }
            case VAULT -> {
                ticks++;
                if (ticks > VAULT_TICKS + 4 || (ground && ticks > 3)) set(Mode.NONE);
            }
            case HANG -> {
                Vec3d at = p.getPos();
                if (sneak) {
                    drop(p, 0.08);
                } else if (jumpEdge || fwd > 0.5) {
                    startPath(p, Mode.PULL, ledgeY);
                } else if (ledge(p, at, at.y, 1.8, 2.3) == null) {
                    drop(p, 0); // the ledge went (broken, or slid off the end)
                }
            }
            case CLIMB -> {
                if (sneak || (!p.isCreative() && Move.stamina <= 0)) drop(p, 0.1);
                else if (jumpEdge) {
                    // Kick off backwards.
                    set(Mode.NONE);
                    cooldown = 8;
                    p.setVelocity(-dir.getOffsetX() * 0.38, 0.45, -dir.getOffsetZ() * 0.38);
                } else if (ground && fwd < 0) set(Mode.NONE);
            }
            default -> { }
        }
        if (ground) groundY = p.getY();
        lastGround = ground;
        syncOut();
    }

    private static void drop(ClientPlayerEntity p, double push) {
        set(Mode.NONE);
        cooldown = 10;
        p.setVelocity(-dir.getOffsetX() * push, 0, -dir.getOffsetZ() * push);
    }

    private static void startPath(ClientPlayerEntity p, Mode m, double top) {
        Vec3d at = p.getPos();
        Vec3d up = new Vec3d(at.x, top + 0.01, at.z);
        Vec3d end = ahead(up, 0.8);
        if (!empty(p, column(at.x, at.y, at.z, top + 1.8 - at.y)) || !empty(p, column(end.x, end.y, end.z, 1.8))) return;
        from = at;
        to = end;
        ledgeY = top;
        p.setVelocity(Vec3d.ZERO);
        p.fallDistance = 0;
        set(m);
    }

    private static void set(Mode m) {
        mode = m;
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
                // Shuffle along the ledge (left is +sideways), if it carries on that way.
                float s = p.input.movementSideways;
                side = s > 0.1f ? -1 : s < -0.1f ? 1 : 0;
                if (side != 0) {
                    Vec3d along = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(side * 0.055);
                    Vec3d next = p.getPos().add(along);
                    if (empty(p, column(next.x, next.y, next.z, 1.8)) && ledge(p, next, next.y, 1.8, 2.3) != null) {
                        p.move(MovementType.SELF, along);
                        phase += 0.55f;
                    } else side = 0;
                }
                return true;
            }
            case CLIMB -> {
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                float f = p.input.movementForward, s = p.input.movementSideways;
                double dy = f > 0.1f ? 0.06 : f < -0.1f ? -0.08 : 0;
                Vec3d along = new Vec3d(-dir.getOffsetZ(), 0, dir.getOffsetX()).multiply(s > 0.1f ? -0.05 : s < -0.1f ? 0.05 : 0);
                side = s > 0.1f ? -1 : s < -0.1f ? 1 : 0;
                if (dy == 0 && along.lengthSquared() == 0) return true;
                Vec3d at = p.getPos(), next = at.add(along.x, dy, along.z);
                if (dy > 0) {
                    // Hands over the top: pull up onto it.
                    Double top = ledge(p, at, at.y, 1.5, 2.1);
                    if (top != null) {
                        startPath(p, Mode.PULL, top);
                        return true;
                    }
                }
                if (!empty(p, column(next.x, next.y, next.z, 1.8)) || !wall(p, next, 0.3, 1.6)) {
                    if (!wall(p, at, 0.3, 1.6)) set(Mode.NONE); // nothing left to hold
                    return true;
                }
                p.move(MovementType.SELF, next.subtract(at));
                phase += 0.5f;
                if (!p.isCreative()) Move.stamina = Math.max(0, Move.stamina - CLIMB_COST);
                return true;
            }
            case PULL, MANTLE -> {
                int total = mode == Mode.PULL ? PULL_TICKS : MANTLE_TICKS;
                ticks++;
                float k = Math.min(1, ticks / (float) total);
                // Up first (most of it), then over onto the top.
                Vec3d target;
                if (k < 0.62f) {
                    float u = ease(k / 0.62f);
                    target = new Vec3d(from.x, MathHelper.lerp(u, from.y, to.y), from.z);
                } else {
                    float u = ease((k - 0.62f) / 0.38f);
                    target = new Vec3d(MathHelper.lerp(u, from.x, to.x), to.y, MathHelper.lerp(u, from.z, to.z));
                }
                p.setVelocity(Vec3d.ZERO);
                p.fallDistance = 0;
                Vec3d before = p.getPos();
                p.move(MovementType.SELF, target.subtract(before));
                if (p.getPos().squaredDistanceTo(target) > 0.04) set(Mode.NONE); // something got in the way
                else if (k >= 1) set(Mode.NONE);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
