package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * Re-enlisting (Prestige): what starts over, what you keep, what you gain, and the perk you take
 * with you. Pick a perk, then hold the button to commit.
 */
public final class PrestigeScreen extends Screen {
    private static Net.PrestigeView view;
    private String chosen = "";
    private long holdFrom;
    private final Sleek.Hits hits = new Sleek.Hits();
    private static final long HOLD = 1600;

    public PrestigeScreen() {
        super(Text.literal("Prestige"));
    }

    public static void on(Net.PrestigeView v) {
        view = v;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (v.open() && !(mc.currentScreen instanceof PrestigeScreen)) mc.setScreen(new PrestigeScreen());
    }

    private static String roman(int n) {
        return com.pglol.aotrpg.Prestige.roman(n);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        hits.clear();
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        if (view == null) return;
        int w = Math.min(460, width - 24), h = Math.min(300, height - 24), x = (width - w) / 2, y = (height - h) / 2;
        Ui.glass(c, x, y, w, h);
        Ui.text(c, Ui.title("PRESTIGE"), width / 2f, y + 10, 1.4f, Ui.GOLD, true);
        String rank = view.rank() == 0 ? "Not yet re-enlisted" : "Prestige " + roman(view.rank()) + " of X";
        Ui.text(c, Text.literal(rank), width / 2f, y + 30, 0.7f, Ui.CREAM, true);

        // What happens.
        int cy = y + 46, col = (w - 24) / 3;
        String[][] cols = {
            {"STARTS OVER", "Level 1", "Stats and skills", "(points back to spend)"},
            {"YOU KEEP", "Gear, Marks, homes", "Horses, cosmetics", "Story and Veteran ranks"},
            {"YOU GAIN", "A perk (below)", "Marks and a finisher", "Faster XP · gear unlocks sooner"}};
        int[] tones = {0xFFD06A5A, 0xFF8CC08A, Ui.GOLD};
        for (int i = 0; i < 3; i++) {
            int cx = x + 12 + i * col;
            c.fill(cx, cy, cx + col - 6, cy + 52, 0x30000000);
            c.fill(cx, cy, cx + col - 6, cy + 1, tones[i]);
            Ui.text(c, Ui.heading(cols[i][0]), cx + 6, cy + 5, 0.62f, tones[i], false);
            for (int k = 1; k < 4; k++) Ui.text(c, Text.literal(cols[i][k]), cx + 6, cy + 9 + k * 10, 0.6f, Ui.CREAM, false);
        }
        Ui.text(c, Text.literal("New Game+: enemies near you come harder, and pay in better loot."), width / 2f, cy + 58, 0.6f, Ui.MUTED, true);

        // The perks.
        int py = cy + 72;
        Ui.text(c, Ui.heading("Choose a perk"), x + 12, py, 0.7f, Ui.CREAM, false);
        py += 12;
        int pw = (w - 24 - 6) / 2, ph = 22;
        for (int i = 0; i < view.perks().size(); i++) {
            String[] p = view.perks().get(i).split("\\|");
            if (p.length < 5) continue;
            int have = Integer.parseInt(p[3]), max = Integer.parseInt(p[4]);
            int px = x + 12 + (i % 2) * (pw + 6), pyy = py + (i / 2) * (ph + 4);
            boolean full = have >= max, sel = p[0].equals(chosen), hov = Sleek.over(px, pyy, pw, ph);
            Sleek.tile(c, px, pyy, pw, ph, full ? 0xFF55524A : Ui.GOLD, hov && !full, sel, full);
            Ui.text(c, Text.literal(p[1]), px + 6, pyy + 4, 0.7f, full ? 0xFF6A665C : Ui.CREAM, false);
            Ui.text(c, Text.literal(p[2]), px + 6, pyy + 13, 0.52f, Ui.MUTED, false);
            String pips = "◆".repeat(have) + "◇".repeat(Math.max(0, max - have));
            Ui.text(c, Text.literal(pips), px + pw - 6 - Ui.font().getWidth(pips) * 0.6f, pyy + 4, 0.6f, Ui.GOLD, false);
            String id = p[0];
            if (!full && view.eligible()) hits.add(px, pyy, pw, ph, () -> chosen = id);
        }

        // Commit: hold.
        int by = y + h - 30, bw = 220, bx = width / 2 - bw / 2;
        boolean can = view.eligible() && !chosen.isEmpty();
        String label = !view.eligible() ? (view.rank() >= com.pglol.aotrpg.Prestige.MAX ? "Highest prestige reached" : "Reach level 100 to re-enlist (Lv " + view.level() + ")")
            : chosen.isEmpty() ? "Pick a perk first" : "Hold to re-enlist";
        c.fill(bx, by, bx + bw, by + 20, can ? 0xFFE0B96A : 0x40FFFFFF);
        float k = holdFrom == 0 ? 0 : Math.min(1, (Util.getMeasuringTimeMs() - holdFrom) / (float) HOLD);
        if (k > 0) c.fill(bx, by, bx + (int) (bw * k), by + 20, 0xFFFFF0C8);
        Ui.text(c, Ui.heading(label), width / 2f, by + 6, 0.75f, can ? 0xFF1A1408 : Ui.MUTED, true);
        if (can && holdFrom != 0 && k >= 1) {
            holdFrom = 0;
            ClientPlayNetworking.send(new Net.PrestigeAction("confirm:" + chosen));
            chosen = "";
        }
    }

    private boolean overButton(double mx, double my) {
        int w = Math.min(460, width - 24), h = Math.min(300, height - 24), y = (height - h) / 2;
        int by = y + h - 30, bw = 220, bx = width / 2 - bw / 2;
        return mx >= bx && mx < bx + bw && my >= by && my < by + 20;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (overButton(mx, my) && view != null && view.eligible() && !chosen.isEmpty()) {
            holdFrom = Util.getMeasuringTimeMs();
            return true;
        }
        if (hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        holdFrom = 0;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
