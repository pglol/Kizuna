package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.Locale;

/**
 * The PvP board: casual and ranked queues side by side, the matches under way to watch, and your
 * rank on top. Also draws the match score in the corner while you're in one.
 */
public final class ArenaScreen extends Screen {
    private static Net.ArenaView view;
    private static Net.ArenaHud hud;
    private long lastAsk;

    public ArenaScreen(Net.ArenaView v) {
        super(Text.literal("Arenas"));
        view = v;
    }

    public static void on(Net.ArenaView v) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (v.open()) mc.setScreen(new ArenaScreen(v));
        else {
            view = v;
            if (mc.currentScreen instanceof ArenaScreen s) s.clearAndInit();
        }
    }

    public static void on(Net.ArenaHud h) {
        hud = h.secs() == -1 ? null : h;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final int CW = 150, CH = 34, GAP = 6;

    @Override
    protected void init() {
        if (view == null) return;
        int cols = 3, total = cols * CW + (cols - 1) * 16;
        int x0 = width / 2 - total / 2, y0 = 74;
        int[] row = new int[cols];
        for (Net.ArenaEntry e : view.list()) {
            int col = e.state().equals("ranked") ? 1 : 0;
            int x = x0 + col * (CW + 16), y = y0 + row[col]++ * (CH + GAP);
            boolean queued = e.you().equals("queued"), playing = e.you().equals("playing");
            AotButton b = addDrawableChild(new AotButton(x, y, CW, CH, Ui.heading(e.name()), () ->
                ClientPlayNetworking.send(new Net.ArenaAction(queued ? "leave" : "join", e.id()))));
            String sub = playing ? "In a match" : queued ? "Searching  " + clock(e.secs()) + "  ·  click to leave"
                : !e.ready() ? e.blurb() : e.queued() + " waiting · " + e.playing() + " fighting";
            b.sub(Text.literal(sub));
            b.selected = queued || playing;
            b.active = e.ready() && !playing;
            if (e.state().equals("ranked")) b.accent = 0xFFE0463A;
        }
        int lx = x0 + 2 * (CW + 16), ly = y0;
        for (Net.ArenaLive l : view.live()) {
            if (ly > height - 60) break;
            AotButton b = addDrawableChild(new AotButton(lx, ly, CW, CH, Text.literal(l.title()), () -> {
                ClientPlayNetworking.send(new Net.ArenaAction("watch", String.valueOf(l.id())));
                close();
            }));
            b.sub(Text.literal(l.score() + "  ·  " + l.who()));
            b.textScale = 0.85f;
            ly += CH + GAP;
        }
        AotButton close = addDrawableChild(new AotButton(width / 2 - 50, height - 30, 100, 18, Text.literal("Close"), this::close));
        close.textScale = 0.85f;
    }

    private static String clock(int s) {
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    @Override
    public void tick() {
        // The wait timers tick on.
        if (Util.getMeasuringTimeMs() - lastAsk > 2000) {
            lastAsk = Util.getMeasuringTimeMs();
            ClientPlayNetworking.send(new Net.ArenaAction("view", ""));
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.title("ARENAS"), width / 2f, 14, 1.5f, Ui.GOLD, true);
        if (view == null) return;
        Ui.text(c, Ui.heading(view.tier().toUpperCase(Locale.ROOT) + "  ·  " + view.rating()), width / 2f, 34, 0.9f, 0xFFE0463A, true);
        Ui.text(c, Text.literal(view.wins() + " W  ·  " + view.losses() + " L"), width / 2f, 46, 0.7f, Ui.CREAM, true);
        int total = 3 * CW + 2 * 16, x0 = width / 2 - total / 2;
        String[] heads = {"CASUAL", "RANKED", "WATCH"};
        for (int i = 0; i < 3; i++) Ui.text(c, Ui.heading(heads[i]), x0 + i * (CW + 16) + CW / 2f, 62, 0.75f, 0xFFE8C47A, true);
        if (view.live().isEmpty()) Ui.text(c, Text.literal("No fights right now"), x0 + 2 * (CW + 16) + CW / 2f, 84, 0.7f, Ui.CREAM, true);
    }

    /** The score in the top middle while you're in (or watching) a match. */
    public static void render(DrawContext c, RenderTickCounter t) {
        Net.ArenaHud h = hud;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (h == null || mc.options.hudHidden || mc.player == null) return;
        int w = 150, x = mc.getWindow().getScaledWidth() / 2 - w / 2, y = 4;
        Ui.glass(c, x, y, w, 40);
        Ui.text(c, Text.literal(h.title()), x + w / 2f, y + 4, 0.6f, Ui.CREAM, true);
        Ui.text(c, Ui.title(h.score()), x + w / 2f, y + 13, 1.2f, Ui.GOLD, true);
        String time = h.secs() == -2 ? "GET READY" : h.secs() > 0 ? clock(h.secs()) : "";
        if (!time.isEmpty()) Ui.text(c, Ui.heading(time), x + w - 36, y + 4, 0.6f, h.secs() == -2 ? 0xFFE0463A : Ui.CREAM, false);
        Ui.text(c, Text.literal(h.sub()), x + w / 2f, y + 30, 0.55f, 0xFFD8D0C0, true);
    }
}
