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
    private static long brokenUntil, staggerUntil, parryAt;
    private static int parryKind, lastHurt;
    /** Where the striking fist or foot is in the world, this frame and last (what it runs into on the way). */
    private static Vec3d limbNow, limbPrev;
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
        guard = free && mc.options.useKey.isPressed() && cur == null && !charging && !staggered() && System.nanoTime() > brokenUntil;
        if (guard && target < 0) target = pick(p, 8, 50); // raising a guard locks onto whoever's in front
        if (guard != sentGuard) {
            sentGuard = guard;
            send(KataNet.GUARD, guard ? 1 : 0, 0);
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
                    begin(p, Deck.pick(row(p), true, 0), Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE));
                } else light(p);
            } else if (!charging && cur == null && secs(pressAt) >= Fighting.HEAVY_AFTER) {
                charging = true;
                chargeAt = System.nanoTime();
            }
        }
        // The move in progress: each of its hits lands (on whatever it first touches), then it's done.
        if (cur != null) {
            float s = secs(curStart);
            if (nextHit < cur.hits()) {
                float c = cur.contactOf(nextHit);
                int early = -1;
                if (s >= c - cur.contact() * (cur.turns() ? 0.75f : 0.6f) && limbPrev != null && limbNow != null && limbPrev.squaredDistanceTo(limbNow) > 1e-6) {
                    Hitbox.Contact touch = Hitbox.sweep(nearby(p), td, limbPrev, limbNow, cur.leg() ? 0.09 : 0.07);
                    if (touch != null) {
                        lastPart = touch.hit().part();
                        early = touch.entity().getId();
                    }
                }
                if (early >= 0 || s >= c) {
                    int hit = early >= 0 ? early : struck(mc, p);
                    boolean right = Fighting.rightLimb(cur, curStance, nextHit);
                    int packed = Fighting.id(cur) | (right ? 1 << 8 : 0) | (Math.round(curPower * 100) << 9) | (lastPart.ordinal() << 16);
                    send(KataNet.STRIKE, packed, hit);
                    nextHit++;
                    if (hit >= 0 && cur.from() == Fighting.From.AIR) airLanded = true;
                    // It lands: the strike holds still for a moment (the impact), longer for a heavy.
                    if (hit >= 0) curStart += (long) ((cur.heavy() ? 0.09 : 0.05) * 1e9);
                    // A rising strike lifts you with it.
                    if (cur.path() == Path.UPPER && cur.heavy() && p.isOnGround()) p.addVelocity(0, 0.3, 0);
                }
            }
            if (s >= cur.length()) {
                stanceTo(Fighting.end(cur, curStance));
                cur = null;
                lastEnd = System.nanoTime();
                if (pendingLight) {
                    pendingLight = false;
                    light(p);
                }
            }
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
        if (guard || staggered()) return true; // hands up, or knocked off balance: nothing to throw
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
        if (secs(lastEnd) > 0.55f) chain = 0;
        begin(p, Deck.pick(row(p), false, chain), 1);
        chain++;
    }

    private static void begin(ClientPlayerEntity p, Move m, float power) {
        if (m == null || staggered()) return;
        // In the air only air moves; on the ground none of them.
        if ((m.from() == Fighting.From.AIR) != airborne(p)) return;
        if (m.from() == Fighting.From.AIR) {
            if (airUsed && !airLanded) return; // one per jump, unless the last one landed
            airUsed = true;
            airLanded = false;
        }
        cur = m;
        curStance = stance;
        curPower = m.heavy() ? Math.max(0.3f, power) : 1;
        curStart = System.nanoTime();
        nextHit = 0;
        limbNow = limbPrev = null;
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
            // The lunge sets how fast you fly in (it never stacks on what you already have).
            double fwd = switch (m.path()) {
                case SUPERMAN -> 0.26;
                case DROPKICK -> 0.3;
                case KNEE -> 0.22;
                default -> 0.1;
            };
            double up = m.path() == Path.KNEE ? 0.08 : m.path() == Path.SUPERMAN ? 0.04 : 0;
            Vec3d v = p.getVelocity();
            double along = v.x * f.x + v.z * f.z;
            double add = Math.max(0, fwd - along);
            p.setVelocity(v.x + f.x * add, Math.max(v.y, up), v.z + f.z * add);
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
        Entity t = targetEntity(mc);
        if (t == null && mc.targetedEntity instanceof LivingEntity c) t = c;
        Vec3d want = t != null ? wanted(p, 1, cur, t) : p.getCameraPosVec(1).add(p.getRotationVec(1).multiply(2)).add(0, -0.2, 0);
        Hitbox.Contact h = Hitbox.traceAll(nearby(p), 1, origin(p, 1, cur), want, Fighting.REACH);
        if (h == null) return -1;
        lastPart = h.hit().part();
        return h.entity().getId();
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
    private static final Map<Integer, React> JOLTS = new HashMap<>(), PARRIES = new HashMap<>();
    private static final Map<Integer, Long> STAGGERS = new HashMap<>();

    static void remote(int entity, int kind, int a) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (kind == KataNet.HIT) {
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
                }
                case KataNet.GUARD_BROKEN -> {
                    jolt = 0.22f;
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
            case KataNet.KNOCKDOWN -> MOVES.remove(entity);
            case KataNet.STRIKE -> {
                int id = a & 0xFF;
                if (id < Fighting.MOVES.length) {
                    Move m = Fighting.MOVES[id];
                    int st = STANCES.getOrDefault(entity, Fighting.TR);
                    MOVES.put(entity, new Act(m, st, ((a >> 9) & 0x7F) / 100f, System.nanoTime()));
                    if (m.turns()) SPIN.put(entity, (a & 1 << 8) != 0 ? 1 : -1);
                }
                STANCE_AT.put(entity, System.nanoTime());
            }
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
    }

    // ---- what the body shows ----

    private static boolean mine(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && p.getId() == mc.player.getId();
    }

    /** Is this player fighting (guard up, a strike going, or squared up to someone)? */
    private static boolean engaged(PlayerEntity p) {
        if (mine(p)) return guard || cur != null || charging || target >= 0 || staggered();
        Act a = MOVES.get(p.getId());
        Long at = STANCE_AT.get(p.getId());
        return GUARDS.getOrDefault(p.getId(), false) || (a != null && secs(a.start) < a.move.length()) || (at != null && secs(at) < 4)
            || othersStaggered(p) || (PARRIES.containsKey(p.getId()) && secs(PARRIES.get(p.getId()).at) < 0.5f);
    }

    private static boolean guardUp(PlayerEntity p) {
        return mine(p) ? guard : GUARDS.getOrDefault(p.getId(), false);
    }

    /**
     * How far the body is turned from facing the opponent (degrees): a fighter's stance with a shoulder
     * forward, or turned right round with the back to them. Eases there, fast during a strike; a
     * spinning strike goes the long way round, its side leading.
     */
    public static float bodyTurn(PlayerEntity p, float dt) {
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
                float j = jl / 0.22f;
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
        if (mine(p)) {
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
            if (a == null || secs(a.start) >= a.move.length()) return;
            m = a.move;
            st = a.stance;
            s = secs(a.start);
            power = a.power;
        }
        if (m == null) return;

        // Which hit of the move is going (a combo throws several), and how far out it is: 0 at the guard,
        // 1 at full extension. Speeding up all the way into contact, a beat held there, then drawn back.
        int hit = m.hits() > 1 && m.interval() > 0 ? Math.min(m.hits() - 1, (int) (s / m.interval())) : 0;
        float u = s - hit * m.interval();
        float end = hit < m.hits() - 1 ? m.interval() : m.length() - hit * m.interval();
        float c = m.contact(), hold = m.heavy() ? 0.07f : 0.04f;
        float k;
        if (wind > 0) k = -0.25f * ease(wind);
        else if (m.turns() && u < c) k = MathHelper.clamp(u / c, 0, 1); // steady through the spin, fully out at contact
        else if (u < c) {
            float x = u / c;
            k = x * x * (1.4f - 0.4f * x);
        } else if (u < c + hold) k = 1;
        else k = 1 - ease((u - c - hold) / Math.max(0.04f, end - c - hold));
        boolean right = Fighting.rightLimb(m, st, hit);
        float out_ = right ? -1 : 1; // the limb's outside (model x: right is negative)
        float mass = m.heavy() ? 0.6f + 0.8f * power : 1;
        float sign = right ? -1 : 1; // a right-side strike turns the chest to the left

        Vec3d pos = p.getLerpedPos(td);
        float bodyYaw = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
        Vector3f aim = Animator.toModel(aimPoint(p, td, m), pos, bodyYaw);

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
        out.set(Bone.CHEST, Pose.YAW, out.get(Bone.CHEST, Pose.YAW) + sign * twist * k);
        out.set(Bone.ROOT, Pose.YAW, out.get(Bone.ROOT, Pose.YAW) + sign * twist * 0.4f * k);
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
            limbNow = Ragdoll.toWorld(Ragdoll.joints(out)[right ? Ragdoll.RH : Ragdoll.LH], pos, bodyYaw);
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
    public static void react(LivingEntity e, Pose out) {
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
        double h = switch (m.path()) {
            case PALM -> -0.45;
            case FRONT_KICK, SIDE_KICK, BACK_KICK, KNEE, DROPKICK -> -0.75;
            case LOW_KICK -> -1.25;
            case ROUNDHOUSE -> -0.35;
            default -> -0.2;
        };
        return eye.add(look.multiply(m.leg() ? 1.1 : 1.0)).add(0, h, 0);
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
        return s;
    }

    static void send(int kind, int a, int b) {
        if (ClientPlayNetworking.canSend(KataNet.Fight.ID)) ClientPlayNetworking.send(new KataNet.Fight(kind, a, b));
    }
}
