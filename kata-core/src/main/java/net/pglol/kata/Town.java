package net.pglol.kata;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.LanternBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * Kizuna town: the server's play world, a flat dimension (kata_core:town) the mod builds itself the
 * first time the server starts (and again whenever {@link #VERSION} goes up). A grid of 4 x 4 city
 * blocks, 87 blocks a side, between 13-wide streets (asphalt, raised sidewalks, lamps, crosswalks):
 * downtown towers for rooftop runs and fights along the north, industry down the east, the school in
 * the middle (where everyone spawns), the tournament hall and the shopping street south of it, and
 * neighbourhoods with a dojo in each.
 *
 * The building runs a little every tick (a time budget), so the server stays responsive while it
 * goes; players who join meanwhile are told, and taken to the school when it's done.
 */
public final class Town {
    private Town() {}

    public static final RegistryKey<World> WORLD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(KataCore.ID, "town"));
    /** The first air above the ground: the flat ground's grass is at G - 1. */
    static final int G = 64;
    /** Block pitch, street width, the west/north edge, a city block's size. */
    static final int P = 100, R = 13, MIN = -206, LOT = P - R, MAX = MIN + 4 * P + R - 1;
    /** The tree belt round the outside. */
    static final int BELT = 24;
    /** Bump to rebuild the town on every server that has an older one. */
    private static final int VERSION = 3;

    static int lx(int i) { return MIN + R + i * P; }
    static int lz(int j) { return MIN + R + j * P; }

    private static MinecraftServer server;
    static ServerWorld w;
    private static boolean ready, building;
    private static Path file;
    /** Where everyone arrives: the school courtyard, looking at the school. */
    static BlockPos spawn = new BlockPos(lx(1) + 50, G, lz(1) + 45);
    private static int jobsTotal, jobsDone;
    private static long lastNote;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(Town::open);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            TownEdits.save();
            JOBS.clear();
            building = false;
            ready = false;
            w = null;
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(Town::tick);
        ServerPlayConnectionEvents.JOIN.register((h, sender, srv) -> srv.execute(() -> arrive(h.getPlayer())));
        ServerPlayerEvents.AFTER_RESPAWN.register((o, n, alive) -> {
            if (ready && n.getWorld().getRegistryKey() != WORLD) bring(n);
        });
        // No monsters in town (only fighters).
        ServerEntityEvents.ENTITY_LOAD.register((e, world) -> {
            if (e instanceof HostileEntity && world.getRegistryKey() == WORLD) e.discard();
        });
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("kata")
            .requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("town").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                if (ready) bring(p);
                else c.getSource().sendFeedback(() -> Text.literal(building ? "The town is still being built (" + percent() + "%)." : "The town world isn't loaded."), false);
                return 1;
            }).then(CommandManager.literal("export").executes(c -> {
                if (w == null) return 0;
                int n = TownEdits.export(w);
                c.getSource().sendFeedback(() -> Text.literal(n < 0 ? "Couldn't write the edits file (see the server log)."
                    : "Saved " + n + " hand-built blocks to town_edits.txt in the server folder. Send that file over to have them folded into the town."), false);
                return 1;
            })).then(CommandManager.literal("edits").executes(c -> {
                c.getSource().sendFeedback(() -> Text.literal(TownEdits.count() + " blocks changed by hand since the town was built. /kata town export saves them."), false);
                return 1;
            })).then(CommandManager.literal("rebuild").executes(c -> {
                if (w == null) return 0;
                start(true);
                c.getSource().sendFeedback(() -> Text.literal("Rebuilding the town (" + jobsTotal + " steps)."), true);
                return 1;
            })))));
    }

    private static void open(MinecraftServer s) {
        server = s;
        w = s.getWorld(WORLD);
        if (w == null) {
            KataCore.LOG.error("The town dimension (kata_core:town) isn't loaded; no town this time");
            return;
        }
        file = s.getSavePath(WorldSavePath.ROOT).resolve("kata_core").resolve("town.txt");
        TownEdits.open(s);
        int v = 0;
        try {
            if (Files.exists(file)) v = Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            KataCore.LOG.warn("Could not read town.txt", e);
        }
        if (v == VERSION && intact()) {
            ready = true;
            KataCore.LOG.info("Town ready");
        } else start(v > 0 || !intactGround());
    }

    /** The marker stone under the spawn is there (the town really is in this world). */
    private static boolean intact() {
        return w.getBlockState(spawn.down()).isOf(Blocks.CHISELED_STONE_BRICKS);
    }

    /** Nothing has ever been built here: plain grass at the spawn and at a downtown corner. */
    private static boolean intactGround() {
        return w.getBlockState(spawn.down()).isOf(Blocks.GRASS_BLOCK) && w.getBlockState(new BlockPos(lx(0) + 10, G, lz(0) + 10)).isAir();
    }

    private static void start(boolean clear) {
        JOBS.clear();
        POS.clear();
        STATES.clear();
        JOIN.clear();
        AFTER.clear();
        head = joinHead = 0;
        ready = false;
        // Whatever's been built by hand is saved first and laid back on top at the end.
        if (clear && TownEdits.count() > 0) TownEdits.export(w);
        TownWorks.plan(clear);
        MinecraftServer srv = server;
        ServerWorld tw = w;
        JOBS.add(() -> TownEdits.applyAll(srv, tw));
        jobsTotal = JOBS.size();
        jobsDone = 0;
        building = true;
        KataCore.LOG.info("Building the town: {} steps{}", jobsTotal, clear ? " (clearing the old one first)" : "");
    }

    private static int percent() {
        return jobsTotal == 0 ? 0 : jobsDone * 100 / jobsTotal;
    }

    private static void finish() {
        building = false;
        ready = true;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(VERSION), StandardCharsets.UTF_8);
        } catch (Exception e) {
            KataCore.LOG.error("Could not save town.txt", e);
        }
        KataCore.LOG.info("Town built");
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            p.sendMessage(Text.literal("The town is ready.").formatted(Formatting.GOLD), true);
            if (p.getWorld().getRegistryKey() != WORLD) bring(p);
        }
    }

    private static void arrive(ServerPlayerEntity p) {
        if (ready) {
            if (p.getWorld().getRegistryKey() != WORLD) bring(p);
            else adventure(p);
        } else if (building) {
            p.sendMessage(Text.literal("The town is being built (" + percent() + "%). You'll be taken there when it's done.").formatted(Formatting.GRAY), false);
        }
    }

    /** To the school courtyard, and that's where they come back to. */
    static void bring(ServerPlayerEntity p) {
        ServerWorld tw = server.getWorld(WORLD);
        if (tw == null) return;
        p.teleport(tw, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 180f, 0f);
        p.setSpawnPoint(WORLD, spawn, 180f, true, false);
        adventure(p);
    }

    /** Players don't dig the town up: survival players get adventure mode (operators keep theirs). */
    private static void adventure(ServerPlayerEntity p) {
        if (p.interactionManager.getGameMode() == GameMode.SURVIVAL && !server.getPlayerManager().isOperator(p.getGameProfile()))
            p.changeGameMode(GameMode.ADVENTURE);
    }

    // ------------------------------------------------------------------ the build queue

    /** Each job writes its blocks into the buffer; the buffer is written out under a time budget. */
    static final ArrayDeque<Runnable> JOBS = new ArrayDeque<>();
    private static final LongArrayList POS = new LongArrayList();
    private static final ArrayList<BlockState> STATES = new ArrayList<>();
    /** Panes, bars, fences and walls: joined up to their neighbours once the job's blocks are in. */
    private static final LongArrayList JOIN = new LongArrayList();
    /** Things to do once the job's blocks are in (sign text). */
    private static final ArrayList<Runnable> AFTER = new ArrayList<>();
    private static int head, joinHead;
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;

    private static void tick(MinecraftServer s) {
        TownEdits.tick();
        if (!building || w == null) return;
        TownEdits.ours = true;
        try {
            build(s);
        } finally {
            TownEdits.ours = false;
        }
    }

    private static void build(MinecraftServer s) {
        long end = System.nanoTime() + (s.getCurrentPlayerCount() == 0 ? 45_000_000L : 18_000_000L);
        BlockPos.Mutable m = new BlockPos.Mutable();
        while (System.nanoTime() < end) {
            if (head < POS.size()) {
                int stop = Math.min(POS.size(), head + 256);
                for (; head < stop; head++) {
                    long l = POS.getLong(head);
                    m.set(BlockPos.unpackLongX(l), BlockPos.unpackLongY(l), BlockPos.unpackLongZ(l));
                    w.setBlockState(m, STATES.get(head), FLAGS);
                }
                continue;
            }
            if (joinHead < JOIN.size()) {
                int stop = Math.min(JOIN.size(), joinHead + 256);
                for (; joinHead < stop; joinHead++) {
                    long l = JOIN.getLong(joinHead);
                    m.set(BlockPos.unpackLongX(l), BlockPos.unpackLongY(l), BlockPos.unpackLongZ(l));
                    BlockState st = w.getBlockState(m);
                    BlockState joined = Block.postProcessState(st, w, m);
                    if (joined != st) w.setBlockState(m, joined, FLAGS);
                }
                continue;
            }
            if (!AFTER.isEmpty()) {
                for (Runnable r : AFTER) r.run();
                AFTER.clear();
            }
            POS.clear();
            STATES.clear();
            JOIN.clear();
            head = joinHead = 0;
            Runnable job = JOBS.poll();
            if (job == null) {
                finish();
                return;
            }
            try {
                job.run();
            } catch (Exception e) {
                KataCore.LOG.error("A town building step failed", e);
            }
            jobsDone++;
        }
        long now = System.currentTimeMillis();
        if (now - lastNote > 3000) {
            lastNote = now;
            Text t = Text.literal("Building the town... " + percent() + "%").formatted(Formatting.GRAY);
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) p.sendMessage(t, true);
        }
    }

    /** Wipe one chunk back to bare ground (a rebuild): only sections with anything in them are looked at. */
    static void clearChunk(int cx, int cz) {
        Chunk c = w.getChunk(cx, cz);
        ChunkSection[] secs = c.getSectionArray();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), air = Blocks.AIR.getDefaultState();
        for (int i = 0; i < secs.length; i++) {
            if (secs[i] == null || secs[i].isEmpty()) continue;
            int sy = c.sectionIndexToCoord(i) << 4;
            for (int dy = 0; dy < 16; dy++) {
                int y = sy + dy;
                if (y < G - 4) continue;
                BlockState want = y == G - 1 ? grass : y < G - 1 ? dirt : air;
                for (int x = 0; x < 16; x++)
                    for (int z = 0; z < 16; z++)
                        if (secs[i].getBlockState(x, dy, z) != want) set(cx * 16 + x, y, cz * 16 + z, want);
            }
        }
    }

    // ------------------------------------------------------------------ primitives

    static void set(int x, int y, int z, BlockState s) {
        if (y < 1 || y > 318) return;
        POS.add(BlockPos.asLong(x, y, z));
        STATES.add(s);
        Block b = s.getBlock();
        if (b instanceof PaneBlock || b instanceof FenceBlock || b instanceof WallBlock) JOIN.add(BlockPos.asLong(x, y, z));
    }

    /** A block exactly as given (no joining up to neighbours): hand-built edits. */
    static void setExact(int x, int y, int z, BlockState s) {
        if (y < 1 || y > 318) return;
        POS.add(BlockPos.asLong(x, y, z));
        STATES.add(s);
    }

    static void after(Runnable r) {
        AFTER.add(r);
    }

    static void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, y, z, s);
    }

    /** The four side walls of a box (no floor or ceiling). */
    static void walls(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        box(x0, y0, z0, x1, y1, z0, s);
        box(x0, y0, z1, x1, y1, z1, s);
        box(x0, y0, z0, x0, y1, z1, s);
        box(x1, y0, z0, x1, y1, z1, s);
    }

    static BlockState s(Block b) { return b.getDefaultState(); }

    static final BlockState AIR = Blocks.AIR.getDefaultState();

    static BlockState stair(Block b, Direction facing, boolean top) {
        return b.getDefaultState().with(StairsBlock.FACING, facing).with(StairsBlock.HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM);
    }

    static BlockState slab(Block b, boolean top) {
        return b.getDefaultState().with(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    static BlockState axis(Block b, Direction.Axis a) {
        return b.getDefaultState().with(PillarBlock.AXIS, a);
    }

    static BlockState leaves(Block b) {
        return b.getDefaultState().with(LeavesBlock.PERSISTENT, true);
    }

    /** Ladders, wall signs and the like: facing away from the block they hang on. */
    static BlockState facing(Block b, Direction f) {
        return b.getDefaultState().with(Properties.HORIZONTAL_FACING, f);
    }

    static BlockState lantern(boolean hanging) {
        return Blocks.LANTERN.getDefaultState().with(LanternBlock.HANGING, hanging);
    }

    static BlockState grate() {
        return Blocks.IRON_TRAPDOOR.getDefaultState().with(TrapdoorBlock.HALF, BlockHalf.TOP);
    }

    static void door(int x, int y, int z, Block b, Direction f) {
        set(x, y, z, b.getDefaultState().with(DoorBlock.FACING, f).with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(x, y + 1, z, b.getDefaultState().with(DoorBlock.FACING, f).with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** A wall sign hanging on the block behind it (facing f), with up to four lines, glowing so it reads at night. */
    static void sign(int x, int y, int z, Block wallSign, Direction f, String... lines) {
        set(x, y, z, facing(wallSign, f));
        BlockPos at = new BlockPos(x, y, z);
        AFTER.add(() -> {
            if (w.getBlockEntity(at) instanceof SignBlockEntity be) {
                SignText t = new SignText().withGlowing(true).withColor(DyeColor.WHITE);
                for (int i = 0; i < Math.min(4, lines.length); i++) t = t.withMessage(i, Text.literal(lines[i]));
                be.setText(t, true);
                be.setWaxed(true);
                be.markDirty();
                w.updateListeners(at, be.getCachedState(), be.getCachedState(), Block.NOTIFY_LISTENERS);
            }
        });
    }

    /** A standing sign turned to face (16ths of a turn, 0 = south), with up to four lines. */
    static void post(int x, int y, int z, int rotation, String... lines) {
        set(x, y, z, Blocks.SPRUCE_SIGN.getDefaultState().with(Properties.ROTATION, rotation));
        BlockPos at = new BlockPos(x, y, z);
        AFTER.add(() -> {
            if (w.getBlockEntity(at) instanceof SignBlockEntity be) {
                SignText t = new SignText().withGlowing(true).withColor(DyeColor.WHITE);
                for (int i = 0; i < Math.min(4, lines.length); i++) t = t.withMessage(i, Text.literal(lines[i]));
                be.setText(t, true);
                be.setWaxed(true);
                be.markDirty();
                w.updateListeners(at, be.getCachedState(), be.getCachedState(), Block.NOTIFY_LISTENERS);
            }
        });
    }

    // ------------------------------------------------------------------ a local frame

    /*
     * For buildings that can face any street: u runs along the front (left to right, seen from the
     * street), v runs back from it (v = 0 is the front edge), y is as is. Set with frame() at the start
     * of a job, from the plot's world rectangle and the way it faces.
     */
    static int ox, oz, ux, uz, vx, vz;
    static Direction front, right;

    static void frame(Direction f, int x0, int z0, int x1, int z1) {
        front = f;
        right = f.rotateYClockwise();
        ux = right.getOffsetX();
        uz = right.getOffsetZ();
        vx = -f.getOffsetX();
        vz = -f.getOffsetZ();
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
        switch (f) {
            case NORTH -> { ox = ax; oz = az; }
            case SOUTH -> { ox = bx; oz = bz; }
            case EAST -> { ox = bx; oz = az; }
            default -> { ox = ax; oz = bz; }
        }
    }

    static int fx(int u, int v) { return ox + u * ux + v * vx; }
    static int fz(int u, int v) { return oz + u * uz + v * vz; }

    static void ls(int u, int y, int v, BlockState s) { set(fx(u, v), y, fz(u, v), s); }

    static void lbox(int u0, int y0, int v0, int u1, int y1, int v1, BlockState s) {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++)
            for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++)
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) ls(u, y, v, s);
    }

    static void lwalls(int u0, int y0, int v0, int u1, int y1, int v1, BlockState s) {
        lbox(u0, y0, v0, u1, y1, v0, s);
        lbox(u0, y0, v1, u1, y1, v1, s);
        lbox(u0, y0, v0, u0, y1, v1, s);
        lbox(u1, y0, v0, u1, y1, v1, s);
    }

    /** Directions in the frame. */
    static Direction back() { return front.getOpposite(); }
    static Direction left() { return right.getOpposite(); }
}
