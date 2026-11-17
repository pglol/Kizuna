package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story Moments: the story's quests grouped by chapter, as cards. The ones you've reached can be
 * played (or played again) as side quests; the ones still ahead are sealed.
 */
public final class MomentsScreen extends Screen {
    private static Net.Moments view;
    private int scroll;

    public MomentsScreen(Net.Moments v) {
        super(Text.literal("Story Moments"));
        view = v;
    }

    public static void on(Net.Moments v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v.open()) mc.setScreen(new MomentsScreen(v));
        else {
            view = v;
            if (mc.currentScreen instanceof MomentsScreen s) s.clearAndInit();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final int CW = 150, CH = 44, GAP = 8;

    @Override
    protected void init() {
        if (view == null) return;
        int cols = Math.max(1, Math.min(4, (width - 40) / (CW + GAP)));
        int x0 = width / 2 - (cols * (CW + GAP) - GAP) / 2;
        int y = 56 - scroll;
        Map<String, List<Net.Moment>> byChapter = new LinkedHashMap<>();
        for (Net.Moment m : view.list()) byChapter.computeIfAbsent(m.chapter(), k -> new ArrayList<>()).add(m);
        headers.clear();
        for (var e : byChapter.entrySet()) {
            headers.add(new Object[] {e.getKey(), y});
            y += 14;
            int i = 0;
            for (Net.Moment m : e.getValue()) {
                int x = x0 + (i % cols) * (CW + GAP), yy = y + (i / cols) * (CH + GAP);
                AotButton b = addDrawableChild(new AotButton(x, yy, CW, CH, Text.literal(m.title()), () -> {
                    ClientPlayNetworking.send(new Net.MomentAction("play", m.id()));
                    close();
                }));
                b.sub(Text.literal(m.state() == 2 ? "Live it again" : m.state() == 1 ? (m.memory() ? "A memory" : "Ready") : "Sealed"));
                b.active = m.state() > 0;
                b.selected = m.state() == 1;
                i++;
            }
            y += ((i + cols - 1) / cols) * (CH + GAP) + 6;
        }
        contentH = y + scroll;
    }

    private final List<Object[]> headers = new ArrayList<>();
    private int contentH;

    @Override
    public boolean mouseScrolled(double mx, double my, double hx, double vy) {
        scroll = Math.max(0, Math.min(Math.max(0, contentH - height + 20), scroll - (int) (vy * 20)));
        clearAndInit();
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("STORY MOMENTS"), width / 2f, 18, 1.4f, Ui.GOLD, true);
        for (Object[] h : headers) {
            int y = (int) h[1];
            if (y < 40 || y > height) continue;
            Ui.text(c, Ui.heading(((String) h[0]).toUpperCase()), width / 2f, y, 0.75f, 0xFFE8C47A, true);
        }
    }
}
