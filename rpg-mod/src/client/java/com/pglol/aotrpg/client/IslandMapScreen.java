package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The island's own map (M out on a run): a sea chart of the island with its edge, the flares,
 * the hot zones, the objectives, your squad and the squad's marks, each named, and you with where
 * you're facing. Drag to move it, scroll to zoom, right-click to mark a spot for the squad
 * (right-click your mark again to take it back).
 */
public final class IslandMapScreen extends Screen {
    private final long opened = Util.getMeasuringTimeMs();
    /** Zoom (1 = the whole island) and the middle of the view, in blocks. */
    private static double zoom = 1;
    private static double midX = Double.NaN, midZ = Double.NaN;
    private static String lastIsland = "";

    public IslandMapScreen() {
        super(Text.literal("Island"));
        Net.RunView v = ExtractionHud.view;
        if (v != null && (!v.island().equals(lastIsland) || Double.isNaN(midX))) {
            lastIsland = v.island();
            recentre(v);
        }
    }

    private static void recentre(Net.RunView v) {
        zoom = 1;
        midX = v.cx();
        midZ = v.cz();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // The map key puts it away again.
        if (AotRpgClient.mapKey().matchesKey(key, scan) && !Screen.hasControlDown()) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected void init() {
        AotButton c = addDrawableChild(new AotButton(width - 74, height - 22, 64, 16, Text.literal("Recentre"), () -> {
            if (ExtractionHud.view != null) recentre(ExtractionHud.view);
        }));
        c.textScale = 0.75f;
    }

    private int R() {
        return (int) (Math.min(width, height) * 0.42f);
    }

    /** Blocks per pixel at the current zoom. */
    private double scale(Net.RunView v) {
        return v.radius() * 1.12 / R() / zoom;
    }

    private float sx(Net.RunView v, double x) {
        return (float) (width / 2.0 + (x - midX) / scale(v));
    }

    private float sy(Net.RunView v, double z) {
        return (float) (height / 2.0 + 8 + (z - midZ) / scale(v));
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        Net.RunView v = ExtractionHud.view;
        if (v == null || button != 0) return super.mouseDragged(mx, my, button, dx, dy);
        midX -= dx * scale(v);
        midZ -= dy * scale(v);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        Net.RunView v = ExtractionHud.view;
        if (v == null) return false;
        // Zoom about the point under the mouse.
        double wx = midX + (mx - width / 2.0) * scale(v), wz = midZ + (my - height / 2.0 - 8) * scale(v);
        zoom = MathHelper.clamp(zoom * (vy > 0 ? 1.25 : 0.8), 0.6, 6);
        midX = wx - (mx - width / 2.0) * scale(v);
        midZ = wz - (my - height / 2.0 - 8) * scale(v);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Net.RunView v = ExtractionHud.view;
        if (v != null && button == 1) {
            // Right-click your own mark: take it back. Anywhere else: mark it.
            for (Net.RunPoint pt : v.points()) {
                if (!pt.kind().equals("mymark")) continue;
                double dx = sx(v, pt.x()) - mx, dy = sy(v, pt.z()) - my;
                if (dx * dx + dy * dy < 10 * 10) {
                    ClientPlayNetworking.send(new Net.ExtractionAction("unmark", ""));
                    return true;
                }
            }
            int x = (int) Math.round(midX + (mx - width / 2.0) * scale(v)), z = (int) Math.round(midZ + (my - height / 2.0 - 8) * scale(v));
            ClientPlayNetworking.send(new Net.ExtractionAction("mark", x + "," + z));
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // Deep water, darker towards the edges.
        c.fillGradient(0, 0, width, height, 0xFF0C1C26, 0xFF061017);
        Net.RunView v = ExtractionHud.view;
        if (v == null || client == null || client.player == null) return;
        long t = Util.getMeasuringTimeMs();
        float in = Math.min(1, (t - opened) / 220f);
        double ppb = 1 / scale(v);
        float ix = sx(v, v.cx()), iy = sy(v, v.cz()), ir = (float) (v.radius() * ppb);
        int col = v.color() & 0xFFFFFF;
        // The island: a soft glow of shallows round it, its ground (as far as anyone's seen it),
        // and the line of the sea mist.
        Smooth.glow(c, ix, iy, ir * 1.12f, 0x30000000 | col);
        Smooth.disc(c, ix, iy, ir, 0xFF14231C);
        IslandChart.draw(c, sx(v, IslandChart.x0()), sy(v, IslandChart.z0()), ppb, in);
        Smooth.ring(c, ix, iy, ir, 1.6f, 0xA0000000 | col);
        Smooth.ring(c, ix, iy, ir + 5, 1, 0x30000000 | col);
        // Points: areas first (soft, under everything), then the markers; labels only where they fit.
        Smooth.Labels labels = new Smooth.Labels();
        var pl = client.player;
        float you = 0;
        float px = sx(v, pl.getX()), py = sy(v, pl.getZ());
        labels.block(px - 7, py - 7, px + 7, py + 7);
        java.util.List<Net.RunPoint> pts = new java.util.ArrayList<>(v.points());
        pts.sort(java.util.Comparator.comparingInt(IslandMapScreen::rank));
        for (Net.RunPoint pt : pts) {
            if (!pt.kind().equals("poi")) continue;
            Smooth.glow(c, sx(v, pt.x()), sy(v, pt.z()), (float) Math.max(10, 45 * ppb), (pt.done() ? 0x20000000 : 0x50000000) | (color(pt) & 0xFFFFFF));
        }
        for (Net.RunPoint pt : pts) {
            float x = sx(v, pt.x()), y = sy(v, pt.z());
            labels.block(x - 6, y - 6, x + 6, y + 6);
        }
        for (Net.RunPoint pt : pts) {
            float x = sx(v, pt.x()), y = sy(v, pt.z());
            int pc = color(pt);
            switch (pt.kind()) {
                case "exit" -> {
                    float pulse = (t % 1800) / 1800f;
                    Smooth.ring(c, x, y, 5 + pulse * 10, 1.4f, ((int) (190 * (1 - pulse)) << 24) | (pc & 0xFFFFFF));
                    Smooth.dot(c, x, y, 4, pc);
                    Smooth.disc(c, x, y, 1.5f, 0xFFFFFFFF);
                }
                case "obj" -> Smooth.diamond(c, x, y, 4.5f, pc);
                case "poi" -> {
                    Smooth.ring(c, x, y, 5.5f, 1.5f, pc);
                    Smooth.disc(c, x, y, 2, pc);
                }
                case "mark", "mymark" -> Smooth.pin(c, x, y, pc);
                default -> Smooth.dot(c, x, y, 3.2f, pc);
            }
            String label = pt.kind().equals("exit") ? "Flare" : pt.name() + (pt.done() ? "  \u2714" : "");
            if (label.isEmpty()) continue;
            float sc = 0.7f, lw = Ui.font().getWidth(label) * sc;
            float ly = pt.kind().endsWith("mark") ? y + 4 : y + 7;
            boolean hover = Math.abs(mouseX - x) < 8 && Math.abs(mouseY - y) < 8;
            if (!labels.claim(x - lw / 2 - 3, ly - 1, x + lw / 2 + 3, ly + 9) && !hover) continue;
            Smooth.pill(c, x - lw / 2 - 3, ly - 1.5f, x + lw / 2 + 3, ly + 7.5f, 0xA0081014);
            Ui.text(c, Text.literal(label), x, ly, sc, pt.done() ? 0xFF8A877E : pc, true);
        }
        // You: an arrow with a soft cone of view.
        float yaw = pl.getYaw(delta);
        Smooth.cone(c, px, py, yaw, 34, 1.0f, 0x50FFFFFF);
        Smooth.arrow(c, px, py, yaw, 6.5f, 0xFFFFFFFF);
        // Edges fade into the dark, so the chart sits in the screen instead of being boxed in.
        int fade = 28;
        c.fillGradient(0, 0, width, fade, 0xFF061017, 0x00061017);
        c.fillGradient(0, height - fade, width, height, 0x00061017, 0xFF061017);
        // Compass, title and clock.
        Smooth.pill(c, width / 2f - 70, 6, width / 2f + 70, 46, 0x90061017);
        Ui.text(c, Ui.title(v.name().toUpperCase()), width / 2f, 11, 1.4f, v.color(), true);
        int left = ExtractionHud.secondsLeft();
        String sub = String.format("%d:%02d left", left / 60, left % 60) + (v.matchMinutes() > 0 ? "   \u00B7   " + v.matchMinutes() + " min in" : "");
        Ui.text(c, Text.literal(sub), width / 2f, 31, 0.75f, left < 120 ? 0xFFE03A3A : Ui.CREAM, true);
        float nx = width - 30, ny = 30;
        Smooth.disc(c, nx, ny, 13, 0x90061017);
        Smooth.ring(c, nx, ny, 13, 1, 0x80B8955A);
        Smooth.poly(c, new float[] {nx, ny - 10, nx + 3.5f, ny, nx - 3.5f, ny}, 0xFFE0463A);
        Smooth.poly(c, new float[] {nx, ny + 10, nx - 3.5f, ny, nx + 3.5f, ny}, 0xFFD8D0C0);
        Ui.text(c, Text.literal("N"), nx, ny - 24, 0.7f, Ui.GOLD, true);
        // Legend and controls, along the bottom.
        String[][] legend = {{"exit", "Flare"}, {"obj", "Objective"}, {"poi", "Hot zone"}, {"mate", "Squad"}, {"mark", "Mark"}};
        float lx = 16, lyy = height - 16;
        for (String[] l : legend) {
            int lc = ExtractionHud.color(l[0]);
            switch (l[0]) {
                case "obj" -> Smooth.diamond(c, lx + 3, lyy + 3.5f, 3, lc);
                case "poi" -> Smooth.ring(c, lx + 3, lyy + 3.5f, 3.5f, 1.3f, lc);
                case "mark" -> Smooth.pin(c, lx + 3, lyy + 7, lc);
                default -> Smooth.dot(c, lx + 3, lyy + 3.5f, 2.8f, lc);
            }
            Ui.text(c, Text.literal(l[1]), lx + 11, lyy, 0.72f, Ui.CREAM, false);
            lx += 16 + Ui.font().getWidth(l[1]) * 0.72f + 12;
        }
        String hint = "Drag  \u00B7  Scroll  \u00B7  Right-click: mark";
        Ui.text(c, Text.literal(hint), width - 84 - Ui.font().getWidth(hint) * 0.65f, height - 16, 0.65f, Ui.MUTED, false);
    }

    private static int color(Net.RunPoint pt) {
        return pt.done() ? 0xFF55524A : ExtractionHud.color(pt.kind());
    }

    /** Label priority: who matters most gets the room first. */
    private static int rank(Net.RunPoint pt) {
        return switch (pt.kind()) {
            case "mate" -> 0;
            case "exit" -> 1;
            case "mymark", "mark" -> 2;
            case "obj" -> 3;
            default -> 4;
        };
    }
}
