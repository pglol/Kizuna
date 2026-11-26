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

    /** A move by its id ("taijutsu:jab", or just "jab"), or null. */
    public static Move byId(String id) {
        if (id == null) return null;
        for (Move m : MOVES) if (m.id().equals(id)) return m;
        for (Move m : MOVES) if (m.id().endsWith(":" + id)) return m;
        return null;
    }

    /** The style a move belongs to. */
    public static Style styleOf(Move m) {
        if (m == null) return STYLES[0];
        String sid = m.id().contains(":") ? m.id().substring(0, m.id().indexOf(':')) : "";
        for (Style s : STYLES) if (s.id().equals(sid)) return s;
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
    /** How far a punch reaches (from the eyes); kicks reach a little further (KICK_EXTRA). */
    public static final double REACH = 2.4, KICK_EXTRA = 0.4;
    /** A heavy held this long (seconds) is fully charged: full power, and it breaks guard. */
    public static final float FULL_CHARGE = 0.5f;
    /** Pressed longer than this, a click becomes a charged heavy. */
    public static final float HEAVY_AFTER = 0.12f;

    // ---- server ----

    private static final Map<UUID, Boolean> GUARD = new HashMap<>();
    private static final Map<UUID, Integer> STANCE = new HashMap<>();
    private static final Map<UUID, Integer> GUARD_AT = new HashMap<>();
    private static final Map<UUID, Integer> LAST = new HashMap<>();
    /** Guard stamina (0..1) as of a tick; it comes back on its own a second after the last hit taken. */
    private static final Map<UUID, Float> POISE = new HashMap<>();
    private static final Map<UUID, Integer> POISE_AT = new HashMap<>();
    /** The side a guard was raised towards (0 none, 1 left, 2 right), for parries; when a dodge began. */
    private static final Map<UUID, Integer> PARRY_SIDE = new HashMap<>(), DODGE_AT = new HashMap<>();
    /** Hit and not yet able to throw back (server tick it ends): the attacker's string carries on unless blocked or parried. */
    private static final Map<UUID, Integer> FLINCH_UNTIL = new HashMap<>();
    /** The side the guard is held towards now (0 square, 1 left, 2 right); what kind of dodge or avoid. */
    private static final Map<UUID, Integer> GUARD_SIDE = new HashMap<>(), DODGE_KIND = new HashMap<>();
    /** The last new strike: its move and when (for the spacing between strikes). */
    private static final Map<UUID, Integer> LAST_ID = new HashMap<>(), LAST_START = new HashMap<>(), LAST_HITS = new HashMap<>();
    /** Where the strike being dealt lands. */
    private static Part strikingPart;
    /** A dodge's invulnerable start (ticks): strikes pass through. */
    public static final int DODGE_IFRAMES = 5;
    /** The limb of the strike being dealt (true right, null unknown): it comes in on the other side of them. */
    private static Boolean strikeRight;
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
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(Fighting::tick);
        // Someone coming into view gets the fighter's state as it is now (guard, stance), not only changes.
        net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents.START_TRACKING.register((e, viewer) -> {
            if (!(e instanceof ServerPlayerEntity p) || !ServerPlayNetworking.canSend(viewer, KataNet.FightOf.ID)) return;
            ServerPlayNetworking.send(viewer, new KataNet.FightOf(p.getId(), KataNet.GUARD, GUARD.getOrDefault(p.getUuid(), false) ? 1 : 0));
            Integer st = STANCE.get(p.getUuid());
            if (st != null) ServerPlayNetworking.send(viewer, new KataNet.FightOf(p.getId(), KataNet.STANCE, st));
            int[] md = KataNet.MODES.get(p.getUuid()); // down, hanging...: as they are now
            if (md != null && md[0] != 0 && ServerPlayNetworking.canSend(viewer, KataNet.ActOf.ID))
                ServerPlayNetworking.send(viewer, new KataNet.ActOf(p.getId(), md[0], md[1]));
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            boolean ok = allow(victim, source, amount);
            if (!ok) kataRefused = true; // dodged, parried or blocked: Kata's own refusal
            return ok;
        });
    }

    /** Set when Kata itself turns a hit away (so a refused player hit isn't then forced through). */
    private static boolean kataRefused;
    /** Why Kata turned the last hit away (10 blocked, 11 parried, 12 evaded), for the report. */
    private static int refusal;
    /** Whether the last strike dealt damage; whether Minecraft thought the victim couldn't be hurt (and it was forced). */
    private static boolean dealt, invulnerableNote;

    private static boolean allow(LivingEntity victim, net.minecraft.entity.damage.DamageSource source, float amount) {
            if (chip) return true;
            // Dodging, in its first moments: the strike goes through nothing.
            if (striking != null && evades(victim, striking, strikingPart)) {
                evaded(victim, source.getAttacker());
                refusal = 12;
                return false;
            }
            if (!guarding(victim)) return true;
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
            double rx = -Math.cos(Math.toRadians(victim.getYaw())), rz = -Math.sin(Math.toRadians(victim.getYaw()));
            // The side it comes in on (1 their right, -1 their left): a right-hand (or right-leg) strike
            // comes in on their left; otherwise judged by where the attacker stands.
            int side = strikeRight != null ? (strikeRight ? -1 : 1) : to.x * rx + to.z * rz >= 0 ? 1 : -1;
            // The parry: the guard raised right as it arrives, towards the side it comes from. Towards
            // the wrong side, the guard is out of place and the hit lands clean.
            boolean window = guardAge(victim) <= PERFECT;
            if (victim instanceof Dummy) {
                if (window && w.random.nextFloat() < 0.3f) {
                    parry(victim, by, striking);
                    refusal = 11;
                    return false;
                }
            } else if (window && striking != null) {
                int want = side > 0 ? 2 : 1, ps = PARRY_SIDE.getOrDefault(victim.getUuid(), 0);
                if (ps == want) {
                    parry(victim, by, striking);
                    refusal = 11;
                    return false;
                }
                if (ps != 0) {
                    stagger(victim, 8); // caught reaching the wrong way
                    return true;
                }
            }
            // The guard faces forward: from behind or well round the side, it lands.
            Vec3d face = Vec3d.fromPolar(0, victim.getYaw());
            if ((to.x * face.x + to.z * face.z) / len < 0.34) return true;
            // Straight strikes meet any guard. Swinging ones (hooks, roundhouses, backfists, elbows,
            // spins) come round it: a guard turned to that side stops them, one turned the other way
            // doesn't, and a square guard takes half.
            boolean halfIn = false;
            if (striking != null && curved(striking) && victim instanceof ServerPlayerEntity) {
                int gs = GUARD_SIDE.getOrDefault(victim.getUuid(), 0), want = side > 0 ? 2 : 1;
                if (gs != 0 && gs != want) return true;
                halfIn = gs == 0;
            }
            // A block, paid for in stamina by how hard the hit was. Not enough left and the guard
            // breaks: stunned, and the hit lands.
            float cost = amount * 0.05f + (striking != null && striking.heavy() ? 0.15f : 0);
            float po = poise(victim);
            if (cost > po) {
                setPoise(victim, 0);
                breakGuard(victim);
                stagger(victim, 20);
                w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.9f, 1.1f);
                return true;
            }
            setPoise(victim, po - cost);
            w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.8f, 1.2f + w.random.nextFloat() * 0.2f);
            tell(victim, KataNet.GUARD_HIT, side);
            if (victim instanceof ServerPlayerEntity vp) relayAround(vp, KataNet.BLOCKED, side > 0 ? 1 : 0); // everyone sees the block
            if (victim instanceof Dummy d) d.blocked(side);
            shove(victim, new Vec3d(-to.x / len, 0, -to.z / len), 0.05 + amount * 0.008); // the guard gives a little
            refusal = 10;
            if (halfIn) {
                chip = true;
                try {
                    victim.timeUntilRegen = 0;
                    victim.damage(source, amount * 0.5f);
                } finally {
                    chip = false;
                }
            }
            return false;
    }

    /** A perfect block: a punch is turned aside and the attacker staggers; a kick is caught and they're thrown. */
    private static void parry(net.minecraft.entity.LivingEntity victim, Entity by, Move m) {
        var w = victim.getWorld();
        w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1f, 1.7f);
        boolean kick = m != null && m.leg();
        tell(victim, KataNet.PARRY, kick ? 2 : 1);
        tell(by, KataNet.PARRIED, kick ? 2 : 1);
        // Seen and heard: a bright clash between them and a ringing clang.
        if (w instanceof net.minecraft.server.world.ServerWorld sw) {
            Vec3d mid = victim.getEyePos().add(by.getEyePos()).multiply(0.5).add(0, -0.3, 0);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANTED_HIT, mid.x, mid.y, mid.z, 18, 0.15, 0.15, 0.15, 0.4);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.CRIT, mid.x, mid.y, mid.z, 10, 0.1, 0.1, 0.1, 0.3);
        }
        w.playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.35f, 1.9f);
        if (victim instanceof Dummy d) d.parried(kick);
        if (kick) {
            // Caught the foot: thrown back the way it came, off their feet.
            Vec3d away = by.getPos().subtract(victim.getPos()).multiply(1, 0, 1);
            away = away.lengthSquared() < 1e-4 ? Vec3d.fromPolar(0, victim.getYaw()) : away.normalize();
            knockdown(by, away.multiply(0.35).add(0, 0.24, 0));
        } else stagger(by, 18); // long enough that the counter is sure to land
        setPoise(victim, Math.min(1, poise(victim) + 0.25f));
    }

    /** Guard stamina now: what was left at the last hit, coming back after a second's breather. */
    static float poise(Entity e) {
        Float v = POISE.get(e.getUuid());
        Integer at = POISE_AT.get(e.getUuid());
        if (v == null || at == null || e.getServer() == null) return 1;
        return Math.min(1, v + Math.max(0, e.getServer().getTicks() - at - 20) / 80f);
    }

    static void setPoise(Entity e, float v) {
        if (e.getServer() == null) return;
        POISE.put(e.getUuid(), Math.max(0, v));
        POISE_AT.put(e.getUuid(), e.getServer().getTicks());
        if (POISE.size() > 2048) {
            POISE.clear();
            POISE_AT.clear();
        }
        relayAround(e, KataNet.POISE, Math.round(Math.max(0, v) * 100)); // they see it in your body (strain)
    }

    static void flinch(LivingEntity e, int ticks) {
        if (e instanceof Dummy d) d.flinch(ticks);
        else if (e instanceof ServerPlayerEntity p) {
            int until = p.getServer().getTicks() + ticks;
            FLINCH_UNTIL.merge(p.getUuid(), until, Math::max);
            if (FLINCH_UNTIL.size() > 1024) FLINCH_UNTIL.clear();
            if (ServerPlayNetworking.canSend(p, KataNet.FightOf.ID)) ServerPlayNetworking.send(p, new KataNet.FightOf(p.getId(), KataNet.FLINCH, ticks));
        }
    }

    // ---- air combat ----

    private static final net.minecraft.util.Identifier FLOAT_ID = net.minecraft.util.Identifier.of(KataCore.ID, "juggle");
    /** Who's being held up in the air (gravity lightened) until when (server tick). */
    private static final Map<UUID, Integer> FLOAT_UNTIL = new HashMap<>();
    private static final Map<UUID, net.minecraft.entity.LivingEntity> FLOATING = new HashMap<>();
    /** Who's holding them in an air string (they're kept in front of that fighter). */
    private static final Map<UUID, LivingEntity> TETHER = new HashMap<>();
    /** Smashed towards the ground: concussed when they hit it (or after a moment). */
    private static final Map<UUID, Integer> SMASHED = new HashMap<>();
    private static final Map<UUID, net.minecraft.entity.LivingEntity> SMASHING = new HashMap<>();
    /** How long one air hit holds them up (ticks): enough for the next. */
    public static final int FLOAT_TICKS = 16;

    /**
     * An air hit. On the ground, it lifts them off it; already up, it pops them up a touch and holds them
     * there (light gravity) for the next hit. The attacker hangs with them (their game does that).
     */
    static void juggle(LivingEntity by, LivingEntity t, Vec3d look) {
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        if (flat.lengthSquared() > 1e-4) flat = flat.normalize();
        // Carried to just in front of the attacker at their height (a touch above), so the attacker
        // trails them up through the air rather than hitting someone hanging over their head.
        Vec3d spot = by.getPos().add(flat.multiply(1.4)).add(0, 0.1, 0);
        Vec3d to = spot.subtract(t.getPos());
        double rise = t.isOnGround() ? 0.3 : 0.08; // lifted off the ground, then held (the tether keeps them there)
        Vec3d v = new Vec3d(to.x * 0.4, to.y * 0.4 + rise, to.z * 0.4);
        double hl = Math.hypot(v.x, v.z);
        if (hl > 0.5) v = new Vec3d(v.x * 0.5 / hl, v.y, v.z * 0.5 / hl);
        t.setVelocity(v.x, MathHelper.clamp(v.y, -0.1, 0.55), v.z);
        t.velocityModified = true;
        hold(t, FLOAT_TICKS);
        t.fallDistance = 0;
        // Tethered: while the string goes on, they stay right in front of the one hitting them.
        TETHER.put(t.getUuid(), by);
        relayAround(t, KataNet.TETHER, by.getId());
        tell(by, KataNet.AIR_HIT, FLOAT_TICKS);
        tell(t, KataNet.AIR_HIT, FLOAT_TICKS);
    }

    private static void hold(LivingEntity t, int ticks) {
        var g = t.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_GRAVITY);
        if (g != null && g.getModifier(FLOAT_ID) == null)
            g.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(FLOAT_ID, -0.82, net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        if (t.getServer() == null) return;
        FLOAT_UNTIL.put(t.getUuid(), t.getServer().getTicks() + ticks);
        FLOATING.put(t.getUuid(), t);
    }

    private static void release(LivingEntity t) {
        TETHER.remove(t.getUuid());
        var g = t.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_GRAVITY);
        if (g != null) g.removeModifier(FLOAT_ID);
        FLOAT_UNTIL.remove(t.getUuid());
        FLOATING.remove(t.getUuid());
    }

    /** The held air strike: down they go, hard, and they're concussed when they hit the ground. */
    static void smash(LivingEntity by, LivingEntity t, Vec3d look) {
        release(t);
        Vec3d flat = new Vec3d(look.x, 0, look.z);
        if (flat.lengthSquared() > 1e-4) flat = flat.normalize();
        t.setVelocity(flat.x * 0.06, -1.5, flat.z * 0.06);
        t.velocityModified = true;
        if (t.getServer() == null) return;
        SMASHED.put(t.getUuid(), t.getServer().getTicks());
        SMASHING.put(t.getUuid(), t);
        flinch(t, 30);
    }

    /** Hit the ground from a smash: a burst of dust, a thud, knocked flat, then dazed a while. */
    private static void concuss(LivingEntity t) {
        var w = t.getWorld();
        if (w instanceof net.minecraft.server.world.ServerWorld sw) {
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.POOF, t.getX(), t.getY() + 0.1, t.getZ(), 24, 0.6, 0.05, 0.6, 0.08);
            sw.spawnParticles(net.minecraft.particle.ParticleTypes.CRIT, t.getX(), t.getY() + 0.3, t.getZ(), 12, 0.4, 0.1, 0.4, 0.2);
        }
        w.playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS, 0.45f, 1.5f);
        t.fallDistance = 0;
        knockdown(t, new Vec3d(0, 0.06, 0));
        int dazed = 60;
        flinch(t, dazed + 30);
        if (t instanceof Dummy d) d.stagger(dazed);
        relayAround(t, KataNet.CONCUSS, dazed);
    }

    /** Every tick: let go of whoever has floated long enough; concuss whoever a smash put into the ground. */
    static void tick(net.minecraft.server.MinecraftServer server) {
        KataCore.moveCheck = false; // never left on past a tick (it only means "checking a move right now")
        int now = server.getTicks();
        if (!FLOAT_UNTIL.isEmpty()) {
            for (var it = FLOAT_UNTIL.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                LivingEntity e = FLOATING.get(en.getKey());
                if (e == null || e.isRemoved() || now >= en.getValue()) {
                    it.remove();
                    if (e != null) {
                        var g = e.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_GRAVITY);
                        if (g != null) g.removeModifier(FLOAT_ID);
                    }
                    FLOATING.remove(en.getKey());
                }
            }
        }
        // Air strings: each one held is kept just in front of whoever's hitting them, at their height,
        // for as long as they float and the hitter is still in the air.
        if (!TETHER.isEmpty()) {
            for (var it = TETHER.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                LivingEntity t = FLOATING.get(en.getKey()), by = en.getValue();
                if (t == null || by == null || t.isRemoved() || by.isRemoved() || !by.isAlive() || (by.isOnGround() && t.isOnGround())) {
                    it.remove();
                    continue;
                }
                Vec3d flat = Vec3d.fromPolar(0, by.getYaw());
                Vec3d spot = by.getPos().add(flat.multiply(1.4)).add(0, 0.1, 0);
                Vec3d v = spot.subtract(t.getPos()).multiply(0.45);
                if (v.length() > 0.6) v = v.normalize().multiply(0.6);
                t.setVelocity(v);
                t.velocityModified = true;
                t.fallDistance = 0;
            }
        }
        if (!SMASHED.isEmpty()) {
            for (var it = SMASHED.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                LivingEntity e = SMASHING.get(en.getKey());
                if (e == null || e.isRemoved()) {
                    it.remove();
                    SMASHING.remove(en.getKey());
                    continue;
                }
                int age = now - en.getValue();
                if ((age > 1 && (e.isOnGround() || e.isTouchingWater())) || age > 40) {
                    it.remove();
                    SMASHING.remove(en.getKey());
                    concuss(e);
                }
            }
        }
    }

    /** Strikes that swing round a guard rather than straight through it. */
    static boolean curved(Move m) {
        return m.spin() || switch (m.path()) {
            case HOOK, ROUNDHOUSE, BACKFIST, ELBOW, LOW_KICK -> true;
            default -> false;
        };
    }

    /** Strikes that drive straight in (a slip to the side lets them by). */
    static boolean thrust(Move m) {
        return switch (m.path()) {
            case STRAIGHT, PALM, SUPERMAN, FRONT_KICK, SIDE_KICK, BACK_KICK, KNEE, DROPKICK, OVERHAND -> true;
            default -> false;
        };
    }

    /**
     * Did a dodge or an avoid take them out of this strike? A step back (or a flip): a few invulnerable
     * ticks from anything. A duck: under anything to the head. A slip to the side: past straight
     * thrusts. A hop: over low kicks and anything at the legs. The avoids hold a little longer, but only
     * against what they're for.
     */
    static boolean evades(Entity e, Move m, Part part) {
        if (!(e instanceof ServerPlayerEntity p)) return false;
        Integer at = DODGE_AT.get(p.getUuid());
        if (at == null) return false;
        int age = p.getServer().getTicks() - at, kind = DODGE_KIND.getOrDefault(p.getUuid(), 0);
        if (kind >= 32) return false; // clinging to a wall: no dodge in that
        if (kind == 0 || kind >= 16) return age <= DODGE_IFRAMES;
        if (age > 8) return false;
        return switch (kind) {
            case 1 -> part == Part.HEAD || m.path() == Path.HOOK || m.path() == Path.BACKFIST || m.path() == Path.OVERHAND;
            case 2, 3 -> thrust(m);
            case 4 -> part == Part.LEG || m.path() == Path.LOW_KICK;
            default -> false;
        };
    }

    /** Got out of the way: the one who missed spends breath on air, the one who avoided gets some back. */
    private static void evaded(LivingEntity victim, Entity by) {
        setPoise(victim, Math.min(1, poise(victim) + 0.15f));
        if (by != null) setPoise(by, Math.max(0, poise(by) - 0.15f));
        tell(victim, KataNet.EVADED, 0);
        if (by != null) tell(by, KataNet.WHIFFED, 0); // the one who missed feels it too (the slow-down)
        victim.getWorld().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.PLAYERS, 0.7f, 1.4f);
    }

    static boolean dodging(Entity e) {
        if (!(e instanceof ServerPlayerEntity p)) return false;
        Integer at = DODGE_AT.get(p.getUuid());
        return at != null && p.getServer().getTicks() - at <= DODGE_IFRAMES;
    }

    /** A dodge begins (dir: 0 back, 1 forward, 2 left, 3 right): i-frames, and everyone sees it. */
    static void dodge(ServerPlayerEntity p, int dir) {
        DODGE_AT.put(p.getUuid(), p.getServer().getTicks());
        if (DODGE_AT.size() > 1024) DODGE_AT.clear();
        DODGE_KIND.put(p.getUuid(), MathHelper.clamp(dir, 0, 63));
        if (DODGE_KIND.size() > 1024) DODGE_KIND.clear();
        relay(p, KataNet.DODGE, MathHelper.clamp(dir, 0, 63));
    }

    private static void breakGuard(Entity e) {
        if (e instanceof ServerPlayerEntity p) {
            GUARD.put(p.getUuid(), false);
            tell(p, KataNet.GUARD_BROKEN, 0);
            relay(p, KataNet.GUARD, 0); // the guard is down for everyone watching too
        } else if (e instanceof Dummy d) d.guardBroken();
    }

    /** Knocked off their feet (limp) with this velocity (blocks a tick). */
    /**
     * How hard this fighter hits: the multiplier on every push and knockdown. 1 for everyone for now;
     * strength (training, stats, buffs) raises it later, and only that makes hits send people further.
     */
    public static float might(Entity e) {
        return 1;
    }

    /** Pushed back along the ground (dir flat, s blocks a tick): no hop, and their own run mostly cancelled. */
    static void shove(LivingEntity e, Vec3d dir, double s) {
        Vec3d v = e.getVelocity();
        e.setVelocity(v.x * 0.4 + dir.x * s, Math.min(v.y, 0.05), v.z * 0.4 + dir.z * s);
        e.velocityModified = true;
    }

    public static void knockdown(Entity e, Vec3d v) {
        // Fighters, not cannonballs: never more than a few blocks a second across, nor much up.
        double hl = Math.hypot(v.x, v.z);
        if (hl > 0.28) v = new Vec3d(v.x * 0.28 / hl, v.y, v.z * 0.28 / hl);
        v = new Vec3d(v.x, Math.min(v.y, 0.3), v.z);
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
        else if (e instanceof Dummy d) {
            d.stagger(ticks);
            relayAround(d, KataNet.STAGGER, ticks); // everyone sees it reeling
        }
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
        if (e instanceof LivingEntity) relayAround(e, kind, a); // dummies too (everyone sees their parries)
    }

    /** The guard packet: bit 0 up or down, bits 1-2 the side it was raised towards (for a parry). */
    static void guard(ServerPlayerEntity p, int packed) {
        boolean on = (packed & 1) != 0;
        if (on && !GUARD.getOrDefault(p.getUuid(), false)) PARRY_SIDE.put(p.getUuid(), (packed >> 1) & 3);
        GUARD_SIDE.put(p.getUuid(), on ? (packed >> 1) & 3 : 0);
        if (GUARD_SIDE.size() > 1024) GUARD_SIDE.clear();
        if (on && GUARD.getOrDefault(p.getUuid(), false)) return; // already up: only which way it's turned changed
        if (PARRY_SIDE.size() > 1024) PARRY_SIDE.clear();
        guard(p, on);
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
        // Everyone watching sees it start (the load-up is the tell), not only when it lands.
        relay(p, KataNet.WINDUP, packed);
    }

    /** A heavy being charged (1) or let go of (0): everyone sees the wind-up building. */
    static void charge(ServerPlayerEntity p, int on) {
        relay(p, KataNet.CHARGE, on != 0 ? 1 : 0);
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
        boolean atPlayer = target >= 0 && p.getWorld().getEntityById(target) instanceof ServerPlayerEntity;
        float power = m.heavy() ? MathHelper.clamp(((packed >> 9) & 0x7F) / 100f, 0.3f, 1f) : 1f;
        int now = p.getServer().getTicks();
        Integer last = LAST.get(p.getUuid());
        // A new strike only once the last has nearly run its course; the later hits of the same move
        // (a flurry, a one-two) come as fast as the move throws them, but never more of them than it has.
        Integer lastId = LAST_ID.get(p.getUuid()), lastStart = LAST_START.get(p.getUuid());
        boolean sameMove = lastId != null && lastId == id && lastStart != null && now - lastStart < Math.round(m.length() * 20) + 2;
        if (sameMove) {
            int n = LAST_HITS.merge(p.getUuid(), 1, Integer::sum);
            if (n > m.hits()) {
                dbg(p, 1, n);
                return;
            }
        } else {
            if (last != null && now - last < 2) {
                dbg(p, 1, now - last); // faster than any string allows
                return;
            }
            if (lastId != null && lastStart != null && lastId < MOVES.length && now - lastStart < Math.round(MOVES[lastId].length() * 20 * 0.75f)) {
                dbg(p, 2, now - lastStart);
                return;
            }
            LAST_ID.put(p.getUuid(), id);
            LAST_START.put(p.getUuid(), now);
            LAST_HITS.put(p.getUuid(), 1);
            if (LAST_ID.size() > 1024) {
                LAST_ID.clear();
                LAST_START.clear();
                LAST_HITS.clear();
            }
        }
        LAST.put(p.getUuid(), now);
        if (LAST.size() > 1024) LAST.clear();
        // Still reeling from a hit (a little slack for lag): nothing thrown lands.
        Integer fl = FLINCH_UNTIL.get(p.getUuid());
        if (fl != null && now < fl - 2) {
            dbg(p, 3, fl - now);
            return;
        }
        relay(p, KataNet.STRIKE, packed);
        if (target < 0 || guarding(p)) {
            dbg(p, target < 0 ? 4 : 5, 0);
            return;
        }
        Entity e = p.getWorld().getEntityById(target);
        if (!(e instanceof LivingEntity t) || !t.isAlive() || t == p) {
            dbg(p, 6, target);
            return;
        }
        // In reach (with some slack for lag) and somewhere in front.
        Vec3d eye = p.getEyePos();
        var b = t.getBoundingBox();
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, b.minX, b.maxX), MathHelper.clamp(eye.y, b.minY, b.maxY), MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        // A downed dummy's body lies wherever it flew (its box may be elsewhere): judge by distance only.
        boolean lying = t instanceof Dummy dd && dd.down() > 0;
        if (eye.distanceTo(near) > (lying ? 7 : REACH + (m.leg() ? KICK_EXTRA : 0) + 0.6)) { // a little slack for lag
            dbg(p, 7, (int) Math.round(eye.distanceTo(near) * 100));
            return;
        }
        Vec3d to = t.getPos().subtract(p.getPos());
        Vec3d look = Vec3d.fromPolar(0, p.getYaw());
        double len = Math.hypot(to.x, to.z);
        // Anywhere a limb really reached them counts (behind too): the touch was judged on the striker's side.
        Part part = Part.values()[Math.min(3, (packed >> 16) & 0x3)];
        strikeRight = (packed & 1 << 8) != 0;
        try {
            boolean landed = land(p, t, m, power, part, look, p.getDamageSources().playerAttack(p));
            int code = dealt ? (invulnerableNote ? 14 : 0) : t instanceof ServerPlayerEntity vp && vp.isCreative() ? 9 : refusal != 0 ? refusal : landed ? 13 : 8;
            dbg(p, code, Math.round(t.getHealth() * 10));

        } finally {
            strikeRight = null;
        }
    }

    /** Players who asked to see why their strikes land or don't (/kdebug). */
    private static final java.util.Set<UUID> DEBUG = new java.util.HashSet<>();

    static void debug(ServerPlayerEntity p, boolean on) {
        if (on) DEBUG.add(p.getUuid());
        else DEBUG.remove(p.getUuid());
    }

    /** Tell the striker what became of a strike at a player, whether or not they're debugging. */
    private static void report(ServerPlayerEntity p, int code, int value) {
        if (!DEBUG.contains(p.getUuid()) && ServerPlayNetworking.canSend(p, KataNet.FightOf.ID))
            ServerPlayNetworking.send(p, new KataNet.FightOf(p.getId(), KataNet.DEBUG, code | (Math.max(0, Math.min(0xFFFFFF, value)) << 4)));
    }

    /** Tell a debugging player what became of their strike (a code, and a number for it). */
    private static void dbg(ServerPlayerEntity p, int code, int value) {
        if (DEBUG.contains(p.getUuid()) && ServerPlayNetworking.canSend(p, KataNet.FightOf.ID))
            ServerPlayNetworking.send(p, new KataNet.FightOf(p.getId(), KataNet.DEBUG, code | (Math.max(0, Math.min(0xFFFFFF, value)) << 4)));
    }

    /** A strike landing (from a player or a dummy): damage by part, guard, parry, knockback, launch, knockdown. */
    static boolean land(LivingEntity by, LivingEntity t, Move m, float power, Part part, Vec3d look, net.minecraft.entity.damage.DamageSource src) {
        float dmg = m.damage() * (m.heavy() ? 0.5f + 0.5f * power : 1) * part.share;
        t.timeUntilRegen = 0; // strings land: no invulnerability gap between hits
        breaking = m.heavy() && power >= 0.98f;
        striking = m;
        strikingPart = part;
        boolean hit;
        kataRefused = false;
        refusal = 0;
        invulnerableNote = false;
        try {
            hit = t.damage(src, dmg);
            // A fighter's strike that really touched another player: the game's PvP and team rules
            // don't get a say (they turned every player hit away). Creative and spectators stay untouchable.
            if (!hit && !kataRefused && t instanceof ServerPlayerEntity victim && by instanceof ServerPlayerEntity && !victim.isCreative() && !victim.isSpectator()
                && victim.isAlive() && !guarding(victim)) {
                var key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE, net.minecraft.util.Identifier.of(KataCore.ID, "strike"));
                var type = t.getWorld().getRegistryManager().get(net.minecraft.registry.RegistryKeys.DAMAGE_TYPE).getEntry(key);
                net.minecraft.entity.damage.DamageSource own = type.isPresent() ? new net.minecraft.entity.damage.DamageSource(type.get()) : t.getDamageSources().generic();
                t.timeUntilRegen = 0;
                hit = t.damage(own, dmg);
                if (!hit && !kataRefused) {
                    // Still turned away (Minecraft thinks they can't be hurt right now: a teleport, a join,
                    // an invulnerable flag): the touch was real, so the health comes off directly.
                    if (victim.isInvulnerableTo(own)) invulnerableNote = true;
                    float left = victim.getHealth() - dmg;
                    if (left > 0) victim.setHealth(left);
                    else {
                        victim.setHealth(0);
                        victim.onDeath(own);
                    }
                    victim.timeUntilRegen = 10;
                    victim.hurtTime = victim.maxHurtTime = 10;
                    t.getWorld().sendEntityDamage(victim, own); // the hurt flash and sound, as for any hit
                    hit = true;
                }
                if (hit) t.setAttacker(by);
            }
        } finally {
            breaking = false;
            striking = null;
            strikingPart = null;
        }
        dealt = hit;
        // A creative-mode player takes no damage, but still feels the hit (unless it was blocked).
        if (!hit && !(t instanceof net.minecraft.entity.player.PlayerEntity pl && pl.isCreative() && !guarding(t))) return false;
        // The hit's reaction, for everyone to see: where it landed, what kind of strike, from which side.
        Vec3d from = by.getPos().subtract(t.getPos());
        double ty = Math.toRadians(t.getYaw());
        boolean fromRight = from.x * -Math.cos(ty) + from.z * -Math.sin(ty) > 0;
        // How charged a heavy was (0 barely, 1 half, 2 well): the impact frame it gets.
        int tier = !m.heavy() ? 0 : power < 0.4f ? 0 : power < 0.8f ? 1 : 2;
        int react = part.ordinal() | m.path().ordinal() << 2 | (fromRight ? 1 << 7 : 0) | (m.heavy() ? 1 << 8 : 0) | tier << 9;
        relayAround(t, KataNet.HIT, react);
        // Hitstun: long enough for the attacker's next strike in the string to arrive first.
        int stun = Math.round(m.length() * 20 * 0.85f) + (m.heavy() ? 6 : 3);
        flinch(t, stun);
        // In the air: hits keep them up there (a juggle); the held one smashes them into the ground.
        if (m.from() == From.AIR) {
            if (m.heavy()) smash(by, t, look);
            else juggle(by, t, look);
            t.getWorld().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 0.9f, 1.1f + t.getRandom().nextFloat() * 0.2f);
            return true;
        }
        float shove = m.push() * (m.heavy() ? 0.6f + 0.6f * power : 1) * might(by);
        boolean down = m.knockdown() && (!m.heavy() || power >= 0.9f);
        if (down) {
            // Knocked down a couple of body lengths, not across the field: further only with more might.
            knockdown(t, look.multiply(shove * 0.5).add(0, 0.15 + m.lift() * 0.6, 0));
        } else {
            // An ordinary hit moves you back a step along the ground, never off it: fighters stay in range
            // to trade. Only knockdowns send anyone flying.
            Vec3d flat = new Vec3d(look.x, 0, look.z);
            if (flat.lengthSquared() > 1e-4) shove(t, flat.normalize(), shove * 0.42);
            if (m.lift() > 0) {
                t.addVelocity(0, m.lift() * (m.heavy() ? 0.6f + 0.6f * power : 1), 0);
                t.velocityModified = true;
            }
        }
        t.getWorld().playSound(null, t.getX(), t.getY(), t.getZ(),
            m.heavy() || down ? SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
            SoundCategory.PLAYERS, 0.9f, (m.heavy() ? 0.75f : 1f) + t.getRandom().nextFloat() * 0.2f);
        return true;
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
