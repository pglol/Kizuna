package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.List;

/**
 * The battle pass: where you are this season, the track of rewards (free on top, premium under),
 * what's ready to claim glowing, the premium pass and what it brings, skipping ahead, and, once
 * you have premium, gifting it to a friend.
 */
public final class BattlePassScreen extends Screen {
    private static final int COL = 64;
    private final Sleek.Hits hits = new Sleek.Hits();
    private int scroll = -1;
    private boolean gifting;
    private TextFieldWidget giftTo;

    public BattlePassScreen() {
        super(Text.literal("Battle Pass"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() { }

    static ItemStack icon(String id) {
        Identifier i = Identifier.tryParse(id);
        var item = i == null ? Items.PAPER : Registries.ITEM.get(i);
        return new ItemStack(item == Items.AIR ? Items.PAPER : item);
    }

    private static int tier(Net.PassView v) {
        return (int) Math.min(v.tiers().size(), v.xp() / Math.max(1, v.xpPerTier()));
    }

    @Override
    protected void init() {
        giftTo = new TextFieldWidget(Ui.font(), 0, 0, 120, 16, Text.literal("Player"));
        giftTo.setPlaceholder(Text.literal("Player name").withColor(0xFF7A766C));
        addSelectableChild(giftTo);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (gifting && giftTo.isMouseOver(mx, my)) {
            giftTo.setFocused(true);
            setFocused(giftTo);
            return giftTo.mouseClicked(mx, my, button);
        }
        giftTo.setFocused(false);
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (giftTo.isFocused() && key != 256) return giftTo.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (giftTo.isFocused()) return giftTo.charTyped(ch, mods);
        return super.charTyped(ch, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll -= (int) Math.signum(v) * 2;
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xF0080A09, 0xF8040505);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        Net.PassView v = ClientState.pass;
        Ui.text(c, Ui.title("BATTLE PASS"), 16, 14, 1.3f, Sleek.GOLD, false);
        Sleek.button(c, hits, width - 30, 10, 20, 18, "✕", false, true, this::close);
        String gold = String.format(java.util.Locale.ROOT, "%,d", ClientState.gold);
        float gw = Ui.font().getWidth(gold) * 0.85f;
        Sleek.coin(c, width - 50 - gw, 19, 4);
        Ui.text(c, Text.literal(gold), width - 44 - gw, 15, 0.85f, Sleek.GOLD, false);
        c.fill(12, 36, width - 12, 37, Sleek.LINE);
        if (v == null) {
            Ui.text(c, Text.literal("Loading…"), width / 2f, height / 2f, 0.9f, Ui.CREAM, true);
            return;
        }
        int tier = tier(v), n = v.tiers().size();
        boolean maxed = tier >= n;
        long into = maxed ? v.xpPerTier() : v.xp() % v.xpPerTier();

        // The season and where you are in it.
        int x = 16, y = 46;
        Ui.text(c, Ui.heading(v.name()), x, y, 1f, Ui.CREAM, false);
        String ends = v.ended() ? "Season ended" : v.endsAt() > 0 ? "Ends in " + days(v.endsAt()) : "";
        Ui.text(c, Text.literal(ends), x, y + 13, 0.62f, v.ended() ? Ui.RED : 0xFF9A958A, false);
        int px = x + 170, pw = width - px - 290;
        Ui.text(c, Text.literal("TIER"), px, y, 0.55f, 0xFF9A958A, false);
        Ui.text(c, Ui.title(String.valueOf(tier)), px, y + 8, 1.2f, Sleek.GOLD, false);
        Ui.text(c, Text.literal("/ " + n), px + 4 + Ui.font().getWidth(String.valueOf(tier)) * 1.2f, y + 13, 0.7f, 0xFF9A958A, false);
        int bx = px + 44;
        c.fill(bx, y + 12, bx + pw - 44, y + 16, 0x30FFFFFF);
        c.fill(bx, y + 12, bx + (int) ((pw - 44) * (into / (float) v.xpPerTier())), y + 16, Sleek.GOLD);
        Ui.text(c, Text.literal(maxed ? "Season complete" : into + " / " + v.xpPerTier() + " XP to tier " + (tier + 1)), bx, y + 1, 0.58f, 0xFFC8BEA6, false);
        boolean any = false;
        for (int t = 0; t < tier; t++) {
            Net.PassTier pt = v.tiers().get(t);
            if (!pt.freeClaimed() && !pt.free().isEmpty() || v.premium() && !pt.premiumClaimed() && !pt.premium().isEmpty()) any = true;
        }
        if (!maxed && !v.ended()) Sleek.button(c, hits, bx + pw - 44 - 128, y + 20, 128, 16, "+1 tier  ·  " + com.pglol.aotrpg.Season.SKIP_GOLD + " Gold", false,
            ClientState.gold >= com.pglol.aotrpg.Season.SKIP_GOLD, () -> ClientPlayNetworking.send(new Net.PassAction("skip", 1)));
        Sleek.button(c, hits, bx, y + 20, 100, 16, any ? "Claim all" : "Nothing to claim", any, any, () -> ClientPlayNetworking.send(new Net.PassAction("claimall", 0)));

        // Premium: buy it, or gift it.
        premium(c, v, width - 274, 44, 258, 82);

        // The track.
        int ty = 140, cardH = 74, rowGap = 22;
        int vis = Math.max(1, (width - 100) / COL);
        if (scroll < 0) scroll = Math.max(0, tier - 2);
        scroll = Math.max(0, Math.min(scroll, n - vis));
        Ui.text(c, Ui.heading("FREE"), 16, ty + cardH / 2f - 3, 0.7f, Ui.CREAM, false);
        Ui.text(c, Ui.heading("PREMIUM"), 16, ty + cardH + rowGap + cardH / 2f - 3, 0.62f, Sleek.GOLD, false);
        int x0 = 76;
        Net.PassTier hover = null;
        boolean hoverPrem = false;
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        for (int i = 0; i < vis && scroll + i < n; i++) {
            int k = scroll + i;
            Net.PassTier pt = v.tiers().get(k);
            int cx = x0 + i * COL;
            boolean reached = k < tier;
            // The tier number on the line between the rows, the line gold up to where you are.
            int ly = ty + cardH + rowGap / 2;
            c.fill(cx, ly, cx + COL, ly + 1, reached ? Sleek.GOLD : 0x30FFFFFF);
            boolean here = k == tier;
            Ui.text(c, Text.literal(String.valueOf(k + 1)), cx + COL / 2f, ly - 4, here ? 0.8f : 0.62f, here ? Ui.CREAM : reached ? Sleek.GOLD : 0xFF7A766C, true);
            if (here) Smooth.ring(c, cx + COL / 2f, ly, 9, 1, 0xC0E8C36F);
            if (card(c, cx + 3, ty, COL - 6, cardH, pt.freeIcon(), pt.freeColor(), reached, pt.freeClaimed(), true, pt.free().isEmpty(), t, k)) hover = pt;
            if (card(c, cx + 3, ty + cardH + rowGap, COL - 6, cardH, pt.premiumIcon(), pt.premiumColor(), reached, pt.premiumClaimed(), v.premium(), pt.premium().isEmpty(), t, k)) {
                hover = pt;
                hoverPrem = true;
            }
            if (reached) {
                hits.add(cx + 3, ty, COL - 6, cardH, () -> ClientPlayNetworking.send(new Net.PassAction("claim", k)));
                if (v.premium()) hits.add(cx + 3, ty + cardH + rowGap, COL - 6, cardH, () -> ClientPlayNetworking.send(new Net.PassAction("claim", k)));
            }
        }
        int ay = ty + cardH * 2 + rowGap + 10;
        if (scroll > 0) Sleek.button(c, hits, 16, ay, 50, 16, "‹ Back", false, true, () -> scroll -= vis - 1);
        if (scroll + vis < n) Sleek.button(c, hits, width - 66, ay, 50, 16, "More ›", false, true, () -> scroll += vis - 1);
        Ui.text(c, Text.literal("Pass XP comes from titans, quests, work orders, crafting and a daily login bonus  ·  click a reached tier to claim it"),
            width / 2f, ay + 4, 0.56f, 0xFF7A766C, true);
        if (hover != null) {
            String what = hoverPrem ? hover.premium() : hover.free();
            boolean claimed = hoverPrem ? hover.premiumClaimed() : hover.freeClaimed();
            String state = claimed ? "Claimed" : hoverPrem && !v.premium() ? "Premium pass only" : "";
            c.drawTooltip(Ui.font(), state.isEmpty() ? List.of(Text.literal(what)) : List.of(Text.literal(what), Text.literal(state).withColor(0xFF9A958A)), mouseX, mouseY);
        }
    }

    /** One reward. Returns whether it's hovered. */
    private boolean card(DrawContext c, int x, int y, int w, int h, String icon, int color, boolean reached, boolean claimed, boolean owned, boolean empty, float t, int k) {
        int accent = color != 0 ? 0xFF000000 | color : 0xFF9A958A;
        boolean ready = reached && owned && !claimed && !empty;
        boolean hov = Sleek.over(x, y, w, h);
        Sleek.tile(c, x, y, w, h, accent, hov, false, !reached || !owned);
        if (empty) return false;
        if (ready) {
            // Ready to claim: a soft pulse of gold.
            int a = (int) (40 + 30 * Math.sin(t * 4 + k));
            c.fill(x + 1, y + 2, x + w - 1, y + h - 1, (a << 24) | 0xE8C36F);
            c.drawBorder(x, y, w, h, Sleek.GOLD);
        }
        Ui.item(c, icon(icon), x + w / 2 - 12, y + h / 2 - 16, 1.5f);
        if (claimed) {
            c.fill(x + 1, y + 2, x + w - 1, y + h - 1, 0x90000000);
            Ui.text(c, Text.literal("✔"), x + w / 2f, y + h / 2f - 5, 1.2f, 0xFF5BD35B, true);
        } else if (!owned) {
            c.fill(x + 1, y + 2, x + w - 1, y + h - 1, 0x60000000);
            Sleek.chip(c, x + w / 2f - 18, y + h - 14, "PREMIUM", Sleek.GOLD);
        } else if (ready) Sleek.chip(c, x + w / 2f - 15, y + h - 14, "CLAIM", Sleek.GOLD);
        return hov;
    }

    private void premium(DrawContext c, Net.PassView v, int x, int y, int w, int h) {
        Sleek.tile(c, x, y, w, h, Sleek.GOLD, false, false, false);
        c.fillGradient(x + 1, y + 2, x + w - 1, y + h - 1, 0x30E8C36F, 0x00000000);
        if (!v.premium()) {
            Ui.text(c, Ui.heading("Premium Pass"), x + 10, y + 9, 0.95f, Sleek.GOLD, false);
            int extra = 0;
            for (Net.PassTier pt : v.tiers()) if (!pt.premium().isEmpty()) extra++;
            Ui.text(c, Text.literal(extra + " more rewards on the premium track, claimable back to tier 1"), x + 10, y + 22, 0.56f, 0xFFC8BEA6, false);
            // A taste: the first few premium rewards' icons.
            int ix = x + 10;
            for (int i = 0, shown = 0; i < v.tiers().size() && shown < 8; i++) {
                Net.PassTier pt = v.tiers().get(i);
                if (pt.premium().isEmpty()) continue;
                c.drawItem(icon(pt.premiumIcon()), ix, y + 34);
                ix += 19;
                shown++;
            }
            boolean can = !v.ended() && ClientState.gold >= v.premiumGold();
            Sleek.price(c, x + 10, y + h - 15, v.premiumGold(), 0, 0.8f);
            Sleek.button(c, hits, x + w - 110, y + h - 22, 100, 18, v.ended() ? "Season over" : can ? "Unlock" : "Not enough Gold", true, can,
                () -> ClientPlayNetworking.send(new Net.PassAction("buy", 0)));
            return;
        }
        Ui.text(c, Ui.heading("Premium active"), x + 10, y + 9, 0.95f, Sleek.GOLD, false);
        Ui.text(c, Text.literal("Treat a friend: gift them the premium pass"), x + 10, y + 22, 0.58f, 0xFFC8BEA6, false);
        if (!gifting) {
            Sleek.button(c, hits, x + 10, y + h - 26, w - 20, 18, "Gift Premium  ·  " + v.premiumGold() + " Gold", false, !v.ended(), () -> gifting = true);
            return;
        }
        giftTo.setX(x + 10);
        giftTo.setY(y + 36);
        giftTo.setWidth(w - 90);
        giftTo.render(c, Sleek.mx, Sleek.my, 0);
        boolean ok = !giftTo.getText().isBlank() && ClientState.gold >= v.premiumGold();
        Sleek.button(c, hits, x + w - 74, y + 35, 64, 18, "Send", true, ok, () -> {
            ClientPlayNetworking.send(new Net.PassAction("gift:" + giftTo.getText().trim(), 0));
            gifting = false;
        });
        Sleek.button(c, hits, x + 10, y + h - 22, 60, 14, "Cancel", false, true, () -> gifting = false);
    }

    static String days(long endsAt) {
        long ms = endsAt - System.currentTimeMillis();
        if (ms <= 0) return "0d";
        long d = ms / 86_400_000L, hrs = ms / 3_600_000L % 24;
        return d > 0 ? d + "d " + hrs + "h" : hrs + "h";
    }
}
