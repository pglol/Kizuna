package net.pglol.kata.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Path;
import net.pglol.kata.KataNet;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Hand to hand, your side.
 *
 * Bare hands:
 *  - left click: a quick click throws the next light of the chain; holding winds up the heavy,
 *    thrown on release (harder the longer it's held, breaking guard at full charge)
 *  - in the air: only the air moves (a superman punch, a flying knee, a dropkick...)
 *  - right click (held): guard; raised just as a hit arrives, facing it, it parries
 *  - R: lock on (the camera holds them; a mouse drag turns your stance)
 *
 * Strikes are thrown along real paths: fists and feet are solved onto the line they travel (straight,
 * hooking, rising, chambered kicks), driven by the hips and chest and the body's weight, and they land
 * on whatever body part they first touch.
 */
public final class Fight {
    private Fight() {}

    // ---- your own state ----
    static int target = -1;
    static int stance = Fighting.TR;
    static boolean guard;
    /** The deck's move preview: what to show on your body while the deck draws it, from which stance, since when. */
    static Move previewMove;
    static int previewStance;
    static boolean previewing;
    static long previewStart;
    /** Frozen at this time into the move (frame export), or -1 to play. */
    static float previewFixed = -1;
    /** Which way the guard is turned: 0 square, 1 left, 2 right. */
    static int guardSide;
    private static boolean sentGuard;
    private static boolean pressing, charging;
    private static long pressAt, chargeAt;
    // The move in progress: what, from which stance and row, power, start, the next hit to land.
    private static Move cur;
    private static int curStance, nextHit;
    private static float curPower = 1;
    private static long curStart;
    private static boolean pendingLight;
    private static int chain;
    private static long lastEnd;
    private static double flickX, flickY;
    /** A blocked hit's jolt (seconds left) and the side it came from (+1 right, -1 left). */
    private static float jolt;
    private static int joltSide;
    private static long brokenUntil, staggerUntil, parryAt, flinchUntil, readyAt;
    private static boolean curLanded, smashDive;
    /** Hanging in the air (an air hit, given or taken): gravity eased right off until then. */
    private static long hangUntil;
    private static int parryKind, lastHurt;
    /** Where the striking fist or foot is in the world, this frame and last (what it runs into on the way). */
    private static Vec3d limbNow, limbPrev, limbMid;
    private static Fighting.Part lastPart = Fighting.Part.TORSO;
    /** One air attack per jump, unless it lands (then the air chain can carry on). */
    private static boolean airUsed, airLanded;

    // ---- everyone else (from the server) ----
    private record Act(Move move, int stance, float power, long start) { }
    private static final Map<Integer, Act> MOVES = new HashMap<>();
    private static final Map<Integer, Boolean> GUARDS = new HashMap<>();
    private static final Map<Integer, Integer> STANCES = new HashMap<>();
    private static final Map<Integer, Long> STANCE_AT = new HashMap<>();
    /** The turn each body is showing (eases to its stance's), and which way a spin goes (+1 right, -1 left). */
    private static final Map<Integer, Float> TURN = new HashMap<>();
    private static final Map<Integer, Integer> SPIN = new HashMap<>();
    /** Where a spin started from (degrees), so it turns smoothly all the way round. */
    private static final Map<Integer, Float> SPIN_FROM = new HashMap<>();

    public static boolean guarding() {
        return guard || charging;
    }

    public static boolean locked() {
        return target >= 0;
    }

    public static int stance() {
        return stance;
    }

    public static Entity targetEntity(MinecraftClient mc) {
        return mc.world == null || target < 0 ? null : mc.world.getEntityById(target);
    }

    /** Is the local player fighting bare-handed right now, or limp (the mouse buttons are Kata's)? */
    public static boolean bareHands() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p != null && (fists(p) || limp(p));
    }

    public static boolean limpNow() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p != null && limp(p);
    }

    /** Knocked flat (ragdoll, faceplant): no will, so no hands either. */
    static boolean limp(ClientPlayerEntity p) {
        Parkour.Mode m = Parkour.view(p).mode();
        return m == Parkour.Mode.RAGDOLL || m == Parkour.Mode.FACEPLANT;
    }

    /** Bare fists, free to fight (on your feet or in the air). */
    static boolean fists(ClientPlayerEntity p) {
        Parkour.Mode m = Parkour.view(p).mode();
        return p.getMainHandStack().isEmpty() && !p.isSpectator() && (m == Parkour.Mode.NONE || m == Parkour.Mode.LAND);
    }

    private static boolean airborne(ClientPlayerEntity p) {
        return !p.isOnGround() && !p.isTouchingWater() && !p.getAbilities().flying;
    }

    private static float secs(long since) {
        return (System.nanoTime() - since) / 1e9f;
    }

    /** Out of a dodge and not yet able to act (its i-frames are over, the step not finished). */
    static boolean flinching() {
        return System.nanoTime() < flinchUntil;
    }

    static boolean recovering() {
        return dodgeAt != 0 && secs(dodgeAt) < DODGE_RECOVER;
    }

    /**
     * The dodge key with a direction. On the ground: back (or nothing) is a short step back, invulnerable
     * for a moment; forward ducks under (anything at the head); left or right slips aside (past straight
     * thrusts); with jump held, a hop (over low kicks). The avoids barely move your feet. In the air:
     * a flip whichever way you're pressing (once a jump), invulnerable while it starts.
     */
    private static void dodge(ClientPlayerEntity p) {
        if (limp(p) || cur != null || charging || staggered() || recovering() || flinching() || p.getAbilities().flying) return;
        if (net.pglol.kata.client.Move.winded() || net.pglol.kata.client.Move.stamina < DODGE_COST) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        float f = p.input.movementForward, sdw = p.input.movementSideways;
        Vec3d fw = Vec3d.fromPolar(0, p.getYaw()), left = new Vec3d(fw.z, 0, -fw.x);
        int dir;
        if (!p.isOnGround()) {
            if (flipUsed) return;
            flipUsed = true;
            // Any way at all: the angle of what you're pressing, in sixteenths (none: a back flip).
            double ang = Math.abs(f) + Math.abs(sdw) < 0.2f ? Math.PI : Math.atan2(sdw, f);
            int idx = Math.floorMod((int) Math.round(ang / (Math.PI * 2) * 16), 16);
            dir = 16 + idx;
            controlledUntil = System.nanoTime() + 1_000_000_000L;
            Vec3d d = fw.multiply(Math.cos(idx * Math.PI / 8)).add(left.multiply(Math.sin(idx * Math.PI / 8)));
            Vec3d v = p.getVelocity();
            p.setVelocity(v.x * 0.6 + d.x * 0.16, Math.max(v.y, 0.4), v.z * 0.6 + d.z * 0.16); // a real second jump
        } else {
            if (mc.options.jumpKey.isPressed()) dir = 4;
            else if (Math.abs(sdw) > 0.3f && Math.abs(sdw) >= Math.abs(f)) dir = sdw > 0 ? 2 : 3;
            else dir = f > 0.3f ? 1 : 0;
            dodgeDir = switch (dir) {
                case 0 -> fw.multiply(-1);
                case 2 -> left;
                case 3 -> left.multiply(-1);
                default -> Vec3d.ZERO;
            };
            dodgeSpeed = dir == 0 ? 1 : 0.4f; // a step back; the slips barely move
            Afterimages.dodge(p.getId());
            dodgeTick = 0;
            if (dir == 4) p.setVelocity(p.getVelocity().x * 0.5, 0.34, p.getVelocity().z * 0.5);
        }
        net.pglol.kata.client.Move.stamina = Math.max(0, net.pglol.kata.client.Move.stamina - DODGE_COST);
        dodgeAt = System.nanoTime();
        pressing = false;
        DODGES.put(p.getId(), new React(dir, dodgeAt));
        send(KataNet.DODGE, dir, 0);
    }

    static boolean clinging() {
        return System.nanoTime() < clingUntil;
    }

    /** The face of a wall you're right up against (its outward normal, flat), or null. */
    private static Vec3d wallNormal(ClientPlayerEntity p) {
        var w = p.getWorld();
        Vec3d best = null;
        double bestD = 0.5;
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            boolean solid = false;
            for (int up = 0; up <= 1; up++) {
                net.minecraft.util.math.BlockPos b = net.minecraft.util.math.BlockPos.ofFloored(p.getX(), p.getY() + 0.4 + up * 0.9, p.getZ()).offset(d);
                if (!w.getBlockState(b).getCollisionShape(w, b).isEmpty()) solid = true;
            }
            if (!solid) continue;
            // How far you are from that face.
            double gap = switch (d) {
                case EAST -> Math.floor(p.getX()) + 1 - p.getX();
                case WEST -> p.getX() - Math.floor(p.getX());
                case SOUTH -> Math.floor(p.getZ()) + 1 - p.getZ();
                default -> p.getZ() - Math.floor(p.getZ());
            } - p.getWidth() / 2;
            if (gap < bestD) {
                bestD = gap;
                best = new Vec3d(-d.getOffsetX(), 0, -d.getOffsetZ());
            }
        }
        return best;
    }

    /** Land on the wall, sideways and crouched like a spider, for up to 1.5 s. */
    private static void cling(ClientPlayerEntity p, Vec3d normal) {
        clingNormal = normal;
        clingUntil = System.nanoTime() + 1_500_000_000L;
        clungThisJump = true;
        flipUsed = false; // a jump off the wall can still flip
        p.setVelocity(0, 0, 0);
        int idx = Math.floorMod((int) Math.round(Math.atan2(normal.z, normal.x) / (Math.PI * 2) * 16), 16);
        DODGES.put(p.getId(), new React(32 + idx, System.nanoTime()));
        send(KataNet.DODGE, 32 + idx, 0);
    }

    /**
     * Off the wall. At someone (locked on, or looking at them): a pounce straight at them, the opening air
     * strike thrown as you arrive. Otherwise a boost the way you're looking, pushed out from the wall.
     */
    private static void wallJump(MinecraftClient mc, ClientPlayerEntity p) {
        clingUntil = 0;
        Entity t = targetEntity(mc);
        if (t == null && mc.targetedEntity instanceof LivingEntity l) t = l;
        if (t == null) t = lookedAt(p, 16);
        Vec3d look = p.getRotationVec(1);
        Vec3d dir;
        double speed;
        if (t != null) {
            dir = t.getPos().add(0, t.getHeight() * 0.5, 0).subtract(p.getEyePos().add(0, -0.4, 0)).normalize();
            speed = 1.0;
            pounceAt = t.getId();
            pounceUntil = System.nanoTime() + 1_200_000_000L;
        } else {
            Vec3d n = clingNormal == null ? Vec3d.ZERO : clingNormal;
            // Never back into the wall: what's left of the look once its into-the-wall part is taken out.
            double into = look.dotProduct(n);
            Vec3d l2 = into < 0 ? look.subtract(n.multiply(into)) : look;
            dir = l2.multiply(0.8).add(n.multiply(0.55)).normalize();
            speed = 0.95;
        }
        p.setVelocity(dir.x * speed, Math.max(dir.y * speed, 0.32), dir.z * speed);
        controlledUntil = System.nanoTime() + 1_500_000_000L; // flying on purpose: land on your feet
        // A flip off it, whichever way you're going.
        double rel = Math.atan2(-(dir.x * Math.cos(Math.toRadians(p.getYaw())) + dir.z * Math.sin(Math.toRadians(p.getYaw()))),
            -dir.x * Math.sin(Math.toRadians(p.getYaw())) + dir.z * Math.cos(Math.toRadians(p.getYaw())));
        int idx = Math.floorMod((int) Math.round(-rel / (Math.PI * 2) * 16), 16);
        DODGES.put(p.getId(), new React(16 + idx, System.nanoTime()));
        send(KataNet.DODGE, 16 + idx, 0);
        flipUsed = true;
    }

    /** Someone alive in front of you within range (a narrow cone round where you look), or null. */
    private static Entity lookedAt(ClientPlayerEntity p, double range) {
        Vec3d eye = p.getEyePos(), look = p.getRotationVec(1);
        Entity best = null;
        double bestCos = 0.94;
        for (Entity e : p.getWorld().getOtherEntities(p, p.getBoundingBox().expand(range))) {
            if (!(e instanceof LivingEntity l) || !l.isAlive() || e.isSpectator()) continue;
            Vec3d to = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            double d = to.length();
            if (d > range || d < 1e-3) continue;
            double cos = to.dotProduct(look) / d;
            if (cos > bestCos) {
                bestCos = cos;
                best = e;
            }
        }
        return best;
    }

    /** Flying at someone off a wall: when they're in reach, the opening air strike goes. */
    private static void pounce(MinecraftClient mc, ClientPlayerEntity p) {
        if (pounceAt < 0) return;
        Entity t = p.getWorld().getEntityById(pounceAt);
        if (t == null || System.nanoTime() > pounceUntil || p.isOnGround()) {
            pounceAt = -1;
            return;
        }
        if (t.squaredDistanceTo(p) < 2.6 * 2.6 && cur == null) {
            pounceAt = -1;
            if (target < 0) target = t.getId();
            readyAt = 0;
            airUsed = false;
            begin(p, Deck.pick(Fighting.AIR, false, 0), 1);
        }
    }

    /** The dodge's step, eased over a few ticks: quick out, settling (never a shove). */
    private static void dodgeStep(ClientPlayerEntity p) {
        if (dodgeDir == null || dodgeTick >= DODGE_PROFILE.length) return;
        double sp = DODGE_PROFILE[dodgeTick++] * dodgeSpeed;
        Vec3d v = p.getVelocity();
        p.setVelocity(dodgeDir.x * sp + v.x * 0.2, v.y, dodgeDir.z * sp + v.z * 0.2);
    }

    /** Whoever this is mid-flip: how far round (0..1) and which way (sixteenths), else null. */
    static float[] flip(LivingEntity e) {
        React d = DODGES.get(e.getId());
        if (d == null || d.packed < 16) return null;
        float s = secs(d.at);
        if (s > FLIP_TIME) return null;
        return new float[] {s / FLIP_TIME, d.packed - 16};
    }

    private static boolean othersStaggered(PlayerEntity p) {
        Long u = STAGGERS.get(p.getId());
        return u != null && System.nanoTime() < u;
    }

    private static boolean staggered() {
        return System.nanoTime() < staggerUntil;
    }

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) {
            target = -1;
            cur = null;
            guard = sentGuard = pressing = charging = false;
            MOVES.clear();
            GUARDS.clear();
            STANCES.clear();
            STANCE_AT.clear();
            TURN.clear();
            SPIN.clear();
            return;
        }
        while (KataClient.lockKey.wasPressed()) target = target >= 0 ? -1 : pick(p, 14, 60);
        Entity t = targetEntity(mc);
        if (target >= 0 && (t == null || !t.isAlive() || t.squaredDistanceTo(p) > 20 * 20)) target = -1;

        boolean free = fists(p) && mc.currentScreen == null;
        // Dodge: a quick step out of the way, the way you're pressing (back if nothing). Strikes pass
        // through its first moments; then a short recovery when you can't guard or throw.
        if (p.isOnGround()) flipUsed = false;
        // Double jump: space again in the air (a fresh press, not the one you took off with) jumps again,
        // flipping whichever way you're pressing.
        boolean jumpNow = mc.options.jumpKey.isPressed() && mc.currentScreen == null;
        // A second jump needs the key let go of and pressed again after leaving the ground (this runs after
        // the move that lifted you, so the press you took off with must never count).
        if (p.isOnGround() || p.isTouchingWater()) {
            airTicks = 0;
            releasedInAir = false;
        } else {
            airTicks++;
            if (!jumpNow) releasedInAir = true;
        }
        boolean fresh = jumpNow && !jumpWas && releasedInAir && airTicks > 2;
        jumpWas = jumpNow;
        if (clinging()) {
            // On the wall: jump off it (or at someone), or drop when time's up or you touch down.
            p.fallDistance = 0;
            Vec3d v = p.getVelocity();
            p.setVelocity(0, Math.max(-0.03, Math.min(v.y, 0)) * 0.5, 0); // a slow slide
            if (fresh) wallJump(mc, p);
            else if (p.isOnGround() || System.nanoTime() > clingUntil) clingUntil = 0;
        } else if (fresh && !p.isOnGround() && !p.isTouchingWater() && !p.getAbilities().flying
            && Parkour.view(p).mode() == Parkour.Mode.NONE) {
            // A double jump at a wall lands you on it, sideways, crouched; otherwise a flip.
            Vec3d wall = wallNormal(p);
            if (wall != null && !clungThisJump) cling(p, wall);
            else if (!flipUsed) dodge(p);
        }
        if (p.isOnGround()) clungThisJump = false;
        pounce(mc, p);
        if (mc.world != null && mc.world.getTime() % 3 == 0) stars(mc);
        while (KataClient.dodgeKey.wasPressed()) dodge(p);
        dodgeStep(p);
        guard = free && mc.options.useKey.isPressed() && cur == null && !charging && !staggered() && !recovering() && System.nanoTime() > brokenUntil;
        if (guard && target < 0) target = pick(p, 8, 50); // raising a guard locks onto whoever's in front
        // Pressing left or right while guarding turns the guard to that side (it stops swinging strikes
        // from there); raised while pressing one, it's a parry towards that side.
        float sideIn = p.input.movementSideways;
        int gs = !guard ? 0 : sideIn > 0.3f ? 1 : sideIn < -0.3f ? 2 : 0;
        if (guard != sentGuard || gs != guardSide) {
            sentGuard = guard;
            guardSide = gs;
            send(KataNet.GUARD, guard ? 1 | gs << 1 : 0, 0);
        }
        if (jolt > 0) jolt = Math.max(0, jolt - 0.05f);
        // Hit while winding up: the heavy is gone.
        if (p.hurtTime > lastHurt && charging) charging = pressing = false;
        lastHurt = p.hurtTime;
        if (!free) pressing = charging = false;
    }

    /** Every frame: input timing (click or hold), the strike's progress, the camera on the opponent. */
    static void frame(MinecraftClient mc, float td, float dt) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (!airborne(p)) airUsed = airLanded = false;
        // The left button: released quickly, a light; held, a heavy winding up until it's let go.
        if (pressing) {
            boolean held = mc.options.attackKey.isPressed();
            if (!held) {
                pressing = false;
                if (charging) {
                    charging = false;
                    send(KataNet.CHARGE, 0, 0);
                    begin(p, Deck.pick(row(p), true, 0), Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE));
                } else light(p);
            } else if (airborne(p) && secs(pressAt) >= Fighting.HEAVY_AFTER) {
                // Held in the air: the smash, straight away, cutting off whatever air strike was going.
                pressing = false;
                pendingLight = false;
                if (cur != null && cur.from() == Fighting.From.AIR) cur = null;
                readyAt = 0;
                begin(p, Deck.pick(Fighting.AIR, true, 0), 1);
            } else if (!charging && cur == null && secs(pressAt) >= Fighting.HEAVY_AFTER) {
                charging = true;
                chargeAt = System.nanoTime();
                send(KataNet.CHARGE, 1, 0); // they see it building
            }
        }
        // The move in progress: each of its hits lands (on whatever it first touches), then it's done.
        if (cur != null) {
            float s = secs(curStart);
            if (nextHit < cur.hits()) {
                float c = cur.contactOf(nextHit);
                int early = -1;
                // Touching anyone, from early in the swing through the beat it holds at full extension:
                // the fist (or foot) along its path, and the whole forearm (or shin) as it is now.
                boolean drawn = limbNow != null;
                float late = c + (cur.heavy() ? 0.12f : 0.08f);
                if (drawn && s >= c - cur.contact() * 0.85f && s <= late) {
                    var others = nearby(p);
                    double r = cur.leg() ? 0.13 : 0.1; // a fist or shin's thickness
                    Hitbox.Contact touch = limbPrev != null && limbPrev.squaredDistanceTo(limbNow) > 1e-6 ? Hitbox.sweep(others, td, limbPrev, limbNow, r) : null;
                    if (touch == null && limbMid != null) touch = Hitbox.sweep(others, td, limbMid, limbNow, r);
                    if (touch != null) {
                        lastPart = touch.hit().part();
                        early = touch.entity().getId();
                    }
                }
                // Drawn (third person): it lands on a touch, or misses once the extension is over.
                // First person: at contact, along its line.
                if (early >= 0 || (drawn ? s > late : s >= c)) {
                    int hit = early >= 0 ? early : drawn ? -1 : struck(mc, p);
                    boolean right = Fighting.rightLimb(cur, curStance, nextHit);
                    int packed = Fighting.id(cur) | (right ? 1 << 8 : 0) | (Math.round(curPower * 100) << 9) | (lastPart.ordinal() << 16);
                    send(KataNet.STRIKE, packed, hit);
                    nextHit++;
                    if (hit >= 0 && cur.from() == Fighting.From.AIR) airLanded = true;
                    // It lands: the strike holds still for a moment (the impact), longer for a heavy.
                    if (hit >= 0) {
                        // The hit stop: a heavy holds on contact longer the more it was charged.
                        float stop = cur.heavy() ? (curPower >= 0.8f ? 0.15f : curPower >= 0.4f ? 0.11f : 0.07f) : 0.05f;
                        curStart += (long) (stop * 1e9);
                        curLanded = true;
                    }
                    if (smashDive) {
                        smashDive = false;
                        hangUntil = 0;
                        Vec3d v = p.getVelocity();
                        p.setVelocity(v.x * 0.3, -0.9, v.z * 0.3); // down after them
                    }
                    // A rising strike lifts you with it.
                    if (cur.path() == Path.UPPER && cur.heavy() && p.isOnGround()) p.addVelocity(0, 0.3, 0);
                }
            }
            if (s >= cur.length()) {
                stanceTo(Fighting.end(cur, curStance));
                // A beat before the next: longer after a heavy, longer still after a miss.
                readyAt = System.nanoTime() + (long) (((cur.heavy() ? 0.16 : 0.07) + (curLanded ? 0 : 0.1)) * 1e9);
                cur = null;
                lastEnd = System.nanoTime();
            }
        }
        // The next in the chain, pressed during the last, goes as soon as the beat between them is over.
        if (cur == null && pendingLight && System.nanoTime() >= readyAt) {
            pendingLight = false;
            light(p);
        }
        // Locked on: the camera holds them.
        Entity t = targetEntity(mc);
        if (t == null) return;
        Vec3d to = t.getLerpedPos(td).add(0, t.getHeight() * 0.7, 0).subtract(p.getCameraPosVec(td));
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)));
        float k = 1 - (float) Math.exp(-dt * 12);
        float ny = p.getYaw() + MathHelper.wrapDegrees(yaw - p.getYaw()) * k;
        float np = MathHelper.clamp(p.getPitch() + (pitch + 8 - p.getPitch()) * k, -60, 60);
        p.setYaw(ny);
        p.setPitch(np);
        p.prevYaw = ny;
        p.prevPitch = np;
    }

    /** The deck row to throw from: the air if you're off the ground, else your stance. */
    private static int row(ClientPlayerEntity p) {
        return airborne(p) ? Fighting.AIR : stance;
    }

    /** Left button pressed. True when Kata handles it (bare fists). */
    public static boolean attack() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return false;
        if (limp(p)) return true; // a limp body throws nothing (and doesn't swing either)
        if (!fists(p)) return false;
        if (guard || staggered() || recovering() || flinching() || concussed(p)) return true; // hands up, off balance, out of a dodge or reeling: nothing to throw
        pressing = true;
        pressAt = System.nanoTime();
        return true;
    }

    private static void light(ClientPlayerEntity p) {
        if (staggered()) return;
        if (cur != null) {
            // Pressed during a strike: the next in the chain follows straight on (not across ground and air).
            if (secs(curStart) > cur.contact() * 0.5f && (cur.from() == Fighting.From.AIR) == airborne(p)) pendingLight = true;
            return;
        }
        if (System.nanoTime() < readyAt) {
            pendingLight = true; // too soon: it goes on the beat
            return;
        }
        if (secs(lastEnd) > 0.55f) chain = 0;
        begin(p, Deck.pick(row(p), false, chain), 1);
        chain++;
    }

    private static void begin(ClientPlayerEntity p, Move m, float power) {
        if (flinching()) return; // reeling from a hit: block or parry, not throw
        if (m == null || staggered()) return;
        // In the air only air moves; on the ground none of them.
        if ((m.from() == Fighting.From.AIR) != airborne(p)) return;
        if (m.from() == Fighting.From.AIR) {
            if (airUsed && !airLanded) return; // one per jump, unless the last one landed
            airUsed = true;
            airLanded = false;
        }
        if (System.nanoTime() < readyAt) return;
        cur = m;
        curLanded = false;
        curStance = stance;
        curPower = m.heavy() ? Math.max(0.3f, power) : 1;
        curStart = System.nanoTime();
        nextHit = 0;
        limbNow = limbPrev = limbMid = null;
        if (m.heavy()) chain = 0;
        boolean right = Fighting.rightLimb(m, stance, 0);
        // A spinning strike turns you as you throw it, its side leading the turn.
        if (m.turns()) {
            SPIN.put(p.getId(), right ? 1 : -1);
            stanceTo(Fighting.end(m, stance));
        }
        // Anyone it's aimed at sees it coming.
        Entity t = targetEntity(MinecraftClient.getInstance());
        send(KataNet.WINDUP, Fighting.id(m) | (right ? 1 << 8 : 0), t != null ? t.getId() : -1);
        // The body behind it: a step in on the ground; in the air, the lunge the move flies on.
        Vec3d f = Vec3d.fromPolar(0, p.getYaw());
        if (m.from() == Fighting.From.AIR) {
            Vec3d v = p.getVelocity();
            if (m.heavy()) {
                // The smash: a hitch at the top, then down with it.
                p.setVelocity(v.x * 0.3, Math.max(v.y, 0.12), v.z * 0.3);
                smashDive = true;
            } else {
                // Air strikes keep you up and drift you in to stay on them.
                Entity tg = targetEntity(MinecraftClient.getInstance());
                Vec3d in = tg != null ? tg.getPos().subtract(p.getPos()).multiply(1, 0, 1) : f;
                double dist = in.length();
                Vec3d dir = dist > 1e-3 ? in.multiply(1 / dist) : f;
                double drift = tg != null ? MathHelper.clamp((dist - 1.4) * 0.12, 0, 0.14) : 0.06;
                p.setVelocity(v.x * 0.4 + dir.x * drift, Math.max(v.y, 0.1), v.z * 0.4 + dir.z * drift);
            }
        } else if (p.isOnGround() && !m.leg()) {
            double step = m.heavy() ? 0.06 + 0.1 * curPower : 0.035;
            p.addVelocity(f.x * step, 0, f.z * step);
        }
    }

    private static void stanceTo(int s) {
        if (s == stance) return;
        stance = s;
        send(KataNet.STANCE, s, 0);
    }

    /**
     * What the strike hits: traced along its line from the shoulder (the hip for a kick) into the locked
     * opponent (or whoever's under the crosshair), landing on the first body part it meets. -1 for a miss.
     */
    private static int struck(MinecraftClient mc, ClientPlayerEntity p) {
        // Your body is drawn (third person): only a real touch counts. The fist or foot at contact, with
        // the limb's thickness, against every body's limbs and torso; the crosshair doesn't come into it.
        if (limbNow != null) {
            Vec3d from = limbPrev != null ? limbPrev : limbNow;
            Hitbox.Contact c = Hitbox.sweep(nearby(p), 1, from, limbNow, cur.leg() ? 0.2 : 0.16);
            if (c == null) return -1;
            lastPart = c.hit().part();
            return c.entity().getId();
        }
        // First person (your body isn't drawn, so there's no limb to follow): along the strike's line.
        Entity t = targetEntity(mc);
        if (t == null) t = softTarget(p, cur);
        if (t == null && mc.targetedEntity instanceof LivingEntity c) t = c;
        Vec3d want = t != null ? wanted(p, 1, cur, t) : p.getCameraPosVec(1).add(p.getRotationVec(1).multiply(2)).add(0, -0.2, 0);
        Hitbox.Contact h = Hitbox.traceAll(nearby(p), 1, origin(p, 1, cur), want, Fighting.REACH + (cur.leg() ? Fighting.KICK_EXTRA : 0));
        if (h == null) return -1;
        lastPart = h.hit().part();
        return h.entity().getId();
    }

    /**
     * Not locked on: whoever's right in front of you within reach (the nearest, in a wide cone round your
     * look), so your strike goes at their body and the limb really meets it. null if no one.
     */
    static Entity softTarget(PlayerEntity p, Move m) {
        if (!(p instanceof ClientPlayerEntity cp)) return null;
        double reach = Fighting.REACH + (m != null && m.leg() ? Fighting.KICK_EXTRA : 0) + 0.9;
        Vec3d look = Vec3d.fromPolar(0, p.getYaw());
        Entity best = null;
        double bestD = reach;
        for (Entity e : nearby(cp)) {
            Vec3d to = e.getPos().subtract(p.getPos()).multiply(1, 0, 1);
            double d = to.length();
            if (d > bestD || d < 1e-3) continue;
            if (to.dotProduct(look) / d < 0.15) continue; // in front or out to the side, not behind
            if (Math.abs(e.getY() - p.getY()) > 2.2) continue;
            best = e;
            bestD = d;
        }
        return best;
    }

    /** Bodies a strike could touch: anyone alive within reach but you. */
    private static java.util.List<Entity> nearby(ClientPlayerEntity p) {
        java.util.List<Entity> out = new java.util.ArrayList<>();
        for (Entity e : p.getWorld().getOtherEntities(p, p.getBoundingBox().expand(Fighting.REACH + 1))) {
            if (e instanceof LivingEntity && e.isAlive() && !(e instanceof ArmorStandEntity) && !e.isSpectator()) out.add(e);
        }
        return out;
    }

    /** Where a strike starts from (world): the shoulder, or the hip for a kick. */
    private static Vec3d origin(PlayerEntity p, float td, Move m) {
        return p.getCameraPosVec(td).add(0, m != null && m.leg() ? -0.85 : -0.3, 0);
    }

    /** Where on its target a strike goes: the head, the body, the legs. */
    private static Vec3d wanted(PlayerEntity p, float td, Move m, Entity t) {
        double h = m == null ? 0.88 : switch (m.path()) {
            case PALM, FRONT_KICK, SIDE_KICK, BACK_KICK, KNEE, DROPKICK -> 0.6;
            case LOW_KICK -> 0.28;
            case UPPER -> 0.82;
            default -> 0.88;
        };
        return t.getLerpedPos(td).add(0, t.getHeight() * h, 0);
    }

    /** The best opponent to lock: alive, in range, nearest the middle of the view. */
    private static int pick(ClientPlayerEntity p, double range, double cone) {
        Vec3d look = p.getRotationVec(1);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : p.getWorld().getOtherEntities(p, p.getBoundingBox().expand(range))) {
            if (!(e instanceof LivingEntity) || !e.isAlive() || e instanceof ArmorStandEntity || e.isSpectator() || e.isInvisible()) continue;
            Vec3d to = e.getPos().add(0, e.getHeight() * 0.6, 0).subtract(p.getEyePos());
            double d = to.length();
            if (d > range) continue;
            double ang = Math.toDegrees(Math.acos(MathHelper.clamp(to.normalize().dotProduct(look), -1, 1)));
            if (ang > cone) continue;
            double score = ang + d * 3;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best == null ? -1 : best.getId();
    }

    /**
     * Mouse movement while locked on: a deliberate drag towards a quadrant turns you into it (side to
     * side changes the side, up and down the front or back). Small movements fade away.
     */
    public static void look(double dx, double dy) {
        flickX = flickX * 0.92 + dx;
        flickY = flickY * 0.92 + dy;
        double mag = Math.hypot(flickX, flickY);
        if (mag < 26 || cur != null) return;
        boolean top = Fighting.top(stance), right = Fighting.rightSide(stance);
        if (Math.abs(flickX) > mag * 0.45) right = flickX > 0;
        if (Math.abs(flickY) > mag * 0.45) top = flickY < 0;
        stanceTo(top ? (right ? Fighting.TR : Fighting.TL) : (right ? Fighting.BR : Fighting.BL));
        flickX = flickY = 0;
    }

    /** What the next press throws: the next light in the chain (a quick click), and the heavy (hold). */
    public static Move nextLight() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        int step = cur != null || secs(lastEnd) <= 0.55f ? chain : 0;
        return Deck.pick(p != null ? row(p) : stance, false, step);
    }

    public static Move nextHeavy() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return Deck.pick(p != null ? row(p) : stance, true, 0);
    }

    public static boolean chargingHeavy() {
        return charging;
    }

    public static float charge() {
        return charging ? Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE) : 0;
    }

    /** Hits and blocks being shown on bodies (anyone's, yours too): what, and since when. */
    private record React(int packed, long at) { }
    private static final Map<Integer, React> HITS = new HashMap<>(), BLOCKS = new HashMap<>();
    /** The same for other players' blocked hits (side), parries (kind) and staggers (until). */
    private static final Map<Integer, React> JOLTS = new HashMap<>(), PARRIES = new HashMap<>(), DODGES = new HashMap<>();
    private static long dodgeAt;
    /** A dodge: how fast it carries you (blocks a tick), what it costs, how long until you can act again. */
    private static final float DODGE_COST = 0.16f, DODGE_RECOVER = 0.3f, FLIP_TIME = 0.5f;
    private static final double[] DODGE_PROFILE = {0.24, 0.2, 0.13, 0.06};
    private static Vec3d dodgeDir;
    private static float dodgeSpeed;
    private static int dodgeTick = 99;
    private static boolean flipUsed, jumpWas, clungThisJump, releasedInAir;
    private static int airTicks;
    /** A launch you meant (off a wall, a pounce, a flip): no speed ragdoll for it until then. */
    private static long controlledUntil;

    public static boolean controlled() {
        return System.nanoTime() < controlledUntil;
    }
    /** On a wall until then (nanos), facing out along this normal. */
    private static long clingUntil;
    private static Vec3d clingNormal;
    /** Pouncing at someone off a wall: who, until when. */
    private static int pounceAt = -1;
    private static long pounceUntil;
    private static final Map<Integer, Long> STAGGERS = new HashMap<>();
    /** How much guard stamina others have left (it shows: a guard near breaking strains). */
    private static final Map<Integer, Float> POISES = new HashMap<>();
    /** Others charging a heavy (since), hanging in the air from a hit (until), concussed (until). */
    private static final Map<Integer, Long> CHARGES = new HashMap<>(), HANGS = new HashMap<>(), CONCUSSED = new HashMap<>();

    static void remote(int entity, int kind, int a) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // Seen by everyone: afterimages for parries, evasions and dodges.
        if (mc.world != null) {
            if (kind == KataNet.EVADED) Afterimages.evade(entity);
            else if (kind == KataNet.PARRY) Afterimages.parry(entity);
            else if (kind == KataNet.PARRIED) {
                Entity pe = mc.world.getEntityById(entity);
                Vec3d back = pe == null ? Vec3d.ZERO : Vec3d.fromPolar(0, pe.getYaw()).multiply(-0.8);
                Afterimages.parried(entity, back);
            } else if (kind == KataNet.DODGE && a < 16) Afterimages.dodge(entity);
        }
        if (kind == KataNet.HIT) {
            // A heavy landing (half charged or more): an impact frame for anyone close enough to see it.
            int tier = (a >> 9) & 3;
            if (tier > 0 && mc.world != null && mc.player != null) {
                Entity he = mc.world.getEntityById(entity);
                if (he != null && he.squaredDistanceTo(mc.player) < 16 * 16) ImpactFrames.hit(he.getEyePos().add(0, -0.3, 0), tier == 1 ? 0.55f : 1.0f);
            }
            HITS.put(entity, new React(a, System.nanoTime()));
            if (HITS.size() > 256) HITS.clear();
            return;
        }
        if (kind == KataNet.BLOCKED) {
            BLOCKS.put(entity, new React(a, System.nanoTime()));
            if (BLOCKS.size() > 256) BLOCKS.clear();
            return;
        }
        if (mc.player != null && entity == mc.player.getId()) {
            switch (kind) {
                case KataNet.GUARD_HIT -> {
                    jolt = 0.22f;
                    joltSide = a;
                    // The hit pushes the stance the other way: struck on the right, you're turned to the left.
                    boolean top = Fighting.top(stance);
                    stanceTo(a > 0 ? (top ? Fighting.TL : Fighting.BL) : (top ? Fighting.TR : Fighting.BR));
                }
                case KataNet.FLINCH -> {
                    // Hit: the strike you were throwing is gone and you can't throw for a moment. Guard or
                    // parry to get out of their string.
                    flinchUntil = Math.max(flinchUntil, System.nanoTime() + a * 50_000_000L);
                    cur = null;
                    chain = 0;
                    pendingLight = pressing = charging = false;
                }
                case KataNet.POISE -> net.pglol.kata.client.Move.stamina = Math.min(net.pglol.kata.client.Move.stamina, a / 100f); // blocking costs the same breath running does
                case KataNet.GUARD_BROKEN -> {
                    jolt = 0.22f;
                    Hud.pop("GUARD BROKEN", 0xFFFF5A4A);
                    brokenUntil = System.nanoTime() + 700_000_000L;
                }
                case KataNet.STAGGER -> {
                    // Knocked off balance: whatever you were doing is gone for a moment.
                    staggerUntil = System.nanoTime() + a * 50_000_000L;
                    cur = null;
                    chain = 0;
                    pendingLight = pressing = charging = false;
                }
                case KataNet.PARRY -> {
                    parryAt = System.nanoTime();
                    parryKind = a;
                    net.pglol.kata.client.Move.stamina = Math.min(1, net.pglol.kata.client.Move.stamina + 0.25f); // a parry gives breath back
                }
                case KataNet.DODGE -> { }
                case KataNet.AIR_HIT -> {
                    // An air hit, given or taken: hang there for the next. Given, another air strike is yours.
                    hangUntil = System.nanoTime() + a * 50_000_000L;
                    HANGS.put(entity, hangUntil);
                    airLanded = true;
                    // Rising with them: the string carries you both up together.
                    if (mc.player != null && !mc.player.isOnGround()) {
                        Vec3d v = mc.player.getVelocity();
                        mc.player.setVelocity(v.x, Math.max(v.y, 0.16), v.z);
                    }
                }
                case KataNet.CONCUSS -> {
                    CONCUSSED.put(entity, System.nanoTime() + a * 50_000_000L);
                    Hud.pop("CONCUSSED", 0xFFFF5A4A);
                }
                case KataNet.KNOCKDOWN -> {
                    cur = null;
                    pendingLight = pressing = charging = false;
                    Parkour.knockdown(mc.player, Fighting.unpackVelocity(a));
                }
                default -> { }
            }
            return;
        }
        switch (kind) {
            case KataNet.GUARD -> GUARDS.put(entity, a != 0);
            case KataNet.GUARD_HIT -> JOLTS.put(entity, new React(a, System.nanoTime()));
            case KataNet.GUARD_BROKEN -> {
                JOLTS.put(entity, new React(0, System.nanoTime()));
                GUARDS.put(entity, false);
            }
            case KataNet.STAGGER -> {
                STAGGERS.put(entity, System.nanoTime() + a * 50_000_000L);
                MOVES.remove(entity); // whatever they were throwing is gone
            }
            case KataNet.PARRY -> PARRIES.put(entity, new React(a, System.nanoTime()));
            case KataNet.DODGE -> DODGES.put(entity, new React(a, System.nanoTime()));
            case KataNet.POISE -> POISES.put(entity, a / 100f);
            case KataNet.KNOCKDOWN -> MOVES.remove(entity);
            case KataNet.WINDUP, KataNet.STRIKE -> {
                // Seen from its start (the wind-up is relayed); the landing of a move already showing
                // doesn't restart it.
                int id = a & 0xFF;
                if (id < Fighting.MOVES.length) {
                    Move m = Fighting.MOVES[id];
                    Act was = MOVES.get(entity);
                    boolean showing = was != null && was.move == m && secs(was.start) < m.length();
                    if (kind == KataNet.WINDUP || !showing) {
                        int st = STANCES.getOrDefault(entity, Fighting.TR);
                        MOVES.put(entity, new Act(m, st, ((a >> 9) & 0x7F) / 100f, System.nanoTime()));
                        if (m.turns()) SPIN.put(entity, (a & 1 << 8) != 0 ? 1 : -1);
                    }
                    CHARGES.remove(entity);
                }
                STANCE_AT.put(entity, System.nanoTime());
            }
            case KataNet.CHARGE -> {
                if (a != 0) CHARGES.put(entity, System.nanoTime());
                else CHARGES.remove(entity);
                STANCE_AT.put(entity, System.nanoTime());
            }
            case KataNet.AIR_HIT -> HANGS.put(entity, System.nanoTime() + a * 50_000_000L);
            case KataNet.CONCUSS -> CONCUSSED.put(entity, System.nanoTime() + a * 50_000_000L);
            case KataNet.STANCE -> {
                STANCES.put(entity, MathHelper.clamp(a, 0, 3));
                STANCE_AT.put(entity, System.nanoTime());
            }
            default -> { }
        }
        if (MOVES.size() > 256) MOVES.clear();
        if (STANCE_AT.size() > 256) STANCE_AT.clear();
        if (JOLTS.size() > 256) JOLTS.clear();
        if (PARRIES.size() > 256) PARRIES.clear();
        if (STAGGERS.size() > 256) STAGGERS.clear();
        if (DODGES.size() > 256) DODGES.clear();
        if (POISES.size() > 256) POISES.clear();
        if (CHARGES.size() > 256) CHARGES.clear();
        if (HANGS.size() > 256) HANGS.clear();
        if (CONCUSSED.size() > 256) CONCUSSED.clear();
    }

    // ---- what the body shows ----

    private static boolean mine(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && p.getId() == mc.player.getId();
    }

    public static boolean engagedOf(PlayerEntity p) {
        return engaged(p);
    }

    /** Is this player fighting (guard up, a strike going, or squared up to someone)? */
    private static boolean engaged(PlayerEntity p) {
        if (mine(p) && previewing) return true; // the deck's preview: squared up to someone in front
        if (mine(p)) return guard || cur != null || charging || target >= 0 || staggered();
        Act a = MOVES.get(p.getId());
        Long at = STANCE_AT.get(p.getId());
        return GUARDS.getOrDefault(p.getId(), false) || (a != null && secs(a.start) < a.move.length()) || (at != null && secs(at) < 4)
            || othersStaggered(p) || (PARRIES.containsKey(p.getId()) && secs(PARRIES.get(p.getId()).at) < 0.5f);
    }

    public static boolean guardUpOf(PlayerEntity p) {
        return guardUp(p);
    }

    private static boolean guardUp(PlayerEntity p) {
        return mine(p) ? guard : GUARDS.getOrDefault(p.getId(), false);
    }

    /**
     * How far the body is turned from facing the opponent (degrees): a fighter's stance with a shoulder
     * forward, or turned right round with the back to them. Eases there, fast during a strike; a
     * spinning strike goes the long way round, its side leading.
     */
    /** Facing an opponent from this stance: how far the body is turned (degrees). */
    private static float stanceTurn(int s) {
        return switch (s) {
            case Fighting.TR -> 30;
            case Fighting.TL -> -30;
            case Fighting.BR -> 145;
            default -> -145;
        };
    }

    /** The deck preview's body turn: its stance, and a spinning move's whole turn the long way round. */
    private static float previewTurn() {
        Move m = previewMove;
        int st = previewStance;
        float from = stanceTurn(st);
        if (m == null || !m.turns()) return from;
        float s = previewFixed >= 0 ? previewFixed : secs(previewStart) % (m.length() + 0.5f);
        if (s > m.length()) return from;
        float to = stanceTurn(Fighting.end(m, st));
        int spin = Fighting.rightLimb(m, st, 0) ? 1 : -1;
        while (spin > 0 && to < from + 60) to += 360;
        while (spin < 0 && to > from - 60) to -= 360;
        float prog = MathHelper.clamp(s / m.contact(), 0, 1);
        float e = prog < 0.5f ? 4 * prog * prog * prog : 1 - (float) Math.pow(-2 * prog + 2, 3) / 2;
        return from + (to - from) * e;
    }

    public static float bodyTurn(PlayerEntity p, float dt) {
        if (mine(p) && previewing) return previewTurn();
        float want = 0;
        if (engaged(p)) {
            int s = mine(p) ? stance : STANCES.getOrDefault(p.getId(), Fighting.TR);
            want = switch (s) {
                case Fighting.TR -> 30;
                case Fighting.TL -> -30;
                case Fighting.BR -> 145;
                default -> -145;
            };
        }
        float c = TURN.getOrDefault(p.getId(), 0f);
        boolean busy = mine(p) ? cur != null : MOVES.containsKey(p.getId()) && secs(MOVES.get(p.getId()).start) < MOVES.get(p.getId()).move.length();
        int spin = SPIN.getOrDefault(p.getId(), 0);
        if (busy && spin != 0) {
            // A spinning strike: a full, smooth turn the long way round (its side leading), arriving
            // exactly as it lands, not a snap.
            float from = SPIN_FROM.computeIfAbsent(p.getId(), k -> TURN.getOrDefault(p.getId(), 0f));
            float to = want;
            while (spin > 0 && to < from + 60) to += 360;
            while (spin < 0 && to > from - 60) to -= 360;
            Move m;
            float s;
            if (mine(p)) {
                m = cur;
                s = cur != null ? secs(curStart) : 0;
            } else {
                Act a = MOVES.get(p.getId());
                m = a != null ? a.move : null;
                s = a != null ? secs(a.start) : 0;
            }
            float prog = m == null ? 1 : MathHelper.clamp(s / m.contact(), 0, 1);
            float e = prog < 0.5f ? 4 * prog * prog * prog : 1 - (float) Math.pow(-2 * prog + 2, 3) / 2;
            c = from + (to - from) * e;
            TURN.put(p.getId(), c);
            return c;
        } else {
            SPIN.remove(p.getId());
            SPIN_FROM.remove(p.getId());
            c = want + MathHelper.wrapDegrees(c - want); // done spinning: the same turn, without the extra lap
        }
        float k = 1 - (float) Math.exp(-dt * (busy ? 18 : 9));
        c += (want - c) * k;
        TURN.put(p.getId(), c);
        if (TURN.size() > 256) TURN.clear();
        return c;
    }

    /** Hands loose and low-ish when squared up; a tight high guard when blocking. weight[0]: how much (0..1). */
    public static Pose pose(PlayerEntity p, float[] weight) {
        weight[0] = 0;
        if (!engaged(p)) return null;
        Pose g;
        if (guardUp(p)) {
            // Blocking: forearms up and tight in front of the face, chin down, shoulders rounded.
            g = Presets.GUARD.copy();
            g.set(Bone.RIGHT_ARM, Pose.PITCH, g.get(Bone.RIGHT_ARM, Pose.PITCH) - 14);
            g.set(Bone.LEFT_ARM, Pose.PITCH, g.get(Bone.LEFT_ARM, Pose.PITCH) - 14);
            g.set(Bone.RIGHT_ARM, Pose.YAW, g.get(Bone.RIGHT_ARM, Pose.YAW) - 10);
            g.set(Bone.LEFT_ARM, Pose.YAW, g.get(Bone.LEFT_ARM, Pose.YAW) + 10);
            g.set(Bone.RIGHT_FOREARM, Pose.PITCH, 118);
            g.set(Bone.LEFT_FOREARM, Pose.PITCH, 122);
            g.set(Bone.CHEST, Pose.PITCH, g.get(Bone.CHEST, Pose.PITCH) + 8);
            g.set(Bone.HEAD, Pose.PITCH, g.get(Bone.HEAD, Pose.PITCH) + 8);
        } else {
            // Squared up: the guard's shape, loose, hands lower.
            g = Pose.lerp(new Pose(), Presets.GUARD, 0.65f);
            g.set(Bone.RIGHT_FOREARM, Pose.PITCH, 75);
            g.set(Bone.LEFT_FOREARM, Pose.PITCH, 80);
        }
        if (guardUp(p)) {
            // Turned to a side: the body and guard face that way, the arm on that side higher and out.
            int turned = mine(p) ? guardSide : 0;
            if (turned != 0) {
                float sd = turned == 1 ? 1 : -1; // left is +x in the model
                g.set(Bone.CHEST, Pose.YAW, g.get(Bone.CHEST, Pose.YAW) + 14 * sd);
                Bone arm = turned == 1 ? Bone.LEFT_ARM : Bone.RIGHT_ARM;
                g.set(arm, Pose.PITCH, g.get(arm, Pose.PITCH) - 12);
                g.set(arm, Pose.ROLL, g.get(arm, Pose.ROLL) + 14 * sd);
            }
            // The struggle: as the guard's stamina runs low the arms shake, the body sags back and the
            // head drops behind the guard.
            float po = mine(p) ? net.pglol.kata.client.Move.stamina : POISES.getOrDefault(p.getId(), 1f);
            float strain = MathHelper.clamp((0.7f - po) / 0.7f, 0, 1);
            if (strain > 0) {
                float tt = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
                float shake = MathHelper.sin(tt * 47) * 2.2f * strain, shake2 = MathHelper.sin(tt * 53 + 1.3f) * 2.2f * strain;
                g.set(Bone.RIGHT_ARM, Pose.PITCH, g.get(Bone.RIGHT_ARM, Pose.PITCH) + shake);
                g.set(Bone.LEFT_ARM, Pose.PITCH, g.get(Bone.LEFT_ARM, Pose.PITCH) + shake2);
                g.set(Bone.RIGHT_FOREARM, Pose.PITCH, g.get(Bone.RIGHT_FOREARM, Pose.PITCH) - shake2);
                g.set(Bone.LEFT_FOREARM, Pose.PITCH, g.get(Bone.LEFT_FOREARM, Pose.PITCH) - shake);
                g.set(Bone.CHEST, Pose.PITCH, g.get(Bone.CHEST, Pose.PITCH) - 8 * strain + MathHelper.sin(tt * 6) * 2 * strain);
                g.set(Bone.HEAD, Pose.PITCH, g.get(Bone.HEAD, Pose.PITCH) + 10 * strain);
            }
        }
        // A strike just blocked: the arm on its side goes out to meet it, forearm up to take it.
        React b = BLOCKS.get(p.getId());
        float bs = b == null ? 9 : secs(b.at);
        int bSide = mine(p) ? joltSide : (b != null && b.packed != 0 ? 1 : -1);
        float meet = mine(p) ? jolt / 0.22f : bs < 0.25f ? 1 - bs / 0.25f : 0;
        if (meet > 0 && guardUp(p)) {
            if (bSide > 0) g = Pose.lerp(g, g.copy().set(Bone.RIGHT_ARM, -75, -10, 38).set(Bone.RIGHT_FOREARM, 70, 0, 0), meet);
            else g = Pose.lerp(g, g.copy().set(Bone.LEFT_ARM, -75, 10, -38).set(Bone.LEFT_FOREARM, 70, 0, 0), meet);
        }
        // The same reactions on everyone: yours from your own state, others' from what the server sent.
        float jl;
        int jSide;
        boolean stag;
        float ps;
        int pKind;
        if (mine(p)) {
            jl = jolt;
            jSide = joltSide;
            stag = staggered();
            ps = secs(parryAt);
            pKind = parryKind;
        } else {
            React jr = JOLTS.get(p.getId());
            jl = jr == null ? 0 : Math.max(0, 0.22f - secs(jr.at));
            jSide = jr == null ? 0 : jr.packed;
            stag = othersStaggered(p);
            React pr = PARRIES.get(p.getId());
            ps = pr == null ? 9 : secs(pr.at);
            pKind = pr == null ? 0 : pr.packed;
        }
        {
            int joltSide = jSide;
            if (jl > 0) {
                // A blocked hit drives the guard back into the face and turns the body away from its side.
                float j = jl / 0.22f * (1.4f - 0.6f * (mine(p) ? net.pglol.kata.client.Move.stamina : POISES.getOrDefault(p.getId(), 1f))); // harder the less is left
                g.set(Bone.CHEST, Pose.PITCH, g.get(Bone.CHEST, Pose.PITCH) - 14 * j);
                g.set(Bone.CHEST, Pose.YAW, g.get(Bone.CHEST, Pose.YAW) - 16 * j * joltSide);
                g.set(Bone.CHEST, Pose.ROLL, g.get(Bone.CHEST, Pose.ROLL) + 6 * j * joltSide);
                g.set(Bone.RIGHT_FOREARM, Pose.PITCH, g.get(Bone.RIGHT_FOREARM, Pose.PITCH) + 18 * j);
                g.set(Bone.LEFT_FOREARM, Pose.PITCH, g.get(Bone.LEFT_FOREARM, Pose.PITCH) + 18 * j);
                g.set(Bone.HEAD, Pose.PITCH, g.get(Bone.HEAD, Pose.PITCH) - 10 * j);
            }
            if (stag) {
                // Off balance: chest and head thrown back, arms flung open.
                g.set(Bone.CHEST, -16, 0, 0).set(Bone.HEAD, -20, 0, 0);
                g.set(Bone.RIGHT_ARM, -25, 0, 38).set(Bone.LEFT_ARM, -25, 0, -38);
                g.set(Bone.RIGHT_FOREARM, 25, 0, 0).set(Bone.LEFT_FOREARM, 25, 0, 0);
            }
            int parryKind = pKind;
            if (parryKind != 0 && ps < 0.5f) {
                float k = ps < 0.12f ? ps / 0.12f : 1 - ease((ps - 0.12f) / 0.38f);
                if (parryKind == 2) {
                    // The kick caught: both hands down on the foot, then the throw (arms sweeping up).
                    float up = ps > 0.2f ? ease((ps - 0.2f) / 0.2f) : 0;
                    g = Pose.lerp(g, new Pose().set(Bone.RIGHT_ARM, -55 - 70 * up, 10, 0).set(Bone.LEFT_ARM, -55 - 70 * up, -10, 0)
                        .set(Bone.RIGHT_FOREARM, 35, 0, 0).set(Bone.LEFT_FOREARM, 35, 0, 0).set(Bone.CHEST, 18 - 30 * up, 0, 0), k);
                } else {
                    // A punch turned aside: the lead hand slaps it out wide.
                    g = Pose.lerp(g, g.copy().set(Bone.LEFT_ARM, -70, 0, -55).set(Bone.LEFT_FOREARM, 30, 0, 0).set(Bone.CHEST, 4, 18, 0), k);
                }
            }
        }
        weight[0] = 1;
        return g;
    }

    /**
     * The strike (or a heavy's wind-up) on top of the body's springs: first the body drives it (hips and
     * chest turning through, weight shifting, leaning in or back), then the striking limb is solved so
     * the fist or foot travels its path from where it is to where it lands.
     */
    static void apply(PlayerEntity p, float td, Pose out) {
        Move m;
        int st;
        float s, power;
        float wind = 0;
        if (mine(p) && previewing && previewMove != null) {
            // The deck's preview: the selected move, thrown over and over with a breath between.
            m = previewMove;
            st = previewStance;
            float loop = m.length() + 0.5f;
            s = previewFixed >= 0 ? previewFixed : secs(previewStart) % loop;
            if (s > m.length()) return;
            power = 1;
        } else if (mine(p)) {
            m = cur;
            st = curStance;
            s = cur != null ? secs(curStart) : 0;
            power = curPower;
            if (charging && cur == null) {
                ClientPlayerEntity cp = (ClientPlayerEntity) p;
                m = Deck.pick(row(cp), true, 0);
                st = stance;
                wind = Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE);
                s = 0;
            }
        } else {
            Act a = MOVES.get(p.getId());
            Long ch = CHARGES.get(p.getId());
            if (ch != null && (a == null || secs(a.start) >= a.move.length())) {
                // Someone else winding up a heavy: we don't know which yet, so it's their back-stance-free
                // heavy from where they stand (the load-up reads the same).
                m = Fighting.MOVES.length > 0 ? heavyFor(STANCES.getOrDefault(p.getId(), Fighting.TR)) : null;
                st = STANCES.getOrDefault(p.getId(), Fighting.TR);
                wind = Math.min(1, secs(ch) / Fighting.FULL_CHARGE);
                s = 0;
                power = 1;
            } else {
                if (a == null || secs(a.start) >= a.move.length()) return;
                m = a.move;
                st = a.stance;
                s = secs(a.start);
                power = a.power;
            }
        }
        if (m == null) return;

        // Which hit of the move is going (a combo throws several), and how far out it is: 0 at the guard,
        // 1 at full extension. Speeding up all the way into contact, a beat held there, then drawn back.
        int hit = m.hits() > 1 && m.interval() > 0 ? Math.min(m.hits() - 1, (int) (s / m.interval())) : 0;
        float u = s - hit * m.interval();
        float end = hit < m.hits() - 1 ? m.interval() : m.length() - hit * m.interval();
        float c = m.contact(), hold = m.heavy() ? 0.07f : 0.04f;
        float k = strikeK(m, u, c, end, hold, hit, wind);
        // Part by part: the hips lead, the chest follows a beat later, the arm last (the fist arrives
        // after the body has already turned into it), and they settle back in the same order.
        float kHips = strikeK(m, u + 0.24f * c, c, end, hold, hit, wind), kChest = strikeK(m, u + 0.12f * c, c, end, hold, hit, wind);
        boolean right = Fighting.rightLimb(m, st, hit);
        float out_ = right ? -1 : 1; // the limb's outside (model x: right is negative)
        float mass = m.heavy() ? 0.6f + 0.8f * power : 1;
        float sign = right ? -1 : 1; // a right-side strike turns the chest to the left

        // How loaded it is (0..1): the wind-up of a heavy, or the first part of any strike.
        float tell = k < 0 ? -k / 0.4f : 0;
        if (tell > 0) {
            // The whole body says which side: weight onto the back foot of that side, the shoulder on the
            // striking side drawn back and dropped, the head tucked; a swinging strike's arm cocked out wide.
            out.set(Bone.CHEST, Pose.ROLL, out.get(Bone.CHEST, Pose.ROLL) + sign * 9 * tell);
            out.set(Bone.ROOT, Pose.ROLL, out.get(Bone.ROOT, Pose.ROLL) + sign * 4 * tell);
            out.set(Bone.HEAD, Pose.PITCH, out.get(Bone.HEAD, Pose.PITCH) + 6 * tell);
            out.offset[0] += -out_ * 1.2f * tell;
            Bone arm = right ? Bone.RIGHT_ARM : Bone.LEFT_ARM;
            if (m.path() == Path.HOOK || m.path() == Path.BACKFIST || m.path() == Path.ELBOW || m.path() == Path.OVERHAND)
                out.set(arm, Pose.ROLL, out.get(arm, Pose.ROLL) - out_ * 28 * tell);
            if (m.leg()) {
                // A kick: leaning away over the standing leg, arms opening for balance.
                out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) - 10 * tell);
                out.set(Bone.RIGHT_ARM, Pose.ROLL, out.get(Bone.RIGHT_ARM, Pose.ROLL) + 14 * tell);
                out.set(Bone.LEFT_ARM, Pose.ROLL, out.get(Bone.LEFT_ARM, Pose.ROLL) - 14 * tell);
            }
        }

        Vec3d pos = p.getLerpedPos(td);
        float bodyYaw = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
        // The deck preview aims straight ahead of the body as drawn (as at an opponent in front).
        Vec3d aimW = mine(p) && previewing
            ? pos.add(0, p.getStandingEyeHeight(), 0).add(Vec3d.fromPolar(0, bodyYaw).multiply(m.leg() ? 1.1 : 1.0)).add(0, aimDrop(m), 0)
            : aimPoint(p, td, m);
        Vector3f aim = Animator.toModel(aimW, pos, bodyYaw);

        if (m.leg()) {
            kick(p, out, m, k, right, out_, sign, mass, aim, pos, bodyYaw);
            return;
        }

        // The body behind a hand strike.
        float twist = (switch (m.path()) {
            case STRAIGHT -> m.lead() ? 12 : 28;
            case HOOK -> 30;
            case UPPER -> 18;
            case OVERHAND, PALM -> 32;
            case SUPERMAN -> 20;
            default -> 0; // spinning strikes: the spin itself turns the body (see bodyTurn)
        }) * mass;
        out.set(Bone.CHEST, Pose.YAW, out.get(Bone.CHEST, Pose.YAW) + sign * twist * kChest);
        out.set(Bone.ROOT, Pose.YAW, out.get(Bone.ROOT, Pose.YAW) + sign * twist * 0.4f * kHips);
        if (m.path() == Path.UPPER) {
            // Dip into the legs, then drive up through them onto the toes, chest rising and leaning back.
            float dip = k < 0.35f ? ease(k / 0.35f) : 1 - ease((k - 0.35f) / 0.4f);
            float rise = k > 0.35f ? ease((k - 0.35f) / 0.65f) : 0;
            out.offset[1] += 2.6f * dip * mass - 1.6f * rise * mass;
            out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + 14 * dip - 14 * rise);
            out.set(Bone.RIGHT_SHIN, Pose.PITCH, out.get(Bone.RIGHT_SHIN, Pose.PITCH) + 35 * dip);
            out.set(Bone.LEFT_SHIN, Pose.PITCH, out.get(Bone.LEFT_SHIN, Pose.PITCH) + 35 * dip);
            out.set(Bone.RIGHT_LEG, Pose.PITCH, out.get(Bone.RIGHT_LEG, Pose.PITCH) - 18 * dip);
            out.set(Bone.LEFT_LEG, Pose.PITCH, out.get(Bone.LEFT_LEG, Pose.PITCH) - 18 * dip);
        } else if (m.path() == Path.SUPERMAN) {
            // Flying in flat: the body tips forward behind the fist, the legs trailing.
            out.set(Bone.ROOT, Pose.PITCH, out.get(Bone.ROOT, Pose.PITCH) + 45 * Math.max(0, k));
            out.set(Bone.RIGHT_LEG, Pose.PITCH, out.get(Bone.RIGHT_LEG, Pose.PITCH) + 35 * Math.max(0, k));
            out.set(Bone.LEFT_LEG, Pose.PITCH, out.get(Bone.LEFT_LEG, Pose.PITCH) + 45 * Math.max(0, k));
            Bone other = right ? Bone.LEFT_ARM : Bone.RIGHT_ARM;
            out.set(other, Pose.PITCH, out.get(other, Pose.PITCH) + 50 * Math.max(0, k));
        } else {
            out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + (m.heavy() ? 9 : 5) * k);
            out.offset[2] -= (m.heavy() ? 3.5f * mass : 2f) * k;
            if (m.heavy()) out.offset[1] += 0.9f * Math.abs(k);
        }

        // The fist's path, from where the guard holds it to where it lands.
        Bone arm = right ? Bone.RIGHT_ARM : Bone.LEFT_ARM, fore = right ? Bone.RIGHT_FOREARM : Bone.LEFT_FOREARM;
        Vector3f shoulder = new Vector3f(right ? -5 : 5, 2, 0);
        Vector3f guardFist = Ragdoll.joints(out)[right ? Ragdoll.RH : Ragdoll.LH];
        Vector3f fist;
        if (m.path() == Path.BACKFIST) {
            // The arm swings out wide to its side and the spin carries it round into them, back of the fist first.
            fist = new Vector3f(guardFist).lerp(new Vector3f(out_ * 15.5f, 3, -3), Math.max(0, k));
        } else if (m.path() == Path.ELBOW) {
            // The elbow comes up level and out to the side, the fist tucked at the chest; the spin drives it in.
            fist = new Vector3f(guardFist).lerp(new Vector3f(out_ * 2.5f, 2.5f, -4.5f), Math.max(0, k));
        } else if (m.path() == Path.UPPER && k >= 0) {
            // Down to the hip first, then up through the line.
            Vector3f low = new Vector3f(out_ * 1.5f, 10, -4);
            fist = k < 0.35f ? new Vector3f(guardFist).lerp(low, ease(k / 0.35f)) : new Vector3f(low).lerp(aim, ease((k - 0.35f) / 0.65f));
        } else if (k < 0) {
            Vector3f back = new Vector3f(guardFist).sub(aim).normalize().mul(6 * -k); // drawn back, away from them
            fist = new Vector3f(guardFist).add(back);
        } else {
            fist = new Vector3f(guardFist).lerp(aim, k);
            float arc = MathHelper.sin(MathHelper.PI * k);
            switch (m.path()) {
                case HOOK -> fist.add(out_ * 6 * arc, -1 * arc, 0);
                case OVERHAND -> fist.add(out_ * 2 * arc, -5 * arc, 0);
                default -> { }
            }
        }
        Matrix4f frame = Animator.armFrame(out).invert();
        Vector3f pole = m.path() == Path.ELBOW || m.path() == Path.HOOK
            ? new Vector3f(out_, -0.15f, 0.25f)                                // elbow up and out to the side
            : m.path() == Path.BACKFIST ? new Vector3f(out_ * 0.3f, 0.2f, 1)   // arm out straight, elbow back
            : new Vector3f(out_ * 0.3f, 1, 0.45f);                             // elbow down, behind the punch
        Animator.reach(out, arm, fore, shoulder, fist, frame, 1, pole);
        if (mine(p) && cur != null) {
            limbPrev = limbNow;
            Vector3f[] jt = Ragdoll.joints(out);
            limbNow = Ragdoll.toWorld(jt[right ? Ragdoll.RH : Ragdoll.LH], pos, bodyYaw);
            limbMid = Ragdoll.toWorld(jt[right ? Ragdoll.RE : Ragdoll.LE], pos, bodyYaw); // the elbow: the forearm hits too
        }
    }

    /**
     * A kick: the knee chambers up, then the foot drives out along the kick's line (straight through,
     * whipping round, low to the leg, back behind you) while the hips turn and the body counter-leans
     * over the standing leg. A dropkick throws both feet, the body laid out behind them.
     */
    private static void kick(PlayerEntity p, Pose out, Move m, float k, boolean right, float out_, float sign, float mass, Vector3f aim, Vec3d pos, float bodyYaw) {
        float kk = Math.max(0, k);
        Bone leg = right ? Bone.RIGHT_LEG : Bone.LEFT_LEG, shin = right ? Bone.RIGHT_SHIN : Bone.LEFT_SHIN;
        Bone standShin = right ? Bone.LEFT_SHIN : Bone.RIGHT_SHIN;
        Vector3f hip = new Vector3f(right ? -1.9f : 1.9f, 12, 0);

        // The body: hips turning into it, leaning back over the standing leg (forward for a back kick).
        float hips = m.turns() ? 0 : switch (m.path()) {
            case ROUNDHOUSE -> 45;
            case SIDE_KICK -> 70;
            case LOW_KICK -> 25;
            default -> 8;
        } * mass;
        float lean = switch (m.path()) {
            case BACK_KICK -> 28;
            case KNEE -> 8;
            case DROPKICK -> 0;
            case SIDE_KICK -> -22;
            default -> -14;
        };
        // The hips turn over the standing foot (it stays planted, the body pivoting on it), the chest
        // counter-twisting a little so the turn reads through the whole body.
        float turnBefore = out.get(Bone.ROOT, Pose.YAW);
        out.set(Bone.ROOT, Pose.YAW, turnBefore + sign * hips * kk);
        out.set(Bone.CHEST, Pose.YAW, out.get(Bone.CHEST, Pose.YAW) - sign * hips * 0.35f * kk);
        Vector3f standFoot = new Vector3f(right ? 1.9f : -1.9f, 0, 0);
        Vector3f was = new org.joml.Quaternionf().rotationY((float) Math.toRadians(turnBefore)).transform(new Vector3f(standFoot));
        Vector3f now = new org.joml.Quaternionf().rotationY((float) Math.toRadians(out.get(Bone.ROOT, Pose.YAW))).transform(new Vector3f(standFoot));
        out.offset[0] += was.x - now.x;
        out.offset[2] += was.z - now.z;
        Bone standLeg = right ? Bone.LEFT_LEG : Bone.RIGHT_LEG;
        out.set(standLeg, Pose.YAW, out.get(standLeg, Pose.YAW) - sign * hips * 0.6f * kk); // the foot stays pointing where it was
        out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + lean * kk);
        if (m.path() == Path.SIDE_KICK) out.set(Bone.CHEST, Pose.ROLL, out.get(Bone.CHEST, Pose.ROLL) - out_ * 18 * kk);
        out.set(standShin, Pose.PITCH, out.get(standShin, Pose.PITCH) + 14 * kk);
        // Hands stay up to guard while the leg works.
        if (m.path() == Path.DROPKICK) {
            // Laid out behind both feet.
            out.set(Bone.ROOT, Pose.PITCH, out.get(Bone.ROOT, Pose.PITCH) - 70 * kk);
            out.set(Bone.RIGHT_ARM, Pose.ROLL, out.get(Bone.RIGHT_ARM, Pose.ROLL) + 40 * kk);
            out.set(Bone.LEFT_ARM, Pose.ROLL, out.get(Bone.LEFT_ARM, Pose.ROLL) - 40 * kk);
        }

        Matrix4f inv = Ragdoll.rootFrame(out).invert();
        if (m.path() == Path.KNEE) {
            // The knee drives up into them; the foot tucked under.
            out.set(leg, Pose.PITCH, out.get(leg, Pose.PITCH) - 105 * kk);
            out.set(shin, Pose.PITCH, out.get(shin, Pose.PITCH) + 115 * kk);
            out.set(Bone.RIGHT_ARM, Pose.PITCH, out.get(Bone.RIGHT_ARM, Pose.PITCH) - 30 * kk);
            out.set(Bone.LEFT_ARM, Pose.PITCH, out.get(Bone.LEFT_ARM, Pose.PITCH) - 30 * kk);
        } else {
            // Rest, chamber, extension.
            Vector3f rest = new Vector3f(hip.x, 24, 0);
            float chamberY = m.path() == Path.LOW_KICK ? 19 : 16;
            Vector3f chamber = new Vector3f(hip.x + (m.path() == Path.ROUNDHOUSE ? out_ * 3 : 0), chamberY, m.path() == Path.BACK_KICK ? 3 : -3);
            Vector3f target = clampReach(hip, aim, 11.6f);
            Vector3f foot;
            if (m.turns()) {
                // A spinning kick: the leg chambers and goes out wide on its own side, held there in the
                // body's frame so it turns with the spin, and only at the very end sweeps onto the target.
                Matrix4f body = Ragdoll.rootFrame(out);
                Vector3f restB = body.transformPosition(new Vector3f(rest));
                Vector3f chamberB = body.transformPosition(new Vector3f(hip.x + out_ * 3, 16, -2));
                Vector3f wide = body.transformPosition(new Vector3f(hip.x + out_ * 10.5f, 10, -2));
                if (kk < 0.3f) foot = restB.lerp(chamberB, ease(kk / 0.3f));
                else if (kk < 0.85f) foot = chamberB.lerp(wide, ease((kk - 0.3f) / 0.4f));
                else foot = wide.lerp(target, ease((kk - 0.85f) / 0.15f));
            } else if (kk < 0.4f) foot = new Vector3f(rest).lerp(chamber, ease(kk / 0.4f));
            else {
                float x = (kk - 0.4f) / 0.6f;
                foot = new Vector3f(chamber).lerp(target, x);
                if (m.path() == Path.ROUNDHOUSE) foot.add(out_ * 6 * MathHelper.sin(MathHelper.PI * x), -1.5f * MathHelper.sin(MathHelper.PI * x), 0);
            }
            Vector3f pole = switch (m.path()) {
                case ROUNDHOUSE, SIDE_KICK -> new Vector3f(out_, -0.2f, -0.4f); // knee turned out to the side
                case BACK_KICK -> new Vector3f(0, 0, 1);                       // knee behind
                default -> new Vector3f(out_ * 0.15f, 0, -1);                  // knee forward
            };
            Animator.kickLeg(out, leg, shin, hip, foot, inv, 1, pole);
            if (m.path() == Path.DROPKICK) {
                Bone leg2 = right ? Bone.LEFT_LEG : Bone.RIGHT_LEG, shin2 = right ? Bone.LEFT_SHIN : Bone.RIGHT_SHIN;
                Vector3f hip2 = new Vector3f(-hip.x, 12, 0);
                Vector3f foot2 = new Vector3f(foot).add(-hip.x * 2, 0, 0);
                Animator.kickLeg(out, leg2, shin2, hip2, foot2, inv, 1, new Vector3f(-out_ * 0.15f, 0, -1));
            }
        }
        if (mine(p) && cur != null) {
            limbPrev = limbNow;
            Vector3f[] j = Ragdoll.joints(out);
            limbNow = Ragdoll.toWorld(m.path() == Path.KNEE ? j[right ? Ragdoll.RK : Ragdoll.LK] : j[right ? Ragdoll.RF : Ragdoll.LF], pos, bodyYaw);
            limbMid = Ragdoll.toWorld(m.path() == Path.KNEE ? j[right ? Ragdoll.RHIP : Ragdoll.LHIP] : j[right ? Ragdoll.RK : Ragdoll.LK], pos, bodyYaw); // the shin (the thigh for a knee)
        }
    }

    private static Vector3f clampReach(Vector3f root, Vector3f at, float reach) {
        Vector3f d = new Vector3f(at).sub(root);
        if (d.length() > reach) d.normalize().mul(reach);
        return d.add(root);
    }

    /**
     * The body taking a hit, from where it landed, what kind of strike and which side it came from:
     * an uppercut snaps the head back to look at the sky, a straight knocks it back, a hook or a kick to
     * the head whips it away from the side it came from; a body shot folds you; a low kick buckles that
     * knee. Heavies hit harder. Fast in, eased out. Added on top of everything else.
     */
    /**
     * How far out a strike is at time u into its hit (0 at the guard, 1 at full extension, negative while
     * loading). A charging heavy holds a load; a spin turns steadily into contact; a hand strike loads for
     * its first 45% and snaps through; otherwise it speeds into contact, holds a beat, and draws back.
     */
    private static float strikeK(Move m, float u, float c, float end, float hold, int hit, float wind) {
        if (wind > 0) return -0.25f * ease(wind);
        if (m.turns() && u < c) return MathHelper.clamp(u / c, 0, 1);
        if (u < c && !m.leg() && hit == 0) {
            float x = Math.max(0, u / c), load = 0.45f;
            if (x < load) return -0.4f * ease(x / load);
            float y = (x - load) / (1 - load);
            return -0.4f + 1.4f * y * y * (1.4f - 0.4f * y);
        }
        if (u < c) {
            float x = Math.max(0, u / c);
            return x * x * (1.4f - 0.4f * x);
        }
        if (u < c + hold) return 1;
        return 1 - ease(Math.min(1, (u - c - hold) / Math.max(0.04f, end - c - hold)));
    }

    /** The heavy someone would throw from this stance with the starter deck (for showing their wind-up). */
    private static Move heavyFor(int stance) {
        Fighting.Style s = Fighting.STYLES[0];
        int[] row = s.starter()[Math.max(0, Math.min(3, stance))];
        return s.move(row[Fighting.HEAVY_SLOT]);
    }

    /** Is the local player hanging in the air from an air hit (gravity eased off)? */
    public static boolean hanging() {
        return System.nanoTime() < hangUntil;
    }

    /** Concussed: slow and unsteady. */
    static boolean concussed(LivingEntity e) {
        Long u = CONCUSSED.get(e.getId());
        return u != null && System.nanoTime() < u;
    }

    static boolean hitRecently(LivingEntity e) {
        React r = HITS.get(e.getId());
        return r != null && secs(r.at) < 1.5f;
    }

    public static void react(LivingEntity e, Pose out) {
        dodgePose(e, out);
        suspendedPose(e, out);
        dazedPose(e, out);
        React r = HITS.get(e.getId());
        if (r == null) return;
        boolean heavy = (r.packed & 1 << 8) != 0;
        float dur = heavy ? 0.55f : 0.38f, s = secs(r.at);
        if (s > dur) return;
        float w = s < 0.05f ? s / 0.05f : 1 - ease((s - 0.05f) / (dur - 0.05f));
        w *= heavy ? 1.5f : 1;
        Fighting.Part part = Fighting.Part.values()[(r.packed & 3) % Fighting.Part.values().length];
        Path path = Path.values()[Math.min(Path.values().length - 1, (r.packed >> 2) & 0x1F)];
        float side = (r.packed & 1 << 7) != 0 ? 1 : -1; // it came from their right (+1) or left
        switch (part) {
            case HEAD -> {
                if (path == Path.UPPER) {
                    add(out, Bone.HEAD, Pose.PITCH, -45 * w);
                    add(out, Bone.CHEST, Pose.PITCH, -14 * w);
                    out.offset[2] += 1 * w;
                } else if (path == Path.STRAIGHT || path == Path.PALM || path == Path.SUPERMAN || path == Path.OVERHAND || path == Path.KNEE || path == Path.FRONT_KICK) {
                    add(out, Bone.HEAD, Pose.PITCH, -24 * w);
                    add(out, Bone.CHEST, Pose.PITCH, -10 * w);
                    out.offset[2] += 1.5f * w;
                } else {
                    add(out, Bone.HEAD, Pose.YAW, -40 * side * w);
                    add(out, Bone.HEAD, Pose.ROLL, 15 * side * w);
                    add(out, Bone.CHEST, Pose.YAW, -12 * side * w);
                    add(out, Bone.CHEST, Pose.ROLL, 6 * side * w);
                }
            }
            case TORSO -> {
                add(out, Bone.CHEST, Pose.PITCH, 24 * w);
                add(out, Bone.HEAD, Pose.PITCH, 10 * w);
                add(out, Bone.CHEST, Pose.YAW, -8 * side * w);
                add(out, Bone.RIGHT_FOREARM, Pose.PITCH, 25 * w);
                add(out, Bone.LEFT_FOREARM, Pose.PITCH, 25 * w);
                out.offset[2] += 1.5f * w;
                out.offset[1] += 0.8f * w;
            }
            case LEG -> {
                Bone leg = side > 0 ? Bone.RIGHT_LEG : Bone.LEFT_LEG, shin = side > 0 ? Bone.RIGHT_SHIN : Bone.LEFT_SHIN;
                add(out, leg, Pose.PITCH, 12 * w);
                add(out, shin, Pose.PITCH, 45 * w);
                add(out, Bone.CHEST, Pose.ROLL, 8 * side * w);
                out.offset[1] += 1.6f * w;
            }
            default -> {
                Bone arm = side > 0 ? Bone.RIGHT_ARM : Bone.LEFT_ARM;
                add(out, arm, Pose.PITCH, 25 * w);
                add(out, Bone.CHEST, Pose.YAW, -8 * side * w);
            }
        }
    }

    /**
     * A dodge, an avoid or a flip as the body shows it. Step back: leaning away, low. Duck: knees deep,
     * folded under. Slip: the head and shoulders out to the side, feet nearly still. Hop: knees tucked
     * up. Flip: a whole turn about the waist whichever way it was thrown, tucked tight, opening to land.
     */
    private static void dodgePose(LivingEntity e, Pose out) {
        React d = DODGES.get(e.getId());
        if (d == null) return;
        float s = secs(d.at);
        if (d.packed >= 32) {
            // On a wall: tipped out from it with the feet on it, crouched deep, a hand back on it.
            if (s > 1.6f) return;
            double ang = (d.packed - 32) * Math.PI / 8; // the wall's outward normal, in the world
            float nx = (float) Math.cos(ang), nz = (float) Math.sin(ang);
            double yaw = Math.toRadians(e.bodyYaw);
            float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw), lx = (float) Math.cos(yaw), lz = (float) Math.sin(yaw);
            // Towards the wall, in the model (left +x, forward -z).
            float wx = -(nx * lx + nz * lz), wz = (nx * fx + nz * fz);
            float ln = (float) Math.sqrt(wx * wx + wz * wz);
            if (ln < 1e-4f) return;
            wx /= ln;
            wz /= ln;
            float w = Math.min(1, s / 0.08f);
            add(out, Bone.RIGHT_LEG, Pose.PITCH, -70 * w);
            add(out, Bone.LEFT_LEG, Pose.PITCH, -55 * w);
            add(out, Bone.RIGHT_SHIN, Pose.PITCH, 110 * w);
            add(out, Bone.LEFT_SHIN, Pose.PITCH, 95 * w);
            add(out, Bone.CHEST, Pose.PITCH, 22 * w);
            add(out, Bone.HEAD, Pose.PITCH, -20 * w);
            Bone hand = wx > 0 ? Bone.LEFT_ARM : Bone.RIGHT_ARM;
            add(out, hand, Pose.PITCH, -40 * w);
            add(out, hand, Pose.ROLL, (wx > 0 ? -1 : 1) * 60 * w);
            // Tip the whole body away from the wall so the feet are on it (about the waist).
            Vector3f axis = new Vector3f(wz, 0, -wx); // tipping the head away from the wall
            org.joml.Quaternionf tip = new org.joml.Quaternionf().rotationAxis((float) Math.toRadians(62 * w), axis.normalize());
            org.joml.Quaternionf was = new org.joml.Quaternionf().rotationZYX(out.rad(Bone.ROOT, Pose.ROLL), out.rad(Bone.ROOT, Pose.YAW), out.rad(Bone.ROOT, Pose.PITCH));
            org.joml.Quaternionf now = new org.joml.Quaternionf(tip).mul(was);
            Vector3f waist = new Vector3f(0, -12, 0);
            Vector3f a = was.transform(new Vector3f(waist)), b = now.transform(new Vector3f(waist));
            out.offset[0] += a.x - b.x + wx * 3 * w;
            out.offset[1] += a.y - b.y;
            out.offset[2] += a.z - b.z + wz * 3 * w;
            Vector3f eul = new org.joml.Matrix3f().set(now).getEulerAnglesZYX(new Vector3f());
            out.set(Bone.ROOT, (float) Math.toDegrees(eul.x), (float) Math.toDegrees(eul.y), (float) Math.toDegrees(eul.z));
            return;
        }
        if (d.packed >= 16) {
            if (s > FLIP_TIME) return;
            float u = s / FLIP_TIME;
            float turn = ease(u) * (float) (Math.PI * 2);
            // Tucked through the middle of the turn, open at both ends.
            float tuck = MathHelper.sin(MathHelper.PI * Math.min(1, u * 1.15f));
            add(out, Bone.RIGHT_LEG, Pose.PITCH, -95 * tuck);
            add(out, Bone.LEFT_LEG, Pose.PITCH, -95 * tuck);
            add(out, Bone.RIGHT_SHIN, Pose.PITCH, 120 * tuck);
            add(out, Bone.LEFT_SHIN, Pose.PITCH, 120 * tuck);
            add(out, Bone.RIGHT_ARM, Pose.PITCH, -50 * tuck);
            add(out, Bone.LEFT_ARM, Pose.PITCH, -50 * tuck);
            add(out, Bone.CHEST, Pose.PITCH, 25 * tuck);
            // The direction, in the model: forward is -z, left is +x. The turn tips the head that way.
            double ang = (d.packed - 16) * Math.PI / 8;
            float dx = (float) Math.sin(ang), dz = (float) -Math.cos(ang);
            Vector3f axis = new Vector3f(-dz, 0, dx);
            if (axis.lengthSquared() < 1e-6f) return;
            axis.normalize();
            // In the model y is down, so "tipping the head forward" is a positive turn about this axis.
            org.joml.Quaternionf flip = new org.joml.Quaternionf().rotationAxis(turn, axis);
            org.joml.Quaternionf was = new org.joml.Quaternionf().rotationZYX(out.rad(Bone.ROOT, Pose.ROLL), out.rad(Bone.ROOT, Pose.YAW), out.rad(Bone.ROOT, Pose.PITCH));
            org.joml.Quaternionf now = new org.joml.Quaternionf(flip).mul(was);
            // About the waist, not the feet: keep the waist where it was.
            Vector3f waist = new Vector3f(0, -12, 0);
            Vector3f a = was.transform(new Vector3f(waist)), b = now.transform(new Vector3f(waist));
            out.offset[0] += a.x - b.x;
            out.offset[1] += a.y - b.y;
            out.offset[2] += a.z - b.z;
            Vector3f eul = new org.joml.Matrix3f().set(now).getEulerAnglesZYX(new Vector3f());
            out.set(Bone.ROOT, (float) Math.toDegrees(eul.x), (float) Math.toDegrees(eul.y), (float) Math.toDegrees(eul.z));
            return;
        }
        float dur = DODGE_RECOVER + 0.05f;
        if (s > dur) return;
        float w = s < 0.05f ? s / 0.05f : 1 - ease((s - 0.05f) / (dur - 0.05f));
        float knees = 1;
        switch (d.packed) {
            case 1 -> { // duck: down and folded under it
                add(out, Bone.CHEST, Pose.PITCH, 34 * w);
                add(out, Bone.HEAD, Pose.PITCH, -16 * w);
                knees = 2.1f;
            }
            case 2, 3 -> { // slip: head and shoulders out of the line, feet nearly still
                float sd = d.packed == 2 ? 1 : -1;
                add(out, Bone.CHEST, Pose.ROLL, 20 * sd * w);
                add(out, Bone.CHEST, Pose.YAW, 12 * sd * w);
                add(out, Bone.HEAD, Pose.ROLL, 10 * sd * w);
                add(out, Bone.ROOT, Pose.ROLL, 5 * sd * w);
                out.offset[0] += 1.6f * sd * w;
                knees = 0.8f;
            }
            case 4 -> { // hop: knees tucked up under it
                add(out, Bone.RIGHT_LEG, Pose.PITCH, -40 * w);
                add(out, Bone.LEFT_LEG, Pose.PITCH, -40 * w);
                add(out, Bone.RIGHT_SHIN, Pose.PITCH, 70 * w);
                add(out, Bone.LEFT_SHIN, Pose.PITCH, 70 * w);
                knees = 0;
            }
            default -> { // step back: leaning away from it
                add(out, Bone.CHEST, Pose.PITCH, -14 * w);
                add(out, Bone.HEAD, Pose.PITCH, 8 * w);
            }
        }
        if (knees > 0) {
            add(out, Bone.RIGHT_LEG, Pose.PITCH, -12 * w * knees);
            add(out, Bone.LEFT_LEG, Pose.PITCH, -12 * w * knees);
            add(out, Bone.RIGHT_SHIN, Pose.PITCH, 26 * w * knees);
            add(out, Bone.LEFT_SHIN, Pose.PITCH, 26 * w * knees);
            out.offset[1] += 1.8f * w * knees;
        }
    }

    /** Juggled: hanging in the air from the hits, limbs thrown loose, the body tipped back and turning slowly. */
    private static void suspendedPose(LivingEntity e, Pose out) {
        Long u = HANGS.get(e.getId());
        if (u == null || e.isOnGround()) return;
        float left = (u - System.nanoTime()) / 1e9f;
        if (left <= -0.15f) return;
        // Only for whoever took the hit (the attacker is busy striking).
        if (e instanceof PlayerEntity pl && mine(pl) && cur != null) return;
        if (!(e instanceof PlayerEntity pl2 && mine(pl2)) && MOVES.containsKey(e.getId()) && secs(MOVES.get(e.getId()).start) < MOVES.get(e.getId()).move.length()) return;
        float w = MathHelper.clamp(left / 0.15f + 1, 0, 1);
        float tt = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
        add(out, Bone.ROOT, Pose.PITCH, -22 * w);
        add(out, Bone.CHEST, Pose.PITCH, -12 * w);
        add(out, Bone.HEAD, Pose.PITCH, -18 * w);
        add(out, Bone.RIGHT_ARM, Pose.PITCH, (-60 + 12 * MathHelper.sin(tt * 9)) * w);
        add(out, Bone.LEFT_ARM, Pose.PITCH, (-55 + 12 * MathHelper.sin(tt * 8 + 1)) * w);
        add(out, Bone.RIGHT_ARM, Pose.ROLL, 30 * w);
        add(out, Bone.LEFT_ARM, Pose.ROLL, -30 * w);
        add(out, Bone.RIGHT_LEG, Pose.PITCH, -30 * w);
        add(out, Bone.LEFT_LEG, Pose.PITCH, 10 * w);
        add(out, Bone.RIGHT_SHIN, Pose.PITCH, 50 * w);
        add(out, Bone.LEFT_SHIN, Pose.PITCH, 35 * w);
    }

    /** Concussed: the head lolls round, the body sways, the arms hang heavy. */
    private static void dazedPose(LivingEntity e, Pose out) {
        Long u = CONCUSSED.get(e.getId());
        if (u == null) return;
        float left = (u - System.nanoTime()) / 1e9f;
        if (left <= 0) return;
        float w = Math.min(1, left / 0.5f);
        float tt = (System.nanoTime() % 1_000_000_000_000L) / 1e9f;
        add(out, Bone.HEAD, Pose.ROLL, 14 * MathHelper.sin(tt * 2.6f) * w);
        add(out, Bone.HEAD, Pose.PITCH, (10 + 8 * MathHelper.cos(tt * 2.6f)) * w);
        add(out, Bone.CHEST, Pose.ROLL, 6 * MathHelper.sin(tt * 1.7f) * w);
        add(out, Bone.CHEST, Pose.PITCH, 8 * w);
        add(out, Bone.RIGHT_ARM, Pose.PITCH, 10 * w);
        add(out, Bone.LEFT_ARM, Pose.PITCH, 10 * w);
        out.offset[0] += 0.8f * MathHelper.sin(tt * 1.7f) * w;
    }

    /** Stars round a concussed head (every few ticks). */
    private static void stars(MinecraftClient mc) {
        if (mc.world == null || CONCUSSED.isEmpty()) return;
        long now = System.nanoTime();
        for (var en : CONCUSSED.entrySet()) {
            if (now > en.getValue()) continue;
            Entity e = mc.world.getEntityById(en.getKey());
            if (e == null) continue;
            double a = (now / 1e9) * 5;
            for (int i = 0; i < 3; i++) {
                double ang = a + i * Math.PI * 2 / 3;
                mc.world.addParticle(net.minecraft.particle.ParticleTypes.CRIT, e.getX() + Math.cos(ang) * 0.35, e.getEyeY() + 0.35, e.getZ() + Math.sin(ang) * 0.35, 0, 0.01, 0);
            }
        }
    }

    private static void add(Pose p, Bone b, int axis, float deg) {
        p.set(b, axis, p.get(b, axis) + deg);
    }

    /**
     * Where a strike is aimed: on the locked opponent, the point where its line first meets their body
     * (a guard's forearm, say), so the fist or foot lands on it and stops there. Otherwise straight out.
     */
    private static Vec3d aimPoint(PlayerEntity p, float td, Move m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity t = mine(p) ? targetEntity(mc) : null;
        if (t == null && mine(p)) t = softTarget(p, m); // whoever's in front: the limb goes at them
        Vec3d eye = p.getCameraPosVec(td);
        if (t != null) {
            Vec3d want = wanted(p, td, m, t);
            Hitbox.Hit h = Hitbox.trace(t, td, origin(p, td, m), want, Fighting.REACH);
            Vec3d at = h != null ? h.point() : want;
            Vec3d from = origin(p, td, m);
            Vec3d d = at.subtract(from);
            double max = m.leg() ? 1.3 : 1.2;
            return d.length() > max ? from.add(d.normalize().multiply(max)) : at; // past full reach the limb just points at it
        }
        Vec3d look = Vec3d.fromPolar(0, p.getYaw(td));
        return eye.add(look.multiply(m.leg() ? 1.1 : 1.0)).add(0, aimDrop(m), 0);
    }

    /** How far below the eyes a strike aims when there's no one to aim at (blocks). */
    private static double aimDrop(Move m) {
        return switch (m.path()) {
            case PALM -> -0.45;
            case FRONT_KICK, SIDE_KICK, BACK_KICK, KNEE, DROPKICK -> -0.75;
            case LOW_KICK -> -1.25;
            case ROUNDHOUSE -> -0.35;
            default -> -0.2;
        };
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /**
     * Footwork: locked on, a top stance walks in faster and backs off slower (a back stance the other
     * way round). Guarding, winding up a heavy or reeling slows you down.
     */
    public static float speedScale(PlayerEntity p) {
        if (!mine(p)) return 1;
        ClientPlayerEntity c = (ClientPlayerEntity) p;
        float f = c.input.movementForward;
        float s = 1;
        if (target >= 0) {
            if (f > 0.1f) s = Fighting.top(stance) ? 1.15f : 0.85f;
            else if (f < -0.1f) s = Fighting.top(stance) ? 0.85f : 1.15f;
        }
        if (guard) s *= 0.75f;
        if (charging) s *= 0.8f;
        if (staggered()) s *= 0.5f;
        if (concussed(p)) s *= 0.55f;
        return s;
    }

    static void send(int kind, int a, int b) {
        if (ClientPlayNetworking.canSend(KataNet.Fight.ID)) ClientPlayNetworking.send(new KataNet.Fight(kind, a, b));
    }
}
