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
import net.pglol.kata.KataNet;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.HashMap;
import java.util.Map;

/**
 * Fists: left click punches, right click guards, the lock-on key (R) locks onto an opponent.
 *
 * Locked on, the camera holds them and the stance diamond shows at your feet: which quadrant your body
 * is in relative to them (top: chest to them, bottom: back to them). The stance picks the punch (see
 * Fighting) and each punch leaves you in a new one. While guarding, a flick of the mouse turns you into
 * another quadrant. Guarding blocks from everywhere. Top stances walk in faster, bottom ones back off faster.
 */
public final class Fight {
    private Fight() {}

    // ---- your own state ----
    static int target = -1;
    static int stance = Fighting.TR;
    static boolean guard;
    private static boolean sentGuard;
    static int move = -1;
    private static long moveStart;
    private static boolean hitSent, queued;
    private static double flickX, flickY;
    /** A blocked hit: a short jolt through the guard (seconds left). */
    private static float jolt;

    // ---- everyone else (from the server) ----
    private record Act(int move, long start) { }
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

    /** Is the local player fighting bare-handed right now (mouse buttons are Kata's)? */
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
            move = -1;
            guard = sentGuard = false;
            MOVES.clear();
            GUARDS.clear();
            STANCES.clear();
            TURN.clear();
            return;
        }
        while (KataClient.lockKey.wasPressed()) {
            if (target >= 0) target = -1;
            else target = pick(p, 14, 60);
        }
        Entity t = targetEntity(mc);
        if (target >= 0 && (t == null || !t.isAlive() || t.squaredDistanceTo(p) > 20 * 20)) target = -1;

        boolean free = fists(p) && mc.currentScreen == null;
        guard = free && mc.options.useKey.isPressed() && move < 0;
        // Raising a guard with nobody locked: lock onto whoever's in front.
        if (guard && target < 0) target = pick(p, 8, 50);
        if (guard != sentGuard) {
            sentGuard = guard;
            send(KataNet.GUARD, guard ? 1 : 0, 0);
        }
        if (jolt > 0) jolt = Math.max(0, jolt - 0.05f);

        // The punch in progress: contact, then ready again (and the next one if it was pressed early).
        if (move >= 0) {
            float s = secs(moveStart);
            if (!hitSent && s >= Fighting.CONTACT[move]) {
                hitSent = true;
                send(KataNet.STRIKE, move, struck(mc, p));
            }
            if (s >= Fighting.LENGTH[move]) {
                move = -1;
                if (queued && free) {
                    queued = false;
                    begin(p);
                }
                queued = false;
            }
        }

        // Guarding and locked on: a flick of the mouse turns you into another quadrant.
        if (guard && target >= 0) {
            double mag = Math.hypot(flickX, flickY);
            if (mag > 35) {
                boolean top = Fighting.top(stance), right = stance == Fighting.TR || stance == Fighting.BR;
                if (Math.abs(flickX) > mag * 0.4) right = flickX > 0;
                if (Math.abs(flickY) > mag * 0.4) top = flickY < 0;
                setStance(top ? (right ? Fighting.TR : Fighting.TL) : (right ? Fighting.BR : Fighting.BL));
                flickX = flickY = 0;
            }
        }
        flickX *= 0.6;
        flickY *= 0.6;
    }

    /** Left click. True when Kata handled it (bare fists). */
    public static boolean attack() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || !fists(p)) return false;
        if (guard) return true; // hands are up: let go of guard to strike
        if (move >= 0) {
            // Pressed during a punch: it chains as soon as this one's done.
            if (secs(moveStart) > Fighting.CONTACT[move] * 0.5f) queued = true;
            return true;
        }
        begin(p);
        return true;
    }

    private static void begin(ClientPlayerEntity p) {
        move = Fighting.moveFor(stance);
        moveStart = System.nanoTime();
        hitSent = false;
        // The move carries you into its end stance (the body turns through it as you throw).
        setStance(Fighting.END[move]);
    }

    private static void setStance(int s) {
        if (s == stance) return;
        stance = s;
        send(KataNet.STANCE, s, 0);
    }

    /** What the punch hits: the locked opponent if they're in reach in front, else whoever's under the crosshair. */
    private static int struck(MinecraftClient mc, ClientPlayerEntity p) {
        Entity t = targetEntity(mc);
        if (t != null && inReach(p, t)) return t.getId();
        Entity c = mc.targetedEntity;
        if (c instanceof LivingEntity && inReach(p, c)) return c.getId();
        return -1;
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

    /** Mouse movement while locked on: the camera holds the opponent, the mouse flicks your stance. */
    public static void look(double dx, double dy) {
        if (guard) {
            flickX += dx;
            flickY += dy;
        }
    }

    /** Every frame: the camera turns to hold the opponent. */
    static void frame(MinecraftClient mc, float td, float dt) {
        ClientPlayerEntity p = mc.player;
        Entity t = targetEntity(mc);
        if (p == null || t == null) return;
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

    /** A hit landed on your guard from one side: it turns you (your stance changes side). */
    static void guardHit(int side) {
        jolt = 0.2f;
        setStance(switch (stance) {
            case Fighting.TR -> Fighting.TL;
            case Fighting.TL -> Fighting.TR;
            case Fighting.BR -> Fighting.BL;
            default -> Fighting.BR;
        });
    }

    static void remote(int entity, int kind, int a) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && entity == mc.player.getId()) {
            if (kind == KataNet.GUARD_HIT) guardHit(a);
            return;
        }
        switch (kind) {
            case KataNet.GUARD -> GUARDS.put(entity, a != 0);
            case KataNet.STRIKE -> {
                MOVES.put(entity, new Act(MathHelper.clamp(a, 0, 3), System.nanoTime()));
                STANCES.put(entity, Fighting.END[MathHelper.clamp(a, 0, 3)]);
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

    /** Is this player fighting (guard up, a punch going, or squared up to someone)? */
    private static boolean engaged(PlayerEntity p) {
        if (mine(p)) return guard || move >= 0 || target >= 0;
        Act a = MOVES.get(p.getId());
        Long at = STANCE_AT.get(p.getId());
        // Squared up for a few seconds after their last move or stance change.
        return GUARDS.getOrDefault(p.getId(), false) || (a != null && secs(a.start) < Fighting.LENGTH[a.move]) || (at != null && secs(at) < 4);
    }

    /**
     * How far the body is turned from facing the opponent (degrees): a fighter's stance, the lead
     * shoulder forward, or turned right round with the back to them. Eases there, quickly during a move
     * (the spin of a spinning strike).
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
        float cur = TURN.getOrDefault(p.getId(), 0f);
        boolean busy = mine(p) ? move >= 0 : MOVES.containsKey(p.getId());
        float k = 1 - (float) Math.exp(-dt * (busy ? 16 : 9));
        cur += (want - cur) * k; // not wrapped: a back stance is reached by turning, not snapping round
        TURN.put(p.getId(), cur);
        if (TURN.size() > 256) TURN.clear();
        return cur;
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
     * The punch in progress, as what it adds to the guard (null if none): out fast and snapping to full
     * extension at contact, held a beat, then back. Added after the body's springs so it lands crisp.
     */
    public static Pose strikeDelta(PlayerEntity p) {
        int mv;
        float s;
        if (mine(p)) {
            mv = move;
            s = move >= 0 ? secs(moveStart) : 0;
        } else {
            Act a = MOVES.get(p.getId());
            mv = a != null && secs(a.start) < Fighting.LENGTH[a.move] ? a.move : -1;
            s = a != null ? secs(a.start) : 0;
        }
        if (mv < 0) return null;
        float c = Fighting.CONTACT[mv], len = Fighting.LENGTH[mv];
        float k;
        if (s < c) {
            float u = s / c;
            k = 1 - (1 - u) * (1 - u) * (1 - u);
        } else if (s < c + 0.05f) k = 1;
        else k = 1 - ease((s - c - 0.05f) / Math.max(0.05f, len - c - 0.05f));
        Pose full = strike(mv), g = Presets.GUARD;
        Pose d = new Pose();
        for (Bone b : Bone.values()) for (int a = 0; a < 3; a++) d.set(b, a, (full.get(b, a) - g.get(b, a)) * k);
        for (int a = 0; a < 3; a++) d.offset[a] = (full.offset[a] - g.offset[a]) * k;
        return d;
    }

    /** Each move at full extension. */
    private static Pose strike(int mv) {
        Pose o = Presets.GUARD.copy();
        switch (mv) {
            case Fighting.JAB -> {
                // Lead (left) hand straight out, shoulder turned in behind it.
                o.set(Bone.LEFT_ARM, -88, -6, 0).set(Bone.LEFT_FOREARM, 6, 0, 0);
                o.set(Bone.CHEST, 4, 22, 0);
                o.offset[2] = -1.5f;
            }
            case Fighting.CROSS -> {
                // Rear (right) hand across the body, hips and chest driving round behind it.
                o.set(Bone.RIGHT_ARM, -90, 8, 0).set(Bone.RIGHT_FOREARM, 4, 0, 0);
                o.set(Bone.CHEST, 6, -32, 0);
                o.set(Bone.ROOT, 0, -10, 0);
                o.offset[2] = -2.5f;
            }
            case Fighting.BACKFIST -> {
                // Spinning round, the right arm whips out flat at head height.
                o.set(Bone.RIGHT_ARM, -85, 0, 55).set(Bone.RIGHT_FOREARM, 15, 0, 0);
                o.set(Bone.CHEST, 0, -25, 0);
            }
            case Fighting.BACK_ELBOW -> {
                // Spinning round, the left elbow leads, the hand tucked to the chest.
                o.set(Bone.LEFT_ARM, -70, 30, -40).set(Bone.LEFT_FOREARM, 135, 0, 0);
                o.set(Bone.CHEST, 0, 28, 0);
            }
            default -> { }
        }
        return o;
    }

    private static float ease(float x) {
        x = MathHelper.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }

    /**
     * Footwork while locked on: in a top stance you walk in faster and back off slower; in a bottom
     * stance it's the other way round. Guarding slows you down.
     */
    public static float speedScale(PlayerEntity p) {
        if (!mine(p) || target < 0) return 1;
        ClientPlayerEntity c = (ClientPlayerEntity) p;
        float f = c.input.movementForward;
        float s = 1;
        if (f > 0.1f) s = Fighting.top(stance) ? 1.15f : 0.85f;
        else if (f < -0.1f) s = Fighting.top(stance) ? 0.85f : 1.15f;
        if (guard) s *= 0.75f;
        return s;
    }

    static void send(int kind, int a, int b) {
        if (ClientPlayNetworking.canSend(KataNet.Fight.ID)) ClientPlayNetworking.send(new KataNet.Fight(kind, a, b));
    }
}
