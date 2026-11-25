package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * A chart of the whole island: the server's picture of it (IslandMaps), kept current by what you see
 * around you, coloured and hill-shaded, with water and shorelines, drawn smooth under the markers.
 */
public final class IslandChart {
    private IslandChart() {}

    private static final int TEX = 384, REACH = 112, BUDGET = 700;
    private static final Identifier ID = Identifier.of("aot_rpg", "island_chart");
    private static NativeImage img;
    private static NativeImageBackedTexture tex;
    private static boolean[] known = new boolean[TEX * TEX];
    private static String key = "";
    private static double x0, z0, bpt;
    private static int scan, dirtyTicks;
    private static boolean dirty;
    private static String name = "";
    private static byte[][] parts;

    /** A piece of the server's picture of the whole island. */
    public static void onChunk(Net.MapChunk c) {
        if (!c.name().equals(name) || img == null) return;
        if (parts == null || parts.length != c.total()) parts = new byte[c.total()][];
        parts[c.index()] = c.data();
        for (byte[] p : parts) if (p == null) return;
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) all.writeBytes(p);
        parts = null;
        try (NativeImage full = NativeImage.read(new java.io.ByteArrayInputStream(all.toByteArray()))) {
            int w = Math.min(TEX, full.getWidth()), h = Math.min(TEX, full.getHeight());
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int i = z * TEX + x;
                    if (known[i]) continue;
                    int col = full.getColor(x, z);
                    if ((col >>> 24) == 0) continue;
                    img.setColor(x, z, col);
                    known[i] = true;
                }
            }
            tex.upload();
        } catch (Exception e) {
            com.pglol.aotrpg.AotRpg.LOG.warn("Could not read the island map", e);
        }
    }

    public static void tick(MinecraftClient mc) {
        Net.RunView v = ExtractionHud.view;
        if (!ExtractionHud.active() || mc.player == null || mc.world == null) return;
        String k = v.island() + ":" + v.cx() + ":" + v.cz();
        if (img == null) {
            img = new NativeImage(TEX, TEX, true);
            tex = new NativeImageBackedTexture(img);
            tex.setFilter(true, false);
            mc.getTextureManager().registerTexture(ID, tex);
        }
        if (!k.equals(key)) {
            key = k;
            img.fillRect(0, 0, TEX, TEX, 0);
            java.util.Arrays.fill(known, false);
            // The whole island, rendered by the server; what you see around you keeps it current.
            parts = null;
            name = "island:" + k;
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Net.MapRequest(name));
            double half = v.radius() + 48;
            x0 = v.cx() - half;
            z0 = v.cz() - half;
            bpt = half * 2 / TEX;
            dirty = true;
        }
        ClientWorld w = mc.world;
        int ptx = (int) ((mc.player.getX() - x0) / bpt), ptz = (int) ((mc.player.getZ() - z0) / bpt);
        int r = (int) (REACH / bpt), side = r * 2 + 1;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int n = 0; n < BUDGET; n++) {
            scan = (scan + 1) % (side * side);
            int tx = ptx - r + scan % side, tz = ptz - r + scan / side;
            if (tx < 0 || tz < 0 || tx >= TEX || tz >= TEX) continue;
            int dx = tx - ptx, dz = tz - ptz;
            if (dx * dx + dz * dz > r * r) continue;
            int i = tz * TEX + tx;
            if (known[i]) continue;
            int bx = (int) Math.floor(x0 + (tx + 0.5) * bpt), bz = (int) Math.floor(z0 + (tz + 0.5) * bpt);
            if (!w.getChunkManager().isChunkLoaded(bx >> 4, bz >> 4)) continue;
            int col = sample(w, bx, bz, pos);
            if (col == 0) continue;
            known[i] = true;
            img.setColor(tx, tz, abgr(col));
            dirty = true;
        }
        if (dirty && ++dirtyTicks >= 10) {
            dirtyTicks = 0;
            dirty = false;
            tex.upload();
        }
    }

    private static int height(ClientWorld w, int x, int z) {
        return w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
    }

    /** One column: its colour, shaded by the slope towards the north-west; 0 if unknown. */
    private static int sample(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
        int y = height(w, x, z);
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
        // Under the canopy: the ground below the leaves reads better than a sea of green.
        int h = y;
        if (mc == MapColor.CLEAR || st.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
            int g = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            pos.setY(g);
            BlockState under = w.getBlockState(pos);
            MapColor uc = under.getMapColor(w, pos);
            if (uc != MapColor.CLEAR) {
                // Leaves tint the ground they shade.
                int a = uc.color, b = mc == MapColor.CLEAR ? a : mc.color;
                h = g;
                int rr = ((a >> 16 & 255) * 2 + (b >> 16 & 255)) / 3, gg = ((a >> 8 & 255) * 2 + (b >> 8 & 255)) / 3, bb = ((a & 255) * 2 + (b & 255)) / 3;
                return shade(w, x, z, h, rr, gg, bb);
            }
            if (mc == MapColor.CLEAR) return 0;
        }
        int c = mc.color;
        return shade(w, x, z, h, c >> 16 & 255, c >> 8 & 255, c & 255);
    }

    private static int shade(ClientWorld w, int x, int z, int h, int r, int g, int b) {
        int step = Math.max(1, (int) Math.round(bpt));
        int hn = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x - step, z - step) - 1;
        int hh = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        float k = Math.max(0.7f, Math.min(1.25f, 1 + (hh - hn) * 0.04f));
        // A touch of height: higher ground is a little lighter.
        k *= 0.92f + Math.min(0.16f, Math.max(0, (h - 62) * 0.004f));
        return argb((int) (r * k), (int) (g * k), (int) (b * k));
    }

    /** Draws the chart under the map: sx, sy turn world x, z into screen coordinates. */
    public static void draw(DrawContext c, double screenX0, double screenZ0, double pxPerBlock, float alpha) {
        if (img == null || ExtractionHud.view == null) return;
        var m = c.getMatrices();
        m.push();
        m.translate(screenX0, screenZ0, 0);
        float s = (float) (bpt * pxPerBlock);
        m.scale(s, s, 1);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.setShaderColor(1, 1, 1, alpha);
        c.drawTexture(ID, 0, 0, 0, 0, TEX, TEX, TEX, TEX);
        c.setShaderColor(1, 1, 1, 1);
        m.pop();
    }

    static double x0() {
        return x0;
    }

    static double z0() {
        return z0;
    }

    private static int argb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static int abgr(int c) {
        return (c & 0xFF00FF00) | (c & 0xFF) << 16 | (c >> 16 & 0xFF);
    }
}
