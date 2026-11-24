package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/** The death screen: what happened, what you kept, and a way back into the fight. */
public class AotDeathScreen extends Screen {
    private static final String[] QUOTES = {
        "“If you don't fight, you can't win.”",
        "“Dedicate your heart.”",
        "“The world is cruel, but it is also very beautiful.”",
        "“Give up on your dreams and die.”",
        "“We are born free.”"
    };
    private final String quote = QUOTES[(int) (Util.getMeasuringTimeMs() / 1000 % QUOTES.length)];
    private int ticks;
    private boolean respawning;
    private final Sleek.Hits hits = new Sleek.Hits();

    public AotDeathScreen() {
        super(Text.literal("You have fallen"));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    protected void init() {
        // Fallen on a run: no death screen, straight up and into watching your squad.
        if (Util.getMeasuringTimeMs() - ExtractionHud.lastRunAt < 8000 && client.player != null) {
            client.player.requestRespawn();
            client.setScreen(null);
        }
    }

    private void rise() {
        if (respawning || ticks < 20) return;
        if (client.player != null) client.player.requestRespawn();
        respawning = true;
    }

    private void leave() {
        boolean single = client.isInSingleplayer();
        if (client.world != null) client.world.disconnect();
        if (single) client.disconnect(new MessageScreen(Text.translatable("menu.savingLevel")));
        else client.disconnect();
        client.setScreen(single ? new TitleScreen() : new MultiplayerScreen(new TitleScreen()));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE || key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            rise();
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
    public void tick() {
        ticks++;
        // Close once the server has respawned us.
        if (respawning && client.player != null && !client.player.isDead()) {
            ClientState.death = null;
            close();
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        float t = (ticks + delta) / 20f;
        float in = Math.min(1f, t / 1.2f);
        // The world bleeds out: a red wash that deepens, and a vignette closing in.
        c.fill(0, 0, width, height, ((int) (0xB0 * in) << 24) | 0x140404);
        c.fillGradient(0, 0, width, height / 2, ((int) (0x50 * in) << 24) | 0x701010, 0);
        int v = Math.round(Math.min(width, height) * 0.18f * in);
        for (int i = 0; i < 10; i++) {
            int e = v * i / 10, al = (int) (0x18 * in);
            c.fill(0, e, width, e + v / 10 + 1, al << 24);
            c.fill(0, height - e - v / 10 - 1, width, height - e, al << 24);
        }
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        hits.clear();
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        float t = (ticks + delta) / 20f;
        float k = ease(clamp((t - 0.2f) / 0.9f));
        int cy = height / 4;
        // The title spreads into place.
        String title = "YOU HAVE FALLEN";
        float spread = (1 - k) * 6;
        float scale = 2.6f;
        float tw = 0;
        for (char ch : title.toCharArray()) tw += Ui.font().getWidth(String.valueOf(ch)) * scale + spread;
        float x = width / 2f - tw / 2f;
        for (char ch : title.toCharArray()) {
            String s1 = String.valueOf(ch);
            Ui.text(c, Ui.title(s1), x, cy, scale, a(0xFF, 0xD8403A, k), false);
            x += Ui.font().getWidth(s1) * scale + spread;
        }
        // A red line drawn out from the middle.
        float lk = ease(clamp((t - 0.6f) / 0.6f));
        int lw = Math.round(Math.min(360, width - 60) * lk);
        c.fill(width / 2 - lw / 2, cy + 30, width / 2 + lw / 2, cy + 31, a(0xFF, 0xD8403A, lk));
        Net.DeathInfo d = ClientState.death;
        int y = cy + 42;
        if (d != null) {
            float mk = ease(clamp((t - 0.8f) / 0.5f));
            Ui.text(c, Text.literal(d.message()), width / 2f, y, 0.95f, a(0xFF, 0xEDE3C8, mk), true);
            y += 22;
            boolean story = d.mode().equals("story");
            int pw = Math.min(170, (width - 50) / 2), gap = 10, px = width / 2 - pw - gap / 2;
            float ck = ease(clamp((t - 1.0f) / 0.5f));
            int oy = Math.round((1 - ck) * 16);
            // What you kept, and what it cost.
            card(c, px, y + oy, pw, 70, 0x8FCB6A, ck);
            Ui.text(c, Ui.heading("KEPT"), px + 10, y + oy + 9, 0.8f, a(0xFF, 0x8FCB6A, ck), false);
            int ly = y + oy + 24;
            ly = row(c, px, pw, ly, "Satchel", "safe", ck);
            ly = row(c, px, pw, ly, "Gear", story ? "protected" : "dropped", ck);
            row(c, px, pw, ly, "Mode", story ? "Open World" : "Extraction", ck);
            int qx = px + pw + gap;
            card(c, qx, y + oy, pw, 70, 0xD8403A, ck);
            Ui.text(c, Ui.heading("LOST"), qx + 10, y + oy + 9, 0.8f, a(0xFF, 0xD8403A, ck), false);
            ly = y + oy + 24;
            ly = row(c, qx, pw, ly, "XP", d.xpLost() > 0 ? "-" + d.xpLost() : "none", ck);
            row(c, qx, pw, ly, "Wear", story ? (d.gearWorn() > 0 ? d.gearWorn() + " items -10%" : "none") : "everything carried", ck);
            y += 84;
        }
        // Buttons, once the moment has landed.
        float bk = ease(clamp((t - 1.0f) / 0.4f));
        if (bk > 0) {
            int bw = 150, by = Math.max(y + 6, height - 70);
            boolean ready = ticks >= 20 && !respawning;
            Sleek.button(c, hits, width / 2 - bw - 5, by, bw, 24, respawning ? "RISING…" : "RISE AGAIN", true, ready, this::rise);
            Sleek.button(c, hits, width / 2 + 5, by, bw, 24, "LEAVE THE SERVER", false, ticks >= 20, this::leave);
            Ui.text(c, Text.literal("Space to rise"), width / 2f, by + 30, 0.6f, a(0x90, 0x8F8A7A, bk), true);
        }
        float qk = ease(clamp((t - 1.8f) / 1.2f));
        Ui.text(c, Text.literal(quote), width / 2f, height - 22, 0.75f, a(0xC0, 0x8F8A7A, qk), true);
    }

    private int row(DrawContext c, int px, int pw, int y, String k, String v, float a) {
        Ui.text(c, Text.literal(k), px + 10, y, 0.7f, a(0xFF, 0x8F8A7A, a), false);
        Ui.text(c, Text.literal(v), px + pw - 10 - Ui.font().getWidth(v) * 0.7f, y, 0.7f, a(0xFF, 0xEDE3C8, a), false);
        return y + 13;
    }

    private static void card(DrawContext c, int x, int y, int w, int h, int acc, float k) {
        c.fillGradient(x, y, x + w, y + h, a(0xE8, 0x141212, k), a(0xF0, 0x0A0808, k));
        c.fillGradient(x, y, x + w, y + h / 2, a(0x16, acc, k), 0);
        c.fill(x, y, x + w, y + 2, a(0xFF, acc, k));
        c.drawBorder(x, y, w, h, a(0x26, 0xFFFFFF, k));
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    private static float ease(float x) {
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    private static int a(int alpha, int rgb, float k) {
        return ((int) (Math.max(0, Math.min(255, alpha * k))) << 24) | (rgb & 0xFFFFFF);
    }
}
