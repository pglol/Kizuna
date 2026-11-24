package com.pglol.aotrpg.client.story;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.Sleek;
import com.pglol.aotrpg.client.Smooth;
import com.pglol.aotrpg.client.Ui;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * A conversation, framed like a film: letterbox bars ease in, a dark glass card rises from the
 * bottom with the speaker's portrait in a gold frame, their name on a banner, and their words typed
 * out with a beat at every comma and full stop. Your answers sit under it as rows that slide out
 * when you point at them. In a party scene the host answers; the others can suggest a reply (shown
 * beside it for the host). Number keys pick, Space hurries the text or continues.
 */
public final class DialogueScreen extends Screen {
    private long shownAt, openedAt;
    private String lastText = "";
    /** When each character of the current line appears (ms after shownAt). */
    private int[] at = new int[0];
    private final Sleek.Hits hits = new Sleek.Hits();
    private final float[] slide = new float[10];

    public DialogueScreen() {
        super(Text.literal("Dialogue"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() {
        clearAndInit();
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = Util.getMeasuringTimeMs();
        Net.DialogueView v = StoryClient.dialogue;
        if (v == null) return;
        if (!v.text().equals(lastText)) {
            lastText = v.text();
            shownAt = Util.getMeasuringTimeMs();
            // Typing rhythm: steady letters, a breath after commas, a pause after sentences.
            String s = v.text();
            at = new int[s.length() + 1];
            int ms = 0;
            for (int i = 0; i < s.length(); i++) {
                at[i] = ms;
                char ch = s.charAt(i);
                ms += ch == '.' || ch == '!' || ch == '?' ? 220 : ch == ',' || ch == ';' || ch == ':' ? 110 : ch == '—' || ch == '…' ? 160 : 20;
            }
            at[s.length()] = ms;
            java.util.Arrays.fill(slide, 0);
        }
    }

    private int shown(String text) {
        long el = Util.getMeasuringTimeMs() - shownAt;
        int n = 0;
        while (n < text.length() && n < at.length && at[n] <= el) n++;
        return n;
    }

    private boolean typing() {
        Net.DialogueView v = StoryClient.dialogue;
        return v != null && shown(v.text()) < v.text().length();
    }

    private void pick(int index) {
        if (typing()) {
            shownAt = 0;
            return;
        }
        ClientPlayNetworking.send(new Net.DialoguePick(index));
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        Net.DialogueView v = StoryClient.dialogue;
        if (v != null) {
            if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER) {
                if (typing()) shownAt = 0;
                else if (v.choices().isEmpty() && v.host()) pick(-1);
                return true;
            }
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                int i = key - GLFW.GLFW_KEY_1;
                if (i < v.choices().size() && v.choices().get(i).enabled()) pick(v.choices().get(i).index());
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (hits.click(mx, my)) return true;
            // A click anywhere hurries the text along, or continues.
            Net.DialogueView v = StoryClient.dialogue;
            if (v != null && typing()) {
                shownAt = 0;
                return true;
            }
            if (v != null && v.choices().isEmpty() && v.host()) {
                pick(-1);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        hits.clear();
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        Net.DialogueView v = StoryClient.dialogue;
        if (v == null) return;
        long now = Util.getMeasuringTimeMs();
        float t = (now - openedAt) / 1000f;
        float in = ease(MathHelper.clamp(t / 0.45f, 0, 1));
        float lt = (now - shownAt) / 1000f;

        // Film frame: letterbox bars and a soft darkening towards the bottom.
        int bar = Math.round(height * 0.07f * in);
        c.fill(0, 0, width, bar, 0xFF000000);
        c.fill(0, height - bar, width, height, 0xFF000000);
        c.fillGradient(0, height / 3, width, height - bar, 0x00000000, (int) (0xC0 * in) << 24);

        int w = Math.min(600, width - 32);
        int left = (width - w) / 2;
        int rows = v.choices().size();
        int textH = 54;
        int cardH = 26 + textH + 12;
        int choicesH = rows * 24;
        int bottom = height - bar - 12;
        int top = bottom - cardH - (rows > 0 ? choicesH + 8 : 0) + Math.round((1 - in) * 40);
        int alpha = (int) (255 * in);

        // The card: dark glass, hairline edge, a gold seam along the top.
        int cx = left + 92, cy = top;
        int cw = w - 92;
        c.fillGradient(cx, cy, cx + cw, cy + cardH, a(0xF0, 0x121614, in), a(0xF4, 0x0A0D0C, in));
        c.drawBorder(cx, cy, cw, cardH, a(0x30, 0xFFFFFF, in));
        float seam = ease(MathHelper.clamp((t - 0.15f) / 0.5f, 0, 1));
        c.fill(cx, cy, cx + Math.round(cw * seam), cy + 1, a(0xFF, 0xE8C36F, in));
        c.fillGradient(cx, cy + 1, cx + cw, cy + 22, a(0x16, 0xE8C36F, in), 0);

        // Name banner: a gold tab that slides out from the portrait.
        String name = v.speaker().isEmpty() ? "" : v.speaker().toUpperCase(java.util.Locale.ROOT);
        if (!name.isEmpty()) {
            float bk = ease(MathHelper.clamp((t - 0.1f) / 0.4f, 0, 1));
            int nw = (int) (Ui.font().getWidth(name) * 0.85f) + 22;
            int bx = cx - 4, by = cy - 9;
            int bw = Math.round(nw * bk);
            c.fill(bx, by, bx + bw, by + 15, a(0xFF, 0xE8C36F, in));
            c.fill(bx + bw, by, bx + bw + 3, by + 15, a(0x80, 0xE8C36F, in));
            if (bk > 0.6f) Ui.text(c, Ui.heading(name), bx + 10, by + 4, 0.85f, a(0xFF, 0x1A1408, (bk - 0.6f) / 0.4f), false);
        }

        // Portrait: the speaker's face in a gold frame, breathing very slightly.
        int ps = 78, px = left, py = cy + cardH - ps;
        float breathe = 1 + (float) Math.sin(t * 1.6) * 0.008f;
        var ms = c.getMatrices();
        ms.push();
        ms.translate(px + ps / 2f, py + ps / 2f, 0);
        ms.scale(breathe, breathe, 1);
        ms.translate(-(px + ps / 2f), -(py + ps / 2f), 0);
        c.fill(px - 2, py - 2, px + ps + 2, py + ps + 2, a(0xFF, 0x8C7248, in));
        c.fill(px - 1, py - 1, px + ps + 1, py + ps + 1, a(0xFF, 0xE8C36F, in));
        c.fill(px, py, px + ps, py + ps, a(0xFF, 0x141714, in));
        if (!v.skin().isEmpty() && in > 0.05f) {
            var tex = StoryClient.skinTexture(v.skin());
            c.setShaderColor(1, 1, 1, in);
            c.drawTexture(tex, px + 3, py + 3, ps - 6, ps - 6, 8, 8, 8, 8, 64, 64);
            c.drawTexture(tex, px + 3, py + 3, ps - 6, ps - 6, 40, 8, 8, 8, 64, 64);
            c.setShaderColor(1, 1, 1, 1);
        } else {
            Ui.text(c, Ui.title("?"), px + ps / 2f, py + ps / 2f - 8, 2.2f, a(0xFF, 0x8C7248, in), true);
        }
        // A sheen across the glass of the frame now and then.
        float sh = (t * 0.25f) % 1f;
        if (sh < 0.3f) {
            int sx = px + Math.round((sh / 0.3f) * (ps + 30)) - 15;
            for (int i = 0; i < 10; i++) {
                int x0 = Math.max(px, sx + i), x1 = Math.min(px + ps, sx + i + 1);
                if (x1 > x0) c.fill(x0, py, x1, py + ps, 0x10FFFFFF);
            }
        }
        // A vignette inside the frame.
        c.fillGradient(px, py + ps / 2, px + ps, py + ps, 0, a(0x70, 0x000000, in));
        ms.pop();

        // The words, typed out.
        int tx = cx + 14, ty = cy + 18;
        String text = v.text();
        int n = shown(text);
        Sleek.wrap(c, text.substring(0, n), tx, ty, cw - 28, 0.95f, a(0xFF, 0xEDE3C8, in));
        if (n >= text.length() && rows == 0) {
            // Waiting on you: a soft pulsing arrow.
            float p = 0.5f + 0.5f * (float) Math.sin(t * 5);
            String hint = v.host() ? "CONTINUE" : v.hostName().toUpperCase(java.util.Locale.ROOT) + " CONTINUES";
            float hw = Ui.font().getWidth(hint) * 0.6f;
            Ui.text(c, Ui.heading(hint), cx + cw - 22 - hw, cy + cardH - 14, 0.6f, a((int) (120 + 100 * p), 0xE8C36F, in), false);
            Ui.text(c, Text.literal("▼"), cx + cw - 14, cy + cardH - 15 + p * 2, 0.7f, a((int) (150 + 100 * p), 0xE8C36F, in), true);
            if (v.host()) hits.add(cx, cy, cw, cardH, () -> pick(-1));
        } else if (n < text.length()) {
            // A caret at the end of the line being typed.
            if ((now / 260) % 2 == 0) {
                Ui.text(c, Text.literal("▍"), cx + cw - 16, cy + cardH - 15, 0.6f, a(0x90, 0xE8C36F, in), false);
            }
        }

        // Replies.
        if (rows > 0) {
            float ck = ease(MathHelper.clamp((lt - (n >= text.length() ? 0 : 99)) / 0.35f, 0, 1));
            if (n >= text.length()) {
                int y = cy + cardH + 8;
                for (int i = 0; i < rows; i++) {
                    Net.ChoiceView ch = v.choices().get(i);
                    float rk = ease(MathHelper.clamp((ck * 1.6f) - i * 0.15f, 0, 1));
                    if (rk <= 0) {
                        y += 24;
                        continue;
                    }
                    int rx = cx + Math.round((1 - rk) * 30), rw = cw;
                    boolean hov = ch.enabled() && Sleek.over(rx, y, rw, 20);
                    slide[i % slide.length] = MathHelper.lerp(Math.min(1, delta * 0.4f), slide[i % slide.length], hov ? 1 : 0);
                    float sl = slide[i % slide.length];
                    int off = Math.round(sl * 8);
                    int acc = ch.tag().isEmpty() ? 0xE8C36F : 0x9AB8D8;
                    float ra = in * rk;
                    c.fillGradient(rx + off, y, rx + rw, y + 20, a(hov ? 0xF0 : 0xD8, hov ? 0x1C2220 : 0x111513, ra), a(0xD8, 0x0B0E0D, ra));
                    c.fill(rx + off, y, rx + off + 2, y + 20, a(ch.enabled() ? 0xFF : 0x50, acc, ra));
                    if (hov) c.fillGradient(rx + off + 2, y, rx + off + 80, y + 20, a(0x28, acc, ra), 0);
                    c.drawBorder(rx + off, y, rw - off, 20, a(hov ? 0x70 : 0x22, hov ? acc : 0xFFFFFF, ra));
                    // Number badge.
                    Smooth.disc(c, rx + off + 13, y + 10, 6.5f, a(ch.enabled() ? 0xFF : 0x60, acc, ra));
                    Ui.text(c, Text.literal(String.valueOf(i + 1)), rx + off + 13, y + 6.5f, 0.7f, a(0xFF, 0x1A1408, ra), true);
                    float lx = rx + off + 26;
                    if (!ch.tag().isEmpty()) {
                        lx += Sleek.chip(c, lx, y + 5.5f, ch.tag().toUpperCase(java.util.Locale.ROOT), a(0xFF, acc, ra)) + 6;
                    }
                    Ui.text(c, Text.literal(ch.text()), lx, y + 6, 0.85f, ch.enabled() ? a(0xFF, hov ? 0xFFFFFF : 0xEDE3C8, ra) : a(0xFF, 0x6A665C, ra), false);
                    if (!ch.suggestedBy().isEmpty()) {
                        String sg = "suggested by " + ch.suggestedBy();
                        Ui.text(c, Text.literal(sg), rx + rw - 8 - Ui.font().getWidth(sg) * 0.6f, y + 7, 0.6f, a(0xFF, 0xD070FF, ra), false);
                    }
                    if (ch.enabled()) {
                        int idx = ch.index();
                        hits.add(rx, y, rw, 20, () -> pick(idx));
                    }
                    y += 24;
                }
            }
        }
        if (!v.host()) {
            Ui.text(c, Text.literal(v.hostName() + " decides · pick a reply to suggest it"), width / 2f, height - bar + Math.max(2, bar / 2 - 4), 0.6f, a(0xFF, 0xD070FF, in), true);
        } else if (rows > 0 && n >= text.length()) {
            Ui.text(c, Text.literal("1–" + rows + " to answer"), width / 2f, height - bar + Math.max(2, bar / 2 - 4), 0.6f, a(0xB0, 0x8F8A7A, in), true);
        }
    }

    private static float ease(float x) {
        return 1 - (1 - x) * (1 - x) * (1 - x);
    }

    private static int a(int alpha, int rgb, float k) {
        return ((int) (MathHelper.clamp(alpha * k, 0, 255)) << 24) | (rgb & 0xFFFFFF);
    }
}
