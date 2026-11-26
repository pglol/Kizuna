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

    /** How a strike travels: straight down the line, round in an arc, up from below, over the top, an open palm, a back-hand spin, an elbow. */
    public enum Path { STRAIGHT, HOOK, UPPER, OVERHAND, PALM, BACKFIST, ELBOW }

    /**
     * One move of a style.
     *  - id: style:move, for example "taijutsu:jab"
     *  - lead: thrown with the lead hand (else the rear)
     *  - heavy: a charged heavy (the heavy slot), else a light for the chain
     *  - back: thrown from a back stance, spinning round to face them
     *  - contact and length: seconds (from the press for a light, from the release for a heavy)
     *  - damage: in half-hearts
     *  - push: knockback
     */
    public record Move(String id, String name, Path path, boolean lead, boolean heavy, boolean back, float contact, float length, float damage, float push) {
        public boolean turns() {
            return back;
        }

        /** Can it go in this slot: a light slot (0-2) or the heavy slot (3), in a top or back quadrant? */
        public boolean fits(int stance, int slot) {
            return heavy == (slot == HEAVY_SLOT) && back == !top(stance);
        }
    }

    /** A deck has four quadrants of four slots: three lights (the chain), then the heavy. */
    public static final int SLOTS = 4, HEAVY_SLOT = 3;

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
            Move jab = new Move("basic:jab", "Jab", Path.STRAIGHT, true, false, false, 0.08f, 0.25f, 2, 0.2f);
            styles.add(new Style("basic", "Basic", "", 0xD8403E, new Move[] {jab}, new int[][] {{0, -1, -1, -1}, {0, -1, -1, -1}, {0, -1, -1, -1}, {0, -1, -1, -1}}));
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
            pool.add(new Move(sid + ":" + m.get("id").getAsString(), m.get("name").getAsString(),
                Path.valueOf(m.get("path").getAsString().toUpperCase(java.util.Locale.ROOT)),
                "lead".equals(m.get("hand").getAsString()), "heavy".equals(m.get("kind").getAsString()), "back".equals(m.get("from").getAsString()),
                m.get("contact").getAsFloat(), m.get("length").getAsFloat(), m.get("damage").getAsFloat(), m.get("push").getAsFloat()));
        }
        Style partial = new Style(sid, o.get("name").getAsString(), o.has("blurb") ? o.get("blurb").getAsString() : "",
            o.has("color") ? Integer.parseInt(o.get("color").getAsString().replace("#", ""), 16) : 0xD8403E, pool.toArray(new Move[0]), null);
        String[] quads = {"top_right", "top_left", "back_right", "back_left"};
        int[][] starter = new int[4][SLOTS];
        com.google.gson.JsonObject st = o.getAsJsonObject("starter");
        for (int q = 0; q < 4; q++) {
            java.util.Arrays.fill(starter[q], -1);
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

    /** The hand a move is thrown with from a stance: true = right. */
    public static boolean rightHand(Move m, int stance) {
        if (!top(stance)) return rightSide(stance); // spinning: the arm on the side you turn
        boolean leadRight = !rightSide(stance); // top-right is orthodox (left lead)
        return m.lead() == leadRight;
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
    private static final Map<UUID, Integer> LAST = new HashMap<>();
    /** Set while a guard-breaking strike is being dealt. */
    private static boolean breaking;

    public static boolean guarding(Entity e) {
        return e instanceof ServerPlayerEntity p && GUARD.getOrDefault(p.getUuid(), false);
    }

    static void register() {
        // Guard: hits from anyone are blocked, from any side, unless a full heavy breaks it. A block
        // still shoves and jolts you; your game is told the side it came from.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (!(victim instanceof ServerPlayerEntity p) || !guarding(p)) return true;
            Entity by = source.getAttacker();
            if (by == null || source.isIn(DamageTypeTags.BYPASSES_SHIELD)) return true;
            Vec3d to = by.getPos().subtract(p.getPos());
            double len = Math.max(1e-3, Math.hypot(to.x, to.z));
            if (breaking) {
                GUARD.put(p.getUuid(), false);
                tell(p, KataNet.GUARD_BROKEN, 0);
                p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_SHIELD_BREAK, SoundCategory.PLAYERS, 0.9f, 1.1f);
                return true;
            }
            p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.8f, 1.2f + p.getRandom().nextFloat() * 0.2f);
            double yaw = Math.toRadians(p.getYaw());
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
            tell(p, KataNet.GUARD_HIT, to.x * rx + to.z * rz >= 0 ? 1 : -1);
            p.takeKnockback(0.15 + amount * 0.03, to.x / len, to.z / len);
            p.velocityModified = true;
            return false;
        });
    }

    private static void tell(ServerPlayerEntity p, int kind, int a) {
        if (ServerPlayNetworking.canSend(p, KataNet.FightOf.ID)) ServerPlayNetworking.send(p, new KataNet.FightOf(p.getId(), kind, a));
    }

    static void guard(ServerPlayerEntity p, boolean on) {
        GUARD.put(p.getUuid(), on);
        if (GUARD.size() > 1024) GUARD.clear();
        relay(p, KataNet.GUARD, on ? 1 : 0);
    }

    static void stance(ServerPlayerEntity p, int stance) {
        relay(p, KataNet.STANCE, MathHelper.clamp(stance, 0, 3));
    }

    /**
     * A strike thrown at target (an entity id, or -1 for none in reach). packed: move id (low 8 bits),
     * right hand (bit 8), heavy power in percent (bits 9-15), the body part it lands on (bits 16-17).
     * Checked, then landed.
     */
    static void strike(ServerPlayerEntity p, int packed, int target) {
        int id = packed & 0xFF;
        if (id >= MOVES.length || !p.isAlive() || p.isSpectator()) return;
        Move m = MOVES[id];
        float power = m.heavy() ? MathHelper.clamp(((packed >> 9) & 0x7F) / 100f, 0.3f, 1f) : 1f;
        int now = p.getServer().getTicks();
        Integer last = LAST.get(p.getUuid());
        if (last != null && now - last < 3) return; // faster than any chain allows
        LAST.put(p.getUuid(), now);
        if (LAST.size() > 1024) LAST.clear();
        relay(p, KataNet.STRIKE, packed);
        if (target < 0 || guarding(p)) return;
        Entity e = p.getWorld().getEntityById(target);
        if (!(e instanceof LivingEntity t) || !t.isAlive() || t == p) return;
        // In reach (with some slack for lag) and in front of them.
        Vec3d eye = p.getEyePos();
        var b = t.getBoundingBox();
        Vec3d near = new Vec3d(MathHelper.clamp(eye.x, b.minX, b.maxX), MathHelper.clamp(eye.y, b.minY, b.maxY), MathHelper.clamp(eye.z, b.minZ, b.maxZ));
        if (eye.distanceTo(near) > REACH + 0.6) return;
        Vec3d to = t.getPos().subtract(p.getPos());
        Vec3d look = Vec3d.fromPolar(0, p.getYaw());
        double len = Math.hypot(to.x, to.z);
        // Anywhere in front, wide: hooks, spins and glancing contacts land off to the side too.
        if (len > 0.3 && (to.x * look.x + to.z * look.z) / len < -0.2) return;
        Part part = Part.values()[Math.min(3, (packed >> 16) & 0x3)];
        float dmg = m.damage() * (m.heavy() ? 0.5f + 0.5f * power : 1) * part.share;
        t.timeUntilRegen = 0; // strings land: no invulnerability gap between hits
        breaking = m.heavy() && power >= 0.98f;
        boolean hit;
        try {
            hit = t.damage(p.getDamageSources().playerAttack(p), dmg);
        } finally {
            breaking = false;
        }
        if (hit) {
            t.takeKnockback(m.push() * (m.heavy() ? 0.6f + 0.6f * power : 1), -look.x, -look.z);
            p.getWorld().playSound(null, t.getX(), t.getY(), t.getZ(),
                m.heavy() ? SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                SoundCategory.PLAYERS, 0.9f, (m.heavy() ? 0.75f : 1f) + p.getRandom().nextFloat() * 0.2f);
        }
    }

    private static void relay(ServerPlayerEntity p, int kind, int value) {
        for (ServerPlayerEntity o : PlayerLookup.tracking(p)) {
            if (o != p && ServerPlayNetworking.canSend(o, KataNet.FightOf.ID)) ServerPlayNetworking.send(o, new KataNet.FightOf(p.getId(), kind, value));
        }
    }
}
