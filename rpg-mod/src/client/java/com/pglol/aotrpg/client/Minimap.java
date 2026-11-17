package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.Heightmap;

/**
 * Top-left minimap: terrain from loaded chunks (1 block per pixel, north up), you as an arrow,
 * party members, titans and hostile mobs, and the current story objective.
 */
public final class Minimap {
    private Minimap() {}

    /**
     * The map is a 256-block square that wraps around (each world column always lands on the same
     * texel), filled in nearest-first as you move and refreshed in the background. It's drawn as a
     * smooth, filtered disc that slides under you a fraction of a pixel at a time, so it keeps up
     * however fast you fly.
     */
    private static final int TEX = 256, VIEW = 96, R = VIEW / 2, RAD = 72, NEW_PER_TICK = 1400, REFRESH_PER_TICK = 260;
    private static final Identifier ID = Identifier.of("aot_rpg", "minimap");
    private static final int NONE = Integer.MIN_VALUE;

    private static final int[] raw = new int[TEX * TEX], hgt = new int[TEX * TEX], wet = new int[TEX * TEX];
    private static final int[] stampX = new int[TEX * TEX], stampZ = new int[TEX * TEX];
    private static final boolean[] dirty = new boolean[TEX * TEX];
    private static final it.unimi.dsi.fastutil.ints.IntArrayList dirtyList = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static NativeImage view;
    private static NativeImageBackedTexture texture;
    private static int refresh;
    private static boolean ready, changed;
    private static net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey;
    /** Screen y just below the minimap and objective, for the party frames. */
    public static int bottom = 4;

    static {
        java.util.Arrays.fill(stampX, NONE);
    }

    private static int idx(int x, int z) {
        return Math.floorMod(z, TEX) * TEX + Math.floorMod(x, TEX);
    }

    private static boolean has(int x, int z) {
        int i = idx(x, z);
        return stampX[i] == x && stampZ[i] == z;
    }

    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity pl = mc.player;
        ClientWorld w = mc.world;
        if (pl == null || w == null || !ClientState.minimap) return;
        if (view == null) {
            view = new NativeImage(TEX, TEX, true);
            view.fillRect(0, 0, TEX, TEX, abgr(UNKNOWN));
            texture = new NativeImageBackedTexture(view);
            mc.getTextureManager().registerTexture(ID, texture);
        }
        if (w.getRegistryKey() != worldKey) {
            // A new world: forget the old one.
            worldKey = w.getRegistryKey();
            java.util.Arrays.fill(stampX, NONE);
            view.fillRect(0, 0, TEX, TEX, abgr(UNKNOWN));
            changed = true;
        }
        int cx = pl.getBlockX(), cz = pl.getBlockZ();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        // New ground first, nearest first: square rings out from where you are.
        int budget = NEW_PER_TICK;
        for (int r = 0; r <= RAD && budget > 0; r++) {
            for (int k = -r; k <= r && budget > 0; k++) {
                budget -= fresh(w, cx + k, cz - r, pos);
                if (r > 0) budget -= fresh(w, cx + k, cz + r, pos);
                if (k > -r && k < r) {
                    budget -= fresh(w, cx - r, cz + k, pos);
                    budget -= fresh(w, cx + r, cz + k, pos);
                }
            }
        }
        // And the rest re-surveyed bit by bit (blocks change, chunks load in).
        int side = RAD * 2 + 1;
        for (int n = 0; n < REFRESH_PER_TICK; n++) {
            refresh = (refresh + 1) % (side * side);
            int x = cx - RAD + refresh % side, z = cz - RAD + refresh / side;
            sample(w, x, z, pos);
        }
        // Ink whatever changed (and its neighbours, whose shading depends on it).
        if (!dirtyList.isEmpty()) {
            for (int j = 0; j < dirtyList.size(); j++) {
                int i = dirtyList.getInt(j);
                dirty[i] = false;
                view.setColor(i % TEX, i / TEX, abgr(ink(i)));
            }
            dirtyList.clear();
            changed = true;
        }
        ready = true;
    }

    /** Surveys a column if it's not on the map yet (1 spent, 0 if it was). */
    private static int fresh(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
        if (has(x, z)) return 0;
        sample(w, x, z, pos);
        return 1;
    }

    private static final int UNKNOWN = 0xFF2A261F;

    /** One column of the world: its surface colour, height and how deep any water is. */
    private static void sample(ClientWorld w, int x, int z, BlockPos.Mutable pos) {
        int i = idx(x, z);
        boolean was = stampX[i] == x && stampZ[i] == z;
        int oh = hgt[i], ow = wet[i], or = raw[i];
        if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
            if (!was) {
                stampX[i] = NONE;
                return;
            }
            return;
        }
        stampX[i] = x;
        stampZ[i] = z;
        wet[i] = 0;
        hgt[i] = NONE;
        raw[i] = UNKNOWN;
        int y = height(w, x, z);
        if (y > w.getBottomY()) {
            hgt[i] = y;
            pos.set(x, y, z);
            BlockState st = w.getBlockState(pos);
            if (st.getFluidState().isOf(Fluids.WATER) || st.getFluidState().isOf(Fluids.FLOWING_WATER)) {
                int depth = 1;
                while (depth < 12) {
                    pos.setY(y - depth);
                    if (w.getFluidState(pos).isEmpty()) break;
                    depth++;
                }
                wet[i] = depth;
            } else {
                MapColor mc = st.getMapColor(w, pos);
                if (mc == MapColor.CLEAR) hgt[i] = NONE;
                else raw[i] = 0xFF000000 | mc.color;
            }
        }
        if (was && oh == hgt[i] && ow == wet[i] && or == raw[i]) return;
        // This one and the neighbours it shades.
        mark(x, z);
        mark(x + 1, z);
        mark(x - 1, z);
        mark(x, z + 1);
        mark(x, z - 1);
    }

    private static void mark(int x, int z) {
        int i = idx(x, z);
        if (!dirty[i]) {
            dirty[i] = true;
            dirtyList.add(i);
        }
    }

    /**
     * A texel as drawn: colours softened into each other and washed towards parchment, hills
     * shaded from the north-west, a contour line every ten blocks of height, pale shorelines.
     */
    private static int ink(int i) {
        if (stampX[i] == NONE || hgt[i] == NONE) return UNKNOWN;
        int x = stampX[i], z = stampZ[i];
        if (wet[i] > 0) {
            float d = Math.min(1, wet[i] / 10f);
            int r = (int) (112 - 40 * d), g = (int) (140 - 38 * d), b = (int) (148 - 26 * d);
            if (land(x - 1, z) || land(x + 1, z) || land(x, z - 1) || land(x, z + 1)) {
                r += 34; g += 34; b += 26;
            }
            return argb(r, g, b);
        }
        int r = 0, g = 0, b = 0, n = 0;
        int[][] around = {{0, 0}, {0, 0}, {0, 0}, {0, 0}, {-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (int[] o : around) {
            int xx = x + o[0], zz = z + o[1];
            if (!land(xx, zz)) continue;
            int j = idx(xx, zz);
            r += raw[j] >> 16 & 255; g += raw[j] >> 8 & 255; b += raw[j] & 255; n++;
        }
        if (n == 0) return UNKNOWN;
        r /= n; g /= n; b /= n;
        float l = (r * 0.3f + g * 0.59f + b * 0.11f) / 255f;
        float pr = 92 + (226 - 92) * l, pg = 74 + (211 - 74) * l, pb = 52 + (176 - 52) * l;
        float keep = 0.42f;
        float fr = pr + (r - pr) * keep, fg = pg + (g - pg) * keep, fb = pb + (b - pb) * keep;
        int h = hgt[i];
        int slope = (h(x + 1, z, h) - h(x - 1, z, h)) + (h(x, z + 1, h) - h(x, z - 1, h));
        float shade = Math.max(0.72f, Math.min(1.18f, 1 + slope * 0.035f));
        if (Math.floorDiv(h, 10) != Math.floorDiv(h(x - 1, z, h), 10) || Math.floorDiv(h, 10) != Math.floorDiv(h(x, z - 1, h), 10)) shade *= 0.82f;
        return argb((int) (fr * shade), (int) (fg * shade), (int) (fb * shade));
    }

    private static boolean land(int x, int z) {
        if (!has(x, z)) return false;
        int j = idx(x, z);
        return wet[j] == 0 && hgt[j] != NONE;
    }

    private static int h(int x, int z, int fallback) {
        if (!has(x, z)) return fallback;
        int v = hgt[idx(x, z)];
        return v == NONE ? fallback : v;
    }

    private static int argb(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** ARGB to the ABGR NativeImage wants. */
    private static int abgr(int c) {
        return (c & 0xFF00FF00) | (c & 0xFF) << 16 | (c >> 16 & 0xFF);
    }

    /** The map as a smooth disc centred on (px, pz), sliding a fraction of a pixel at a time. */
    private static void disc(DrawContext c, int cx, int cy, double px, double pz) {
        if (changed) {
            // Filtered and wrapping (the map is a torus of world columns).
            texture.bindTexture();
            view.upload(0, 0, 0, 0, 0, TEX, TEX, true, false, false, false);
            changed = false;
        }
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, ID);
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionTexProgram);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        org.joml.Matrix4f m = c.getMatrices().peek().getPositionMatrix();
        var buf = net.minecraft.client.render.Tessellator.getInstance().begin(net.minecraft.client.render.VertexFormat.DrawMode.TRIANGLES,
            net.minecraft.client.render.VertexFormats.POSITION_TEXTURE);
        float u = (float) (px / TEX), v = (float) (pz / TEX), du = R / (float) TEX;
        int seg = 72;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg, a1 = (i + 1) * Math.PI * 2 / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            buf.vertex(m, cx, cy, 0).texture(u, v);
            buf.vertex(m, cx + c1 * R, cy + s1 * R, 0).texture(u + c1 * du, v + s1 * du);
            buf.vertex(m, cx + c0 * R, cy + s0 * R, 0).texture(u + c0 * du, v + s0 * du);
        }
        net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buf.end());
    }

    /** True when a point (relative to the map's top-left) lies inside the round map, with a margin. */
    public static boolean onMap(double sx, double sz, int margin) {
        double dx = sx - R, dz = sz - R, r = R - margin;
        return dx * dx + dz * dz <= r * r;
    }

    private static int height(ClientWorld w, int x, int z) {
        return w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
    }

    /** A tiny house icon centred on (cx, cy). */
    static void house(DrawContext c, int cx, int cy, int col) {
        c.fill(cx - 4, cy - 1, cx + 5, cy + 5, 0xFF101010);
        c.fill(cx - 3, cy, cx + 4, cy + 4, col);
        c.fill(cx - 1, cy + 2, cx + 2, cy + 4, 0xFF3A2A16);
        for (int i = 0; i < 4; i++) {
            c.fill(cx - 4 + i, cy - 1 - i, cx + 5 - i, cy - i, i == 0 ? 0xFF101010 : col);
        }
        c.fill(cx, cy - 5, cx + 1, cy - 4, 0xFF101010);
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        // Out on an island the run's own radar takes this corner.
        if (ExtractionHud.active()) return;
        ClientPlayerEntity pl = mc.player;
        bottom = 4;
        if (pl == null || texture == null || !ready || !ClientState.minimap || mc.currentScreen != null || mc.options.hudHidden || mc.getDebugHud().shouldShowDebugHud()) return;
        float td = tick.getTickDelta(true);
        double px = pl.getLerpedPos(td).x, pz = pl.getLerpedPos(td).z;

        // Low enough to clear an FPS counter in the corner.
        int s = VIEW, x = 10, y = 34;
        int cx = x + R, cy = y + R;
        ring(c, cx, cy, R + 4, R + 3, 0x50000000);
        ring(c, cx, cy, R + 3, R + 2, 0xFF8C7248);
        ring(c, cx, cy, R + 2, R, 0xFF16140F);
        disc(c, cx, cy, px, pz);
        // map-space origin (the world point at the top-left of the view)
        double ox = px - R, oz = pz - R;

        // Cooking fires
        int[] fires = ClientState.campfires;
        for (int i = 0; i + 2 < fires.length; i += 3) {
            double sx = fires[i] + 0.5 - ox, sz = fires[i + 2] + 0.5 - oz;
            if (!onMap(sx, sz, 3)) continue;
            int fx = x + (int) sx, fy = y + (int) sz;
            c.fill(fx - 2, fy - 2, fx + 2, fy + 2, 0xFF2A1406);
            c.fill(fx - 1, fy - 1, fx + 1, fy + 1, 0xFFFF9A2E);
        }
        WitnessFx.minimap(c, x, y, s, ox, oz);
        // Entities: one marker per creature (titan hitbox parts are not mobs), diamonds so they never look like roofs.
        java.util.List<int[]> placed = new java.util.ArrayList<>();
        for (Entity e : mc.world.getEntities()) {
            if (e == pl || !(e instanceof net.minecraft.entity.mob.MobEntity le) || !le.isAlive()) continue;
            double sx = e.getX() - ox, sz = e.getZ() - oz;
            if (!onMap(sx, sz, 5)) continue;
            int ex = x + (int) sx, ey = y + (int) sz;
            String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            boolean titan = type.contains("titan");
            if (!titan && !(e instanceof Monster)) continue;
            boolean dup = false;
            for (int[] p : placed) if (Math.abs(p[0] - ex) <= 3 && Math.abs(p[1] - ey) <= 3) dup = true;
            if (dup) continue;
            placed.add(new int[] {ex, ey});
            if (titan) diamond(c, ex, ey, 4, 0xFF1A0C0A, 0xFFC8402F);
            else diamond(c, ex, ey, 2, 0xFF1A0C0A, 0xFFB86050);
        }
        // Party members (also far away: pinned to the rim)
        for (Net.PartyMember m : ClientState.party) {
            if (!m.online() || !m.sameWorld()) continue;
            int[] p = clamp(m.x() - ox, m.z() - oz, s);
            int col = m.leader() ? 0xFFF2C14E : 0xFF8FC07A;
            c.fill(x + p[0] - 2, y + p[1] - 2, x + p[0] + 3, y + p[1] + 3, 0xFF0B0F0C);
            c.fill(x + p[0] - 1, y + p[1] - 1, x + p[0] + 2, y + p[1] + 2, col);
        }
        // Quest targets, map marks and party highlights (pinned to the rim when far away).
        boolean open = MinecraftClient.getInstance().world != null && MinecraftClient.getInstance().world.getRegistryKey() == net.minecraft.world.World.OVERWORLD;
        for (Net.Marker m : open ? ClientState.markers : java.util.List.<Net.Marker>of()) {
            if (m.kind().equals("ferry") || m.kind().equals("giver")) {
                double fx = m.x() + 0.5 - ox, fz = m.z() + 0.5 - oz;
                if (!onMap(fx, fz, 6)) continue;
                String glyph = m.kind().equals("giver") ? "!" : "⚓";
                Text t = Text.literal(glyph);
                c.drawText(Ui.font(), t, x + (int) fx - Ui.font().getWidth(t) / 2, y + (int) fz - 4, 0xFF000000 | m.color(), true);
                continue;
            }
            if (m.kind().equals("home")) {
                // Your homes: a small house, only when on the minimap.
                double hx = m.x() + 0.5 - ox, hz = m.z() + 0.5 - oz;
                if (onMap(hx, hz, 6)) house(c, x + (int) hx, y + (int) hz, 0xFF000000 | m.color());
                continue;
            }
            int[] p = clamp(m.x() + 0.5 - ox, m.z() + 0.5 - oz, s);
            diamond(c, x + p[0], y + p[1], 3, 0xFF101010, 0xFF000000 | m.color());
        }
        Net.Objective obj = ClientState.objective;
        if (obj != null && obj.chapter().isEmpty() && obj.text().isEmpty()) obj = null;
        // You: a slim pointer.
        MatrixStack ms = c.getMatrices();
        ms.push();
        ms.translate(x + (float) (px - ox), y + (float) (pz - oz), 0);
        ms.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(pl.getYaw(td) + 180));
        for (int r = -6; r <= 3; r++) {
            int hw = (r + 6) / 2 + 1;
            c.fill(-hw, r, hw + 1, r + 1, 0xFF14120E);
        }
        for (int r = -5; r <= 2; r++) {
            int hw = (r + 5) / 2;
            if (r == 2) {
                c.fill(-hw, r, 0, r + 1, 0xFFF4ECD8);
                c.fill(1, r, hw + 1, r + 1, 0xFFF4ECD8);
            } else {
                c.fill(-hw, r, hw + 1, r + 1, 0xFFF4ECD8);
            }
        }
        ms.pop();

        // North: a small plaque above the rim, joined to it by a brass pin.
        var font = Ui.font();
        int ny = y - 16;
        c.fill(cx - 5, ny, cx + 6, ny + 11, 0xD00D0F0D);
        c.fill(cx - 5, ny + 11, cx + 6, ny + 12, 0x808C7248);
        c.fill(cx, ny + 12, cx + 1, y - 3, 0xFF8C7248);
        c.drawText(font, "N", cx - font.getWidth("N") / 2 + 1, ny + 2, Ui.GOLD, false);

        // Coordinates: a plaque under the rim, the numbers centred in it.
        String coords = pl.getBlockX() + "  " + pl.getBlockZ();
        int cw = font.getWidth(coords) + 12, cyy = y + s + 6;
        c.fill(cx - cw / 2, cyy, cx + cw / 2 + 1, cyy + 12, 0xC00D0F0D);
        c.fill(cx - cw / 2, cyy + 12, cx + cw / 2 + 1, cyy + 13, 0x608C7248);
        c.drawText(font, coords, cx - font.getWidth(coords) / 2 + 1, cyy + 2, 0xFFD8CFB8, false);

        int ty = cyy + 18;
        if (obj != null) {
            // The objective, on its own card with room around the words.
            int pad = 6;
            Text heading = Ui.heading(obj.chapter());
            // Wide enough for the chapter line, so nothing runs past the card's edge.
            int cardX = 4, cardW = Math.max(150, Math.min(240, font.getWidth(heading) + pad * 2 + 3));
            String line = obj.text();
            if (!obj.progress().isEmpty()) line += "  " + obj.progress();
            if (obj.hasTarget()) {
                double dx = obj.x() - pl.getX(), dz = obj.z() - pl.getZ();
                line += "  (" + (int) Math.sqrt(dx * dx + dz * dz) + "m)";
            }
            var lines = font.wrapLines(Text.literal(line), cardW - pad * 2 - 3);
            int cardH = pad + 10 + 2 + lines.size() * 10 + pad - 2;
            c.fill(cardX, ty, cardX + cardW, ty + cardH, 0xB40D0F0D);
            c.fill(cardX, ty, cardX + 2, ty + cardH, 0xFFB8955A);
            int tx = cardX + pad + 1, yy = ty + pad;
            c.drawText(font, font.trimToWidth(heading, cardW - pad * 2 - 3).getString().equals(heading.getString()) ? heading.asOrderedText()
                : net.minecraft.text.OrderedText.concat(net.minecraft.util.Language.getInstance().reorder(font.trimToWidth(heading, cardW - pad * 2 - 12)), Text.literal("…").asOrderedText()), tx, yy, Ui.GOLD, false);
            yy += 12;
            for (var l : lines) {
                c.drawText(font, l, tx, yy, Ui.CREAM, false);
                yy += 10;
            }
            ty += cardH;
        }
        bottom = ty + 4;
    }

    /** A ring between two radii around (cx, cy), drawn a row at a time. */
    private static void ring(DrawContext c, int cx, int cy, int outer, int inner, int col) {
        for (int dy = -outer; dy < outer; dy++) {
            double fy = dy + 0.5;
            int wo = (int) Math.round(Math.sqrt(Math.max(0, outer * outer - fy * fy)));
            int wi = Math.abs(fy) < inner ? (int) Math.round(Math.sqrt(inner * inner - fy * fy)) : 0;
            if (wi == 0) c.fill(cx - wo, cy + dy, cx + wo, cy + dy + 1, col);
            else {
                c.fill(cx - wo, cy + dy, cx - wi, cy + dy + 1, col);
                c.fill(cx + wi, cy + dy, cx + wo, cy + dy + 1, col);
            }
        }
    }

    private static void diamond(DrawContext c, int x, int y, int r, int outline, int fill) {
        for (int i = -r; i <= r; i++) {
            int w = r - Math.abs(i);
            c.fill(x - w - 1, y + i, x + w + 2, y + i + 1, outline);
        }
        for (int i = -r + 1; i <= r - 1; i++) {
            int w = r - 1 - Math.abs(i);
            c.fill(x - w, y + i, x + w + 1, y + i + 1, fill);
        }
    }

    private static int[] clamp(double sx, double sz, int s) {
        double dx = sx - R, dz = sz - R;
        double lim = R - 5, d = Math.sqrt(dx * dx + dz * dz);
        if (d > lim) {
            dx = dx * lim / d;
            dz = dz * lim / d;
        }
        return new int[] {(int) Math.round(R + dx), (int) Math.round(R + dz)};
    }
}
