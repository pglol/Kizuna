package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * The store. Featured (this week's spotlight, a quarter off), every cosmetic and title for sale
 * any time, and crates. Pick anything to see it big on the right: you wearing it, what it is,
 * its price, buy it, try it on in the showroom, or send it to a friend as a gift. Things like it
 * that you don't have yet sit underneath.
 */
public final class StoreScreen extends Screen {
    /** The last store view from the server. */
    public static Net.StoreView view;
    /** Opened to send someone a gift (from People): their name goes in the gift box. */
    public static String giftFor;
    private static final int[] RARITY = {0xFFB0B0B0, 0xFF5BD35B, 0xFF5A9AE0, 0xFFB06AE0, 0xFFF2C14E, 0xFFE02A2A};
    private static final String[] RARITY_NAME = {"Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic"};
    private static final String[] TABS = {"Featured", "Cosmetics", "Titles", "Crates"};
    private final Screen parent;
    private final Sleek.Hits hits = new Sleek.Hits();
    private int tab;
    private String filter = "";
    private String selected;
    private int scroll;
    private boolean gifting;
    private TextFieldWidget giftTo;

    public StoreScreen(Screen parent) {
        this(parent, null);
    }

    /** Opened on one thing ("cosmetic:id", "title:id"), or on a tab ("crates"). */
    public StoreScreen(Screen parent, String focus) {
        super(Text.literal("Store"));
        this.parent = parent;
        if ("crates".equals(focus)) tab = 3;
        else if (focus != null) {
            selected = focus;
            tab = focus.startsWith("title:") ? 2 : 1;
            if (focus.startsWith("cosmetic:")) {
                CosmeticFx.Category cat = categoryOf(focus.substring(9));
                if (cat != null) filter = cat.slot();
            }
        }
    }

    static CosmeticFx.Category categoryOf(String id) {
        for (CosmeticFx.Category c : CosmeticFx.CATEGORIES) for (CosmeticFx.Entry e : c.entries()) if (e.id().equals(id)) return c;
        return null;
    }

    public static void onOpened(Net.CrateOpened o) {
        List<String> loot = List.of();
        if (view != null) for (Net.StoreCrate c : view.crates()) if (c.id().equals(o.crateId())) loot = c.loot();
        // A gifted crate opened from the inbox.
        if (loot.isEmpty()) loot = InboxScreen.loot(o.crateId());
        CrateOpening.start(o, loot);
    }

    @Override
    protected void init() {
        ClientPlayNetworking.send(new Net.StoreAction("open", ""));
        giftTo = new TextFieldWidget(Ui.font(), 0, 0, 120, 16, Text.literal("Player"));
        giftTo.setMaxLength(32);
        giftTo.setPlaceholder(Text.literal("Player name").withColor(0xFF7A766C));
        addSelectableChild(giftTo);
        if (giftFor != null) {
            giftTo.setText(giftFor);
            if (tab == 3) tab = 0;
        }
    }

    public void refresh() { }

    private static boolean revealing() {
        return CrateOpening.active();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (CrateOpening.click()) return true;
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
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v));
        return true;
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
    public void close() {
        giftFor = null;
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() { return false; }

    // ------------------------------------------------------------------ what's for sale

    private static boolean cosmetic(Net.StoreOffer o) {
        return o.id().startsWith("cosmetic:");
    }

    private static int tierOf(Net.StoreOffer o) {
        String[] k = o.kind().split(":");
        try {
            return Integer.parseInt(k[k.length - 1]);
        } catch (Exception e) {
            return 1;
        }
    }

    private static String slotOf(Net.StoreOffer o) {
        String[] k = o.kind().split(":");
        return k.length >= 3 ? k[1] : "";
    }

    private static int colorOf(Net.StoreOffer o) {
        return cosmetic(o) ? Sleek.TIER[Math.min(4, tierOf(o))] : RARITY[Math.min(5, tierOf(o))];
    }

    private static String rarityName(Net.StoreOffer o) {
        return cosmetic(o) ? Sleek.TIER_NAME[Math.min(4, tierOf(o))] : RARITY_NAME[Math.min(5, tierOf(o))];
    }

    private List<Net.StoreOffer> listed() {
        List<Net.StoreOffer> out = new ArrayList<>();
        if (view == null) return out;
        for (Net.StoreOffer o : view.offers()) {
            boolean ok = switch (tab) {
                case 0 -> o.featured();
                case 1 -> cosmetic(o) && (filter.isEmpty() || slotOf(o).equals(filter));
                case 2 -> o.id().startsWith("title:");
                default -> false;
            };
            if (ok) out.add(o);
        }
        // Not owned first, then by rarity, rarest first.
        out.sort((a, b) -> a.owned() != b.owned() ? (a.owned() ? 1 : -1) : tierOf(b) - tierOf(a));
        return out;
    }

    private Net.StoreOffer find(String id) {
        if (view == null || id == null) return null;
        for (Net.StoreOffer o : view.offers()) if (o.id().equals(id)) return o;
        return null;
    }

    // ------------------------------------------------------------------ drawing

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
        if (revealing()) {
            CrateOpening.render(c, width, height);
            return;
        }
        // Header: the name, the tabs, your purse.
        Ui.text(c, Ui.title("STORE"), 16, 14, 1.3f, Sleek.GOLD, false);
        Sleek.tabs(c, hits, 96, 14, TABS, tab, k -> {
            tab = k;
            scroll = 0;
            selected = null;
            gifting = false;
        });
        String gold = String.format(java.util.Locale.ROOT, "%,d", ClientState.gold), marks = String.format(java.util.Locale.ROOT, "%,d Marks", ClientState.marks);
        float gw = Ui.font().getWidth(gold) * 0.85f, mw = Ui.font().getWidth(marks) * 0.7f;
        Ui.text(c, Text.literal(marks), width - 40 - mw, 17, 0.7f, 0xFFB8B0A0, false);
        Sleek.coin(c, width - 54 - mw - gw, 19, 4);
        Ui.text(c, Text.literal(gold), width - 48 - mw - gw, 15, 0.85f, Sleek.GOLD, false);
        Sleek.button(c, hits, width - 30, 10, 20, 18, "✕", false, true, this::close);
        c.fill(12, 36, width - 12, 37, Sleek.LINE);
        if (view == null) {
            Ui.text(c, Text.literal("Loading the store..."), width / 2f, height / 2f, 0.9f, Ui.CREAM, true);
            return;
        }
        int detailW = tab == 3 ? 0 : Math.min(250, width / 3);
        int gx = 14, gy = 44, gw2 = width - 28 - (detailW > 0 ? detailW + 10 : 0);
        if (tab == 3) crates(c, gx, gy, gw2);
        else {
            if (tab == 1) gy = filters(c, gx, gy, gw2);
            if (tab == 0) {
                long s = view.secondsLeft();
                String left = s > 86400 ? (s / 86400) + "d " + (s % 86400 / 3600) + "h" : (s / 3600) + "h " + (s % 3600 / 60) + "m";
                Ui.text(c, Ui.heading("This week's picks"), gx, gy + 2, 0.85f, Ui.CREAM, false);
                Sleek.chip(c, gx + 4 + Ui.font().getWidth("This week's picks") * 0.85f + 6, gy + 2, "25% OFF", 0xFF5BD35B);
                String r = "New picks in " + left;
                Ui.text(c, Text.literal(r), gx + gw2 - Ui.font().getWidth(r) * 0.6f, gy + 4, 0.6f, 0xFF9A958A, false);
                gy += 18;
            }
            grid(c, gx, gy, gw2, tab == 0 ? 120 : 92);
            if (detailW > 0) detail(c, width - 14 - detailW, 44, detailW, height - 58);
        }
    }

    /** The category filter for cosmetics. Returns where the grid starts. */
    private int filters(DrawContext c, int x, int y, int w) {
        float cx = x;
        int row = y;
        List<String[]> opts = new ArrayList<>();
        opts.add(new String[] {"", "All"});
        for (CosmeticFx.Category cat : CosmeticFx.CATEGORIES) opts.add(new String[] {cat.slot(), cat.title()});
        for (String[] o : opts) {
            int bw = (int) (Ui.font().getWidth(o[1]) * 0.62f) + 12;
            if (cx + bw > x + w) {
                cx = x;
                row += 15;
            }
            boolean sel = o[0].equals(filter), hov = Sleek.over(cx, row, bw, 13);
            c.fill((int) cx, row, (int) cx + bw, row + 13, sel ? 0x40E8C36F : hov ? 0x20FFFFFF : 0x10FFFFFF);
            c.drawBorder((int) cx, row, bw, 13, sel ? Sleek.GOLD : Sleek.LINE);
            Ui.text(c, Text.literal(o[1]), cx + bw / 2f, row + 3, 0.62f, sel ? Sleek.GOLD : 0xFFC8BEA6, true);
            String f = o[0];
            hits.add((int) cx, row, bw, 13, () -> {
                filter = f;
                scroll = 0;
            });
            cx += bw + 4;
        }
        return row + 20;
    }

    private void grid(DrawContext c, int x, int y, int w, int th) {
        List<Net.StoreOffer> list = listed();
        int gap = 6, cols = Math.max(2, Math.min(5, (w + gap) / ((tab == 0 ? 150 : 112) + gap)));
        int tw = (w - gap * (cols - 1)) / cols;
        int rows = Math.max(1, (height - 14 - y) / (th + gap));
        int total = (list.size() + cols - 1) / cols;
        scroll = Math.max(0, Math.min(scroll, total - rows));
        if (list.isEmpty()) Ui.text(c, Text.literal("Nothing here right now."), x + w / 2f, y + 40, 0.8f, 0xFF9A958A, true);
        for (int i = scroll * cols; i < list.size() && i < (scroll + rows) * cols; i++) {
            Net.StoreOffer o = list.get(i);
            int r = i / cols - scroll, col = i % cols;
            int tx = x + col * (tw + gap), ty = y + r * (th + gap);
            card(c, o, tx, ty, tw, th);
            hits.add(tx, ty, tw, th, () -> {
                selected = o.id();
                gifting = giftFor != null && !giftFor.isBlank();
            });
        }
    }

    /** One thing for sale. */
    private void card(DrawContext c, Net.StoreOffer o, int x, int y, int w, int h) {
        int col = colorOf(o);
        Sleek.tile(c, x, y, w, h, col, Sleek.over(x, y, w, h), o.id().equals(selected), false);
        int ph = h - 34;
        picture(c, o, x + 1, y + 3, w - 2, ph);
        Ui.text(c, Text.literal(fit(o.title(), w - 10, 0.68f)), x + 5, y + h - 29, 0.68f, o.id().startsWith("title:") ? 0xFF000000 | o.color() : Ui.CREAM, false);
        Sleek.chip(c, x + 5, y + h - 17, rarityName(o).toUpperCase(), col);
        if (o.owned()) Sleek.chip(c, x + w - 5 - (Ui.font().getWidth("OWNED") * 0.55f + 8), y + h - 17, "OWNED", 0xFF5BD35B);
        else {
            String p = String.format(java.util.Locale.ROOT, "%,d", o.price());
            Sleek.price(c, x + w - 16 - Ui.font().getWidth(p) * 0.62f, y + h - 16, o.price(), 0, 0.62f);
            if (o.was() > o.price()) Sleek.chip(c, x + w - 32, y + 6, "-25%", 0xFF5BD35B);
        }
    }

    /** The thing itself: you wearing it, the effect's emblem, or the title on a plaque. */
    private void picture(DrawContext c, Net.StoreOffer o, int x, int y, int w, int h) {
        if (cosmetic(o)) {
            CosmeticFx.Entry e = CosmeticFx.entry(o.id().substring(9));
            if (e != null) Showcase.thumb(c, x, y, w, h, slotOf(o), e, false);
        } else {
            int cy = y + h / 2;
            c.fill(x + 8, cy - 9, x + w - 8, cy + 9, 0x50000000);
            c.drawBorder(x + 8, cy - 9, w - 16, 18, (0x80 << 24) | (o.color() & 0xFFFFFF));
            Ui.text(c, Text.literal(fit(o.title(), w - 22, 0.8f)), x + w / 2f, cy - 3, 0.8f, 0xFF000000 | o.color(), true);
        }
    }

    /** The chosen thing, big: what it is, buy it, try it on, gift it, and more like it. */
    private void detail(DrawContext c, int x, int y, int w, int h) {
        Sleek.panel(c, x, y, w, h);
        Net.StoreOffer o = find(selected);
        if (o == null) {
            Ui.text(c, Text.literal("Pick something to see it here"), x + w / 2f, y + h / 2f, 0.7f, 0xFF7A766C, true);
            return;
        }
        int col = colorOf(o);
        c.fill(x, y, x + w, y + 2, col);
        int stage = Math.min(170, h / 2 - 20);
        c.fillGradient(x + 1, y + 2, x + w - 1, y + stage, (0x26 << 24) | (col & 0xFFFFFF), 0x00000000);
        if (cosmetic(o)) {
            CosmeticFx.Entry e = CosmeticFx.entry(o.id().substring(9));
            String slot = slotOf(o);
            if (e != null && Showcase.wearable(slot)) {
                c.enableScissor(x + 1, y + 2, x + w - 1, y + stage);
                float spin = (Util.getMeasuringTimeMs() % 12000) / 12000f * 360;
                Showcase.model(c, x + w / 2f, y + stage - 8, stage * 0.36f, spin + (slot.equals("back") ? 180 : 0), slot, e.id());
                c.disableScissor();
            } else if (e != null) {
                Ui.item(c, Showcase.icon(slot), x + w / 2 - 24, y + stage / 2 - 26, 3f);
                Ui.text(c, Text.literal("Try it on to see it played on you"), x + w / 2f, y + stage - 14, 0.55f, 0xFF9A958A, true);
            }
        } else picture(c, o, x + 1, y + 2, w - 2, stage);
        int ty = y + stage + 4;
        Ui.text(c, Ui.heading(fit(o.title(), w - 16, 1f)), x + 8, ty, 1f, cosmetic(o) ? Ui.CREAM : 0xFF000000 | o.color(), false);
        int cw = Sleek.chip(c, x + 8, ty + 13, rarityName(o).toUpperCase(), col);
        String kind = cosmetic(o) ? (categoryOf(o.id().substring(9)) == null ? "Cosmetic" : categoryOf(o.id().substring(9)).title()) : "Title";
        Ui.text(c, Text.literal(kind), x + 14 + cw, ty + 15, 0.55f, 0xFF9A958A, false);
        CosmeticFx.Entry e = cosmetic(o) ? CosmeticFx.entry(o.id().substring(9)) : null;
        int dy = ty + 28;
        if (e != null) dy = Sleek.wrap(c, e.desc(), x + 8, dy, w - 16, 0.66f, 0xFFC8BEA6) + 4;
        else {
            Ui.text(c, Text.literal("Shown with your name for everyone to see."), x + 8, dy, 0.6f, 0xFFC8BEA6, false);
            dy += 12;
        }
        // Price and buying.
        if (o.owned()) Sleek.chip(c, x + 8, dy + 2, "IN YOUR COLLECTION", 0xFF5BD35B);
        else {
            Sleek.price(c, x + 8, dy + 2, o.price(), o.was(), 0.95f);
            if (o.was() > o.price()) Sleek.chip(c, x + w - 44, dy + 2, "-25%", 0xFF5BD35B);
        }
        dy += 16;
        int bw = w - 16;
        if (!o.owned()) {
            boolean can = ClientState.gold >= o.price();
            Sleek.button(c, hits, x + 8, dy, bw, 20, can ? "Buy" : "Not enough Gold", true, can, () -> ClientPlayNetworking.send(new Net.StoreAction("buy", o.id())));
            dy += 24;
        }
        int half = (bw - 4) / 2;
        if (e != null) {
            String slot = slotOf(o);
            Sleek.button(c, hits, x + 8, dy, half, 18, "Try it on", false, true, () -> client.setScreen(new CosmeticsScreen(slot, e.id())));
            Sleek.button(c, hits, x + 12 + half, dy, half, 18, gifting ? "Cancel gift" : "Gift", false, true, () -> gifting = !gifting);
        } else Sleek.button(c, hits, x + 8, dy, bw, 18, gifting ? "Cancel gift" : "Gift to a friend", false, true, () -> gifting = !gifting);
        dy += 22;
        if (gifting) {
            Ui.text(c, Text.literal("Send it to (they must be online):"), x + 8, dy, 0.55f, 0xFF9A958A, false);
            giftTo.setX(x + 8);
            giftTo.setY(dy + 9);
            giftTo.setWidth(bw - 64);
            giftTo.render(c, Sleek.mx, Sleek.my, 0);
            boolean ok = !giftTo.getText().isBlank() && ClientState.gold >= o.price();
            Sleek.button(c, hits, x + w - 62, dy + 9, 54, 16, "Send", true, ok,
                () -> {
                    ClientPlayNetworking.send(new Net.StoreAction("gift", o.id() + "|" + giftTo.getText().trim()));
                    gifting = false;
                });
            // Who's on: click a name.
            float nx = x + 8;
            int ny = dy + 29;
            var nh = client.getNetworkHandler();
            if (nh != null) for (var entry : nh.getPlayerList()) {
                String name = entry.getProfile().getName();
                if (client.player != null && name.equals(client.player.getGameProfile().getName())) continue;
                int nw = (int) (Ui.font().getWidth(name) * 0.55f) + 8;
                if (nx + nw > x + w - 8) {
                    nx = x + 8;
                    ny += 12;
                    if (ny > y + h - 60) break;
                }
                boolean hov = Sleek.over(nx, ny, nw, 10);
                c.fill((int) nx, ny, (int) nx + nw, ny + 10, hov ? 0x30FFFFFF : 0x14FFFFFF);
                Ui.text(c, Text.literal(name), nx + 4, ny + 2, 0.55f, Ui.CREAM, false);
                hits.add((int) nx, ny, nw, 10, () -> giftTo.setText(name));
                nx += nw + 3;
            }
            dy = ny + 16;
        }
        // More like it.
        if (e != null && dy < y + h - 70) {
            Ui.text(c, Ui.heading("More like this"), x + 8, dy + 2, 0.7f, 0xFFB8B0A0, false);
            int sy = dy + 14, sw = (bw - 8) / 3, n = 0;
            for (Net.StoreOffer s : view.offers()) {
                if (n >= 3 || s.owned() || s.id().equals(o.id()) || !cosmetic(s) || !slotOf(s).equals(slotOf(o))) continue;
                int sx = x + 8 + n * (sw + 4);
                int sh = Math.min(64, y + h - 6 - sy);
                if (sh < 40) break;
                Sleek.tile(c, sx, sy, sw, sh, colorOf(s), Sleek.over(sx, sy, sw, sh), false, false);
                picture(c, s, sx + 1, sy + 3, sw - 2, sh - 16);
                String p = String.format(java.util.Locale.ROOT, "%,d", s.price());
                Sleek.price(c, sx + sw / 2f - Ui.font().getWidth(p) * 0.28f - 5, sy + sh - 11, s.price(), 0, 0.55f);
                hits.add(sx, sy, sw, sh, () -> selected = s.id());
                n++;
            }
        }
    }

    // ------------------------------------------------------------------ crates

    private void crates(DrawContext c, int x, int y, int w) {
        Ui.text(c, Ui.heading("Crates"), x, y + 2, 0.85f, Ui.CREAM, false);
        Ui.text(c, Text.literal("Pay in Marks or Gold  ·  hover one for what's inside and the odds"), x + 60, y + 4, 0.58f, 0xFF9A958A, false);
        y += 18;
        int gap = 8, cols = Math.max(2, Math.min(3, (w + gap) / (200 + gap))), cw = (w - gap * (cols - 1)) / cols, ch = 96;
        Net.StoreCrate hover = null;
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        for (int i = 0; i < view.crates().size(); i++) {
            Net.StoreCrate k = view.crates().get(i);
            int cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
            int tone = crateTone(k.id());
            boolean hov = Sleek.over(cx, cy, cw, ch - 26);
            Sleek.tile(c, cx, cy, cw, ch, tone, hov, false, false);
            var ms = c.getMatrices();
            ms.push();
            ms.translate(cx + 30, cy + 34, 0);
            float bob = (float) Math.sin(t * 2 + i) * 1.5f;
            ms.translate(0, bob, 0);
            ms.scale(2.2f, 2.2f, 1);
            c.drawItem(new ItemStack(net.minecraft.item.Items.CHEST), -8, -8);
            ms.pop();
            Ui.text(c, Ui.heading(fit(k.title(), cw - 64, 0.85f)), cx + 58, cy + 10, 0.85f, tone, false);
            Sleek.wrap(c, k.desc(), cx + 58, cy + 22, cw - 66, 0.58f, 0xFF9A958A);
            int bw = (cw - 20) / 2;
            boolean m = ClientState.marks >= k.marks(), g = ClientState.gold >= k.gold();
            Sleek.button(c, hits, cx + 8, cy + ch - 24, bw, 17, fmt(k.marks()) + " Marks", false, m, () -> ClientPlayNetworking.send(new Net.StoreAction("crate", k.id())));
            Sleek.button(c, hits, cx + 12 + bw, cy + ch - 24, bw, 17, k.gold() + " Gold", true, g, () -> ClientPlayNetworking.send(new Net.StoreAction("crate_gold", k.id())));
            if (hov) hover = k;
        }
        if (hover != null) preview(c, hover, Sleek.mx, Sleek.my);
    }

    private static String fmt(long n) {
        return n >= 1000 && n % 1000 == 0 ? (n / 1000) + "k" : n >= 1000 ? String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    /** What's inside a crate: every roll with its rarity colour, icon, chance, and colour swatches of the cosmetics. */
    private void preview(DrawContext c, Net.StoreCrate k, int mx, int my) {
        float t = (Util.getMeasuringTimeMs() % 100000) / 1000f;
        int pw = 250, rowH = 20;
        int rows = k.loot().size();
        int ph = 34 + rows * rowH + 8;
        int px = mx + 12 + pw > width - 4 ? mx - 12 - pw : mx + 12, py = Math.max(4, Math.min(my - 10, height - ph - 4));
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 400);
        c.fill(px, py, px + pw, py + ph, 0xF0100E0C);
        c.drawBorder(px, py, pw, ph, crateTone(k.id()));
        Ui.text(c, Ui.heading(k.title()), px + 8, py + 6, 0.95f, crateTone(k.id()), false);
        Ui.text(c, Text.literal("WHAT'S INSIDE  ·  odds per opening"), px + 8, py + 19, 0.55f, Ui.MUTED, false);
        int y = py + 30;
        for (String line : k.loot()) {
            String[] f = line.split("\\|", 4);
            if (f.length < 4) continue;
            int rar = Math.max(0, Math.min(5, Integer.parseInt(f[0])));
            int permille = Integer.parseInt(f[1]);
            int col = RARITY[rar];
            if (rar == 5) {
                // Mythic lines pulse.
                int pa = (int) (60 + 50 * Math.sin(t * 4));
                c.fill(px + 3, y - 1, px + pw - 3, y + rowH - 3, (pa << 24) | 0xE02A2A);
            }
            c.fill(px + 4, y, px + 7, y + rowH - 4, col);
            c.fill(px + 10, y, px + 26, y + 16, 0xFF1A1612);
            c.drawBorder(px + 10, y, 16, 16, col);
            c.drawItem(iconStack(f[2]), px + 10, y);
            Ui.text(c, Text.literal(fit(f[3], pw - 110, 0.62f)), px + 30, y + 1, 0.62f, col, false);
            Ui.text(c, Text.literal(RARITY_NAME[rar]), px + 30, y + 9, 0.5f, Ui.MUTED, false);
            // The chance, and a bar for it (a log scale, so the tiny ones still show).
            String pct = permille >= 10 ? (permille / 10.0 + "%").replace(".0%", "%") : (permille / 10.0) + "%";
            Ui.text(c, Text.literal(pct), px + pw - 8 - Ui.font().getWidth(pct) * 0.65f, y + 1, 0.65f, Ui.CREAM, false);
            int bw = (int) (60 * Math.log10(1 + permille) / 3);
            c.fill(px + pw - 70, y + 11, px + pw - 10, y + 13, 0x40FFFFFF);
            c.fill(px + pw - 70, y + 11, px + pw - 70 + bw, y + 13, col);
            // Cosmetic lines: a strip of what could come out, in their colours.
            if (f[2].endsWith("amethyst_shard") || f[2].endsWith("nether_star")) {
                boolean myth = rar == 5;
                int sx = px + 30 + (int) (Ui.font().getWidth(RARITY_NAME[rar]) * 0.5f) + 6, n = 0;
                for (CosmeticFx.Category cat : CosmeticFx.CATEGORIES) {
                    for (CosmeticFx.Entry e : cat.entries()) {
                        if (e.tier() == 0 || (e.tier() == 4) != myth || n >= 12) continue;
                        c.fill(sx + n * 6, y + 9, sx + n * 6 + 3, y + 13, 0xFF000000 | e.color());
                        c.fill(sx + n * 6 + 3, y + 9, sx + n * 6 + 5, y + 13, 0xFF000000 | e.color2());
                        n++;
                    }
                }
            }
            y += rowH;
        }
        c.getMatrices().pop();
    }

    private static int crateTone(String id) {
        return switch (id) {
            case "armory" -> 0xFF5A9AE0;
            case "wardrobe" -> 0xFFB06AE0;
            case "honors" -> 0xFFE0B96A;
            case "officer" -> 0xFF5BD35B;
            case "commander" -> 0xFFF2C14E;
            default -> 0xFFB0B0B0;
        };
    }

    private static ItemStack iconStack(String id) {
        Identifier ident = Identifier.tryParse(id);
        var item = ident == null ? null : Registries.ITEM.get(ident);
        return item == null ? new ItemStack(net.minecraft.item.Items.CHEST) : new ItemStack(item);
    }

    private static String fit(String s, int w, float scale) {
        var f = Ui.font();
        if (f.getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && f.getWidth(s + "...") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
