package com.pglol.aotrpg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Places worth swinging through, all over the map (not only Marley's city and the great forest):
 * <ul>
 *   <li>Ruined Quarter: a district of broken stone towers, twenty to fifty high, bridged here and there;</li>
 *   <li>Stone Spires: a field of tall rock needles and natural arches;</li>
 *   <li>Giant Grove: a stand of the giant trees, bare trunks and high branches;</li>
 *   <li>Wall Breach: a fallen section of a great wall with its watchtowers.</li>
 * </ul>
 * Sites are planned around every region of the map, out of the towns, and each is built the first
 * time someone comes near (a few thousand blocks a tick, so nobody feels it). Every block changed is
 * remembered, so an admin can take a site down again exactly as it was: /aotrpg swingsites.
 */
public final class SwingSites {
    private SwingSites() {}

    public enum Kind {
        RUINS("Ruined Quarter"), SPIRES("Stone Spires"), GROVE("Giant Grove"), WALL("Wall Breach");

        public final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    static final class Site {
        String id;
        Kind kind;
        int x, z;
        boolean built;
        long seed;
    }

    static final class Data {
        boolean auto = true;
        List<Site> sites = new ArrayList<>();
    }

    private static Data data = new Data();
    private static Path dir;
    private static MinecraftServer server;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** A site being built: what's left to place, and the record of what was there. */
    private static Site building;
    private static ArrayDeque<Object[]> queue = new ArrayDeque<>();
    private static List<String> undo = new ArrayList<>();
    private static final int PER_TICK = 3000;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            dir = s.getSavePath(WorldSavePath.ROOT).resolve("aot_rpg").resolve("swing_sites");
            try {
                Path f = dir.resolve("sites.json");
                if (Files.exists(f)) data = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
            } catch (Exception e) {
                AotRpg.LOG.error("Could not read swing sites", e);
            }
            if (data == null) data = new Data();
            if (data.sites == null) data.sites = new ArrayList<>();
            if (data.sites.isEmpty()) plan();
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (building != null) step();
            else if (data.auto && s.getTicks() % 40 == 17) wake();
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("aotrpg").requires(src -> src.hasPermissionLevel(2))
            .then(CommandManager.literal("swingsites")
                .then(CommandManager.literal("list").executes(c -> {
                    for (Site st : data.sites) c.getSource().sendFeedback(() -> Text.literal(st.id + "  " + st.kind.title + "  at " + st.x + ", " + st.z
                        + (st.built ? "  (built)" : "")).formatted(st.built ? Formatting.GREEN : Formatting.GRAY), false);
                    c.getSource().sendFeedback(() -> Text.literal("Building automatically: " + (data.auto ? "on" : "off")), false);
                    return 1;
                }))
                .then(CommandManager.literal("auto").then(CommandManager.argument("on", StringArgumentType.word()).executes(c -> {
                    data.auto = StringArgumentType.getString(c, "on").equalsIgnoreCase("on");
                    save();
                    c.getSource().sendFeedback(() -> Text.literal("Swing sites build automatically: " + (data.auto ? "on" : "off")), true);
                    return 1;
                })))
                .then(CommandManager.literal("here").then(CommandManager.argument("kind", StringArgumentType.word()).executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    Kind k;
                    try {
                        k = Kind.valueOf(StringArgumentType.getString(c, "kind").toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        c.getSource().sendFeedback(() -> Text.literal("Kinds: ruins, spires, grove, wall").formatted(Formatting.RED), false);
                        return 0;
                    }
                    Site st = new Site();
                    st.id = "s" + System.currentTimeMillis() % 100000;
                    st.kind = k;
                    st.x = p.getBlockX();
                    st.z = p.getBlockZ();
                    st.seed = p.getRandom().nextLong();
                    data.sites.add(st);
                    save();
                    if (building == null) start(st);
                    c.getSource().sendFeedback(() -> Text.literal("Building a " + k.title + " here (" + st.id + ")").formatted(Formatting.GREEN), true);
                    return 1;
                })))
                .then(CommandManager.literal("remove").then(CommandManager.argument("id", StringArgumentType.word()).executes(c -> {
                    String id = StringArgumentType.getString(c, "id");
                    Site st = null;
                    for (Site x : data.sites) if (x.id.equals(id)) st = x;
                    if (st == null || building == st) {
                        c.getSource().sendFeedback(() -> Text.literal("No such finished site.").formatted(Formatting.RED), false);
                        return 0;
                    }
                    int n = restore(st);
                    data.sites.remove(st);
                    save();
                    c.getSource().sendFeedback(() -> Text.literal("Removed " + id + ": " + n + " blocks put back.").formatted(Formatting.YELLOW), true);
                    return 1;
                }))))));
    }

    /** Markers for the map and minimap. */
    public static void markers(ServerPlayerEntity p, List<Net.Marker> list) {
        for (Site s : data.sites) {
            if (!s.built) continue;
            if (Math.hypot(p.getX() - s.x, p.getZ() - s.z) > 1500) continue;
            list.add(new Net.Marker("swing", s.kind.title, s.x, 0, s.z, 0x9AD0FF));
        }
    }

    /** One or two sites around each region of the map, out of towns and apart from each other. */
    private static void plan() {
        Random r = Random.create(server.getOverworld().getSeed() ^ 0x5717);
        Kind[] kinds = Kind.values();
        int n = 0;
        for (Net.Area a : AotRpg.PLACES.areas()) {
            for (int k = 0; k < 2 && data.sites.size() < 48; k++) {
                for (int tries = 0; tries < 10; tries++) {
                    double ang = r.nextDouble() * Math.PI * 2, d = 160 + r.nextInt(200);
                    int x = (int) (a.x() + Math.cos(ang) * d), z = (int) (a.z() + Math.sin(ang) * d);
                    if (AotRpg.PLACES.nearest(x, z, 180, "town", "village", "city", "capital", "camp") != null) continue;
                    boolean near = false;
                    for (Site s : data.sites) if (Math.hypot(s.x - x, s.z - z) < 420) near = true;
                    if (near) continue;
                    Site s = new Site();
                    s.id = "s" + (++n);
                    s.kind = kinds[r.nextInt(kinds.length)];
                    s.x = x;
                    s.z = z;
                    s.seed = r.nextLong();
                    data.sites.add(s);
                    break;
                }
            }
        }
        save();
        AotRpg.LOG.info("Planned {} swing sites", data.sites.size());
    }

    /** Someone close to an unbuilt site whose ground is loaded: start building it. */
    private static void wake() {
        ServerWorld w = server.getOverworld();
        for (Site s : data.sites) {
            if (s.built) continue;
            boolean near = false;
            for (ServerPlayerEntity p : w.getPlayers()) if (Math.hypot(p.getX() - s.x, p.getZ() - s.z) < 220) near = true;
            if (!near) continue;
            if (!w.isChunkLoaded((s.x - 48) >> 4, (s.z - 48) >> 4) || !w.isChunkLoaded((s.x + 48) >> 4, (s.z + 48) >> 4)
                || !w.isChunkLoaded((s.x - 48) >> 4, (s.z + 48) >> 4) || !w.isChunkLoaded((s.x + 48) >> 4, (s.z - 48) >> 4)) continue;
            start(s);
            return;
        }
    }

    private static void start(Site s) {
        building = s;
        queue = new ArrayDeque<>();
        undo = new ArrayList<>();
        ServerWorld w = server.getOverworld();
        Random r = Random.create(s.seed);
        Map<BlockPos, BlockState> plan = new LinkedHashMap<>();
        switch (s.kind) {
            case RUINS -> ruins(w, s, r, plan);
            case SPIRES -> spires(w, s, r, plan);
            case WALL -> wall(w, s, r, plan);
            case GROVE -> { }
        }
        for (var e : plan.entrySet()) queue.add(new Object[] {e.getKey(), e.getValue()});
        AotRpg.LOG.info("Building swing site {} ({}) at {}, {}: {} blocks", s.id, s.kind.title, s.x, s.z, queue.size());
    }

    private static void step() {
        ServerWorld w = server.getOverworld();
        Site s = building;
        if (s.kind == Kind.GROVE && queue.isEmpty() && !s.built) {
            // The trees grow straight into the world (their builder places the blocks itself).
            Random r = Random.create(s.seed);
            int n = 6 + r.nextInt(5);
            for (int i = 0; i < n; i++) {
                double ang = r.nextDouble() * Math.PI * 2, d = 8 + r.nextInt(46);
                int x = (int) (s.x + Math.cos(ang) * d), z = (int) (s.z + Math.sin(ang) * d);
                BlockPos base = new BlockPos(x, w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                // Remember what the tree replaces, for undo.
                BlockPos lo = base.add(-14, -3, -14), hi = base.add(14, 84, 14);
                List<BlockPos> at = new ArrayList<>();
                List<BlockState> was = new ArrayList<>();
                for (BlockPos b : BlockPos.iterate(lo, hi)) {
                    at.add(b.toImmutable());
                    was.add(w.getBlockState(b));
                }
                GiantTrees.grow(w, base, r, r.nextInt(3) == 0 ? GiantTrees.Wood.JUNGLE : GiantTrees.Wood.FOREST, 46, 72, 9);
                for (int k = 0; k < at.size(); k++) {
                    BlockPos b = at.get(k);
                    if (!w.getBlockState(b).equals(was.get(k))) undo.add(b.getX() + " " + b.getY() + " " + b.getZ() + " " + BlockArgumentParser.stringifyBlockState(was.get(k)));
                }
            }
            finish(s);
            return;
        }
        for (int i = 0; i < PER_TICK && !queue.isEmpty(); i++) {
            Object[] q = queue.poll();
            BlockPos b = (BlockPos) q[0];
            BlockState st = (BlockState) q[1];
            BlockState was = w.getBlockState(b);
            if (was.equals(st) || w.getBlockEntity(b) != null) continue;
            undo.add(b.getX() + " " + b.getY() + " " + b.getZ() + " " + BlockArgumentParser.stringifyBlockState(was));
            w.setBlockState(b, st, 2);
        }
        if (queue.isEmpty()) finish(s);
    }

    private static void finish(Site s) {
        s.built = true;
        building = null;
        try {
            Files.createDirectories(dir);
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(dir.resolve(s.id + ".undo.gz"))), StandardCharsets.UTF_8))) {
                for (String l : undo) {
                    out.write(l);
                    out.newLine();
                }
            }
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save swing site undo", e);
        }
        undo = new ArrayList<>();
        save();
        for (ServerPlayerEntity p : server.getOverworld().getPlayers()) {
            if (Math.hypot(p.getX() - s.x, p.getZ() - s.z) < 300) AotRpg.QUESTS.markers(p, true);
        }
    }

    private static int restore(Site s) {
        ServerWorld w = server.getOverworld();
        Path f = dir.resolve(s.id + ".undo.gz");
        if (!Files.exists(f)) return 0;
        List<String> lines = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
            String l;
            while ((l = in.readLine()) != null) lines.add(l);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not read swing site undo", e);
        }
        int n = 0;
        // Backwards: the first state seen for each block is the original.
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] p = lines.get(i).split(" ", 4);
            try {
                BlockPos b = new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                BlockState st = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), p[3], false).blockState();
                w.setBlockState(b, st, 2);
                n++;
            } catch (Exception ignored) {
            }
        }
        try {
            Files.deleteIfExists(f);
        } catch (Exception ignored) {
        }
        return n;
    }

    private static void save() {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("sites.json"), GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AotRpg.LOG.error("Could not save swing sites", e);
        }
    }

    // ------------------------------------------------------------------ the shapes

    private static int ground(ServerWorld w, int x, int z) {
        return w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static BlockState pick(Random r, BlockState... states) {
        return states[r.nextInt(states.length)];
    }

    /** A quarter of broken towers: walls with window gaps, floors every few storeys, broken tops, bridges between. */
    private static void ruins(ServerWorld w, Site s, Random r, Map<BlockPos, BlockState> plan) {
        int n = 8 + r.nextInt(5);
        List<int[]> towers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2, d = 6 + r.nextInt(52);
            int x = (int) (s.x + Math.cos(ang) * d), z = (int) (s.z + Math.sin(ang) * d);
            boolean clash = false;
            for (int[] t : towers) if (Math.abs(t[0] - x) < 14 && Math.abs(t[1] - z) < 14) clash = true;
            if (clash) continue;
            int half = 2 + r.nextInt(3), h = 22 + r.nextInt(28);
            int g = ground(w, x, z);
            towers.add(new int[] {x, z, g + h, half});
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    boolean edge = Math.abs(dx) == half || Math.abs(dz) == half;
                    int gy = ground(w, x + dx, z + dz);
                    // Broken tops: each column of the wall stops a little short of the next.
                    int top = g + h - (edge ? r.nextInt(4) : 0) - (Math.abs(dx) + Math.abs(dz) > half * 2 - 1 && r.nextInt(3) == 0 ? r.nextInt(6) : 0);
                    for (int y = gy - 2; y <= top; y++) {
                        BlockPos b = new BlockPos(x + dx, y, z + dz);
                        if (edge) {
                            boolean window = (y - g) % 5 == 3 && (dx == 0 || dz == 0) && y < top - 2;
                            if (!window) plan.put(b, pick(r, Blocks.STONE_BRICKS.getDefaultState(), Blocks.CRACKED_STONE_BRICKS.getDefaultState(),
                                Blocks.MOSSY_STONE_BRICKS.getDefaultState(), Blocks.COBBLESTONE.getDefaultState()));
                            else plan.put(b, Blocks.AIR.getDefaultState());
                        } else if ((y - g) % 10 == 0 && y > g) {
                            plan.put(b, Blocks.SPRUCE_PLANKS.getDefaultState());
                        } else if (y > gy) {
                            plan.put(b, Blocks.AIR.getDefaultState());
                        }
                    }
                }
            }
            // A roof lip.
            for (int dx = -half - 1; dx <= half + 1; dx++) {
                for (int dz = -half - 1; dz <= half + 1; dz++) {
                    if (Math.abs(dx) == half + 1 || Math.abs(dz) == half + 1) {
                        if (r.nextInt(4) != 0) plan.put(new BlockPos(x + dx, g + h - 1, z + dz), Blocks.DARK_OAK_PLANKS.getDefaultState());
                    }
                }
            }
        }
        // Bridges between near neighbours, up high.
        for (int i = 0; i < towers.size(); i++) {
            for (int j = i + 1; j < towers.size(); j++) {
                int[] a = towers.get(i), b = towers.get(j);
                double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
                if (d > 30 || r.nextInt(2) == 0) continue;
                int y = Math.min(a[2], b[2]) - 6 - r.nextInt(8);
                int steps = (int) d;
                for (int k = 0; k <= steps; k++) {
                    double f = k / (double) steps;
                    int x = (int) Math.round(a[0] + (b[0] - a[0]) * f), z = (int) Math.round(a[1] + (b[1] - a[1]) * f);
                    if (r.nextInt(9) == 0) continue; // a gap or two
                    plan.putIfAbsent(new BlockPos(x, y, z), Blocks.DARK_OAK_SLAB.getDefaultState());
                }
            }
        }
    }

    /** Tall rock needles tapering as they climb, banded in stone, with natural arches between pairs. */
    private static void spires(ServerWorld w, Site s, Random r, Map<BlockPos, BlockState> plan) {
        int n = 10 + r.nextInt(6);
        List<int[]> tops = new ArrayList<>();
        BlockState[] bands = {Blocks.STONE.getDefaultState(), Blocks.ANDESITE.getDefaultState(), Blocks.TUFF.getDefaultState(), Blocks.DEEPSLATE.getDefaultState()};
        for (int i = 0; i < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2, d = 6 + r.nextInt(56);
            int x = (int) (s.x + Math.cos(ang) * d), z = (int) (s.z + Math.sin(ang) * d);
            int g = ground(w, x, z), h = 32 + r.nextInt(40);
            float rad = 3 + r.nextFloat() * 3;
            tops.add(new int[] {x, z, g + h});
            for (int y = -3; y <= h; y++) {
                float k = 1 - y / (float) h;
                float rr = Math.max(0.8f, rad * (0.35f + 0.65f * k)) + (float) Math.sin(y * 0.4 + i) * 0.4f;
                BlockState band = bands[Math.floorMod((y + i * 3) / 6, bands.length)];
                int ri = (int) Math.ceil(rr);
                for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dz * dz <= rr * rr) plan.put(new BlockPos(x + dx, g + y, z + dz), band);
                }
            }
        }
        // Arches between some near pairs: a curve of stone from one to the other.
        for (int i = 0; i + 1 < tops.size(); i += 2) {
            int[] a = tops.get(i), b = tops.get(i + 1);
            double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
            if (d < 10 || d > 40) continue;
            int base = Math.min(a[2], b[2]) - 14;
            int steps = (int) (d * 1.5);
            for (int k = 0; k <= steps; k++) {
                double f = k / (double) steps;
                int x = (int) Math.round(a[0] + (b[0] - a[0]) * f), z = (int) Math.round(a[1] + (b[1] - a[1]) * f);
                int y = base + (int) (Math.sin(f * Math.PI) * d * 0.3);
                for (int t = 0; t < 2; t++) plan.put(new BlockPos(x, y - t, z), Blocks.STONE.getDefaultState());
            }
        }
    }

    /** A fallen length of a great wall: thick, fifty high, breached in the middle, with watchtowers. */
    private static void wall(ServerWorld w, Site s, Random r, Map<BlockPos, BlockState> plan) {
        double ang = r.nextDouble() * Math.PI;
        double dx = Math.cos(ang), dz = Math.sin(ang), nx = -dz, nz = dx;
        int len = 90, thick = 7, h = 46 + r.nextInt(8);
        int gap0 = len / 2 - 6 - r.nextInt(4), gap1 = len / 2 + 6 + r.nextInt(4);
        for (int i = -len / 2; i <= len / 2; i++) {
            int along = i + len / 2;
            for (int t = -thick / 2; t <= thick / 2; t++) {
                int x = (int) Math.round(s.x + dx * i + nx * t), z = (int) Math.round(s.z + dz * i + nz * t);
                int g = ground(w, x, z);
                int top = g + h;
                // The breach: a ragged hole down to the ground with rubble.
                if (along > gap0 && along < gap1) top = g + r.nextInt(5);
                else if (along >= gap0 - 4 && along <= gap1 + 4) top = g + h - r.nextInt(18);
                for (int y = g - 2; y <= top; y++) {
                    plan.put(new BlockPos(x, y, z), y > top - 2 ? Blocks.STONE_BRICKS.getDefaultState()
                        : pick(r, Blocks.SMOOTH_STONE.getDefaultState(), Blocks.STONE.getDefaultState(), Blocks.POLISHED_ANDESITE.getDefaultState()));
                }
            }
        }
        // Watchtowers on the wall top at both ends and the middle of each half.
        for (int i : new int[] {-len / 2 + 4, -len / 4, len / 4, len / 2 - 4}) {
            int x = (int) Math.round(s.x + dx * i), z = (int) Math.round(s.z + dz * i);
            int g = ground(w, x, z) + h;
            for (int y = 1; y <= 9; y++) for (int a = -2; a <= 2; a++) for (int b = -2; b <= 2; b++) {
                boolean edge = Math.abs(a) == 2 || Math.abs(b) == 2;
                if (edge && !(y % 4 == 2 && (a == 0 || b == 0))) plan.put(new BlockPos(x + a, g + y, z + b), Blocks.STONE_BRICKS.getDefaultState());
            }
            for (int a = -3; a <= 3; a++) for (int b = -3; b <= 3; b++) plan.put(new BlockPos(x + a, g + 10, z + b), Blocks.DARK_OAK_PLANKS.getDefaultState());
        }
    }
}
