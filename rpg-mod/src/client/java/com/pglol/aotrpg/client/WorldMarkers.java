package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The AR layer: every waypoint (the tracked quest, world activities, your marks, contracts,
 * events, your party) is pinned to its place in the world but drawn on the screen itself, so no
 * shader pack can wash it out, fog it or bury it in terrain. Each one floats well up above its
 * spot: a diamond in its colour, its name and the distance. Walk up and it eases out of the way;
 * turn away and it slides to the edge of the screen as an arrow pointing round to it.
 */
public final class WorldMarkers {
    private WorldMarkers() {}

    private static final Matrix4f proj = new Matrix4f(), view = new Matrix4f();
    private static Vec3d cam = Vec3d.ZERO;
    private static boolean ready;

    /** How high over its spot a marker floats. */
    static final double LIFT = 9;

    /** Each frame, after the world: the camera's matrices, to place markers on screen. */
    public static void capture(WorldRenderContext ctx) {
        proj.set(ctx.projectionMatrix());
        view.set(ctx.positionMatrix());
        cam = ctx.camera().getPos();
        ready = true;
    }

    /** Screen position (scaled GUI pixels) of a world point, and whether it's in front of the camera. */
    static float[] project(Vec3d p, int sw, int sh) {
        Vector4f v = new Vector4f((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1f);
        view.transform(v);
        proj.transform(v);
        boolean front = v.w > 0.001f;
        float w = Math.abs(v.w) < 1e-4f ? 1e-4f : v.w;
        float nx = v.x / w, ny = v.y / w;
        if (!front) {
            nx = -nx;
            ny = -ny;
        }
        return new float[] {(nx * 0.5f + 0.5f) * sw, (1 - (ny * 0.5f + 0.5f)) * sh, front ? 1 : 0};
    }

    /** One thing to point at. */
    record Pin(String label, Vec3d at, int color, boolean big) { }

    static List<Pin> pins(MinecraftClient mc, float td) {
        List<Pin> out = new ArrayList<>();
        if (mc.world == null || mc.player == null || mc.world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return out;
        for (Net.Marker m : ClientState.markers) {
            if (m.kind().equals("home") || m.kind().equals("ferry") || m.kind().equals("giver")) continue;
            // Swing sites are for the map; on screen only once you're close enough to see them anyway.
            if (m.kind().equals("swing") && Math.hypot(m.x() - mc.player.getX(), m.z() - mc.player.getZ()) > 260) continue;
            double g = Beams.groundY(mc, m);
            boolean big = m.kind().equals("quest") || m.kind().equals("activity");
            out.add(new Pin(m.label().isEmpty() ? "Waypoint" : m.label(), new Vec3d(m.x() + 0.5, g + LIFT, m.z() + 0.5), m.color(), big));
        }
        for (Net.PartyMember pm : ClientState.party) {
            if (!pm.online() || !pm.sameWorld() || pm.id().equals(mc.player.getUuid())) continue;
            var ent = mc.world.getPlayerByUuid(pm.id());
            Vec3d pos = ent != null ? ent.getLerpedPos(td).add(0, ent.getHeight() + 1.4, 0) : new Vec3d(pm.x(), pm.y() + 3.2, pm.z());
            out.add(new Pin(pm.name(), pos, pm.leader() ? 0xF2C14E : 0x5BD35B, false));
        }
        return out;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!ready || mc.player == null || mc.options.hudHidden || mc.currentScreen != null || !Settings.markers) return;
        if (com.pglol.aotrpg.client.story.CutscenePlayer.active()) return;
        int sw = c.getScaledWindowWidth(), sh = c.getScaledWindowHeight();
        float td = tick.getTickDelta(true);
        long now = Util.getMeasuringTimeMs();
        for (Pin p : pins(mc, td)) {
            double dist = p.at().subtract(mc.player.getPos()).horizontalLength();
            // Up close it gets out of the way (party members: their name plate takes over).
            float fade = p.big() ? (float) MathHelper.clamp((dist - 5) / 10, 0, 1) : (float) MathHelper.clamp((dist - 22) / 10, 0, 1);
            if (!p.big() && dist < 22) continue;
            if (fade < 0.03f) continue;
            float[] s = project(p.at(), sw, sh);
            boolean on = s[2] > 0 && s[0] >= 14 && s[0] <= sw - 14 && s[1] >= 14 && s[1] <= sh - 14;
            float x = s[0], y = s[1];
            int a = (int) (235 * fade);
            if (on) {
                // A gentle bob so it reads as floating.
                y += (float) Math.sin((now + p.at().x * 37) / 700.0) * 1.5f;
                pin(c, x, y, p, dist, a, now);
            } else {
                // Off screen: an arrow at the edge, pointing round to it.
                float cx = sw / 2f, cy = sh / 2f;
                float dx = x - cx, dy = y - cy;
                if (s[2] == 0 && Math.abs(dx) < 1 && Math.abs(dy) < 1) dy = 1;
                float k = Math.min((sw / 2f - 18) / Math.max(1e-3f, Math.abs(dx)), (sh / 2f - 18) / Math.max(1e-3f, Math.abs(dy)));
                float ex = cx + dx * k, ey = cy + dy * k;
                edge(c, ex, ey, (float) Math.atan2(dy, dx), p, dist, Math.min(a, 200));
            }
        }
    }

    private static String meters(double d) {
        return d >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", d / 1000) : (int) d + "m";
    }

    private static void pin(DrawContext c, float x, float y, Pin p, double dist, int a, long now) {
        int rgb = p.color() & 0xFFFFFF;
        float r = p.big() ? 6 : 4;
        var m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        // The diamond: a dark rim, its colour, a bright core; the big ones breathe a little.
        float pulse = p.big() ? 1 + 0.08f * (float) Math.sin(now / 260.0) : 1;
        diamond(c, r * pulse + 1.5f, (a * 3 / 4) << 24);
        diamond(c, r * pulse, (a << 24) | rgb);
        diamond(c, r * 0.4f, (a << 24) | 0xFFFFFF);
        // A thin stem down toward the spot.
        if (p.big()) c.fill(0, (int) (r + 2), 1, (int) (r + 10), ((a / 2) << 24) | rgb);
        m.pop();
        Text label = Ui.heading(p.label());
        String md = meters(dist);
        float ls = p.big() ? 0.7f : 0.6f;
        float ty = y - r - 13;
        int lw = (int) (Ui.font().getWidth(label) * ls);
        c.fill((int) (x - lw / 2f - 4), (int) ty - 2, (int) (x + lw / 2f + 4), (int) ty + 8, ((a / 2) << 24) | 0x0B0E10);
        Ui.text(c, label, x, ty, ls, (a << 24) | 0xEDE3C8, true);
        Ui.text(c, Text.literal(md), x, y + r + (p.big() ? 12 : 4), 0.6f, (a << 24) | 0xE0B96A, true);
    }

    private static void edge(DrawContext c, float x, float y, float angle, Pin p, double dist, int a) {
        int rgb = p.color() & 0xFFFFFF;
        var m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotation(angle));
        // A chevron pointing outward.
        for (int i = 0; i < 6; i++) c.fill(-i, -6 + i, -i + 2, 6 - i, (a << 24) | rgb);
        m.pop();
        float ix = x - (float) Math.cos(angle) * 14, iy = y - (float) Math.sin(angle) * 14;
        Ui.text(c, Text.literal(meters(dist)), ix, iy - 3, 0.55f, (a << 24) | 0xEDE3C8, true);
    }

    private static void diamond(DrawContext c, float r, int argb) {
        var m = c.getMatrices();
        m.push();
        m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(45));
        float h = r / 1.4142f;
        c.fill((int) -h, (int) -h, (int) Math.ceil(h), (int) Math.ceil(h), argb);
        m.pop();
    }
}
