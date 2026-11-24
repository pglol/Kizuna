package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The compass ribbon under the top bar: the way you're facing, ticks every 15 degrees with the
 * cardinal points, and every waypoint as a diamond in its colour at its bearing (the tracked one
 * with its distance). Anything behind you waits at the ribbon's ends.
 */
public final class Compass {
    private Compass() {}

    public static final int H = 18, W = 260;
    private static final float SPAN = 200; // degrees across the ribbon

    public static boolean visible() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return Settings.compass && mc.player != null && !mc.options.hudHidden && ClientState.profile != null
            && mc.world != null && mc.world.getRegistryKey() == net.minecraft.world.World.OVERWORLD
            && !com.pglol.aotrpg.client.story.CutscenePlayer.active();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        if (!visible()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = c.getScaledWindowWidth();
        int x0 = sw / 2 - W / 2, y0 = TopBar.barBottom() + 3;
        float yaw = MathHelper.wrapDegrees(mc.player.getYaw(tick.getTickDelta(true)));
        // The ribbon: dark, fading out at both ends.
        for (int i = 0; i < W; i += 2) {
            float edge = Math.min(i, W - i) / (W * 0.18f);
            int a = (int) (0x90 * Math.min(1, edge));
            c.fill(x0 + i, y0, x0 + i + 2, y0 + H, a << 24 | 0x0B0E10);
        }
        c.fill(x0 + W / 2 - 40, y0 + H - 1, x0 + W / 2 + 40, y0 + H, 0x60E0B96A);
        // Ticks and the cardinal points.
        for (int deg = 0; deg < 360; deg += 15) {
            float d = MathHelper.wrapDegrees(deg - 180 - yaw);
            if (Math.abs(d) > SPAN / 2) continue;
            float x = x0 + W / 2f + d / SPAN * W;
            int a = (int) (255 * Math.max(0, 1 - Math.abs(d) / (SPAN / 2)) * 0.9f + 20);
            String name = switch (deg) {
                case 0 -> "S";
                case 90 -> "W";
                case 180 -> "N";
                case 270 -> "E";
                case 45 -> "SW";
                case 135 -> "NW";
                case 225 -> "NE";
                case 315 -> "SE";
                default -> null;
            };
            if (name != null) {
                boolean card = name.length() == 1;
                Ui.text(c, Ui.heading(name), x, y0 + 5, card ? 0.8f : 0.55f, (Math.min(255, a) << 24) | (card ? 0xEDE3C8 : 0xA8A294), true);
            } else c.fill((int) x, y0 + 3, (int) x + 1, y0 + 7, (Math.min(255, a) / 2 << 24) | 0xEDE3C8);
        }
        // The waypoints.
        float td = tick.getTickDelta(true);
        Vec3d me = mc.player.getLerpedPos(td);
        WorldMarkers.Pin tracked = null;
        double trackedD = 0;
        for (WorldMarkers.Pin p : WorldMarkers.pins(mc, td)) {
            Vec3d to = p.at().subtract(me);
            float bearing = (float) (MathHelper.atan2(to.z, to.x) * MathHelper.DEGREES_PER_RADIAN) - 90;
            float d = MathHelper.wrapDegrees(bearing - yaw);
            boolean behind = Math.abs(d) > SPAN / 2;
            float x = x0 + W / 2f + MathHelper.clamp(d, -SPAN / 2, SPAN / 2) / SPAN * W;
            int rgb = p.color() & 0xFFFFFF;
            int a = behind ? 0x90 : 0xF0;
            float r = p.big() ? 4 : 3;
            var m = c.getMatrices();
            m.push();
            m.translate(x, y0 + H - 6, 0);
            m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(45));
            c.fill((int) -r, (int) -r, (int) r, (int) r, (a << 24) | rgb);
            m.pop();
            if (p.big() && (tracked == null || to.horizontalLength() < trackedD)) {
                tracked = p;
                trackedD = to.horizontalLength();
            }
        }
        // Your heading.
        c.fill(sw / 2, y0 - 1, sw / 2 + 1, y0 + 4, 0xFFE0B96A);
        if (tracked != null) {
            String t = (trackedD >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", trackedD / 1000) : (int) trackedD + "m");
            Ui.text(c, Text.literal(t), sw / 2f, y0 + H + 2, 0.55f, 0xD0E0B96A, true);
        }
    }
}
