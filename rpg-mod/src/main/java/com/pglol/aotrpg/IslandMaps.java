package com.pglol.aotrpg;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole extraction island as a picture, so the island map shows all of it from the first second
 * instead of filling in as you explore. Rendered once per island instance on the server (a few chunks
 * a tick, so nobody feels it), saved under config/aot_rpg/islands/, and sent to players when they ask.
 * Same framing and colours as the client's own chart: the island's centre, its radius plus 48 blocks.
 */
public final class IslandMaps {
    private IslandMaps() {}

    static final int TEX = 384;
    private static final long BUDGET_NS = 6_000_000;
    private static final Map<String, byte[]> done = new HashMap<>();
    private static final Map<String, Set<UUID>> waiting = new HashMap<>();
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static MinecraftServer server;

    private static final class Job {
        final String name;
        final Extraction.Island island;
        final ServerWorld world;
        final double x0, z0, bpt;
        final int cx0, cz0, cx1, cz1;
        final BufferedImage img = new BufferedImage(TEX, TEX, BufferedImage.TYPE_INT_ARGB);
        int ccx, ccz;

        Job(String name, Extraction.Island island, ServerWorld world, int cx, int cz) {
            this.name = name;
            this.island = island;
            this.world = world;
            double half = island.radius + 48;
            x0 = cx - half;
            z0 = cz - half;
            bpt = half * 2 / TEX;
            cx0 = (int) Math.floor(x0) >> 4;
            cz0 = (int) Math.floor(z0) >> 4;
            cx1 = (int) Math.floor(x0 + half * 2) >> 4;
            cz1 = (int) Math.floor(z0 + half * 2) >> 4;
            ccx = cx0;
            ccz = cz0;
        }
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            server = s;
            tick();
        });
    }

    private static Path file(String name) {
        return FabricLoader.getInstance().getConfigDir().resolve("aot_rpg").resolve("islands").resolve(name.replace(':', '_') + ".png");
    }

    /** "island:<id>:<cx>:<cz>" from the island map: send it, or render it and send it when ready. */
    public static void request(ServerPlayerEntity p, String name) {
        String[] parts = name.split(":");
        if (parts.length != 4) return;
        Extraction.Island island = Extraction.Island.of(parts[1]);
        if (island == null) return;
        int cx, cz;
        try {
            cx = Integer.parseInt(parts[2]);
            cz = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            return;
        }
        byte[] png = done.get(name);
        if (png == null) {
            try {
                Path f = file(name);
                if (Files.exists(f)) done.put(name, png = Files.readAllBytes(f));
            } catch (Exception ignored) {
            }
        }
        if (png != null) {
            send(p, name, png);
            return;
        }
        waiting.computeIfAbsent(name, k -> new HashSet<>()).add(p.getUuid());
        for (Job j : queue) if (j.name.equals(name)) return;
        ServerWorld w = p.getServer().getWorld(island.world);
        if (w == null) return;
        queue.add(new Job(name, island, w, cx, cz));
    }

    private static void send(ServerPlayerEntity p, String name, byte[] data) {
        if (!ServerPlayNetworking.canSend(p, Net.MapChunk.ID)) return;
        int size = 512 * 1024, total = (data.length + size - 1) / size;
        for (int i = 0; i < total; i++) {
            byte[] part = java.util.Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
            ServerPlayNetworking.send(p, new Net.MapChunk(name, i, total, part));
        }
    }

    private static void tick() {
        Job j = queue.peek();
        if (j == null) return;
        long start = System.nanoTime();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        while (System.nanoTime() - start < BUDGET_NS) {
            if (j.ccz > j.cz1) {
                finish(j);
                queue.poll();
                return;
            }
            chunk(j, j.ccx, j.ccz, pos);
            if (++j.ccx > j.cx1) {
                j.ccx = j.cx0;
                j.ccz++;
            }
        }
    }

    /** The texels whose sample column falls in this chunk. */
    private static void chunk(Job j, int chx, int chz, BlockPos.Mutable pos) {
        int tx0 = Math.max(0, (int) Math.ceil((chx * 16 - j.x0) / j.bpt - 0.5));
        int tx1 = Math.min(TEX - 1, (int) Math.ceil((chx * 16 + 16 - j.x0) / j.bpt - 0.5) - 1);
        int tz0 = Math.max(0, (int) Math.ceil((chz * 16 - j.z0) / j.bpt - 0.5));
        int tz1 = Math.min(TEX - 1, (int) Math.ceil((chz * 16 + 16 - j.z0) / j.bpt - 0.5) - 1);
        if (tx0 > tx1 || tz0 > tz1) return;
        j.world.getChunk(chx, chz, ChunkStatus.FULL, true);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                int bx = (int) Math.floor(j.x0 + (tx + 0.5) * j.bpt), bz = (int) Math.floor(j.z0 + (tz + 0.5) * j.bpt);
                int col = sample(j.world, bx, bz, j.bpt, pos);
                if (col != 0) j.img.setRGB(tx, tz, col);
            }
        }
    }

    private static void finish(Job j) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(j.img, "png", out);
            byte[] png = out.toByteArray();
            done.put(j.name, png);
            Path f = file(j.name);
            Files.createDirectories(f.getParent());
            Files.write(f, png);
            AotRpg.LOG.info("Island map {} rendered ({} KB)", j.name, png.length / 1024);
            Set<UUID> who = waiting.remove(j.name);
            if (who != null && server != null) {
                for (UUID id : who) {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) send(p, j.name, png);
                }
            }
        } catch (Exception e) {
            AotRpg.LOG.warn("Could not save island map {}: {}", j.name, e.toString());
        }
    }

    // ---- Colours: the same as the client's chart (IslandChart) ----

    private static int top(ServerWorld w, Heightmap.Type t, int x, int z) {
        return w.getTopY(t, x, z) - 1;
    }

    private static int sample(ServerWorld w, int x, int z, double bpt, BlockPos.Mutable pos) {
        int y = top(w, Heightmap.Type.WORLD_SURFACE, x, z);
        if (y <= w.getBottomY()) return 0;
        pos.set(x, y, z);
        BlockState st = w.getBlockState(pos);
        if (!st.getFluidState().isEmpty()) {
            int depth = 1;
            while (depth < 14) {
                pos.setY(y - depth);
                if (w.getFluidState(pos).isEmpty()) break;
                depth++;
            }
            float d = Math.min(1, depth / 12f);
            return argb((int) (70 - 30 * d), (int) (120 - 40 * d), (int) (150 - 30 * d));
        }
        MapColor mc = st.getMapColor(w, pos);
        if (mc == MapColor.CLEAR || st.isIn(BlockTags.LEAVES)) {
            int g = top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            pos.setY(g);
            BlockState under = w.getBlockState(pos);
            MapColor uc = under.getMapColor(w, pos);
            if (uc != MapColor.CLEAR) {
                int a = uc.color, b = mc == MapColor.CLEAR ? a : mc.color;
                int rr = ((a >> 16 & 255) * 2 + (b >> 16 & 255)) / 3, gg = ((a >> 8 & 255) * 2 + (b >> 8 & 255)) / 3, bb = ((a & 255) * 2 + (b & 255)) / 3;
                return shade(w, x, z, g, bpt, rr, gg, bb);
            }
            if (mc == MapColor.CLEAR) return 0;
        }
        int c = mc.color;
        return shade(w, x, z, y, bpt, c >> 16 & 255, c >> 8 & 255, c & 255);
    }

    private static int shade(ServerWorld w, int x, int z, int h, double bpt, int r, int g, int b) {
        int step = Math.max(1, (int) Math.round(bpt));
        int hn = top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x - step, z - step);
        int hh = top(w, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        float k = Math.max(0.7f, Math.min(1.25f, 1 + (hh - hn) * 0.04f));
        k *= 0.92f + Math.min(0.16f, Math.max(0, (h - 62) * 0.004f));
        return argb((int) (r * k), (int) (g * k), (int) (b * k));
    }

    private static int argb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
