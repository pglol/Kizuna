package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * PvP: matches in arenas built for swinging, away from the real map. Deathmatch (free for all),
 * casual 1v1 / 2v2 / 3v3 rounds, and ranked team queues with a rating, bigger pay and, the higher you
 * climb, opponents further above your level. Duels can be fought in an arena too. Nobody loses
 * anything here: the blow that would kill takes you out of the round (or back onto a spawn).
 * Where fighters left the real map, a marker stays: anyone can use it to watch the fight.
 */
public final class Arenas {
    private Arenas() { }

    public static final RegistryKey<World> WORLD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("aot_rpg", "arena"));
    private static final int GROUND = 64, BUILD_VERSION = 3, SLOTS = 3, SLOT_SPACING = 1500;
    private static final int DM_TO_WIN = 12, DM_SECS = 360, DM_MAX = 12, ROUND_SECS = 150, BETWEEN_TICKS = 80, SHIELD_TICKS = 60;
    public static final int RANKED_LEVEL = 15;
    private static final String SPOT = "aot_arena_spot";

    /** The maps. Each is built SLOTS times over, so several matches can run on one at once. */
    public enum ArenaMap {
        GROVE("grove", "High Tree Grove", 0),
        SKYLINE("skyline", "Skyline", 3000);

        final String id, title;
        final int x;

        ArenaMap(String id, String title, int x) {
            this.id = id;
            this.title = title;
            this.x = x;
        }

        int ox(int slot) {
            return x;
        }

        int oz(int slot) {
            return slot * SLOT_SPACING;
        }

        static ArenaMap of(String id) {
            for (ArenaMap m : values()) if (m.id.equals(id)) return m;
            return null;
        }
    }

    /** The ways to play. team 0 is a free-for-all. */
    public enum Mode {
        DEATHMATCH("dm", "Deathmatch", "Free for all · first to " + DM_TO_WIN, 0, false, true),
        CASUAL_1("c1", "1v1", "Casual · first to 3 rounds", 1, false, true),
        CASUAL_2("c2", "2v2", "Casual · first to 3 rounds", 2, false, true),
        CASUAL_3("c3", "3v3", "Casual · first to 3 rounds", 3, false, true),
        RANKED_1("r1", "Ranked 1v1", "Rated · better pay", 1, true, true),
        RANKED_2("r2", "Ranked 2v2", "Rated · better pay", 2, true, true),
        RANKED_3("r3", "Ranked 3v3", "Rated · better pay", 3, true, true),
        DUEL("duel", "Duel", "", 1, false, false);

        final String id, title, blurb;
        final int team;
        final boolean ranked, queued;

        Mode(String id, String title, String blurb, int team, boolean ranked, boolean queued) {
            this.id = id;
            this.title = title;
            this.blurb = blurb;
            this.team = team;
            this.ranked = ranked;
            this.queued = queued;
        }

        int rounds() {
            return this == DUEL ? 2 : 3;
        }

        static Mode of(String id) {
            for (Mode m : values()) if (m.id.equals(id)) return m;
            return null;
        }
    }

    /** Players queued together (a party, or one player). */
    private static final class Group {
        final List<UUID> ids = new ArrayList<>();
        final long since = System.currentTimeMillis();
        int level, rating;
    }

    private enum Phase { SHIELD, FIGHT, BETWEEN, DONE }

    private static final class Match {
        final int id;
        final Mode mode;
        final ArenaMap map;
        final int slot;
        final List<UUID> a = new ArrayList<>(), b = new ArrayList<>();
        final java.util.Map<UUID, int[]> score = new HashMap<>();
        final Set<UUID> dead = new HashSet<>(), watchers = new HashSet<>();
        final int[] rounds = new int[2];
        final List<UUID> spots = new ArrayList<>();
        /** A duel's own rules (null for everything else), and how many rounds it's to. */
        Duels.Rules rules;
        int firstTo;
        Phase phase = Phase.SHIELD;
        int timer, left;

        Match(int id, Mode mode, ArenaMap map, int slot) {
            this.id = id;
            this.mode = mode;
            this.map = map;
            this.slot = slot;
        }

        boolean ffa() {
            return mode.team == 0;
        }

        List<UUID> all() {
            List<UUID> l = new ArrayList<>(a);
            l.addAll(b);
            return l;
        }

        int side(UUID id) {
            return a.contains(id) ? 0 : b.contains(id) ? 1 : -1;
        }

        String title() {
            return mode.title + " · " + map.title;
        }
    }

    static final class Data {
        java.util.Map<String, Integer> built = new HashMap<>();
        /** Spawns per map, relative to its origin. */
        java.util.Map<String, List<int[]>> spawns = new HashMap<>();
        /** Where each player came from: uuid -> world|x|y|z|yaw|gamemode. */
        java.util.Map<String, String> back = new HashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().create();
    private static final java.util.Map<Mode, List<Group>> QUEUE = new java.util.EnumMap<>(Mode.class);
    private static final List<Match> MATCHES = new ArrayList<>();
    private static int nextId = 1;
    private static Data data = new Data();
    private static Path file;
    private static MinecraftServer server;
    private static int ticks;
    private static final java.util.ArrayDeque<Runnable> BUILD = new java.util.ArrayDeque<>();
    private static final Set<ArenaMap> building = new HashSet<>();

    public static void register() {
        for (Mode m : Mode.values()) QUEUE.put(m, new ArrayList<>());
        ServerLifecycleEvents.SERVER_STARTED.register(Arenas::open);
        ServerTickEvents.END_SERVER_TICK.register(s -> tick());
        // First in line: a fall in the arena is never a real death (no downing, no dropped gear).
        ServerLivingEntityEvents.ALLOW_DEATH.register((e, src, amt) -> !(e instanceof ServerPlayerEntity p) || !fell(p, src.getAttacker()));
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((e, src, amt) -> {
            if (!(e instanceof ServerPlayerEntity p) || p.getWorld().getRegistryKey() != WORLD) return true;
            Match m = matchOf(p.getUuid());
            if (m == null || m.phase != Phase.FIGHT || m.dead.contains(p.getUuid())) return false;
            if (p.hasStatusEffect(StatusEffects.RESISTANCE) && p.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() >= 4) return false;
            return true;
        });
        // The markers left on the real map: use one to watch.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !entity.getCommandTags().contains(SPOT)) return ActionResult.PASS;
            if (player instanceof ServerPlayerEntity sp) {
                for (String t : entity.getCommandTags()) if (t.startsWith(SPOT + ":")) {
                    try {
                        watch(sp, Integer.parseInt(t.substring(SPOT.length() + 1)));
                    } catch (NumberFormatException ignored) { }
                }
            }
            return ActionResult.SUCCESS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((e, w) -> {
            if (!e.getCommandTags().contains(SPOT)) return;
            for (Match m : MATCHES) if (m.spots.contains(e.getUuid())) return;
            e.discard();
        });
        ServerPlayNetworking.registerGlobalReceiver(Net.ArenaAction.ID, (payload, ctx) -> {
            ServerPlayerEntity p = ctx.player();
            switch (payload.action()) {
                case "join" -> queue(p, Mode.of(payload.arena()));
                case "leave" -> leave(p, true);
                case "watch" -> {
                    try {
                        watch(p, Integer.parseInt(payload.arena()));
                    } catch (NumberFormatException ignored) { }
                }
                default -> { }
            }
            send(p, payload.action().equals("open"));
        });
        ServerPlayConnectionEvents.JOIN.register((h, s, srv) -> srv.execute(() -> {
            ServerPlayerEntity p = h.player;
            if (p.getWorld().getRegistryKey() == WORLD && matchOf(p.getUuid()) == null) goBack(p);
        }));
        ServerPlayConnectionEvents.DISCONNECT.register((h, srv) -> drop(h.player.getUuid()));
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("arena")
            .executes(c -> {
                if (c.getSource().getPlayer() != null) send(c.getSource().getPlayer(), true);
                return 1;
            })
            .then(CommandManager.literal("leave").executes(c -> {
                if (c.getSource().getPlayer() != null) leave(c.getSource().getPlayer(), true);
                return 1;
            }))
            .then(CommandManager.literal("rebuild").requires(s -> s.hasPermissionLevel(2)).executes(c -> {
                data.built.clear();
                for (ArenaMap m : ArenaMap.values()) queueBuild(m);
                c.getSource().sendFeedback(() -> Text.literal("Rebuilding the arenas"), false);
                return 1;
            }))
            .then(CommandManager.literal("rating").then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 4000)).executes(c -> {
                    ServerPlayerEntity t = net.minecraft.command.argument.EntityArgumentType.getPlayer(c, "player");
                    Profile pr = AotRpg.PROFILES.get(t.getUuid());
                    pr.pvpRating = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "value");
                    AotRpg.PROFILES.save(t.getUuid());
                    c.getSource().sendFeedback(() -> Text.literal(pr.name + ": " + pr.pvpRating + " (" + tier(pr.pvpRating) + ")"), false);
                    return 1;
                }))))
            .then(CommandManager.argument("mode", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(
                    java.util.Arrays.stream(Mode.values()).filter(m -> m.queued).map(m -> m.id), b))
                .executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayer();
                    if (p != null) queue(p, Mode.of(StringArgumentType.getString(c, "mode")));
                    return 1;
                }))));
    }

    private static void open(MinecraftServer s) {
        server = s;
        MATCHES.clear();
        file = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("arenas.json");
        try {
            if (Files.exists(file)) data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read arenas.json", e);
        }
        if (data == null) data = new Data();
        if (data.built == null) data.built = new HashMap<>();
        if (data.spawns == null) data.spawns = new HashMap<>();
        if (data.back == null) data.back = new HashMap<>();
        for (ArenaMap m : ArenaMap.values()) {
            // Built by the record, but the blocks are gone (a world reset or moved): build again.
            if (data.built.getOrDefault(m.id, 0) == BUILD_VERSION && !intact(m, 0)) {
                AotRpg.LOG.warn("Arena {} is missing from its world: rebuilding", m.id);
                data.built.remove(m.id);
            }
            if (data.built.getOrDefault(m.id, 0) != BUILD_VERSION) queueBuild(m);
        }
    }

    /** The map is really there in this copy: solid ground near its spawns (looked for over a wide band of heights). */
    private static boolean intact(ArenaMap map, int slot) {
        ServerWorld w = aw();
        List<int[]> sp = spawns(map);
        if (w == null || sp.isEmpty()) return false;
        int found = 0, n = Math.min(3, sp.size());
        for (int i = 0; i < n; i++) {
            int[] s = sp.get(i * sp.size() / n);
            int x = map.ox(slot) + s[0], z = map.oz(slot) + s[2];
            w.getChunk(x >> 4, z >> 4);
            for (int y = GROUND + 48; y >= GROUND - 48; y--) {
                if (!w.getBlockState(new BlockPos(x, y, z)).isAir()) {
                    found++;
                    break;
                }
            }
        }
        // A spawn saved before the fix (at the bottom of the world): put it back on the ground.
        for (int[] s : sp) {
            if (s[1] < GROUND - 48) {
                int y = top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, map.ox(0) + s[0], map.oz(0) + s[2]);
                if (y > GROUND - 48) s[1] = y;
            }
        }
        return found >= Math.max(1, n - 1);
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save arenas.json", e);
        }
    }

    // ------------------------------------------------------------------ ranks

    private static final String[] TIERS = {"Recruit", "Cadet", "Soldier", "Veteran", "Elite", "Captain", "Ackerman"};
    private static final int[] TIER_AT = {0, 950, 1100, 1250, 1400, 1600, 1850};

    static int tierIndex(int rating) {
        int t = 0;
        for (int i = 0; i < TIER_AT.length; i++) if (rating >= TIER_AT[i]) t = i;
        return t;
    }

    public static String tier(int rating) {
        return TIERS[tierIndex(rating)];
    }

    /**
     * How far apart in level a match may be. Ranked opens up with your tier (a Captain meets
     * players well above them), and anyone's range widens a little the longer they wait.
     */
    private static int levelGap(Mode mode, Group g) {
        int wait = (int) ((System.currentTimeMillis() - g.since) / 20_000);
        return mode.ranked ? Math.min(30, 8 + 4 * tierIndex(g.rating) + wait * 2) : Math.min(20, 10 + wait * 2);
    }

    private static int ratingGap(Group g) {
        return 150 + 50 * (int) ((System.currentTimeMillis() - g.since) / 30_000);
    }

    // ------------------------------------------------------------------ who's where

    /** In a match (fighting, or out of the round and watching). */
    public static boolean inMatch(ServerPlayerEntity p) {
        Match m = matchOf(p.getUuid());
        return m != null && m.score.containsKey(p.getUuid());
    }

    /** Who decides whether these two may hit each other: true if both are in the arena world. */
    public static boolean decides(ServerPlayerEntity victim, ServerPlayerEntity attacker) {
        return victim.getWorld().getRegistryKey() == WORLD && attacker.getWorld().getRegistryKey() == WORLD;
    }

    /** No friendly fire on a team; everyone else in the same match is fair game while the round is on. */
    public static boolean allowHit(ServerPlayerEntity victim, ServerPlayerEntity attacker) {
        Match m = matchOf(victim.getUuid());
        if (m == null || m != matchOf(attacker.getUuid()) || m.phase != Phase.FIGHT) return false;
        if (m.dead.contains(attacker.getUuid()) || m.dead.contains(victim.getUuid())) return false;
        return m.ffa() || m.side(victim.getUuid()) != m.side(attacker.getUuid());
    }

    private static Match matchOf(UUID id) {
        for (Match m : MATCHES) if (m.score.containsKey(id) || m.watchers.contains(id)) return m;
        return null;
    }

    private static Group queued(UUID id) {
        for (List<Group> l : QUEUE.values()) for (Group g : l) if (g.ids.contains(id)) return g;
        return null;
    }

    private static Mode queuedMode(UUID id) {
        for (var e : QUEUE.entrySet()) for (Group g : e.getValue()) if (g.ids.contains(id)) return e.getKey();
        return null;
    }

    private static ServerPlayerEntity player(UUID id) {
        return server == null ? null : server.getPlayerManager().getPlayer(id);
    }

    private static String name(UUID id) {
        return AotRpg.PROFILES.get(id).name;
    }

    /** Why this player can't fight right now, or null. */
    private static String busy(ServerPlayerEntity p) {
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        if (!pr.created) return "Make your character first";
        if (Extraction.inRun(p.getUuid()) || Extraction.inLobby(p)) return "You're on an Extraction run";
        if (AotRpg.DUELS.isDueling(p)) return "Finish your duel first";
        if (AotRpg.DOWNED.isDowned(p)) return "You're down";
        if (matchOf(p.getUuid()) != null) return "You're already in a match";
        var k = p.getWorld().getRegistryKey();
        if (k != World.OVERWORLD && k != Homes.WORLD) return "Head back to the open world first";
        return null;
    }

    private static void no(ServerPlayerEntity p, String why) {
        Notify.toast(p, Text.literal(why).formatted(Formatting.RED), Text.empty(), 0xC0463A);
    }

    // ------------------------------------------------------------------ queueing

    private static void queue(ServerPlayerEntity p, Mode mode) {
        if (mode == null || !mode.queued || server == null) return;
        Mode was = queuedMode(p.getUuid());
        if (was == mode) return;
        if (was != null) leave(p, false);
        // A party queues together, its leader for all of them.
        List<ServerPlayerEntity> who = new ArrayList<>();
        var party = AotRpg.PARTIES.of(p.getUuid());
        if (party != null && party.members.size() > 1) {
            if (!party.leader.equals(p.getUuid())) {
                no(p, "Your party leader queues for the party");
                return;
            }
            for (UUID id : party.members) {
                ServerPlayerEntity m = player(id);
                if (m != null) who.add(m);
            }
            if (mode.team > 0 && who.size() > mode.team) {
                no(p, "Your party is too big for " + mode.title);
                return;
            }
        } else who.add(p);
        Group g = new Group();
        int rating = 0;
        for (ServerPlayerEntity m : who) {
            String why = busy(m);
            if (why != null) {
                no(p, m == p ? why : name(m.getUuid()) + ": " + why);
                return;
            }
            Profile pr = AotRpg.PROFILES.get(m.getUuid());
            if (mode.ranked && pr.level < RANKED_LEVEL) {
                no(p, (m == p ? "Ranked opens at level " : name(m.getUuid()) + " needs level ") + RANKED_LEVEL);
                return;
            }
            g.ids.add(m.getUuid());
            g.level = Math.max(g.level, pr.level);
            rating += pr.pvpRating;
        }
        g.rating = rating / who.size();
        QUEUE.get(mode).add(g);
        for (ServerPlayerEntity m : who) {
            Notify.toast(m, Text.literal("Searching · " + mode.title).formatted(Formatting.GOLD),
                Text.literal(mode.ranked ? tier(g.rating) + " · " + g.rating : mode.blurb), 0xE0B96A, "minecraft:iron_sword", null);
            send(m, false);
        }
    }

    /** Leaves the queue, a match (forfeiting your place), or watching. */
    private static void leave(ServerPlayerEntity p, boolean toast) {
        Group g = queued(p.getUuid());
        if (g != null) {
            for (List<Group> l : QUEUE.values()) l.remove(g);
            for (UUID id : g.ids) {
                ServerPlayerEntity m = player(id);
                if (m != null && toast) Notify.toast(m, Text.literal("Left the queue").formatted(Formatting.GRAY), Text.empty(), 0x9A9080);
            }
            return;
        }
        Match m = matchOf(p.getUuid());
        if (m == null) return;
        if (m.watchers.remove(p.getUuid())) {
            hud(p, null);
            goBack(p);
            return;
        }
        out(m, p.getUuid());
        hud(p, null);
        goBack(p);
    }

    /** Gone from a match (left or logged out): out of the round, and if a whole side's gone it's over. */
    private static void drop(UUID id) {
        for (List<Group> l : QUEUE.values()) for (Group g : l) g.ids.remove(id);
        for (List<Group> l : QUEUE.values()) l.removeIf(g -> g.ids.isEmpty());
        Match m = matchOf(id);
        if (m == null) return;
        if (m.watchers.remove(id)) return;
        out(m, id);
    }

    private static void out(Match m, UUID id) {
        if (m.ffa()) {
            m.score.remove(id);
            m.a.remove(id);
            return;
        }
        m.dead.add(id);
        m.score.remove(id);
        int s = m.side(id);
        if (s < 0) return;
        // A side with nobody left forfeits the whole match.
        boolean anyone = false;
        for (UUID o : s == 0 ? m.a : m.b) if (m.score.containsKey(o)) anyone = true;
        if (!anyone) {
            m.rounds[1 - s] = roundsOf(m);
            end(m, 1 - s);
        } else checkRound(m);
    }

    // ------------------------------------------------------------------ matchmaking

    private static void matchmake() {
        for (Mode mode : Mode.values()) {
            List<Group> q = QUEUE.get(mode);
            q.removeIf(g -> {
                g.ids.removeIf(id -> player(id) == null);
                return g.ids.isEmpty();
            });
            if (q.isEmpty()) continue;
            if (mode.team == 0) {
                // Deathmatch: straight into a running match with room, else a new one once two are waiting.
                Match live = null;
                for (Match m : MATCHES) if (m.mode == mode && m.phase != Phase.DONE && m.score.size() < DM_MAX) live = m;
                if (live != null) {
                    for (Group g : new ArrayList<>(q)) {
                        if (live.score.size() + g.ids.size() > DM_MAX) continue;
                        q.remove(g);
                        for (UUID id : g.ids) enter(live, id, 0);
                    }
                    continue;
                }
                int n = 0;
                for (Group g : q) n += g.ids.size();
                long waited = System.currentTimeMillis() - q.get(0).since;
                if (n >= 2 && (waited > 10_000 || n >= 6)) {
                    Match m = create(mode, null);
                    if (m == null) continue;
                    for (Group g : new ArrayList<>(q)) {
                        if (m.a.size() + g.ids.size() > DM_MAX) continue;
                        q.remove(g);
                        m.a.addAll(g.ids);
                    }
                    begin(m);
                }
                continue;
            }
            // Teams: the longest waiting group picks who it can be matched with.
            Group anchor = q.get(0);
            int need = mode.team;
            List<Group> pool = new ArrayList<>();
            for (Group g : q) {
                if (Math.abs(g.level - anchor.level) > Math.max(levelGap(mode, anchor), levelGap(mode, g))) continue;
                if (mode.ranked && Math.abs(g.rating - anchor.rating) > Math.max(ratingGap(anchor), ratingGap(g))) continue;
                pool.add(g);
            }
            // Fill two sides, biggest groups first, each onto the weaker side with room.
            pool.sort((x, y) -> y.ids.size() - x.ids.size());
            List<Group> sa = new ArrayList<>(), sb = new ArrayList<>();
            int na = 0, nb = 0;
            long pa = 0, pb = 0;
            for (Group g : pool) {
                long power = (long) (mode.ranked ? g.rating : g.level * 20) * g.ids.size();
                boolean fitsA = na + g.ids.size() <= need, fitsB = nb + g.ids.size() <= need;
                if (!fitsA && !fitsB) continue;
                if (fitsA && (!fitsB || pa <= pb)) {
                    sa.add(g);
                    na += g.ids.size();
                    pa += power;
                } else {
                    sb.add(g);
                    nb += g.ids.size();
                    pb += power;
                }
                if (na == need && nb == need) break;
            }
            if (na != need || nb != need) continue;
            Match m = create(mode, null);
            if (m == null) continue;
            for (Group g : sa) {
                q.remove(g);
                m.a.addAll(g.ids);
            }
            for (Group g : sb) {
                q.remove(g);
                m.b.addAll(g.ids);
            }
            begin(m);
        }
    }

    /** A free copy of a map (a random one unless asked for), or null if all are in use or still building. */
    private static Match create(Mode mode, ArenaMap want) {
        if (server.getWorld(WORLD) == null) return null;
        List<ArenaMap> maps = new ArrayList<>(List.of(ArenaMap.values()));
        java.util.Collections.shuffle(maps);
        if (want != null) maps = List.of(want);
        for (ArenaMap map : maps) {
            if (building.contains(map)) continue;
            for (int slot = 0; slot < SLOTS; slot++) {
                boolean taken = false;
                for (Match o : MATCHES) if (o.map == map && o.slot == slot) taken = true;
                if (taken) continue;
                if (!intact(map, slot)) {
                    // Never fight over the void: put it back up and use another.
                    data.built.remove(map.id);
                    queueBuild(map);
                    break;
                }
                Match m = new Match(nextId++, mode, map, slot);
                MATCHES.add(m);
                return m;
            }
        }
        return null;
    }

    /** Everyone in, markers left behind, and the first round (or the deathmatch) under way. */
    private static void begin(Match m) {
        for (UUID id : m.all()) enter(m, id, -1);
        if (m.ffa()) {
            m.phase = Phase.FIGHT;
            m.left = DM_SECS * 20;
        } else startRound(m);
        for (UUID id : m.all()) {
            ServerPlayerEntity o = player(id);
            if (o == null) continue;
            String vs = m.ffa() ? "First to " + DM_TO_WIN : names(m.side(id) == 0 ? m.b : m.a);
            banner(o, m.mode.title.toUpperCase(Locale.ROOT), (m.ffa() ? "" : "vs " ) + vs + "  ·  " + m.map.title, 0xE0463A, "minecraft:iron_sword");
            o.playSoundToPlayer(SoundEvents.EVENT_RAID_HORN.value(), SoundCategory.MASTER, 0.7f, 1.1f);
        }
    }

    private static String names(List<UUID> l) {
        List<String> n = new ArrayList<>();
        for (UUID id : l) n.add(name(id));
        return String.join(", ", n);
    }

    /** Takes a player into a match: their way back saved, a marker where they left (side -1 = as already placed). */
    private static void enter(Match m, UUID id, int side) {
        ServerPlayerEntity p = player(id);
        ServerWorld aw = server.getWorld(WORLD);
        if (p == null || aw == null) return;
        if (side == 0 && !m.a.contains(id)) m.a.add(id);
        m.score.put(id, new int[] {0, 0});
        if (p.getWorld().getRegistryKey() != WORLD) {
            remember(p);
            spot(m, (ServerWorld) p.getWorld(), p.getPos());
        }
        if (m.ffa()) {
            respawn(p, m, aw, -1);
            say(m, Text.literal(name(id) + " joins").formatted(Formatting.GRAY));
        }
    }

    private static void remember(ServerPlayerEntity p) {
        data.back.put(p.getUuidAsString(), p.getWorld().getRegistryKey().getValue() + "|" + p.getX() + "|" + p.getY() + "|" + p.getZ() + "|" + p.getYaw()
            + "|" + p.interactionManager.getGameMode().getName());
        save();
    }

    /** A marker on the real map where fighters left it: use it to watch (one per spot). */
    private static void spot(Match m, ServerWorld w, Vec3d at) {
        for (UUID s : m.spots) {
            Entity e = w.getEntity(s);
            if (e != null && e.squaredDistanceTo(at) < 64) return;
        }
        ArmorStandEntity st = new ArmorStandEntity(w, at.x, at.y, at.z);
        st.setInvisible(true);
        st.setNoGravity(true);
        st.setInvulnerable(true);
        st.setCustomName(Text.literal("⚔ " + m.title() + "  ·  watch").formatted(Formatting.GOLD));
        st.setCustomNameVisible(true);
        st.addCommandTag(SPOT);
        st.addCommandTag(SPOT + ":" + m.id);
        if (w.spawnEntity(st)) m.spots.add(st.getUuid());
    }

    private static void clearSpots(Match m) {
        for (ServerWorld w : server.getWorlds()) for (UUID s : m.spots) {
            Entity e = w.getEntity(s);
            if (e != null) e.discard();
        }
        m.spots.clear();
    }

    /** Watch a match from above: spectating, until it ends or you leave. */
    private static void watch(ServerPlayerEntity p, int id) {
        Match m = null;
        for (Match o : MATCHES) if (o.id == id && o.phase != Phase.DONE) m = o;
        ServerWorld aw = server.getWorld(WORLD);
        if (m == null || aw == null) {
            no(p, "That fight is over");
            return;
        }
        if (matchOf(p.getUuid()) != null || queued(p.getUuid()) != null) {
            no(p, "You're in a match or a queue");
            return;
        }
        if (Extraction.inRun(p.getUuid()) || AotRpg.DUELS.isDueling(p)) {
            no(p, "Not right now");
            return;
        }
        remember(p);
        m.watchers.add(p.getUuid());
        p.changeGameMode(GameMode.SPECTATOR);
        p.teleport(aw, m.map.ox(m.slot) + 0.5, GROUND + 70, m.map.oz(m.slot) + 40.5, 180, 35);
        banner(p, "WATCHING", m.title(), 0xC8C0B0, "minecraft:spyglass");
        hud(p, m);
    }

    /** Back to where they were before the arena (or the world's spawn), in the game mode they had. */
    private static void goBack(ServerPlayerEntity p) {
        String b = data.back.remove(p.getUuidAsString());
        save();
        ServerWorld w = null;
        double[] at = null;
        float yaw = p.getYaw();
        GameMode gm = GameMode.SURVIVAL;
        if (b != null) {
            String[] s = b.split("\\|");
            try {
                w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(s[0])));
                at = new double[] {Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3])};
                yaw = Float.parseFloat(s[4]);
                if (s.length > 5) gm = GameMode.byName(s[5], GameMode.SURVIVAL);
            } catch (Exception ignored) {
                w = null;
            }
        }
        if (w == null || at == null) {
            w = server.getOverworld();
            BlockPos sp = w.getSpawnPos();
            at = new double[] {sp.getX() + 0.5, top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, sp.getX(), sp.getZ()), sp.getZ() + 0.5};
        }
        if (gm == GameMode.SPECTATOR) gm = GameMode.SURVIVAL;
        p.changeGameMode(gm);
        p.removeStatusEffect(StatusEffects.RESISTANCE);
        p.setHealth(p.getMaxHealth());
        p.fallDistance = 0;
        p.teleport(w, at[0], at[1], at[2], yaw, 0);
    }

    // ------------------------------------------------------------------ spawning and falling

    private static List<int[]> spawns(ArenaMap map) {
        return data.spawns.getOrDefault(map.id, List.of());
    }

    /** The two spawns furthest apart: one per side. */
    private static int[][] sides(ArenaMap map) {
        List<int[]> sp = spawns(map);
        int[][] best = {{0, GROUND + 2, -30}, {0, GROUND + 2, 30}};
        double far = -1;
        for (int i = 0; i < sp.size(); i++) for (int j = i + 1; j < sp.size(); j++) {
            int[] x = sp.get(i), y = sp.get(j);
            double d = Math.pow(x[0] - y[0], 2) + Math.pow(x[2] - y[2], 2);
            if (d > far) {
                far = d;
                best = new int[][] {x, y};
            }
        }
        return best;
    }

    /** Onto a spawn (side -1: the one furthest from everyone), healed, refuelled and shielded. */
    private static void respawn(ServerPlayerEntity p, Match m, ServerWorld aw, int side) {
        int ox = m.map.ox(m.slot), oz = m.map.oz(m.slot);
        int[] at;
        if (side >= 0) {
            int[] base = sides(m.map)[side];
            List<UUID> team = side == 0 ? m.a : m.b;
            int k = Math.max(0, team.indexOf(p.getUuid()));
            at = new int[] {base[0] + (k % 2 == 0 ? k : -k), base[1], base[2]};
        } else {
            List<int[]> sp = spawns(m.map);
            at = sp.isEmpty() ? new int[] {0, GROUND + 2, 0} : sp.get(0);
            double far = -1;
            Random r = aw.getRandom();
            for (int t = 0; t < 8 && !sp.isEmpty(); t++) {
                int[] c = sp.get(r.nextInt(sp.size()));
                double near = Double.MAX_VALUE;
                for (UUID id : m.score.keySet()) {
                    ServerPlayerEntity o = player(id);
                    if (o == null || o == p || o.getWorld() != aw) continue;
                    near = Math.min(near, o.squaredDistanceTo(ox + c[0] + 0.5, c[1], oz + c[2] + 0.5));
                }
                if (near > far) {
                    far = near;
                    at = c;
                }
            }
        }
        if (p.interactionManager.getGameMode() == GameMode.SPECTATOR) p.changeGameMode(GameMode.SURVIVAL);
        p.setHealth(p.getMaxHealth());
        p.extinguish();
        p.fallDistance = 0;
        p.setVelocity(Vec3d.ZERO);
        for (var eff : new ArrayList<>(p.getStatusEffects())) if (!eff.getEffectType().value().isBeneficial()) p.removeStatusEffect(eff.getEffectType());
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, SHIELD_TICKS, 4, false, false, true));
        OdmBoost.refuel(p, 1);
        BladeCare.sharpen(p, 1);
        p.getHungerManager().setFoodLevel(20);
        float yaw = (float) Math.toDegrees(Math.atan2(at[0], -at[2]));
        p.teleport(aw, ox + at[0] + 0.5, at[1], oz + at[2] + 0.5, yaw, 0);
        p.velocityModified = true;
    }

    /**
     * A fighter who would have died, or fell off the map: their killer scores; in deathmatch they're
     * back on a spawn, in rounds they're out until the next. True if handled here (no real death).
     */
    private static boolean fell(ServerPlayerEntity p, Entity killer) {
        if (p.getWorld().getRegistryKey() != WORLD) return false;
        Match m = matchOf(p.getUuid());
        ServerWorld aw = (ServerWorld) p.getWorld();
        if (m == null || !m.score.containsKey(p.getUuid()) || m.dead.contains(p.getUuid())) {
            p.setHealth(p.getMaxHealth());
            if (m == null) goBack(p);
            return true;
        }
        // Off the edge before the fight's begun (or between rounds): back on your spawn, no harm done.
        if (!m.ffa() && m.phase != Phase.FIGHT) {
            respawn(p, m, aw, m.side(p.getUuid()));
            return true;
        }
        if (killer == null) killer = p.getAttacker();
        if (killer == null) killer = p.getPrimeAdversary();
        m.score.get(p.getUuid())[1]++;
        aw.spawnParticles(ParticleTypes.CLOUD, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.6, 0.4, 0.08);
        String who = name(p.getUuid());
        if (killer instanceof ServerPlayerEntity k && k != p && m.score.containsKey(k.getUuid())) {
            int[] ks = m.score.get(k.getUuid());
            ks[0]++;
            k.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.MASTER, 1, 0.7f);
            k.playSoundToPlayer(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.MASTER, 0.8f, 1.4f);
            say(m, Text.literal(name(k.getUuid())).formatted(Formatting.GOLD)
                .append(Text.literal("  ⚔  ").formatted(Formatting.DARK_RED)).append(Text.literal(who).formatted(Formatting.WHITE)));
        } else say(m, Text.literal(who + " fell").formatted(Formatting.GRAY));
        p.playSoundToPlayer(SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.MASTER, 1, 0.6f);
        if (m.ffa()) {
            respawn(p, m, aw, -1);
            if (killer instanceof ServerPlayerEntity k && m.score.containsKey(k.getUuid())) {
                k.heal(6);
                if (m.score.get(k.getUuid())[0] >= DM_TO_WIN) end(m, -1);
            }
            return true;
        }
        // Out of the round: watching where you fell until the next.
        p.setHealth(p.getMaxHealth());
        m.dead.add(p.getUuid());
        p.changeGameMode(GameMode.SPECTATOR);
        if (p.getY() < -30) p.requestTeleport(p.getX(), GROUND + 30, p.getZ());
        checkRound(m);
        return true;
    }

    private static void say(Match m, Text t) {
        for (UUID id : m.score.keySet()) {
            ServerPlayerEntity o = player(id);
            if (o != null) o.sendMessage(t, true);
        }
        for (UUID id : m.watchers) {
            ServerPlayerEntity o = player(id);
            if (o != null) o.sendMessage(t, true);
        }
    }

    private static void banner(ServerPlayerEntity p, String title, String sub, int color, String icon) {
        if (ServerPlayNetworking.canSend(p, Net.EventBanner.ID)) ServerPlayNetworking.send(p, new Net.EventBanner(title, sub, color, icon));
    }

    // ------------------------------------------------------------------ rounds

    private static void startRound(Match m) {
        ServerWorld aw = server.getWorld(WORLD);
        m.dead.clear();
        // Those who left stay out.
        for (UUID id : m.all()) if (!m.score.containsKey(id)) m.dead.add(id);
        m.phase = Phase.SHIELD;
        m.timer = SHIELD_TICKS;
        m.left = ROUND_SECS * 20;
        for (UUID id : m.all()) {
            ServerPlayerEntity o = player(id);
            if (o == null || !m.score.containsKey(id) || aw == null) continue;
            respawn(o, m, aw, m.side(id));
            int r = m.rounds[0] + m.rounds[1] + 1;
            banner(o, "ROUND " + r, m.rounds[m.side(id)] + " — " + m.rounds[1 - m.side(id)], 0xE8C47A, "minecraft:iron_sword");
        }
    }

    private static int alive(Match m, int side) {
        int n = 0;
        for (UUID id : side == 0 ? m.a : m.b) if (m.score.containsKey(id) && !m.dead.contains(id)) n++;
        return n;
    }

    private static void checkRound(Match m) {
        if (m.ffa() || m.phase != Phase.FIGHT) return;
        int la = alive(m, 0), lb = alive(m, 1);
        if (la > 0 && lb > 0) return;
        roundOver(m, la > 0 ? 0 : lb > 0 ? 1 : -1);
    }

    /** A round to one side (or a draw, -1): first to the match's rounds wins it. */
    private static void roundOver(Match m, int winner) {
        if (winner >= 0) m.rounds[winner]++;
        for (UUID id : m.all()) {
            ServerPlayerEntity o = player(id);
            if (o == null) continue;
            int s = m.side(id);
            String t = winner < 0 ? "DRAW" : winner == s ? "ROUND WON" : "ROUND LOST";
            banner(o, t, m.rounds[s] + " — " + m.rounds[1 - s], winner == s ? 0xFFD24A : 0xC8C0B0, "minecraft:iron_sword");
        }
        if (winner >= 0 && m.rounds[winner] >= roundsOf(m)) {
            end(m, winner);
            return;
        }
        m.phase = Phase.BETWEEN;
        m.timer = BETWEEN_TICKS;
    }

    // ------------------------------------------------------------------ the tick

    private static void tick() {
        if (server == null) return;
        ticks++;
        if (!BUILD.isEmpty() && server.getAverageTickTime() < 30) for (int i = 0; i < 2 && !BUILD.isEmpty(); i++) BUILD.poll().run();
        ServerWorld aw = server.getWorld(WORLD);
        if (aw == null) return;
        if (ticks % 20 == 0) matchmake();
        for (Match m : new ArrayList<>(MATCHES)) {
            if (m.phase == Phase.DONE) continue;
            if (m.phase == Phase.SHIELD && --m.timer <= 0) {
                m.phase = Phase.FIGHT;
                checkRound(m);
            }
            if (m.phase == Phase.BETWEEN && --m.timer <= 0) startRound(m);
            if (m.phase == Phase.FIGHT && --m.left <= 0) {
                if (m.ffa()) end(m, -1);
                else {
                    // Time: the side with more standing takes it, then the one with more health.
                    int la = alive(m, 0), lb = alive(m, 1);
                    if (la != lb) roundOver(m, la > lb ? 0 : 1);
                    else {
                        float ha = health(m, m.a), hb = health(m, m.b);
                        roundOver(m, Math.abs(ha - hb) < 1 ? -1 : ha > hb ? 0 : 1);
                    }
                }
                if (m.phase == Phase.DONE) continue;
            }
            for (UUID id : new ArrayList<>(m.score.keySet())) {
                ServerPlayerEntity o = player(id);
                if (o == null) continue;
                // Left the arena some other way: out of the match.
                if (o.getWorld() != aw) {
                    out(m, id);
                    hud(o, null);
                    continue;
                }
                if (o.getY() < -40 && !m.dead.contains(id)) fell(o, null);
                else if (m.map == ArenaMap.GROVE) {
                    double dx = o.getX() - m.map.ox(m.slot), dz = o.getZ() - m.map.oz(m.slot);
                    if (dx * dx + dz * dz > 110 * 110) {
                        Vec3d in = new Vec3d(-dx, 0, -dz).normalize().multiply(0.9);
                        o.setVelocity(o.getVelocity().multiply(0.4).add(in.x, 0.15, in.z));
                        o.velocityModified = true;
                        if (ticks % 20 == 0) o.sendMessage(Text.literal("Out of bounds").formatted(Formatting.RED), true);
                    }
                }
            }
            if (m.phase == Phase.DONE) continue;
            if (ticks % 20 == 0) {
                for (UUID id : m.score.keySet()) {
                    ServerPlayerEntity o = player(id);
                    if (o != null) hud(o, m);
                }
                for (UUID id : m.watchers) {
                    ServerPlayerEntity o = player(id);
                    if (o != null) hud(o, m);
                }
                // The markers glint, so people find them.
                for (ServerWorld w : server.getWorlds()) for (UUID s : m.spots) {
                    Entity e = w.getEntity(s);
                    if (e != null) w.spawnParticles(ParticleTypes.END_ROD, e.getX(), e.getY() + 1.2, e.getZ(), 3, 0.3, 0.6, 0.3, 0.01);
                }
            }
            if (m.score.isEmpty()) end(m, -1);
        }
        MATCHES.removeIf(m -> m.phase == Phase.DONE);
        // Nobody stays in the arena world outside a match (an operator aside).
        if (ticks % 40 == 0) for (ServerPlayerEntity o : new ArrayList<>(aw.getPlayers())) {
            if (matchOf(o.getUuid()) == null && !o.hasPermissionLevel(2) && o.isAlive()) goBack(o);
        }
    }

    private static float health(Match m, List<UUID> side) {
        float h = 0;
        for (UUID id : side) {
            ServerPlayerEntity o = player(id);
            if (o != null && m.score.containsKey(id) && !m.dead.contains(id)) h += o.getHealth();
        }
        return h;
    }

    // ------------------------------------------------------------------ the end

    /** Pay, rating and the banner, then everyone home. winner: side, or -1 for deathmatch or a draw. */
    private static void end(Match m, int winner) {
        if (m.phase == Phase.DONE) return;
        m.phase = Phase.DONE;
        if (m.ffa()) {
            List<java.util.Map.Entry<UUID, int[]>> rank = new ArrayList<>(m.score.entrySet());
            rank.sort((x, y) -> x.getValue()[0] != y.getValue()[0] ? y.getValue()[0] - x.getValue()[0] : x.getValue()[1] - y.getValue()[1]);
            String top = rank.isEmpty() ? "" : name(rank.get(0).getKey());
            for (int i = 0; i < rank.size(); i++) {
                ServerPlayerEntity o = player(rank.get(i).getKey());
                if (o == null) continue;
                int[] s = rank.get(i).getValue();
                boolean won = i == 0 && s[0] > 0;
                banner(o, won ? "VICTORY" : "#" + (i + 1) + " OF " + rank.size(), won ? s[0] + " kills" : top + " takes it",
                    won ? 0xFFD24A : 0xC8C0B0, won ? "minecraft:totem_of_undying" : "minecraft:iron_sword");
                o.playSoundToPlayer(won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.MASTER, 0.8f, 1);
                AotRpg.WALLET.earn(o, won ? 400 : 60 + 20L * s[0], m.mode.title);
            }
        } else {
            // Ranked: the rating moves by how expected the result was (team averages).
            int ra = avgRating(m.a), rb = avgRating(m.b);
            for (UUID id : m.all()) {
                ServerPlayerEntity o = player(id);
                Profile pr = AotRpg.PROFILES.get(id);
                int s = m.side(id);
                boolean won = winner == s;
                int[] sc = m.score.getOrDefault(id, new int[2]);
                String sub = m.rounds[s] + " — " + m.rounds[1 - s];
                if (m.mode.ranked) {
                    double mine = s == 0 ? ra : rb, theirs = s == 0 ? rb : ra;
                    double expect = 1 / (1 + Math.pow(10, (theirs - mine) / 400));
                    int before = pr.pvpRating, delta = (int) Math.round(28 * ((won ? 1 : 0) - expect));
                    pr.pvpRating = Math.max(0, before + delta);
                    if (won) pr.pvpWins++;
                    else pr.pvpLosses++;
                    sub += "  ·  " + (delta >= 0 ? "+" : "") + delta + "  " + tier(pr.pvpRating);
                    if (o != null && tierIndex(pr.pvpRating) > tierIndex(before))
                        AotRpg.SCHEDULER.later(50, () -> banner(o, "PROMOTED", tier(pr.pvpRating).toUpperCase(Locale.ROOT), 0xFFD24A, "minecraft:nether_star"));
                    if (o != null) AotRpg.WALLET.earn(o, won ? 320 + 40L * tierIndex(pr.pvpRating) + 15L * sc[0] : 90 + 15L * sc[0], m.mode.title);
                } else if (m.mode != Mode.DUEL && o != null) {
                    AotRpg.WALLET.earn(o, won ? 180 + 15L * sc[0] : 60 + 15L * sc[0], m.mode.title);
                }
                AotRpg.PROFILES.save(id);
                if (o == null) continue;
                banner(o, won ? "VICTORY" : winner < 0 ? "DRAW" : "DEFEAT", sub, won ? 0xFFD24A : 0xC8C0B0, won ? "minecraft:totem_of_undying" : "minecraft:iron_sword");
                o.playSoundToPlayer(won ? SoundEvents.UI_TOAST_CHALLENGE_COMPLETE : SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.MASTER, 0.8f, 1);
            }
        }
        List<UUID> home = m.all();
        home.addAll(m.watchers);
        for (UUID id : home) {
            ServerPlayerEntity o = player(id);
            if (o == null) continue;
            hud(o, null);
            if (o.interactionManager.getGameMode() == GameMode.SPECTATOR && !m.watchers.contains(id)) o.changeGameMode(GameMode.SURVIVAL);
            AotRpg.SCHEDULER.later(m.watchers.contains(id) ? 20 : 70, () -> {
                if (o.isAlive() && o.getWorld().getRegistryKey() == WORLD) goBack(o);
            });
        }
        m.score.clear();
        m.watchers.clear();
        clearSpots(m);
    }

    private static int avgRating(List<UUID> l) {
        if (l.isEmpty()) return 1000;
        int s = 0;
        for (UUID id : l) s += AotRpg.PROFILES.get(id).pvpRating;
        return s / l.size();
    }

    // ------------------------------------------------------------------ duels

    /** A duel fought in an arena instead of where the two stand: first to two rounds. */
    /** The custom rules of the arena duel this player is in, or null. */
    public static Duels.Rules duelRules(ServerPlayerEntity p) {
        Match m = matchOf(p.getUuid());
        return m == null || m.rules == null || !m.score.containsKey(p.getUuid()) ? null : m.rules;
    }

    private static int roundsOf(Match m) {
        return m.firstTo > 0 ? m.firstTo : m.mode.rounds();
    }

    public static boolean duel(ServerPlayerEntity a, ServerPlayerEntity b, String mapId, Duels.Rules rules) {
        ArenaMap map = ArenaMap.of(mapId);
        if (map == null || server == null) return false;
        for (ServerPlayerEntity p : List.of(a, b)) {
            Mode q = queuedMode(p.getUuid());
            if (q != null) leave(p, false);
            String why = busy(p);
            if (why != null && !why.startsWith("Finish your duel")) {
                no(a, name(p.getUuid()) + ": " + why);
                no(b, name(p.getUuid()) + ": " + why);
                return true;
            }
        }
        Match m = create(Mode.DUEL, map);
        if (m == null) {
            String why = building.contains(map) ? map.title + " is being built: try again in a minute" : "Every " + map.title + " is in use";
            no(a, why);
            no(b, why);
            return true;
        }
        m.a.add(a.getUuid());
        m.b.add(b.getUuid());
        // Custom rules: first to N, with gear, abilities and food as chosen. Regular: first to 2, all on.
        if (rules != null && rules.rounds() > 0) {
            m.rules = rules;
            m.firstTo = rules.rounds();
        }
        begin(m);
        return true;
    }

    // ------------------------------------------------------------------ to the client

    private static void hud(ServerPlayerEntity p, Match m) {
        if (!ServerPlayNetworking.canSend(p, Net.ArenaHud.ID)) return;
        if (m == null) {
            ServerPlayNetworking.send(p, new Net.ArenaHud("", -1, "", ""));
            return;
        }
        int secs = m.phase == Phase.FIGHT ? m.left / 20 : m.phase == Phase.SHIELD ? -2 : 0;
        String score, sub;
        if (m.ffa()) {
            int[] me = m.score.getOrDefault(p.getUuid(), new int[2]);
            String lead = "";
            int lk = -1;
            for (var e : m.score.entrySet()) if (e.getValue()[0] > lk) {
                lk = e.getValue()[0];
                lead = name(e.getKey());
            }
            score = m.score.containsKey(p.getUuid()) ? me[0] + " / " + DM_TO_WIN : lead + "  " + Math.max(0, lk);
            sub = m.score.containsKey(p.getUuid()) ? "Lead: " + lead + " " + Math.max(0, lk) + "  ·  " + me[1] + " falls" : m.score.size() + " fighting";
        } else {
            int s = Math.max(0, m.side(p.getUuid()));
            score = m.rounds[s] + "  —  " + m.rounds[1 - s];
            sub = alive(m, s) + " v " + alive(m, 1 - s) + " standing  ·  first to " + roundsOf(m);
            if (m.dead.contains(p.getUuid()) && m.score.containsKey(p.getUuid())) sub = "Out until the next round";
        }
        ServerPlayNetworking.send(p, new Net.ArenaHud(m.title(), secs, score, sub));
    }

    static void send(ServerPlayerEntity p, boolean open) {
        if (!ServerPlayNetworking.canSend(p, Net.ArenaView.ID)) return;
        Profile pr = AotRpg.PROFILES.get(p.getUuid());
        Mode qm = queuedMode(p.getUuid());
        Group g = queued(p.getUuid());
        Match mine = matchOf(p.getUuid());
        List<Net.ArenaEntry> l = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            if (!mode.queued) continue;
            int waiting = 0, playing = 0;
            for (Group o : QUEUE.get(mode)) waiting += o.ids.size();
            for (Match o : MATCHES) if (o.mode == mode) playing += o.score.size();
            String you = mine != null && mine.mode == mode ? "playing" : qm == mode ? "queued" : "";
            int secs = qm == mode && g != null ? (int) ((System.currentTimeMillis() - g.since) / 1000) : 0;
            boolean unlocked = !mode.ranked || pr.level >= RANKED_LEVEL;
            String blurb = unlocked ? mode.blurb : "Opens at level " + RANKED_LEVEL;
            l.add(new Net.ArenaEntry(mode.id, mode.title, blurb, waiting, playing, mode.ranked ? "ranked" : mode.team == 0 ? "ffa" : "casual", secs, you, unlocked));
        }
        List<Net.ArenaLive> live = new ArrayList<>();
        for (Match m : MATCHES) {
            if (m.phase == Phase.DONE) continue;
            String score = m.ffa() ? m.score.size() + " fighting" : m.rounds[0] + " — " + m.rounds[1];
            live.add(new Net.ArenaLive(m.id, m.title(), m.ffa() ? names(new ArrayList<>(m.score.keySet())) : names(m.a) + "  vs  " + names(m.b), score));
        }
        ServerPlayNetworking.send(p, new Net.ArenaView(l, live, pr.pvpRating, tier(pr.pvpRating), pr.pvpWins, pr.pvpLosses, open));
    }

    // ------------------------------------------------------------------ building the maps

    private static void queueBuild(ArenaMap map) {
        if (building.contains(map)) return;
        building.add(map);
        List<int[]> spawns = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            // The same layout in every copy (same seed), at its own origin.
            Random r = Random.create(map.id.hashCode() * 31L + BUILD_VERSION);
            List<int[]> sp = slot == 0 ? spawns : new ArrayList<>();
            if (map == ArenaMap.GROVE) grove(map.ox(slot), map.oz(slot), r, sp);
            else skyline(map.ox(slot), map.oz(slot), r, sp);
        }
        BUILD.add(() -> {
            if (aw() == null) return;
            data.spawns.put(map.id, spawns);
            data.built.put(map.id, BUILD_VERSION);
            building.remove(map);
            save();
            AotRpg.LOG.info("Arena {} built", map.id);
        });
    }

    /** The ground height at a column, loading its chunk first (an unloaded one reads as the bottom of the world). */
    private static int top(ServerWorld w, Heightmap.Type type, int x, int z) {
        w.getChunk(x >> 4, z >> 4);
        return w.getTopY(type, x, z);
    }

    private static ServerWorld aw() {
        return server.getWorld(WORLD);
    }

    private static void set(ServerWorld w, int x, int y, int z, BlockState s) {
        w.setBlockState(new BlockPos(x, y, z), s, 2 | 16);
    }

    /** A floating island of meadow, its underside tapering into rock, giant trees all over it. Spawns are relative. */
    private static void grove(int ox, int oz, Random r, List<int[]> spawns) {
        int rad = 90;
        for (int dx = -rad; dx <= rad; dx++) {
            int fx = dx;
            BUILD.add(() -> {
                ServerWorld w = aw();
                if (w == null) return;
                for (int dz = -rad; dz <= rad; dz++) {
                    double d = Math.sqrt(fx * fx + dz * dz);
                    if (d > rad) continue;
                    int top = GROUND + (int) Math.round(Math.sin(fx * 0.045) * 1.6 + Math.cos(dz * 0.05) * 1.4);
                    int depth = (int) (4 + (1 - d / rad) * 30 + Math.sin(fx * 0.3 + dz * 0.2) * 2);
                    for (int y = top - depth; y <= top; y++) {
                        BlockState s = y == top ? Blocks.GRASS_BLOCK.getDefaultState() : y > top - 4 ? Blocks.DIRT.getDefaultState()
                            : Math.floorMod(y + fx + dz, 7) == 0 ? Blocks.ANDESITE.getDefaultState() : Blocks.STONE.getDefaultState();
                        set(w, ox + fx, y, oz + dz, s);
                    }
                    if (d < rad - 3 && Math.floorMod((fx * 73856093) ^ (dz * 19349663), 9) == 0) set(w, ox + fx, top + 1, oz + dz, Blocks.SHORT_GRASS.getDefaultState());
                }
            });
        }
        List<int[]> trees = new ArrayList<>();
        for (int t = 0; t < 400 && trees.size() < 18; t++) {
            double a = r.nextDouble() * Math.PI * 2, d = 12 + r.nextDouble() * 68;
            int x = (int) (Math.cos(a) * d), z = (int) (Math.sin(a) * d);
            boolean ok = true;
            for (int[] o : trees) if ((o[0] - x) * (o[0] - x) + (o[1] - z) * (o[1] - z) < 26 * 26) ok = false;
            if (ok) trees.add(new int[] {x, z});
        }
        for (int[] t : trees) {
            BUILD.add(() -> {
                ServerWorld w = aw();
                if (w == null) return;
                int y = top(w, Heightmap.Type.OCEAN_FLOOR, ox + t[0], oz + t[1]);
                GiantTrees.grow(w, new BlockPos(ox + t[0], y, oz + t[1]), Random.create(t[0] * 31L + t[1]), GiantTrees.Wood.FOREST, 62, 88, 15);
            });
        }
        for (int i = 0; i < 6; i++) {
            double a = r.nextDouble() * Math.PI * 2, d = 20 + r.nextDouble() * 55, dir = r.nextDouble() * Math.PI;
            int x0 = ox + (int) (Math.cos(a) * d), z0 = oz + (int) (Math.sin(a) * d), len = 10 + r.nextInt(8);
            BUILD.add(() -> {
                ServerWorld w = aw();
                if (w == null) return;
                var ax = Math.abs(Math.cos(dir)) > Math.abs(Math.sin(dir)) ? Direction.Axis.X : Direction.Axis.Z;
                BlockState log = Blocks.SPRUCE_LOG.getDefaultState().with(PillarBlock.AXIS, ax);
                for (int s = 0; s < len; s++) {
                    int x = x0 + (int) Math.round(Math.cos(dir) * s), z = z0 + (int) Math.round(Math.sin(dir) * s);
                    int y = top(w, Heightmap.Type.OCEAN_FLOOR, x, z);
                    for (int k = -1; k <= 1; k++) for (int h = 0; h < 2; h++) {
                        int px = ax == Direction.Axis.Z ? x + k : x, pz = ax == Direction.Axis.X ? z + k : z;
                        BlockState at = w.getBlockState(new BlockPos(px, y + h, pz));
                        if (at.isAir() || at.isReplaceable()) set(w, px, y + h, pz, log);
                    }
                }
            });
        }
        for (int i = 0; i < 16; i++) {
            double a = i / 16.0 * Math.PI * 2;
            int x = (int) (Math.cos(a) * 58), z = (int) (Math.sin(a) * 58);
            boolean clear = true;
            for (int[] t : trees) if ((t[0] - x) * (t[0] - x) + (t[1] - z) * (t[1] - z) < 8 * 8) clear = false;
            if (clear) spawns.add(new int[] {x, GROUND + 4, z});
        }
        BUILD.add(() -> {
            ServerWorld w = aw();
            if (w == null) return;
            for (int[] s : spawns) s[1] = top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, ox + s[0], oz + s[2]);
        });
    }

    private static final BlockState[][] FACADES = {
        {Blocks.STONE_BRICKS.getDefaultState(), Blocks.CHISELED_STONE_BRICKS.getDefaultState()},
        {Blocks.BRICKS.getDefaultState(), Blocks.STONE_BRICKS.getDefaultState()},
        {Blocks.POLISHED_ANDESITE.getDefaultState(), Blocks.STONE_BRICKS.getDefaultState()},
        {Blocks.DEEPSLATE_TILES.getDefaultState(), Blocks.POLISHED_DEEPSLATE.getDefaultState()},
        {Blocks.SMOOTH_SANDSTONE.getDefaultState(), Blocks.CUT_SANDSTONE.getDefaultState()},
        {Blocks.MUD_BRICKS.getDefaultState(), Blocks.PACKED_MUD.getDefaultState()},
    };

    /** Towers rising out of nothing: no street, no floor, only the drop between them. */
    private static void skyline(int ox, int oz, Random r, List<int[]> spawns) {
        int n = 5, gap = 30;
        List<int[]> towers = new ArrayList<>();
        for (int gx = 0; gx < n; gx++) for (int gz = 0; gz < n; gz++) {
            if ((gx == 0 || gx == n - 1) && (gz == 0 || gz == n - 1)) continue;
            int cx = ox + (gx - n / 2) * gap + r.nextInt(7) - 3, cz = oz + (gz - n / 2) * gap + r.nextInt(7) - 3;
            int half = 5 + r.nextInt(4);
            boolean middle = gx == n / 2 && gz == n / 2;
            int top = middle ? GROUND + 110 : GROUND + 30 + r.nextInt(55);
            int bottom = top - 70 - r.nextInt(40);
            towers.add(new int[] {cx, cz, half, top, bottom, r.nextInt(FACADES.length)});
        }
        for (int[] t : towers) {
            for (int y0 = t[4]; y0 <= t[3]; y0 += 8) {
                int from = y0;
                BUILD.add(() -> tower(t, from, Math.min(t[3], from + 7)));
            }
            BUILD.add(() -> roof(t));
            spawns.add(new int[] {t[0] - ox, t[3] + 2, t[1] - oz});
        }
        for (int i = 0; i < towers.size(); i++) for (int j = i + 1; j < towers.size(); j++) {
            int[] a = towers.get(i), b = towers.get(j);
            int dx = b[0] - a[0], dz = b[1] - a[1];
            boolean pick = r.nextInt(3) == 0;
            int y = Math.min(a[3], b[3]) - 10 - r.nextInt(20);
            if (dx * dx + dz * dz > (gap + 8) * (gap + 8) || !pick) continue;
            if (y < Math.max(a[4], b[4]) + 4) continue;
            BUILD.add(() -> bridge(a, b, y));
        }
    }

    private static void tower(int[] t, int y0, int y1) {
        ServerWorld w = aw();
        if (w == null) return;
        int cx = t[0], cz = t[1], h = t[2];
        BlockState wall = FACADES[t[5]][0], trim = FACADES[t[5]][1];
        for (int y = y0; y <= y1; y++) {
            boolean floor = (y - t[4]) % 8 == 0;
            for (int dx = -h; dx <= h; dx++) for (int dz = -h; dz <= h; dz++) {
                boolean edge = Math.abs(dx) == h || Math.abs(dz) == h;
                boolean corner = Math.abs(dx) == h && Math.abs(dz) == h;
                if (!edge) {
                    if (floor) set(w, cx + dx, y, cz + dz, trim);
                    continue;
                }
                int along = Math.abs(dx) == h ? dz : dx;
                int band = (y - t[4]) % 8;
                // Windows in rows; every third storey's are open, to fly in and out through.
                boolean window = !corner && !floor && band >= 2 && band <= 5 && Math.floorMod(along, 3) != 0 && Math.abs(along) < h - 1;
                boolean open = window && ((y - t[4]) / 8) % 3 == 1;
                if (open) continue;
                set(w, cx + dx, y, cz + dz, window ? Blocks.GLASS.getDefaultState() : corner || floor ? trim : wall);
            }
        }
        if (y0 == t[4]) {
            for (int k = 1; k <= 6; k++) {
                int hh = h - k;
                if (hh < 1) break;
                for (int dx = -hh; dx <= hh; dx++) for (int dz = -hh; dz <= hh; dz++)
                    if (Math.floorMod(dx * 7 + dz * 13 + k, 4) != 0) set(w, cx + dx, t[4] - k, cz + dz, k < 3 ? trim : Blocks.COBBLESTONE.getDefaultState());
            }
        }
    }

    private static void roof(int[] t) {
        ServerWorld w = aw();
        if (w == null) return;
        int cx = t[0], cz = t[1], h = t[2], y = t[3] + 1;
        BlockState trim = FACADES[t[5]][1];
        for (int dx = -h; dx <= h; dx++) for (int dz = -h; dz <= h; dz++) {
            set(w, cx + dx, t[3], cz + dz, trim);
            if (Math.abs(dx) == h || Math.abs(dz) == h) set(w, cx + dx, y, cz + dz, Blocks.STONE_BRICK_WALL.getDefaultState());
        }
        // A water tower or a spire on the roofs: more to hook onto up high.
        if (Math.floorMod(cx + cz, 2) == 0) {
            for (int k = 0; k < 6; k++) set(w, cx + h - 2, y + k, cz + h - 2, Blocks.SPRUCE_FENCE.getDefaultState());
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int k = 6; k < 9; k++)
                set(w, cx + h - 2 + dx, y + k, cz + h - 2 + dz, k == 8 ? Blocks.SPRUCE_SLAB.getDefaultState() : Blocks.SPRUCE_PLANKS.getDefaultState());
        } else {
            for (int k = 0; k < 14; k++) set(w, cx - h + 2, y + k, cz - h + 2, k < 10 ? trim : Blocks.IRON_BARS.getDefaultState());
        }
    }

    private static void bridge(int[] a, int[] b, int y) {
        ServerWorld w = aw();
        if (w == null) return;
        double dx = b[0] - a[0], dz = b[1] - a[1], len = Math.sqrt(dx * dx + dz * dz);
        for (int s = 0; s <= len; s++) {
            int x = a[0] + (int) Math.round(dx / len * s), z = a[1] + (int) Math.round(dz / len * s);
            for (int k = -1; k <= 1; k++) {
                int px = Math.abs(dx) > Math.abs(dz) ? x : x + k, pz = Math.abs(dx) > Math.abs(dz) ? z + k : z;
                boolean inA = Math.abs(px - a[0]) < a[2] && Math.abs(pz - a[1]) < a[2], inB = Math.abs(px - b[0]) < b[2] && Math.abs(pz - b[1]) < b[2];
                if (inA || inB) continue;
                set(w, px, y, pz, Blocks.DARK_OAK_PLANKS.getDefaultState());
                set(w, px, y + 1, pz, k != 0 && s % 4 == 0 ? Blocks.DARK_OAK_FENCE.getDefaultState() : Blocks.AIR.getDefaultState());
                set(w, px, y + 2, pz, Blocks.AIR.getDefaultState());
            }
        }
    }
}
