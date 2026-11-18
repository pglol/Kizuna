package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * Out on an island the open world's HUD steps aside (no Paradis minimap, markers or area banners):
 * a radar of the island instead, with the flares, hot zones, objectives and your squad on it,
 * the clock, and your tasks and objectives underneath.
 */
public final class ExtractionHud {
    private ExtractionHud() {}

    static Net.RunView view;
    private static long viewAt;

    public static void on(Net.RunView v) {
        view = v.island().isEmpty() ? null : v;
        viewAt = Util.getMeasuringTimeMs();
        if (view == null && MinecraftClient.getInstance().currentScreen instanceof IslandMapScreen) MinecraftClient.getInstance().setScreen(null);
    }

    /** On an island in a run right now. */
    public static boolean active() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return view != null && mc.world != null && mc.world.getRegistryKey().getValue().getPath().equals(view.island());
    }

    static int secondsLeft() {
        if (view == null) return 0;
        return (int) Math.max(0, view.secondsLeft() - (Util.getMeasuringTimeMs() - viewAt) / 1000);
    }

    static int color(String kind) {
        return switch (kind) {
            case "exit" -> 0xFFE0463A;
            case "poi" -> 0xFFC77DFF;
            case "obj" -> 0xFFF2C14E;
            case "mark", "mymark" -> 0xFF4FD8FF;
            default -> 0xFF5BD35B;
        };
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!active() || mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        long t = Util.getMeasuringTimeMs();
        int R = 50, cx = 14 + R, cy = 40 + R;
        float bpp = 1.7f;
        double range = R * bpp;
        float td = tick.getTickDelta(true);
        // Where you are this frame (not this tick): the radar glides with you however fast you fly.
        var lp = mc.player.getLerpedPos(td);
        double px = lp.x, pz = lp.z;
        // The ground itself under a soft rim, north up.
        Smooth.ring(c, cx, cy, R + 4.5f, 3, 0x50000000);
        Smooth.disc(c, cx, cy, R + 1, 0xFF0C1418);
        Minimap.disc(c, cx, cy, R, px, pz, bpp);
        Smooth.ring(c, cx, cy, R * 0.5f, 1, 0x26FFFFFF);
        Smooth.ring(c, cx, cy, R + 1.6f, 2.2f, 0xFF7A6139);
        // The island's edge, when it's in range: arcs of the mist line inside the radar.
        double ex = (view.cx() - px) / range * R, ez = (view.cz() - pz) / range * R, er = view.radius() / range * R;
        double centreD = Math.sqrt(ex * ex + ez * ez);
        if (centreD + R > er) {
            for (int i = 0; i < 240; i++) {
                double a = i * Math.PI * 2 / 240;
                double x = ex + Math.cos(a) * er, z = ez + Math.sin(a) * er;
                if (x * x + z * z < (R - 2) * (R - 2)) Smooth.disc(c, cx + (float) x, cy + (float) z, 0.9f, 0xB0A0D8FF);
            }
        }
        for (Net.RunPoint pt : view.points()) {
            double dx = (pt.x() - px) / range * R, dz = (pt.z() - pz) / range * R;
            double d = Math.sqrt(dx * dx + dz * dz);
            boolean rim = d > R - 5;
            if (rim) {
                dx = dx / d * (R - 5);
                dz = dz / d * (R - 5);
            }
            int col = pt.done() ? 0xFF55524A : color(pt.kind());
            float x = cx + (float) dx, y = cy + (float) dz;
            switch (pt.kind()) {
                case "exit" -> {
                    float pulse = (t % 1600) / 1600f;
                    if (!rim) Smooth.ring(c, x, y, 3 + pulse * 6, 1.2f, ((int) (200 * (1 - pulse)) << 24) | (col & 0xFFFFFF));
                    Smooth.dot(c, x, y, 2.6f, col);
                }
                case "obj" -> Smooth.diamond(c, x, y, 3, col);
                case "mark", "mymark" -> Smooth.pin(c, x, y + 3, col);
                case "mate" -> Smooth.dot(c, x, y, 2, col);
                default -> Smooth.dot(c, x, y, 2.4f, col);
            }
        }
        // You.
        float yaw = mc.player.getYaw(td);
        Smooth.cone(c, cx, cy, yaw, 24, 1.1f, 0x40FFFFFF);
        Smooth.arrow(c, cx, cy, yaw, 5.5f, 0xFFFFFFFF);
        Ui.text(c, Text.literal("N"), cx, cy - R - 10, 0.7f, Ui.GOLD, true);
        // The island, the clock, the match.
        int y = cy + R + 6;
        Ui.text(c, Ui.heading(view.name()), 14, y, 0.9f, view.color(), false);
        int left = secondsLeft();
        String clock = String.format("%d:%02d", left / 60, left % 60);
        int cc = left < 120 ? (t / 400 % 2 == 0 ? 0xFFE03A3A : 0xFFFFFFFF) : left < 300 ? Ui.GOLD : Ui.CREAM;
        Ui.text(c, Text.literal(clock), 14 + R * 2 - Ui.font().getWidth(clock) * 0.9f, y, 0.9f, cc, false);
        y += 12;
        // Tasks and objectives.
        for (Net.RunTask task : view.tasks()) {
            boolean done = task.progress() >= task.goal();
            boolean obj = task.text().startsWith("★");
            c.fill(14, y, 14 + 170, y + 12, 0x80000000);
            c.fill(14, y, 16, y + 12, done ? 0xFF5BD35B : obj ? 0xFFF2C14E : 0xFF8F8A7A);
            Ui.text(c, Text.literal(task.text()), 20, y + 3, 0.62f, done ? 0xFF5BD35B : Ui.CREAM, false);
            String pr = done ? "✔" : task.progress() + "/" + task.goal();
            Ui.text(c, Text.literal(pr), 14 + 166 - Ui.font().getWidth(pr) * 0.62f, y + 3, 0.62f, done ? 0xFF5BD35B : Ui.GOLD, false);
            y += 14;
        }
    }

    static void disc(DrawContext c, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int w = (int) Math.sqrt(r * r - dy * dy);
            c.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, col);
        }
    }

    static void ring(DrawContext c, int cx, int cy, int r, int col) {
        int n = Math.max(24, r * 4);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int x = cx + (int) Math.round(Math.cos(a) * r), y = cy + (int) Math.round(Math.sin(a) * r);
            c.fill(x, y, x + 1, y + 1, col);
        }
    }
}
