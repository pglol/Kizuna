package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * The squad is down: how the run went (time out there, each diver's titans, players and soldiers
 * felled, caches opened, who got out), then where to: aboard the balloon, back to the open world,
 * or keep watching whoever's still out there. The squad leader's choice takes the fallen squad along.
 */
public final class RunSummaryScreen extends Screen {
    private static Net.RunSummary view;
    private final Sleek.Hits hits = new Sleek.Hits();
    private final long opened = Util.getMeasuringTimeMs();

    public RunSummaryScreen() {
        super(Text.literal("Squad down"));
    }

    public static void on(Net.RunSummary s) {
        var mc = MinecraftClient.getInstance();
        if (s.island().isEmpty()) {
            view = null;
            if (mc.currentScreen instanceof RunSummaryScreen) mc.setScreen(null);
            return;
        }
        view = s;
        mc.setScreen(new RunSummaryScreen());
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    private void go(String what) {
        ClientPlayNetworking.send(new Net.ExtractionAction("sum_" + what, ""));
        close();
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xC0080404, 0xE0030202);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        if (view == null) return;
        float in = Math.min(1, (Util.getMeasuringTimeMs() - opened) / 400f);
        int a = (int) (255 * in);
        boolean anyOut = false;
        for (String l : view.lines()) if (l.split("\\|")[1].equals("Extracted")) anyOut = true;
        String head = anyOut ? "RUN OVER" : "SQUAD DOWN";
        Ui.text(c, Ui.title(head), width / 2f, 34, 2f, (a << 24) | (anyOut ? 0xE8C36F : 0xE8433A), true);
        long m = view.seconds() / 60, s = view.seconds() % 60;
        Ui.text(c, Text.literal(view.island() + "  ·  " + m + ":" + String.format("%02d", s) + " out there"), width / 2f, 60, 0.75f,
            (a << 24) | 0xC8BEA6, true);
        // The squad, one row each.
        int w = Math.min(440, width - 40), x = (width - w) / 2, y = 82;
        String[] heads = {"Titans", "Players", "Soldiers", "Caches"};
        int colW = 52, cx0 = x + w - colW * 4;
        for (int k = 0; k < 4; k++) Ui.text(c, Text.literal(heads[k].toUpperCase()), cx0 + k * colW + colW / 2f, y, 0.5f, 0xFF8A857A, true);
        y += 10;
        for (String l : view.lines()) {
            String[] f = l.split("\\|");
            if (f.length < 7) continue;
            boolean out = f[1].equals("Extracted");
            c.fill(x, y, x + w, y + 26, 0x30000000);
            c.fill(x, y, x + 2, y + 26, out ? 0xFF5BD35B : 0xFFE8433A);
            Ui.text(c, Text.literal((f[6].equals("1") ? "★ " : "") + f[0]), x + 10, y + 5, 0.85f, Ui.CREAM, false);
            Ui.text(c, Text.literal(f[1]), x + 10, y + 16, 0.55f, out ? 0xFF5BD35B : 0xFFE8433A, false);
            for (int k = 0; k < 4; k++) Ui.text(c, Text.literal(f[2 + k]), cx0 + k * colW + colW / 2f, y + 9, 0.9f, Ui.CREAM, true);
            y += 30;
        }
        // Where to.
        int bw = 140, by = Math.max(y + 16, height - 70), bx = width / 2 - bw - bw / 2 - 8;
        Sleek.button(c, hits, bx, by, bw, 22, "Back aboard", true, true, () -> go("lobby"));
        Sleek.button(c, hits, bx + bw + 8, by, bw, 22, "Open world", false, true, () -> go("world"));
        Sleek.button(c, hits, bx + (bw + 8) * 2, by, bw, 22, "Keep watching", false, true, () -> go("watch"));
        if (view.leader()) Ui.text(c, Text.literal("You lead the squad: where you go, your fallen squad goes too"), width / 2f, by + 28, 0.58f, 0xFF9A958A, true);
        else Ui.text(c, Text.literal("Your squad leader may take you along"), width / 2f, by + 28, 0.58f, 0xFF7A766C, true);
    }
}
