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
 *  - left click: a quick click throws a light; holding winds up a heavy, thrown on release (harder
 *    the longer it's held, breaking guard at full charge)
 *  - right click (held): guard
 *  - R: lock on (the camera holds them; the mouse turns your stance instead)
 *
 * Locked on, a distinct drag of the mouse towards a quadrant turns you into it. The stance diamond at
 * your feet shows which one you're in.
 *
 * Strikes are thrown along a real path for the fist (straight, hooking, over the top, spinning),
 * driven by the hips and chest and a little step of the body behind them. They speed up into contact
 * and hold a beat when they land.
 */
public final class Fight {
    private Fight() {}

    // ---- your own state ----
    static int target = -1;
    static int stance = Fighting.TR;
    static boolean guard;
    private static boolean sentGuard;
    // The left button: pressed, and since when; winding up a heavy, and since when.
    private static boolean pressing, charging;
    private static long pressAt, chargeAt;
    // The strike in progress.
    private static Move cur;
    private static boolean curRight, hitSent;
    private static float curPower = 1;
    private static long curStart;
    private static boolean pendingLight;
    private static int chain;
    private static long lastEnd;
    private static double flickX, flickY;
    private static float jolt;
    private static long brokenUntil;
    private static int lastHurt;

    // ---- everyone else (from the server) ----
    private record Act(Move move, boolean right, float power, long start) { }
    /** Spinning: +1 turning right, -1 left (the striking arm's side leads), 0 not spinning. */
    private static final Map<Integer, Integer> SPIN = new HashMap<>();
    private static final Map<Integer, Act> MOVES = new HashMap<>();
    private static final Map<Integer, Boolean> GUARDS = new HashMap<>();
    private static final Map<Integer, Integer> STANCES = new HashMap<>();
    private static final Map<Integer, Long> STANCE_AT = new HashMap<>();
    /** The turn each body is showing (eases to its stance's). */
    private static final Map<Integer, Float> TURN = new HashMap<>();

    public static boolean locked() {
        return target >= 0;
    }

    public static int stance() {
        return stance;
    }

    public static Entity targetEntity(MinecraftClient mc) {
        return mc.world == null || target < 0 ? null : mc.world.getEntityById(target);
    }

    /** Is the local player fighting bare-handed right now (the mouse buttons are Kata's)? */
    public static boolean bareHands() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        return p != null && fists(p);
    }

    /** Bare fists, on your feet and free to fight. */
    static boolean fists(ClientPlayerEntity p) {
        Parkour.Mode m = Parkour.view(p).mode();
        return p.getMainHandStack().isEmpty() && !p.isSpectator() && (m == Parkour.Mode.NONE || m == Parkour.Mode.LAND);
    }

    private static float secs(long since) {
        return (System.nanoTime() - since) / 1e9f;
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
            return;
        }
        while (KataClient.lockKey.wasPressed()) target = target >= 0 ? -1 : pick(p, 14, 60);
        Entity t = targetEntity(mc);
        if (target >= 0 && (t == null || !t.isAlive() || t.squaredDistanceTo(p) > 20 * 20)) target = -1;

        boolean free = fists(p) && mc.currentScreen == null;
        guard = free && mc.options.useKey.isPressed() && cur == null && !charging && System.nanoTime() > brokenUntil;
        if (guard && target < 0) target = pick(p, 8, 50); // raising a guard locks onto whoever's in front
        if (guard != sentGuard) {
            sentGuard = guard;
            send(KataNet.GUARD, guard ? 1 : 0, 0);
        }
        if (jolt > 0) jolt = Math.max(0, jolt - 0.05f);
        // Hit while winding up: the heavy is gone.
        if (p.hurtTime > lastHurt && charging) {
            charging = pressing = false;
        }
        lastHurt = p.hurtTime;
        if (!free) {
            pressing = charging = false;
        }
    }

    /** Every frame: input timing (click or hold), the strike's progress, the camera on the opponent. */
    static void frame(MinecraftClient mc, float td, float dt) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        // The left button: released quickly, a light; held, a heavy winding up until it's let go.
        if (pressing) {
            boolean held = mc.options.attackKey.isPressed();
            if (!held) {
                pressing = false;
                if (charging) {
                    charging = false;
                    begin(p, Deck.pick(stance, true, 0), Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE));
                } else light(p);
            } else if (!charging && cur == null && secs(pressAt) >= Fighting.HEAVY_AFTER) {
                charging = true;
                chargeAt = System.nanoTime();
            }
        }
        // The strike in progress: contact (and a beat held if it lands), then done.
        if (cur != null) {
            float s = secs(curStart);
            int early = -1;
            // On its way out, the fist runs into whoever's in its path: the first body it touches takes it.
            if (!hitSent && s >= cur.contact() * 0.4f && fistPrev != null && fistNow != null && fistPrev.squaredDistanceTo(fistNow) > 1e-6) {
                Hitbox.Contact touch = Hitbox.sweep(nearby(p), td, fistPrev, fistNow, 0.07);
                if (touch != null) {
                    lastPart = touch.hit().part();
                    early = touch.entity().getId();
                }
            }
            if (!hitSent && (early >= 0 || s >= cur.contact())) {
                hitSent = true;
                int hit = early >= 0 ? early : struck(mc, p);
                int packed = Fighting.id(cur) | (curRight ? 1 << 8 : 0) | (Math.round(curPower * 100) << 9) | (lastPart.ordinal() << 16);
                send(KataNet.STRIKE, packed, hit);
                // It lands: the strike holds still for a moment (the impact), longer for a heavy.
                if (hit >= 0) curStart += (long) ((cur.heavy() ? 0.09 : 0.05) * 1e9);
            }
            if (s >= cur.length()) {
                stanceTo(Fighting.end(cur, stance));
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

    /** Left button pressed. True when Kata handles it (bare fists). */
    public static boolean attack() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || !fists(p)) return false;
        if (guard) return true; // hands are up: let go of guard to strike
        pressing = true;
        pressAt = System.nanoTime();
        return true;
    }

    private static void light(ClientPlayerEntity p) {
        if (cur != null) {
            // Pressed during a strike: the next in the chain follows straight on.
            if (secs(curStart) > cur.contact() * 0.5f) pendingLight = true;
            return;
        }
        if (secs(lastEnd) > 0.55f) chain = 0;
        begin(p, Deck.pick(stance, false, chain), 1);
        chain++;
    }

    private static void begin(ClientPlayerEntity p, Move m, float power) {
        cur = m;
        curRight = Fighting.rightHand(m, stance);
        curPower = m.heavy() ? Math.max(0.3f, power) : 1;
        curStart = System.nanoTime();
        hitSent = false;
        fistNow = fistPrev = null;
        if (m.heavy()) chain = 0;
        // A spinning strike turns you as you throw it, the striking arm's side leading the turn.
        if (m.turns()) {
            SPIN.put(p.getId(), curRight ? 1 : -1);
            stanceTo(Fighting.end(m, stance));
        }
        // The body steps in behind it.
        Vec3d f = Vec3d.fromPolar(0, p.getYaw());
        double step = m.heavy() ? 0.06 + 0.1 * curPower : 0.035;
        if (p.isOnGround()) p.addVelocity(f.x * step, 0, f.z * step);
    }

    private static void stanceTo(int s) {
        if (s == stance) return;
        stance = s;
        send(KataNet.STANCE, s, 0);
    }

    /** Where the striking fist is in the world, this frame and last (for what it runs into on the way). */
    private static Vec3d fistNow, fistPrev;

    /** The body part the last strike landed on. */
    private static Fighting.Part lastPart = Fighting.Part.TORSO;

    /**
     * What the strike hits: traced along its line from the shoulder into the locked opponent (or
     * whoever's under the crosshair), landing on the first limb it meets. -1 for a miss.
     */
    private static int struck(MinecraftClient mc, ClientPlayerEntity p) {
        Entity t = targetEntity(mc);
        if (t == null && mc.targetedEntity instanceof LivingEntity c) t = c;
        Vec3d want = t != null ? wanted(p, 1, cur, t) : p.getCameraPosVec(1).add(p.getRotationVec(1).multiply(2)).add(0, -0.2, 0);
        Hitbox.Contact h = Hitbox.traceAll(nearby(p), 1, shoulder(p, 1), want, Fighting.REACH);
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

    /** Roughly where the striking shoulder is (world). */
    private static Vec3d shoulder(PlayerEntity p, float td) {
        return p.getCameraPosVec(td).add(0, -0.3, 0);
    }

    /** Where a strike wants to go on its target: the head, the body for a palm. */
    private static Vec3d wanted(PlayerEntity p, float td, Move m, Entity t) {
        double h = m != null && m.path() == Path.PALM ? 0.62 : m != null && m.path() == Path.UPPER ? 0.8 : 0.88;
        return t.getLerpedPos(td).add(0, t.getHeight() * h, 0);
    }

    private static boolean inReach(ClientPlayerEntity p, Entity t) {
        Vec3d eye = p.getEyePos();
        var b = t.getBoundingBox();
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, b.minX, b.maxX), MathHelper.clamp(eye.y, b.minY, b.maxY), MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        return eye.distanceTo(near) <= Fighting.REACH;
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
     * Mouse movement while locked on: a distinct drag towards a quadrant turns you into it (side to
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
        int step = cur != null || secs(lastEnd) <= 0.55f ? chain : 0;
        return Deck.pick(stance, false, step);
    }

    public static Move nextHeavy() {
        return Deck.pick(stance, true, 0);
    }

    public static boolean chargingHeavy() {
        return charging;
    }

    public static float charge() {
        return charging ? Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE) : 0;
    }

    /** A hit landed on your guard: it jolts the guard. */
    static void guardHit() {
        jolt = 0.2f;
    }

    static void remote(int entity, int kind, int a) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && entity == mc.player.getId()) {
            if (kind == KataNet.GUARD_HIT) guardHit();
            if (kind == KataNet.GUARD_BROKEN) {
                // Guard smashed open: the hands are down for a moment.
                jolt = 0.2f;
                brokenUntil = System.nanoTime() + 700_000_000L;
            }
            return;
        }
        switch (kind) {
            case KataNet.GUARD -> GUARDS.put(entity, a != 0);
            case KataNet.STRIKE -> {
                int id = a & 0xFF;
                if (id < Fighting.MOVES.length) {
                    Act act = new Act(Fighting.MOVES[id], (a & 1 << 8) != 0, ((a >> 9) & 0x7F) / 100f, System.nanoTime());
                    MOVES.put(entity, act);
                    if (act.move.turns()) SPIN.put(entity, act.right ? 1 : -1);
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
    }

    // ---- what the body shows ----

    private static boolean mine(PlayerEntity p) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && p.getId() == mc.player.getId();
    }

    /** Is this player fighting (guard up, a strike going, or squared up to someone)? */
    private static boolean engaged(PlayerEntity p) {
        if (mine(p)) return guard || cur != null || charging || target >= 0;
        Act a = MOVES.get(p.getId());
        Long at = STANCE_AT.get(p.getId());
        return GUARDS.getOrDefault(p.getId(), false) || (a != null && secs(a.start) < a.move.length()) || (at != null && secs(at) < 4);
    }

    /**
     * How far the body is turned from facing the opponent (degrees): a fighter's stance with a shoulder
     * forward, or turned right round with the back to them. Eases there, fast during a strike (the
     * spin of a spinning one).
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
        boolean busy = mine(p) ? cur != null : MOVES.containsKey(p.getId());
        int spin = SPIN.getOrDefault(p.getId(), 0);
        if (busy && spin != 0) {
            // A spinning strike goes the long way round, the striking arm's side leading.
            while (spin > 0 && want < c) want += 360;
            while (spin < 0 && want > c) want -= 360;
        } else {
            SPIN.remove(p.getId());
            c = want + MathHelper.wrapDegrees(c - want); // done spinning: the same turn, without the extra lap
        }
        float k = 1 - (float) Math.exp(-dt * (busy ? 18 : 9));
        c += (want - c) * k; // not wrapped: a back stance is reached by turning, not snapping round
        TURN.put(p.getId(), c);
        if (TURN.size() > 256) TURN.clear();
        return c;
    }

    /** The guard (arms up, chest set) when fighting, or null; weight[0] says how much (0..1). */
    public static Pose pose(PlayerEntity p, float[] weight) {
        weight[0] = 0;
        if (!engaged(p)) return null;
        Pose g = Presets.GUARD.copy();
        if (mine(p) && jolt > 0) {
            // A blocked hit knocks the guard back into the face.
            float j = jolt / 0.2f;
            g.set(Bone.CHEST, Pose.PITCH, g.get(Bone.CHEST, Pose.PITCH) - 12 * j);
            g.set(Bone.RIGHT_FOREARM, Pose.PITCH, g.get(Bone.RIGHT_FOREARM, Pose.PITCH) + 15 * j);
            g.set(Bone.LEFT_FOREARM, Pose.PITCH, g.get(Bone.LEFT_FOREARM, Pose.PITCH) + 15 * j);
        }
        weight[0] = 1;
        return g;
    }

    /**
     * The strike (or the heavy's wind-up) on top of the body's springs: first the body drives it (hips
     * and chest turning through, weight forward, a lean in), then the striking arm is solved so the fist
     * travels its path from the guard to the target.
     */
    static void apply(PlayerEntity p, float td, Pose out) {
        Move m;
        boolean right;
        float s, power;
        float wind = 0;
        if (mine(p)) {
            m = cur;
            right = curRight;
            s = cur != null ? secs(curStart) : 0;
            power = curPower;
            if (charging) {
                // Winding up: the heavy from this stance, fist and weight drawn back.
                m = Deck.pick(stance, true, 0);
                right = Fighting.rightHand(m, stance);
                wind = Math.min(1, secs(chargeAt) / Fighting.FULL_CHARGE);
                s = 0;
            }
        } else {
            Act a = MOVES.get(p.getId());
            if (a == null || secs(a.start) >= a.move.length()) return;
            m = a.move;
            right = a.right;
            s = secs(a.start);
            power = a.power;
        }
        if (m == null) return;

        // How far out the strike is (0 guard, 1 full extension): speeding up all the way into contact,
        // a beat held there, then drawn back.
        float c = m.contact(), hold = m.heavy() ? 0.07f : 0.04f;
        float k;
        if (wind > 0) k = -0.25f * ease(wind);
        else if (s < c) {
            float u = s / c;
            k = u * u * (1.4f - 0.4f * u);
        } else if (s < c + hold) k = 1;
        else k = 1 - ease((s - c - hold) / Math.max(0.05f, m.length() - c - hold));

        // The body behind it.
        float mass = m.heavy() ? 0.6f + 0.8f * power : 1;
        float twist = (switch (m.path()) {
            case STRAIGHT -> m.lead() ? 12 : 28;
            case HOOK -> 30;
            case UPPER -> 22;
            case OVERHAND, PALM -> 32;
            case BACKFIST, ELBOW -> 0; // the spin itself turns the body (see bodyTurn)
        }) * mass;
        float sign = right ? -1 : 1; // a right-hand strike turns the chest to the left
        out.set(Bone.CHEST, Pose.YAW, out.get(Bone.CHEST, Pose.YAW) + sign * twist * k);
        out.set(Bone.ROOT, Pose.YAW, out.get(Bone.ROOT, Pose.YAW) + sign * twist * 0.4f * k);
        out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + (m.heavy() ? 9 : 5) * k);
        out.offset[2] -= (m.heavy() ? 3.5f * mass : 2f) * k;
        if (m.heavy()) out.offset[1] += 0.9f * Math.abs(k);

        // The fist's path, from where the guard holds it to the target.
        Bone arm = right ? Bone.RIGHT_ARM : Bone.LEFT_ARM, fore = right ? Bone.RIGHT_FOREARM : Bone.LEFT_FOREARM;
        Vector3f shoulder = new Vector3f(right ? -5 : 5, 2, 0);
        Vector3f[] j = Ragdoll.joints(out);
        Vector3f guardFist = j[right ? Ragdoll.RH : Ragdoll.LH];
        Vec3d pos = p.getLerpedPos(td);
        float bodyYaw = MathHelper.lerpAngleDegrees(td, p.prevBodyYaw, p.bodyYaw);
        Vector3f aim = Animator.toModel(aimPoint(p, td, m), pos, bodyYaw);
        Vector3f fist;
        float out_ = right ? -1 : 1; // the arm's outside (model x: right is negative)
        if (m.path() == Path.BACKFIST) {
            // The arm swings out wide to its side and the spin carries it round into them, back of the fist first.
            fist = new Vector3f(guardFist).lerp(new Vector3f(out_ * 15.5f, 3, -3), Math.max(0, k));
        } else if (m.path() == Path.ELBOW) {
            // The elbow comes up level and out to the side, the fist tucked at the chest; the spin drives it in.
            fist = new Vector3f(guardFist).lerp(new Vector3f(out_ * 2.5f, 2.5f, -4.5f), Math.max(0, k));
        } else if (k < 0) {
            // Drawn back, away from the target.
            Vector3f back = new Vector3f(guardFist).sub(aim).normalize().mul(6 * -k);
            fist = new Vector3f(guardFist).add(back);
        } else {
            fist = new Vector3f(guardFist).lerp(aim, k);
            float arc = MathHelper.sin(MathHelper.PI * k);
            switch (m.path()) {
                case HOOK -> fist.add(out_ * 6 * arc, -1 * arc, 0);
                case OVERHAND -> fist.add(out_ * 2 * arc, -5 * arc, 0);
                case UPPER -> fist.add(-out_ * 1 * arc, 5 * arc, -1 * arc); // dips, then drives up through the line
                default -> { }
            }
        }
        Matrix4f frame = Animator.armFrame(out).invert();
        Vector3f pole = m.path() == Path.ELBOW || m.path() == Path.HOOK
            ? new Vector3f(out_, -0.15f, 0.25f)           // elbow up and out to the side
            : m.path() == Path.BACKFIST ? new Vector3f(out_ * 0.3f, 0.2f, 1) // arm out straight, elbow back
            : new Vector3f(out_ * 0.3f, 1, 0.45f);        // elbow down, behind the punch
        Animator.reach(out, arm, fore, shoulder, fist, frame, 1, pole);
        if (mine(p) && cur != null) {
            Vector3f hand = Ragdoll.joints(out)[right ? Ragdoll.RH : Ragdoll.LH];
            fistPrev = fistNow;
            fistNow = Ragdoll.toWorld(hand, pos, bodyYaw);
        }
    }

    /**
     * Where a strike is aimed: on the opponent, the point where its line first meets their body (a guard's
     * forearm, say), so the fist lands on it and stops there. With nobody locked, straight out ahead.
     */
    private static Vec3d aimPoint(PlayerEntity p, float td, Move m) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity t = mine(p) ? targetEntity(mc) : null;
        Vec3d eye = p.getCameraPosVec(td);
        if (t != null) {
            Vec3d want = wanted(p, td, m, t);
            Hitbox.Hit h = Hitbox.trace(t, td, shoulder(p, td), want, Fighting.REACH);
            Vec3d at = h != null ? h.point() : want;
            Vec3d d = at.subtract(eye);
            return d.length() > 1.2 ? eye.add(d.normalize().multiply(1.2)) : at; // past full reach the arm just points at it
        }
        Vec3d look = p.getRotationVec(td);
        return eye.add(look.multiply(1.0)).add(0, m.path() == Path.PALM ? -0.45 : -0.2, 0);
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /**
     * Footwork while locked on: in a top stance you walk in faster and back off slower, in a bottom
     * stance the other way round. Guarding or winding up a heavy slows you down.
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
        return s;
    }

    static void send(int kind, int a, int b) {
        if (ClientPlayNetworking.canSend(KataNet.Fight.ID)) ClientPlayNetworking.send(new KataNet.Fight(kind, a, b));
    }
}
