package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The join board. A wide hero card for this week's operation (its colour washing across it), the
 * season and any live event beside it, then what's new and what's coming up. It shows when you join
 * if anything on it changed (or once a day), steps aside if another screen is up, and is gone with
 * one key. /board brings it back.
 */
public final class BoardScreen extends Screen {
    private record Row(String kind, String title, String text, int color) { }

    /** Latest board from the server, waiting to be shown when the screen is free. */
    private static Net.Board pending;

    public static void on(Net.Board b) {
        pending = b;
    }

    /** Called every client tick: open the board if it's due and nothing else is on screen. */
    public static void tick(MinecraftClient mc) {
        if (pending == null || mc.player == null) return;
        if (mc.currentScreen != null || com.pglol.aotrpg.client.story.CutscenePlayer.active()) return;
        Net.Board b = pending;
        if (!b.open() && !due(b)) {
            pending = null;
            return;
        }
        pending = null;
        remember(b);
        mc.setScreen(new BoardScreen(b));
    }

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_board.properties");
    }

    private static String hash(Net.Board b) {
        StringBuilder s = new StringBuilder();
        for (String r : b.rows()) if (!r.startsWith("you|") && !r.startsWith("season|") && !r.startsWith("week|") && !r.startsWith("live|")) s.append(r).append('\n');
        return Integer.toHexString(s.toString().hashCode());
    }

    private static boolean due(Net.Board b) {
        var p = new java.util.Properties();
        try (var in = java.nio.file.Files.newInputStream(file())) {
            p.load(in);
        } catch (Exception ignored) {
        }
        String today = java.time.LocalDate.now().toString();
        return !hash(b).equals(p.getProperty("hash")) || !today.equals(p.getProperty("day"));
    }

    private static void remember(Net.Board b) {
        var p = new java.util.Properties();
        p.setProperty("hash", hash(b));
        p.setProperty("day", java.time.LocalDate.now().toString());
        try (var out = java.nio.file.Files.newOutputStream(file())) {
            p.store(out, "AoT RPG board last seen");
        } catch (Exception ignored) {
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Sleek.Hits hits = new Sleek.Hits();
    private long opened;

    private BoardScreen(Net.Board b) {
        super(Text.literal("Board"));
        for (String r : b.rows()) {
            String[] p = r.split("\\|", 4);
            if (p.length < 4) continue;
            int col;
            try {
                col = Integer.parseInt(p[3]);
            } catch (NumberFormatException e) {
                col = 0xE8C36F;
            }
            rows.add(new Row(p[0], p[1], p[2], col));
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        if (opened == 0) opened = Util.getMeasuringTimeMs();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        float k = MathHelper.clamp((Util.getMeasuringTimeMs() - opened) / 300f, 0, 1);
        c.fill(0, 0, width, height, (int) (0xC8 * k) << 24 | 0x060807);
        c.fillGradient(0, 0, width, height / 2, (int) (0x30 * k) << 24 | 0x2A2416, 0);
    }

    private Row first(String kind) {
        for (Row r : rows) if (r.kind.equals(kind)) return r;
        return null;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        hits.clear();
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        float t = (Util.getMeasuringTimeMs() - opened) / 1000f;
        int W = Math.min(640, width - 32), x0 = (width - W) / 2;
        int y0 = Math.max(14, height / 2 - 150);

        float hk = ease(MathHelper.clamp(t / 0.45f, 0, 1));
        Row you = first("you");
        Ui.text(c, Ui.title("THE BOARD"), x0, y0 - (1 - hk) * 8, 1.4f, a(0xFF, 0xEDE3C8, hk), false);
        if (you != null) {
            String s = "Welcome back, " + you.title + " · " + you.text;
            Ui.text(c, Text.literal(s), x0 + W - Ui.font().getWidth(s) * 0.65f, y0 + 4, 0.65f, a(0xFF, 0x8F8A7A, hk), false);
        }
        int y = y0 + 22;

        // Hero: the week's operation.
        Row week = first("week");
        int heroW = (int) (W * 0.58f), heroH = 104;
        if (week != null) {
            float k = ease(MathHelper.clamp((t - 0.1f) / 0.5f, 0, 1));
            int hx = x0, hy = y + Math.round((1 - k) * 24);
            card(c, hx, hy, heroW, heroH, week.color, k);
            // Moving stripes of the operation's colour.
            c.enableScissor(hx + 1, hy + 1, hx + heroW - 1, hy + heroH - 1);
            for (int i = -4; i < 14; i++) {
                float sx = hx + i * 34 + (t * 14) % 34;
                for (int j = 0; j < heroH; j += 2) c.fill((int) (sx + j * 0.6f), hy + j, (int) (sx + j * 0.6f) + 10, hy + j + 2, a(0x0C, week.color, k));
            }
            c.disableScissor();
            Sleek.chip(c, hx + 12, hy + 12, "THIS WEEK'S OPERATION", a(0xFF, week.color, k));
            Ui.text(c, Ui.title(week.title.toUpperCase(java.util.Locale.ROOT)), hx + 12, hy + 30, 1.5f, a(0xFF, 0xFFFFFF, k), false);
            Sleek.wrap(c, week.text, hx + 12, hy + 56, heroW - 24, 0.8f, a(0xFF, 0xCFC6AE, k));
            Sleek.wrap(c, "Everyone on the server gets it: it changes what's worth doing this week.", hx + 12, hy + heroH - 18, heroW - 24, 0.6f, a(0xFF, 0x8F8A7A, k));
        }
        // Side: season and live event.
        int sx = x0 + heroW + 10, sw = W - heroW - 10;
        int sy = y;
        int side = 0;
        for (String kind : new String[] {"season", "live"}) {
            Row r = first(kind);
            if (r == null) continue;
            int sh = first("live") == null ? heroH : (heroH - 6) / 2;
            float k = ease(MathHelper.clamp((t - 0.2f - side * 0.1f) / 0.5f, 0, 1));
            int cy = sy + Math.round((1 - k) * 24);
            card(c, sx, cy, sw, sh, r.color, k);
            Sleek.chip(c, sx + 10, cy + 8, kind.equals("live") ? "LIVE EVENT" : "SEASON", a(0xFF, r.color, k));
            if (kind.equals("live")) {
                float p = 0.5f + 0.5f * (float) Math.sin(t * 5);
                Smooth.disc(c, sx + sw - 12, cy + 12, 3, a((int) (120 + 135 * p), 0xE8433A, k));
            }
            Ui.text(c, Ui.heading(r.title), sx + 10, cy + 22, 0.95f, a(0xFF, 0xEDE3C8, k), false);
            Sleek.wrap(c, r.text, sx + 10, cy + 35, sw - 20, 0.6f, a(0xFF, 0x9A958A, k));
            sy += sh + 6;
            side++;
        }
        y += heroH + 12;

        // What's new, and what's coming.
        int colW = (W - 10) / 2;
        float lk = ease(MathHelper.clamp((t - 0.35f) / 0.5f, 0, 1));
        int listH = Math.min(150, height - y - 46);
        list(c, x0, y, colW, listH, "WHAT'S NEW", "news", 0xE8C36F, lk, t);
        list(c, x0 + colW + 10, y, colW, listH, "COMING UP", "event", 0x9AD0FF, lk, t);
        y += listH + 10;

        float bk = ease(MathHelper.clamp((t - 0.5f) / 0.4f, 0, 1));
        if (bk > 0) {
            Sleek.button(c, hits, x0 + W - 130, y, 130, 20, "CONTINUE", true, true, this::close);
            Ui.text(c, Text.literal("Space to continue · /board to see this again"), x0, y + 7, 0.6f, a(0xFF, 0x6A665C, bk), false);
        }
    }

    private void list(DrawContext c, int x, int y, int w, int h, String title, String kind, int acc, float k, float t) {
        int cy = y + Math.round((1 - k) * 20);
        card(c, x, cy, w, h, acc, k);
        Ui.text(c, Ui.heading(title), x + 10, cy + 9, 0.8f, a(0xFF, acc, k), false);
        int ly = cy + 24, i = 0;
        boolean any = false;
        c.enableScissor(x + 1, cy + 20, x + w - 1, cy + h - 2);
        for (Row r : rows) {
            if (!r.kind.equals(kind)) continue;
            any = true;
            float rk = ease(MathHelper.clamp((t - 0.45f - i * 0.06f) / 0.35f, 0, 1));
            int rx = x + 10 + Math.round((1 - rk) * 14);
            Smooth.disc(c, rx + 2, ly + 4, 2, a(0xFF, acc, k * rk));
            Ui.text(c, Text.literal(r.title), rx + 9, ly, 0.55f, a(0xFF, 0x8F8A7A, k * rk), false);
            ly = Sleek.wrap(c, r.text, rx + 9, ly + 7, w - 30, 0.65f, a(0xFF, 0xEDE3C8, k * rk)) + 5;
            i++;
        }
        c.disableScissor();
        if (!any) Ui.text(c, Text.literal(kind.equals("event") ? "Nothing scheduled yet." : "Nothing new."), x + 10, cy + 26, 0.65f, a(0xFF, 0x6A665C, k), false);
    }

    private static void card(DrawContext c, int x, int y, int w, int h, int acc, float k) {
        c.fillGradient(x, y, x + w, y + h, a(0xF0, 0x141917, k), a(0xF4, 0x0A0D0C, k));
        c.fillGradient(x, y, x + w, y + h / 2, a(0x18, acc, k), 0);
        c.fill(x, y, x + w, y + 2, a(0xFF, acc, k));
        c.drawBorder(x, y, w, h, a(0x28, 0xFFFFFF, k));
    }

    private static float ease(float x) {
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    private static int a(int alpha, int rgb, float k) {
        return ((int) (MathHelper.clamp(alpha * k, 0, 255)) << 24) | (rgb & 0xFFFFFF);
    }
}
