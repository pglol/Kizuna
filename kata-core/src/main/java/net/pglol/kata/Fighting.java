package net.pglol.kata;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Hand to hand: fighting styles, their moves, guarding and stances. The numbers are shared by both
 * sides; the server half (guard state, strikes, blocking) lives here too.
 *
 * Stances are the body's orientation to the opponent, in four quadrants:
 *  - top-right and top-left: chest towards them
 *  - bottom-right and bottom-left: back turned
 * A stance changes when you drag the mouse that way while locked on, and a spinning strike from a
 * back stance turns you round to face them.
 *
 * A style (Taijutsu first) gives each quadrant a three-hit light chain (quick clicks) and a heavy
 * alternative (hold to charge, release to throw).
 * Guarding blocks from every direction; a fully charged heavy breaks it.
 */
public final class Fighting {
    private Fighting() {}

    // Stances.
    public static final int TR = 0, TL = 1, BR = 2, BL = 3;

    public static boolean top(int stance) {
        return stance == TR || stance == TL;
    }

    /** The side a stance leads with: top-right leads with the left hand (orthodox), top-left with the right. */
    public static boolean rightSide(int stance) {
        return stance == TR || stance == BR;
    }

    // ---- moves and styles ----

    /** How a strike travels (and which limb throws it). */
    public enum Path {
        STRAIGHT, HOOK, UPPER, OVERHAND, PALM, BACKFIST, ELBOW, SUPERMAN,
        FRONT_KICK, ROUNDHOUSE, SIDE_KICK, BACK_KICK, LOW_KICK, KNEE, DROPKICK;

        public boolean leg() {
            return ordinal() >= FRONT_KICK.ordinal();
        }
    }

    /** Where a move is thrown from: the top stances, the back stances, or in the air. */
    public enum From { FRONT, BACK, AIR }

    /** A light's place in a chain: openers start it (fast), links carry it, finishers end it (slower, harder). */
    public enum Role { OPENER, LINK, FINISHER }

    /**
     * One move of a style.
     *  - id: style:move, for example "taijutsu:jab"
     *  - lead: thrown with the lead limb (else the rear)
     *  - heavy: a charged heavy (the heavy slot), else a light for the chain
     *  - from: top stances, back stances, or the air
     *  - spin: turns you round as it's thrown (a back stance becomes the top one on the same side)
     *  - contact and length: seconds (from the press for a light, from the release for a heavy)
     *  - damage: in half-hearts, per hit
     *  - push: knockback; lift: how much of it goes up (an uppercut launches)
     *  - hits: how many strikes the move throws, interval apart; alternate: switching limbs each hit
     *  - knockdown: lands hard enough to put them down (for a heavy, only near full charge)
     */
    public record Move(String id, String name, Path path, boolean lead, boolean heavy, From from, boolean spin,
                       float contact, float length, float damage, float push, float lift,
                       int hits, float interval, boolean alternate, boolean knockdown, Role role) {
        public boolean turns() {
            return spin;
        }

        public boolean leg() {
            return path.leg();
        }

        /** When hit i (0-based) lands, seconds from the start. */
        public float contactOf(int i) {
            return contact + i * interval;
        }

        /**
         * Can it go in this slot of this row? The heavy slot takes heavies. The chain keeps a flow: slot
         * 1 takes openers (the fastest), slot 2 openers or links, slot 3 links or finishers (slower,
         * harder).
         */
        public boolean fits(int row, int slot) {
            From want = row == AIR ? From.AIR : top(row) ? From.FRONT : From.BACK;
            if (heavy != (slot == HEAVY_SLOT) || from != want) return false;
            if (heavy) return true;
            return switch (slot) {
                case 0 -> role == Role.OPENER;
                case 1 -> role != Role.FINISHER;
                default -> role != Role.OPENER || from != From.FRONT;
            };
        }
    }

    /** A deck has five rows of four slots (three lights, the chain, then the heavy): one row per stance, and the air. */
    public static final int SLOTS = 4, HEAVY_SLOT = 3, AIR = 4, ROWS = 5;

    /**
     * A fighting style: everything it can throw (its move tree, in the order it's listed) and the deck
     * a new fighter starts with (per stance, indexes into the pool; -1 empty).
     */
    public record Style(String id, String name, String blurb, int color, Move[] pool, int[][] starter) {
        public Move move(int i) {
            return i >= 0 && i < pool.length ? pool[i] : null;
        }

        public int index(String moveId) {
            for (int i = 0; i < pool.length; i++) if (pool[i].id().equals(moveId) || pool[i].id().endsWith(":" + moveId)) return i;
            return -1;
        }
    }

    /** Every style, loaded from the mod's kata_core/styles/ files (see index.json there). */
    public static final Style[] STYLES;
    /** Every move of every style, numbered in style order (the same on both sides, for the network). */
    public static final Move[] MOVES;

    static {
        java.util.List<Style> styles = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonObject index = json("index.json");
            for (com.google.gson.JsonElement n : index.getAsJsonArray("styles")) styles.add(loadStyle(json(n.getAsString() + ".json")));
        } catch (Exception e) {
            KataCore.LOG.error("Couldn't load the fighting styles", e);
        }
        if (styles.isEmpty()) {
            // A fallback so fists always work: one jab.
            Move jab = new Move("basic:jab", "Jab", Path.STRAIGHT, true, false, From.FRONT, false, 0.08f, 0.25f, 2, 0.2f, 0, 1, 0, false, false, Role.OPENER);
            styles.add(new Style("basic", "Basic", "", 0xD8403E, new Move[] {jab}, new int[][] {{0, -1, -1, -1}, {0, -1, -1, -1}, {-1, -1, -1, -1}, {-1, -1, -1, -1}, {-1, -1, -1, -1}}));
        }
        STYLES = styles.toArray(new Style[0]);
        java.util.List<Move> all = new java.util.ArrayList<>();
        for (Style st : STYLES) all.addAll(java.util.List.of(st.pool()));
        MOVES = all.toArray(new Move[0]);
    }

    private static com.google.gson.JsonObject json(String name) throws java.io.IOException {
        try (var in = Fighting.class.getResourceAsStream("/kata_core/styles/" + name)) {
            if (in == null) throw new java.io.FileNotFoundException(name);
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static Style loadStyle(com.google.gson.JsonObject o) {
        String sid = o.get("id").getAsString();
        java.util.List<Move> pool = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement e : o.getAsJsonArray("moves")) {
            com.google.gson.JsonObject m = e.getAsJsonObject();
            String from = m.has("from") ? m.get("from").getAsString() : "front";
            pool.add(new Move(sid + ":" + m.get("id").getAsString(), m.get("name").getAsString(),
                Path.valueOf(m.get("path").getAsString().toUpperCase(java.util.Locale.ROOT)),
                "lead".equals(m.get("hand").getAsString()), "heavy".equals(m.get("kind").getAsString()),
                From.valueOf(from.toUpperCase(java.util.Locale.ROOT)), m.has("spin") && m.get("spin").getAsBoolean(),
                m.get("contact").getAsFloat(), m.get("length").getAsFloat(), m.get("damage").getAsFloat(), m.get("push").getAsFloat(),
                m.has("lift") ? m.get("lift").getAsFloat() : 0,
                m.has("hits") ? m.get("hits").getAsInt() : 1, m.has("interval") ? m.get("interval").getAsFloat() : 0,
                m.has("alternate") && m.get("alternate").getAsBoolean(), m.has("knockdown") && m.get("knockdown").getAsBoolean(),
                Role.valueOf((m.has("role") ? m.get("role").getAsString() : "link").toUpperCase(java.util.Locale.ROOT))));
        }
        Style partial = new Style(sid, o.get("name").getAsString(), o.has("blurb") ? o.get("blurb").getAsString() : "",
            o.has("color") ? Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) : 0xD8403E, pool.toArray(new Move[0]), null);
        String[] quads = {"top_right", "top_left", "back_right", "back_left", "air"};
        int[][] starter = new int[ROWS][SLOTS];
        com.google.gson.JsonObject st = o.getAsJsonObject("starter");
        for (int q = 0; q < ROWS; q++) {
            java.util.Arrays.fill(starter[q], -1);
            if (!st.has(quads[q])) continue;
            var arr = st.getAsJsonArray(quads[q]);
            for (int i = 0; i < SLOTS && i < arr.size(); i++) if (!arr.get(i).isJsonNull()) starter[q][i] = partial.index(arr.get(i).getAsString());
        }
        return new Style(sid, partial.name(), partial.blurb(), partial.color(), partial.pool(), starter);
    }

    public static Style style(String id) {
        for (Style s : STYLES) if (s.id().equals(id)) return s;
        return STYLES[0];
    }

    public static int id(Move m) {
        for (int i = 0; i < MOVES.length; i++) if (MOVES[i] == m) return i;
        return -1;
    }

    // ---- body parts (where a strike lands) ----

    /** What a strike can land on, and how much of its damage gets through there. */
    public enum Part {
        HEAD(1.25f), TORSO(1f), ARM(0.45f), LEG(0.7f);

        public final float share;

        Part(float share) {
            this.share = share;
        }
    }

    /** The limb (hand or foot) hit i of a move is thrown with from a stance: true = right. */
    public static boolean rightLimb(Move m, int stance, int hit) {
        boolean right;
        if (m.from() == From.BACK) right = rightSide(stance); // turned away: the limb on the side you're turned
        else {
            boolean leadRight = !rightSide(stance); // top-right is orthodox (left lead)
            right = m.lead() == leadRight;
        }
        return m.alternate() && (hit & 1) == 1 ? !right : right;
    }

    /** Where a move leaves you. */
    public static int end(Move m, int stance) {
        if (m.turns() && !top(stance)) return stance == BR ? TR : TL;
        return stance;
    }

    /** Reach from the eyes (blocks). */
    public static final double REACH = 3.0;
    /** A heavy held this long (seconds) is fully charged: full power, and it breaks guard. */
    public static final float FULL_CHARGE = 0.8f;
    /** Pressed longer than this, a click becomes a charged heavy. */
    public static final float HEAVY_AFTER = 0.16f;

    // ---- server ----

    private static final Map<UUID, Boolean> GUARD = new HashMap<>();
    private static final Map<UUID, Integer> STANCE = new HashMap<>();
    private static final Map<UUID, Integer> GUARD_AT = new HashMap<>();
    private static final Map<UUID, Integer> LAST = new HashMap<>();
    /** While a strike is being dealt: it breaks guard; what it is (for the defender's parry). */
    private static boolean breaking, chip;
    private static Move striking;

    /** A guard raised this recently (ticks) when a hit arrives, facing it, is a perfect block: a parry. */
    public static final int PERFECT = 3;

    public static boolean guarding(Entity e) {
        if (e instanceof Dummy d) return d.guarding();
        return e instanceof ServerPlayerEntity p && GUARD.getOrDefault(p.getUuid(), false);
    }

    private static int guardAge(Entity e) {
        if (e instanceof Dummy d) return d.guardAge();
        if (!(e instanceof ServerPlayerEntity p)) return 999;
        Integer at = GUARD_AT.get(p.getUuid());
        return at == null ? 999 : p.getServer().getTicks() - at;
    }

    static void register() {
        // Someone coming into view gets the fighter's state as it is now (guard, stance), not only changes.
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register((e, viewer) -> {
            if (!(e instanceof ServerPlayerEntity p) || !ServerPlayNetworking.canSend(viewer, KataNet.FightOf.ID)) return;
            ServerPlayNetworking.send(viewer, new KataNet.FightOf(p.getId(), KataNet.GUARD, GUARD.getOrDefault(p.getUuid(), false) ? 1 : 0));
            Integer st = STANCE.get(p.getUuid());
            if (st != null) ServerPlayNetworking.send(viewer, new KataNet.FightOf(p.getId(), KataNet.STANCE, st));
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (chip || !guarding(victim)) return true;
            Entity by = source.getAttacker();
            if (by == null || source.isIn(DamageTypeTags.BYPASSES_SHIELD)) return true;
            var w = victim.getWorld();
            Vec3d to = by.getPos().subtract(victim.getPos());
            double len = Math.max(1e-3, Math.hypot(to.x, to.z));
            // A full heavy smashes the guard open and lands.
            if (breaking) {
                breakGuard(victim);
                w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.9f, 1.1f);
                return true;
            }
            Vec3d face = Vec3d.fromPolar(0, victim.getYaw());
            boolean front = (to.x * face.x + to.z * face.z) / len >= 0.34; // within about 70 degrees of where they face
            double rx = -Math.cos(Math.toRadians(victim.getYaw())), rz = -Math.sin(Math.toRadians(victim.getYaw()));
            int side = to.x * rx + to.z * rz >= 0 ? 1 : -1;
            // Raised right as it arrives, facing it: a perfect block.
            boolean perfect = front && guardAge(victim) <= PERFECT && (!(victim instanceof Dummy) || w.random.nextFloat() < 0.3f);
            if (perfect) {
                parry(victim, by, striking);
                return false;
            }
            w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.8f, 1.2f + w.random.nextFloat() * 0.2f);
            tell(victim, KataNet.GUARD_HIT, side);
            if (victim instanceof ServerPlayerEntity vp) relayAround(vp, KataNet.BLOCKED, side > 0 ? 1 : 0); // everyone sees the block
            if (victim instanceof Dummy d) d.blocked(side);
            victim.takeKnockback(0.15 + amount * 0.03, to.x / len, to.z / len);
            victim.velocityModified = true;
            if (front) return false; // blocked the way it came: nothing gets through
            // Guarding the wrong way: some of it gets round the guard.
            chip = true;
            try {
                victim.timeUntilRegen = 0;
                victim.damage(source, amount * 0.4f);
            } finally {
                chip = false;
            }
            return false;
        });
    }

    /** A perfect block: a punch is turned aside and the attacker staggers; a kick is caught and they're thrown. */
    private static void parry(net.minecraft.entity.LivingEntity victim, Entity by, Move m) {
        var w = victim.getWorld();
        w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.7f);
        boolean kick = m != null && m.leg();
        tell(victim, KataNet.PARRY, kick ? 2 : 1);
        if (victim instanceof Dummy d) d.parried(kick);
        if (kick) {
            // Caught the foot: thrown back the way it came, off their feet.
            Vec3d away = by.getPos().subtract(victim.getPos()).multiply(1, 0, 1);
            away = away.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0, victim.getYaw()) : away.normalize();
            knockdown(by, away.multiply(0.6).add(0, 0.32, 0));
        } else stagger(by, 12);
    }

    private static void breakGuard(Entity e) {
        if (e instanceof ServerPlayerEntity p) {
            GUARD.put(p.getUuid(), false);
            tell(p, KataNet.GUARD_BROKEN, 0);
            relay(p, KataNet.GUARD, 0); // the guard is down for everyone watching too
        } else if (e instanceof Dummy d) d.guardBroken();
    }

    /** Knocked off their feet (limp) with this velocity (blocks a tick). */
    public static void knockdown(Entity e, Vec3d v) {
        if (e instanceof ServerPlayerEntity p) tell(p, KataNet.KNOCKDOWN, packVelocity(v));
        else if (e instanceof Dummy d) d.knockdown(v);
        else {
            e.setVelocity(v);
            e.velocityModified = true;
        }
    }

    /** Staggered: whatever they were throwing is gone and they can't act for a moment (ticks). */
    public static void stagger(Entity e, int ticks) {
        if (e instanceof ServerPlayerEntity p) tell(p, KataNet.STAGGER, ticks);
        else if (e instanceof Dummy d) d.stagger(ticks);
    }

    public static int packVelocity(Vec3d v) {
        int x = MathHelper.clamp((int) Math.round(v.x * 64) + 128, 0, 255), y = MathHelper.clamp((int) Math.round(v.y * 64) + 128, 0, 255), z = MathHelper.clamp((int) Math.round(v.z * 64) + 128, 0, 255);
        return x << 16 | y << 8 | z;
    }

    public static Vec3d unpackVelocity(int a) {
        return new Vec3d((((a >> 16) & 0xFF) - 128) / 64.0, (((a >> 8) & 0xFF) - 128) / 64.0, ((a & 0xFF) - 128) / 64.0);
    }

    /** What happens to a player's body in a fight goes to them and to everyone who can see them. */
    private static void tell(Entity e, int kind, int a) {
        if (e instanceof ServerPlayerEntity) relayAround(e, kind, a);
    }

    static void guard(ServerPlayerEntity p, boolean on) {
        if (on && !GUARD.getOrDefault(p.getUuid(), false)) GUARD_AT.put(p.getUuid(), p.getServer().getTicks());
        GUARD.put(p.getUuid(), on);
        if (GUARD.size() > 1024) {
            GUARD.clear();
            GUARD_AT.clear();
        }
        relay(p, KataNet.GUARD, on ? 1 : 0);
    }

    static void stance(ServerPlayerEntity p, int stance) {
        STANCE.put(p.getUuid(), MathHelper.clamp(stance, 0, 3));
        if (STANCE.size() > 1024) STANCE.clear();
        relay(p, KataNet.STANCE, MathHelper.clamp(stance, 0, 3));
    }

    /**
     * A strike is on its way (thrown, not landed yet): anyone it's aimed at can see it coming. The
     * training dummies use this to try to get their guard up in time.
     */
    static void windup(ServerPlayerEntity p, int packed, int target) {
        int id = packed & 0xFF;
        if (id >= MOVES.length) return;
        Move m = MOVES[id];
        Entity e = target >= 0 ? p.getWorld().getEntityById(target) : null;
        if (!(e instanceof Dummy)) {
            // Whoever's in front, in reach.
            Vec3d look = Vec3d.fromPolar(0, p.getYaw());
            double best = REACH + 1;
            for (Dummy d : p.getWorld().getEntitiesByClass(Dummy.class, p.getBoundingBox().expand(REACH + 1), x -> true)) {
                Vec3d to = d.getPos().subtract(p.getPos());
                double dist = to.length();
                if (dist < best && (to.x * look.x + to.z * look.z) / Math.max(1e-3, dist) > 0.4) {
                    best = dist;
                    e = d;
                }
            }
        }
        if (e instanceof Dummy d) d.anticipate(p, m, Math.round(m.contact() * 20), (packed & 1 << 8) != 0);
    }

    /**
     * A player's game saw a knocked-down dummy's body come to rest here (packed offset from where the
     * dummy is): it gets up there, not back where it was hit.
     */
    static void dummyRest(ServerPlayerEntity p, int entity, int packed, boolean resting) {
        if (!(p.getWorld().getEntityById(entity) instanceof Dummy d) || d.squaredDistanceTo(p) > 32 * 32) return;
        double dx = ((packed >> 20) << 20 >> 20) / 100.0, dz = (((packed >> 8) & 0xFFF) << 20 >> 20) / 100.0, dy = ((byte) (packed & 0xFF)) / 20.0;
        d.settleAt(d.getPos().add(dx, dy, dz), resting);
    }

    public static int packOffset(double dx, double dy, double dz) {
        int x = MathHelper.clamp((int) Math.round(dx * 100), -2047, 2047) & 0xFFF, z = MathHelper.clamp((int) Math.round(dz * 100), -2047, 2047) & 0xFFF;
        int y = MathHelper.clamp((int) Math.round(dy * 20), -127, 127) & 0xFF;
        return x << 20 | z << 8 | y;
    }

    /**
     * A strike thrown at target (an entity id, or -1 for none in reach). packed:
     *  - move id: bits 0-7
     *  - right limb: bit 8
     *  - heavy power, in percent: bits 9-15
     *  - the body part it lands on: bits 16-17
     * Checked, then landed.
     */
    static void strike(ServerPlayerEntity p, int packed, int target) {
        int id = packed & 0xFF;
        if (id >= MOVES.length || !p.isAlive() || p.isSpectator()) return;
        Move m = MOVES[id];
        float power = m.heavy() ? MathHelper.clamp(((packed >> 9) & 0x7F) / 100f, 0.3f, 1f) : 1f;
        int now = p.getServer().getTicks();
        Integer last = LAST.get(p.getUuid());
        if (last != null && now - last < 2) return; // faster than any string allows
        LAST.put(p.getUuid(), now);
        if (LAST.size() > 1024) LAST.clear();
        relay(p, KataNet.STRIKE, packed);
        if (target < 0 || guarding(p)) return;
        Entity e = p.getWorld().getEntityById(target);
        if (!(e instanceof LivingEntity t) || !t.isAlive() || t == p) return;
        // In reach (with some slack for lag) and somewhere in front.
        Vec3d eye = p.getEyePos();
        var b = t.getBoundingBox();
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, b.minX, b.maxX), MathHelper.clamp(eye.y, b.minY, b.maxY), MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        // A downed dummy's body lies wherever it flew (its box may be elsewhere): judge by distance only.
        boolean lying = t instanceof Dummy dd && dd.down() > 0;
        if (eye.distanceTo(near) > (lying ? 7 : REACH + 0.8)) return;
        Vec3d to = t.getPos().subtract(p.getPos());
        Vec3d look = Vec3d.fromPolar(0, p.getYaw());
        double len = Math.hypot(to.x, to.z);
        if (!lying && len > 0.3 && (to.x * look.x + to.z * look.z) / len < -0.2) return;
        Part part = Part.values()[Math.min(3, (packed >> 16) & 0x3)];
        land(p, t, m, power, part, look, p.getDamageSources().playerAttack(p));
    }

    /** A strike landing (from a player or a dummy): damage by part, guard, parry, knockback, launch, knockdown. */
    static void land(LivingEntity by, LivingEntity t, Move m, float power, Part part, Vec3d look, net.minecraft.entity.damage.DamageSource src) {
        float dmg = m.damage() * (m.heavy() ? 0.5f + 0.5f * power : 1) * part.share;
        t.timeUntilRegen = 0; // strings land: no invulnerability gap between hits
        breaking = m.heavy() && power >= 0.98f;
        striking = m;
        boolean hit;
        try {
            hit = t.damage(src, dmg);
        } finally {
            breaking = false;
            striking = null;
        }
        // A creative-mode player takes no damage, but still feels the hit (unless it was blocked).
        if (!hit && !(t instanceof net.minecraft.entity.player.PlayerEntity pl && pl.isCreative() && !guarding(t))) return;
        // The hit's reaction, for everyone to see: where it landed, what kind of strike, from which side.
        Vec3d from = by.getPos().subtract(t.getPos());
        double ty = Math.toRadians(t.getYaw());
        boolean fromRight = from.x * -Math.cos(ty) + from.z * -Math.sin(ty) > 0;
        int react = part.ordinal() | m.path().ordinal() << 2 | (fromRight ? 1 << 7 : 0) | (m.heavy() ? 1 << 8 : 0);
        relayAround(t, KataNet.HIT, react);
        float shove = m.push() * (m.heavy() ? 0.6f + 0.6f * power : 1);
        boolean down = m.knockdown() && (!m.heavy() || power >= 0.9f);
        if (down) {
            knockdown(t, look.multiply(shove * 0.8).add(0, 0.2 + m.lift() * 0.75, 0));
        } else {
            t.takeKnockback(shove, -look.x, -look.z);
            if (m.lift() > 0) t.addVelocity(0, m.lift() * (m.heavy() ? 0.6f + 0.6f * power : 1), 0);
            t.velocityModified = true;
        }
        t.getWorld().playSound(null, t.getX(), t.getY(), t.getZ(),
            m.heavy() || down ? SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
            SoundCategory.PLAYERS, 0.9f, (m.heavy() ? 0.75f : 1f) + t.getRandom().nextFloat() * 0.2f);
    }

    /** Tell everyone who can see e (and e itself, if a player) about something that happened to it. */
    private static void relayAround(Entity e, int kind, int value) {
        if (e.getWorld().isClient) return;
        for (ServerPlayerEntity o : PlayerLookup.tracking(e)) {
            if (ServerPlayNetworking.canSend(o, KataNet.FightOf.ID)) ServerPlayNetworking.send(o, new KataNet.FightOf(e.getId(), kind, value));
        }
        if (e instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, KataNet.FightOf.ID)) ServerPlayNetworking.send(sp, new KataNet.FightOf(e.getId(), kind, value));
    }

    private static void relay(ServerPlayerEntity p, int kind, int value) {
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p && ServerPlayNetworking.canSend(o, KataNet.FightOf.ID)) ServerPlayNetworking.send(o, new KataNet.FightOf(p.getId(), kind, value));
        }
    }
}
