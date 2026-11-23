package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Stats that float into view while you hold Tab or sit in the pause menu: cards for the server
 * (who's on, this week's operation, the ranked season, the world), for you (standing, arena,
 * titans, Marks) and the leaderboards, each one turning in on its edge, settling, bobbing gently,
 * and turning away for the next. Two streams: the world's, and yours with the boards.
 */
public final class StatDeck {
    private StatDeck() {}

    private record Card(String title, int color, List<Net.StatLine> rows) { }

    private static long lastAsk;
    private static final long PERIOD = 5200, FLIP = 650;

    /** Ask the server for fresh numbers (at most every eight seconds while something's showing them). */
    public static void request() {
        long now = Util.getMeasuringTimeMs();
        if (now - lastAsk < 8000 || MinecraftClient.getInstance().getNetworkHandler() == null) return;
        lastAsk = now;
        ClientPlayNetworking.send(new Net.StatsRequest());
    }

    private static List<Net.StatLine> pick(List<Net.StatLine> from, String... keys) {
        List<Net.StatLine> out = new ArrayList<>();
        for (Net.StatLine l : from) for (String k : keys) if (l.label().equals(k)) out.add(l);
        return out;
    }

    private static List<Net.StatLine> without(List<Net.StatLine> from, String... keys) {
        List<Net.StatLine> out = new ArrayList<>();
        outer:
        for (Net.StatLine l : from) {
            for (String k : keys) if (l.label().equals(k)) continue outer;
            out.add(l);
        }
        return out;
    }

    /** 0: the world (server, this week, season, factions). 1: you and the leaderboards. */
    private static List<Card> cards(int stream) {
        Net.StatsView v = ClientState.stats;
        List<Card> out = new ArrayList<>();
        if (v == null) return out;
        if (stream == 0) {
            List<Net.StatLine> week = pick(v.server(), "This week", "Ranked season", "Season", "Call to Arms");
            List<Net.StatLine> world = without(v.server(), "This week", "Ranked season", "Season", "Call to Arms");
            out.add(new Card("The Server", 0xFFE0B96A, world.subList(0, Math.min(6, world.size()))));
            if (world.size() > 6) out.add(new Card("The World", 0xFF8CC08A, world.subList(6, Math.min(12, world.size()))));
            if (!week.isEmpty()) out.add(new Card("This Week", 0xFFE04A3A, week));
            for (Net.StatBoard b : v.boards()) if (b.title().equals("Hall of Fame")) out.add(new Card(b.title(), 0xFFFFE8A8, top(b.rows())));
        } else {
            List<Net.StatLine> me = v.mine();
            out.add(new Card("You", 0xFF9AD0FF, me.subList(0, Math.min(6, me.size()))));
            if (me.size() > 6) out.add(new Card("Your Record", 0xFF9AD0FF, me.subList(6, Math.min(12, me.size()))));
            for (Net.StatBoard b : v.boards()) if (!b.title().equals("Hall of Fame") && !b.rows().isEmpty()) out.add(new Card(b.title(), 0xFFE0B96A, top(b.rows())));
        }
        return out;
    }

    private static List<Net.StatLine> top(List<Net.StatLine> rows) {
        List<Net.StatLine> out = new ArrayList<>();
        for (int i = 0; i < Math.min(5, rows.size()); i++) {
            Net.StatLine r = rows.get(i);
            out.add(new Net.StatLine((i + 1) + ".  " + r.label(), r.value()));
        }
        return out;
    }

    /**
     * Draws a stream as a column of `slots` cards, w wide, from (x, y). Each slot turns over to the
     * next card in turn, a beat after the one above it.
     */
    public static void render(DrawContext c, int x, int y, int w, int stream, int slots) {
        request();
        List<Card> cs = cards(stream);
        if (cs.isEmpty()) {
            Ui.text(c, Text.literal("Gathering the numbers..."), x + w / 2f, y + 6, 0.6f, 0x90EDE3C8, true);
            return;
        }
        long now = Util.getMeasuringTimeMs();
        int ch = 86;
        slots = Math.min(slots, cs.size());
        for (int s = 0; s < slots; s++) {
            long t = now - s * 260L + stream * 1300L;
            long idx = Math.floorDiv(t, PERIOD);
            long into = Math.floorMod(t, PERIOD);
            Card card = cs.get((int) Math.floorMod(idx * slots + s, cs.size()));
            // Turning in at the start of its turn, turning away at the end.
            float k = into < FLIP ? into / (float) FLIP : into > PERIOD - FLIP * 0.6f ? (PERIOD - into) / (FLIP * 0.6f) : 1;
            k = MathHelper.clamp(k, 0, 1);
            float ease = k * k * (3 - 2 * k);
            float bob = (float) Math.sin((now + s * 900 + stream * 500) / 900.0) * 2.5f;
            int cy = y + s * (ch + 8) + Math.round(bob + (1 - ease) * 10);
            card(c, x, cy, w, ch, card, ease);
        }
    }

    private static void card(DrawContext c, int x, int y, int w, int h, Card card, float k) {
        if (k <= 0.02f) return;
        int a = (int) (255 * k);
        float cx = x + w / 2f, cy = y + h / 2f;
        c.getMatrices().push();
        c.getMatrices().translate(cx, cy, 300);
        // On its edge: narrow as it turns, with a slight lean.
        c.getMatrices().scale(Math.max(0.02f, k), 1, 1);
        c.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Z.rotationDegrees((1 - k) * 4));
        c.getMatrices().translate(-cx, -cy, 0);
        c.fill(x, y, x + w, y + h, (Math.min(0xD8, a) << 24) | 0x0B0E10);
        c.fillGradient(x, y, x + w, y + h / 2, (Math.min(0x16, a / 10) << 24) | 0xFFFFFF, 0);
        c.drawBorder(x, y, w, h, (Math.min(0x30, a / 5) << 24) | 0xFFFFFF);
        c.fill(x, y, x + w, y + 2, (a << 24) | (card.color() & 0xFFFFFF));
        // The turning edge catches the light.
        if (k < 0.95f) c.fill(x + w - 2, y, x + w, y + h, ((int) (a * (1 - k)) << 24) | 0xFFFFFF);
        Ui.text(c, Ui.heading(card.title().toUpperCase(java.util.Locale.ROOT)), x + 7, y + 7, 0.68f, (a << 24) | (card.color() & 0xFFFFFF), false);
        int ry = y + 21;
        var font = Ui.font();
        for (Net.StatLine r : card.rows()) {
            if (ry > y + h - 9) break;
            float sc = 0.56f;
            String label = r.label(), value = r.value();
            float vw = font.getWidth(value) * sc;
            float maxLabel = w - 14 - Math.min(vw, w * 0.6f) - 6;
            while (label.length() > 3 && font.getWidth(label) * sc > maxLabel) label = label.substring(0, label.length() - 2) + "…";
            while (value.length() > 3 && font.getWidth(value) * sc > w * 0.6f) value = value.substring(0, value.length() - 2) + "…";
            Ui.text(c, Text.literal(label), x + 7, ry, sc, (a << 24) | 0x8F8A7A, false);
            Ui.text(c, Text.literal(value), x + w - 7 - font.getWidth(value) * sc, ry, sc, (a << 24) | 0xEDE3C8, false);
            ry += 9;
        }
        c.getMatrices().pop();
    }
}
