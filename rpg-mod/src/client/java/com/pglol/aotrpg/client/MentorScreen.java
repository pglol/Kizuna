package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * A mentor: who they are and what they said, the trials they've set you (each filling as you go),
 * and the technique waiting at the end of them. Learn it when it's earned; put it on your technique
 * key (C) when you have it.
 */
public final class MentorScreen extends Screen {
    private static Net.MentorView view;
    private final long opened = Util.getMeasuringTimeMs();

    public MentorScreen(Net.MentorView v) {
        super(Text.literal(v.name()));
        view = v;
    }

    public static void on(Net.MentorView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v.open() || !(mc.currentScreen instanceof MentorScreen)) {
            if (v.open()) mc.setScreen(new MentorScreen(v));
            return;
        }
        view = v;
        if (mc.currentScreen instanceof MentorScreen s) s.clearAndInit();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int px, py, pw, ph;

    @Override
    protected void init() {
        pw = Math.min(320, width - 32);
        ph = 70 + view.steps().size() * 28 + 92;
        px = width / 2 - pw / 2;
        py = Math.max(16, height / 2 - ph / 2);
        int by = py + ph - 30;
        if (view.state() == 1) {
            addDrawableChild(new AotButton(px + 16, by, pw - 32, 20, Ui.heading("LEARN " + view.tech().toUpperCase()),
                () -> ClientPlayNetworking.send(new Net.MentorAction("learn", view.id())))).selected(true);
        } else if (view.state() == 2) {
            addDrawableChild(new AotButton(px + 16, by, pw - 32, 20, Text.literal(view.equipped() ? "On your technique key" : "Put on technique key"),
                () -> ClientPlayNetworking.send(new Net.MentorAction("equip", view.id())))).selected(view.equipped());
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0x70000000, 0xA0000000);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        if (view == null) return;
        float in = Math.min(1, (Util.getMeasuringTimeMs() - opened) / 250f);
        int a = (int) (255 * in) << 24;
        Ui.glass(c, px, py, pw, ph);
        Ui.text(c, Ui.title(view.name().toUpperCase()), px + pw / 2f, py + 12, 1.3f, a | 0xE8C47A, true);
        Ui.text(c, Text.literal(view.title()), px + pw / 2f, py + 30, 0.7f, a | 0xB8B0A0, true);
        Ui.wrapped(c, Text.literal("\"" + view.quote() + "\""), px + 16, py + 44, pw - 32, a | 0xEDE3C8);
        int y = py + 70;
        for (int i = 0; i < view.steps().size(); i++) {
            int have = view.have().get(i), need = view.need().get(i);
            boolean done = have >= need;
            Ui.text(c, Text.literal((done ? "✔ " : "") + view.steps().get(i)), px + 16, y, 0.85f, done ? 0xFF9FE08A : 0xFFEDE3C8, false);
            String n = have + " / " + need;
            Ui.text(c, Text.literal(n), px + pw - 16 - Ui.font().getWidth(n) * 0.85f, y, 0.85f, done ? 0xFF9FE08A : 0xFFE8C47A, false);
            Ui.bar(c, px + 16, y + 11, pw - 32, 3, need == 0 ? 1 : have / (float) need, done ? 0xFF7FD06A : 0xFFE0B96A);
            y += 28;
        }
        // The technique.
        c.fill(px + 12, y, px + pw - 12, y + 50, 0x50000000);
        c.fill(px + 12, y, px + 14, y + 50, view.state() >= 1 ? 0xFFE8C47A : 0x60E8C47A);
        Ui.text(c, Ui.heading(view.tech().toUpperCase()), px + 22, y + 6, 0.9f, view.state() >= 1 ? 0xFFE8C47A : 0xFF8A8478, false);
        Ui.text(c, Text.literal(view.cooldown() + "s"), px + pw - 22 - Ui.font().getWidth(view.cooldown() + "s") * 0.7f, y + 7, 0.7f, 0xFFB8B0A0, false);
        Ui.wrapped(c, Text.literal(view.techDesc()), px + 22, y + 20, pw - 44, 0xFFD8D0C0);
    }
}
