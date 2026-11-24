package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

/**
 * The first time you meet a system, a card slides in at the left edge and shows you what it is:
 * a little animated scene on top (someone swinging between towers, a reticle hopping between
 * targets, a flare going up), a name, two lines on how it works. It doesn't stop you playing; it
 * stays twelve seconds and goes, once per system, ever. Remembered in config/aot_rpg_help.properties.
 */
public final class HelpCards {
    private HelpCards() {}

    private enum Card {
        ODM("ODM GEAR", "Hooks fire where you look. Hold both to swing, let go at the bottom of the arc to fly.", "Gas runs low; refill at any supply crate or post."),
        LOCK("LOCK ON", "Middle mouse locks onto the fighter you're facing; flick hard to change target.", "Watch their flash: red dodge, purple step back, gold block."),
        MARKERS("MARKERS & ACTIVITIES", "Gold pins are your quest, blue are things happening near you.", "The tracker under the minimap says what to do next."),
        SWING("SWING SITE", "Tall ruins, spires and giant trees: built for gear.", "Stay off the ground and chain swings; height is speed."),
        PURSUIT("PURSUIT", "Someone's pinned or flying. Strike at them from range with gear on:", "a burst of gas fires you after them, blades first."),
        EXTRACTION("EXTRACTION", "Get in, take what you can, and get to a balloon before the titans close.", "Die out there and you lose what you carried in."),
        PRESTIGE("PRESTIGE", "Level 100. You can go round again: start over at 1 with a perk and a rank.", "Open it from the pause menu when you're ready.");

        final String title, line1, line2;

        Card(String title, String line1, String line2) {
            this.title = title;
            this.line1 = line1;
            this.line2 = line2;
        }
    }

    private static final java.util.Properties seen = new java.util.Properties();
    private static boolean loaded;
    private static Card current;
    private static long since;
    private static final long SHOW = 12_000;

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_help.properties");
    }

    private static boolean seen(Card k) {
        if (!loaded) {
            loaded = true;
            try (var in = java.nio.file.Files.newInputStream(file())) {
                seen.load(in);
            } catch (Exception ignored) {
            }
        }
        return seen.containsKey(k.name());
    }

    private static boolean seenIntro() {
        seen(Card.ODM);
        return seen.containsKey("INTRO");
    }

    private static void mark(Card k) {
        seen.setProperty(k.name(), "1");
        save();
    }

    private static void save() {
        try (var out = java.nio.file.Files.newOutputStream(file())) {
            seen.store(out, "AoT RPG help cards seen");
        } catch (Exception ignored) {
        }
    }

    private static Card due(MinecraftClient mc) {
        var pl = mc.player;
        if (OdmMoves.gear && !seen(Card.ODM)) return Card.ODM;
        if (ExtractionHud.active() && !seen(Card.EXTRACTION)) return Card.EXTRACTION;
        boolean blades = com.pglol.aotrpg.Loadout.isGrip(pl.getMainHandStack());
        if (blades && !seen(Card.LOCK)) {
            for (var e : mc.world.getOtherEntities(pl, pl.getBoundingBox().expand(16))) if (LockOn.npc(e) && e.isAlive()) return Card.LOCK;
        }
        if (!seen(Card.MARKERS)) for (var m : ClientState.markers) if (m.kind().equals("quest") || m.kind().equals("activity")) return Card.MARKERS;
        if (!seen(Card.SWING)) {
            for (var m : ClientState.markers) {
                if (m.kind().equals("swing") && Math.hypot(m.x() - pl.getX(), m.z() - pl.getZ()) < 120) return Card.SWING;
            }
        }
        if (blades && OdmMoves.gear && !seen(Card.PURSUIT)) {
            for (var e : mc.world.getOtherEntities(pl, pl.getBoundingBox().expand(30))) {
                if (e.squaredDistanceTo(pl) > 16 && (AdrenalineClient.pinned(e.getId()) || Ragdoll.active(e.getId()))) return Card.PURSUIT;
            }
        }
        if (ClientState.profile != null && ClientState.profile.level() >= 100 && !seen(Card.PRESTIGE)) return Card.PRESTIGE;
        return null;
    }

    private static long lastCheck;

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden || ClientState.profile == null || !Settings.hints) return;
        long now = Util.getMeasuringTimeMs();
        // The very first time you're out in the world with a character: your controls, once.
        if (current == null && mc.currentScreen == null && mc.player.age > 100 && !seenIntro()
            && !ClientState.profile.name().isEmpty() && !com.pglol.aotrpg.client.story.CutscenePlayer.active()) {
            seen.setProperty("INTRO", "1");
            save();
            mc.setScreen(new ControlsScreen(null, true));
            return;
        }
        if (current == null) {
            if (mc.currentScreen != null || now - lastCheck < 500) return;
            lastCheck = now;
            Card k = due(mc);
            if (k == null) return;
            current = k;
            since = now;
            mark(k);
            mc.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_TOAST_IN, 1f, 0.6f));
        }
        if (mc.currentScreen != null) return;
        long age = now - since;
        if (age > SHOW) {
            current = null;
            return;
        }
        float in = MathHelper.clamp(age / 450f, 0, 1), out = MathHelper.clamp((SHOW - age) / 450f, 0, 1);
        in = 1 - (1 - in) * (1 - in) * (1 - in);
        float k = Math.min(in, out);
        int w = 206, ph = 78, h = ph + 64;
        int x = 10 - Math.round((1 - k) * (w + 20)), y = c.getScaledWindowHeight() / 2 - h / 2;
        float t = age / 1000f;
        // Card.
        c.fillGradient(x, y, x + w, y + h, a(0xF0, 0x121614, k), a(0xF4, 0x0A0D0C, k));
        c.drawBorder(x, y, w, h, a(0x30, 0xFFFFFF, k));
        c.fill(x, y, x + w, y + 1, a(0xFF, 0xE8C36F, k));
        // The scene.
        c.enableScissor(x + 1, y + 1, x + w - 1, y + ph);
        scene(c, current, x + 1, y + 1, w - 2, ph - 1, t, k);
        c.disableScissor();
        c.fillGradient(x + 1, y + ph - 18, x + w - 1, y + ph, 0, a(0xC0, 0x0A0D0C, k));
        // Words.
        Sleek.chip(c, x + 8, y + ph + 5, "NEW", a(0xFF, 0xE8C36F, k));
        Ui.text(c, Ui.heading(current.title), x + 36, y + ph + 6, 0.8f, a(0xFF, 0xEDE3C8, k), false);
        int ty = Sleek.wrap(c, current.line1, x + 8, y + ph + 19, w - 16, 0.62f, a(0xFF, 0xCFC6AE, k));
        Sleek.wrap(c, current.line2, x + 8, ty + 1, w - 16, 0.62f, a(0xFF, 0x8F8A7A, k));
        // How long it stays.
        float left = 1 - age / (float) SHOW;
        c.fill(x, y + h - 1, x + Math.round(w * left), y + h, a(0x90, 0xE8C36F, k));
    }

    // ------------------------------------------------------------------ little scenes

    private static void scene(DrawContext c, Card card, int x, int y, int w, int h, float t, float k) {
        // Dusk sky.
        c.fillGradient(x, y, x + w, y + h, a(0xFF, 0x2A3442, k), a(0xFF, 0x6A5444, k));
        int ground = y + h - 10;
        switch (card) {
            case ODM, SWING -> {
                // Towers (or spires), and a figure swinging between them on a line.
                int[] tx = {x + 30, x + w / 2, x + w - 30};
                for (int i = 0; i < 3; i++) {
                    int th = card == Card.SWING ? 54 + i * 6 % 14 : 46 + (i % 2) * 10;
                    int tw = card == Card.SWING ? 7 : 14;
                    c.fill(tx[i] - tw / 2, ground - th, tx[i] + tw / 2, ground, a(0xFF, 0x1C2028, k));
                }
                float ph = (t * 0.55f) % 1f;
                int from = ph < 0.5f ? 0 : 1;
                float u = (ph % 0.5f) / 0.5f;
                float ax = tx[from] + (tx[from + 1] - tx[from]) * 0.5f, ay = ground - 60;
                float ang = (float) Math.cos(u * Math.PI) * 1.0f;
                float len = 36;
                float fx = ax - (float) Math.sin(ang) * len, fy = ay + (float) Math.cos(ang) * len;
                line(c, ax, ay, fx, fy, a(0xC0, 0xDDE6F0, k));
                Smooth.disc(c, fx, fy - 2, 2.2f, a(0xFF, 0xEDE3C8, k));
                c.fill((int) fx - 1, (int) fy, (int) fx + 1, (int) fy + 5, a(0xFF, 0xEDE3C8, k));
                // Gas trail.
                for (int i = 1; i < 6; i++) {
                    float uu = Math.max(0, u - i * 0.04f);
                    float an = (float) Math.cos(uu * Math.PI);
                    Smooth.disc(c, ax - (float) Math.sin(an) * len, ay + (float) Math.cos(an) * len + 2, 1.5f, a(0x60 - i * 10, 0xFFFFFF, k));
                }
            }
            case LOCK -> {
                int[][] foes = {{x + 50, ground}, {x + w / 2 + 10, ground - 6}, {x + w - 40, ground}};
                for (int[] f : foes) {
                    c.fill(f[0] - 2, f[1] - 14, f[0] + 2, f[1], a(0xFF, 0x1C2028, k));
                    Smooth.disc(c, f[0], f[1] - 17, 3, a(0xFF, 0x1C2028, k));
                }
                int i = (int) (t / 1.4f) % 3, j = (i + 1) % 3;
                float u = MathHelper.clamp(((t % 1.4f) - 1.0f) / 0.4f, 0, 1);
                u = u * u * (3 - 2 * u);
                float rx = foes[i][0] + (foes[j][0] - foes[i][0]) * u, ry = foes[i][1] - 10 + (foes[j][1] - foes[i][1]) * u;
                float r = 9 + (float) Math.sin(t * 6) * 1;
                ring(c, rx, ry, r, a(0xFF, 0xE8C36F, k));
                c.fill((int) rx - 1, (int) (ry - r - 4), (int) rx + 1, (int) (ry - r), a(0xFF, 0xE8C36F, k));
                // The tell flash on the locked one.
                int col = new int[] {0xE8433A, 0xB06AE0, 0xE8C36F}[(int) (t / 1.4f) % 3];
                if ((t % 1.4f) < 0.6f) Smooth.disc(c, foes[i][0], foes[i][1] - 17, 5, a(0x70, col, k));
            }
            case MARKERS -> {
                for (int i = 0; i < 5; i++) c.fill(x + 12 + i * 40, ground - 8 - i % 2 * 5, x + 34 + i * 40, ground, a(0xFF, 0x1C2028, k));
                float b = (float) Math.sin(t * 2.4) * 3;
                pin(c, x + w * 0.35f, y + 22 + b, 0xE8C36F, k);
                pin(c, x + w * 0.72f, y + 30 - b, 0x9AD0FF, k);
                c.fillGradient((int) (x + w * 0.35f), (int) (y + 30 + b), (int) (x + w * 0.35f) + 1, ground, a(0x90, 0xE8C36F, k), 0);
                c.fillGradient((int) (x + w * 0.72f), (int) (y + 38 - b), (int) (x + w * 0.72f) + 1, ground, a(0x90, 0x9AD0FF, k), 0);
            }
            case PURSUIT -> {
                c.fill(x + w - 40, y + 6, x + w - 26, ground, a(0xFF, 0x1C2028, k));
                float u = (t % 1.6f) / 1.6f;
                float tx = x + w - 44, ty = y + 34;
                // The pinned target, shaking in the wall.
                float sh = (float) Math.sin(t * 40) * 0.6f;
                c.fill((int) (tx - 2 + sh), (int) ty - 6, (int) (tx + 2 + sh), (int) ty + 8, a(0xFF, 0xC0463A, k));
                float sx = x + 26, sy = ground - 8;
                float e = u < 0.6f ? (u / 0.6f) * (u / 0.6f) : 1;
                float px = sx + (tx - 6 - sx) * e, py = sy + (ty - sy) * e;
                for (int i = 1; i < 8; i++) {
                    float ee = Math.max(0, e - i * 0.05f);
                    Smooth.disc(c, sx + (tx - 6 - sx) * ee, sy + (ty - sy) * ee, 1.6f, a(0x70 - i * 8, 0xEAF4FF, k));
                }
                Smooth.disc(c, px, py - 2, 2.2f, a(0xFF, 0xEDE3C8, k));
                if (u > 0.6f && u < 0.75f) {
                    ring(c, tx, ty, 4 + (u - 0.6f) * 80, a((int) (255 * (0.75f - u) / 0.15f), 0xFFFFFF, k));
                }
            }
            case EXTRACTION -> {
                c.fillGradient(x, y, x + w, y + h, a(0xFF, 0x3A1A1A, k), a(0xFF, 0x6A3424, k));
                for (int i = 0; i < 9; i++) c.fill(x + 6 + i * 23, ground - 20 - (i * 7) % 12, x + 10 + i * 23, ground, a(0xFF, 0x161010, k));
                float by = ground - 20 - ((t * 9) % 60);
                Smooth.disc(c, x + w * 0.6f, by, 9, a(0xFF, 0xD8C8A8, k));
                c.fill((int) (x + w * 0.6f) - 3, (int) by + 10, (int) (x + w * 0.6f) + 3, (int) by + 15, a(0xFF, 0x6A4A2A, k));
                float fu = (t % 2.2f) / 2.2f;
                float fy = ground - fu * 70;
                Smooth.disc(c, x + w * 0.25f, fy, 2.4f, a((int) (255 * (1 - fu)), 0xFF5A3A, k));
                for (int i = 0; i < 6; i++) Smooth.disc(c, x + w * 0.25f, fy + i * 4, 1.2f, a((int) (120 * (1 - fu)) - i * 15, 0xFFB090, k));
                // A titan silhouette with red eyes on the horizon.
                c.fill(x + 20, ground - 40, x + 34, ground, a(0xFF, 0x120C0C, k));
                Smooth.disc(c, x + 27, ground - 44, 7, a(0xFF, 0x120C0C, k));
                c.fill(x + 24, ground - 46, x + 26, ground - 45, a(0xFF, 0xFF3020, k));
                c.fill(x + 28, ground - 46, x + 30, ground - 45, a(0xFF, 0xFF3020, k));
            }
            case PRESTIGE -> {
                float r = 18 + (float) Math.sin(t * 2) * 2;
                for (int i = 0; i < 12; i++) {
                    double an = t * 0.6 + i * Math.PI / 6;
                    line(c, x + w / 2f, y + h / 2f - 4, x + w / 2f + (float) Math.cos(an) * 60, y + h / 2f - 4 + (float) Math.sin(an) * 60, a(0x28, 0xE8C36F, k));
                }
                ring(c, x + w / 2f, y + h / 2f - 4, r, a(0xFF, 0xE8C36F, k));
                Ui.text(c, Ui.title("100"), x + w / 2f, y + h / 2f - 9, 1.1f, a(0xFF, 0xE8C36F, k), true);
            }
        }
        c.fill(x, ground, x + w, y + h, a(0xFF, 0x14120E, k));
    }

    private static void pin(DrawContext c, float x, float y, int rgb, float k) {
        Smooth.disc(c, x, y, 5, a(0xFF, rgb, k));
        Smooth.disc(c, x, y, 2, a(0xFF, 0x0A0D0C, k));
        c.fill((int) x - 1, (int) y + 4, (int) x + 1, (int) y + 9, a(0xFF, rgb, k));
    }

    private static void ring(DrawContext c, float x, float y, float r, int argb) {
        int n = Math.max(16, (int) (r * 3));
        for (int i = 0; i < n; i++) {
            double an = i * Math.PI * 2 / n;
            int px = Math.round(x + (float) Math.cos(an) * r), py = Math.round(y + (float) Math.sin(an) * r);
            c.fill(px, py, px + 1, py + 1, argb);
        }
    }

    private static void line(DrawContext c, float x0, float y0, float x1, float y1, int argb) {
        int n = (int) Math.max(1, Math.hypot(x1 - x0, y1 - y0));
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            int px = Math.round(x0 + (x1 - x0) * u), py = Math.round(y0 + (y1 - y0) * u);
            c.fill(px, py, px + 1, py + 1, argb);
        }
    }

    private static int a(int alpha, int rgb, float k) {
        return ((int) (MathHelper.clamp(alpha * k, 0, 255)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Text helper kept for parity with other HUD pieces. */
    static Text t(String s) {
        return Text.literal(s);
    }
}
