package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * The tracker on the right, under the minimap: the quest you're following (its chapter, the step
 * with a box to tick, progress and distance) and every world activity you have going at once,
 * each with its own colour and distance. In a fight it slims to just the titles.
 */
public final class QuestTracker {
    private QuestTracker() {}

    public static List<String> activities = new ArrayList<>();

    public static void on(Net.Activities a) {
        activities = a.rows();
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Settings.tracker || mc.player == null || mc.options.hudHidden || mc.currentScreen != null || ClientState.profile == null) return;
        if (com.pglol.aotrpg.client.story.CutscenePlayer.active() || mc.getDebugHud().shouldShowDebugHud()) return;
        // Out on an extraction island the run's own tasks (under the radar) are what count.
        if (ExtractionHud.active() || mc.world == null || mc.world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;
        boolean fight = mc.player.age - mc.player.getLastAttackedTime() < 160 || mc.player.hurtTime > 0;
        int w = 168, x = c.getScaledWindowWidth() - w - 8, y = Minimap.bottom + 8;
        Net.Objective o = ClientState.objective;
        boolean quest = o != null && !(o.chapter().isEmpty() && o.text().isEmpty());
        if (!quest && activities.isEmpty()) return;
        long now = Util.getMeasuringTimeMs();
        var pos = mc.player.getPos();
        int rows = (quest ? (fight ? 1 : 2) : 0) + activities.size();
        int h = 14 + rows * (fight ? 11 : 13) + (quest && !fight ? 6 : 0);
        c.fill(x, y, x + w, y + h, 0x9A0B0E10);
        c.fill(x, y, x + 2, y + h, 0xFFE0B96A);
        Ui.text(c, Ui.heading("TRACKED"), x + 8, y + 4, 0.55f, 0xFFE0B96A, false);
        int ry = y + 14;
        if (quest) {
            String title = o.chapter().isEmpty() ? "Quest" : o.chapter();
            Ui.text(c, Text.literal(trim(title, w - 60, 0.68f)), x + 8, ry, 0.68f, Ui.CREAM, false);
            if (o.hasTarget()) {
                double d = Math.hypot(o.x() - pos.x, o.z() - pos.z);
                String m = d >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", d / 1000) : (int) d + "m";
                Ui.text(c, Text.literal(m), x + w - 8 - Ui.font().getWidth(m) * 0.6f, ry + 1, 0.6f, 0xFFE0B96A, false);
            }
            ry += fight ? 11 : 12;
            if (!fight) {
                c.drawBorder(x + 8, ry + 1, 6, 6, 0xA0EDE3C8);
                String step = o.text() + (o.progress().isEmpty() ? "" : "  " + o.progress());
                Ui.text(c, Text.literal(trim(step, w - 26, 0.6f)), x + 18, ry, 0.6f, 0xFFB8B2A4, false);
                ry += 13;
                c.fill(x + 8, ry - 3, x + w - 8, ry - 2, 0x20FFFFFF);
            }
        }
        for (String row : activities) {
            String[] a = row.split("\\|");
            if (a.length < 5) continue;
            int col = 0xFF000000 | Integer.parseInt(a[4]);
            double d = Math.hypot(Integer.parseInt(a[2]) - pos.x, Integer.parseInt(a[3]) - pos.z);
            float pulse = 0.75f + 0.25f * MathHelper.sin(now / 400f + a[0].length());
            var m = c.getMatrices();
            m.push();
            m.translate(x + 11, ry + 3, 0);
            m.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees(45));
            c.fill(-2, -2, 2, 2, ((int) (255 * pulse) << 24) | (col & 0xFFFFFF));
            m.pop();
            Ui.text(c, Text.literal(a[0]), x + 18, ry, 0.62f, Ui.CREAM, false);
            String dm = d >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fkm", d / 1000) : (int) d + "m";
            Ui.text(c, Text.literal(dm), x + w - 8 - Ui.font().getWidth(dm) * 0.58f, ry + 1, 0.58f, col, false);
            if (!fight) {
                Ui.text(c, Text.literal(trim(a[1], w - 26, 0.52f)), x + 18, ry + 7, 0.52f, 0xFF8F8A7A, false);
                ry += 13;
            } else ry += 11;
        }
    }

    private static String trim(String s, int max, float scale) {
        var f = Ui.font();
        if (f.getWidth(s) * scale <= max) return s;
        while (s.length() > 3 && f.getWidth(s + "…") * scale > max) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
