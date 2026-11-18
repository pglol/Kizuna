package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The cosmetics showroom. The middle of the screen is the world itself: a camera circles your
 * character (drag to turn it) and whatever you pick is shown for real, worn on you or played
 * around you exactly as everyone will see it. Categories down the left with what you wear in
 * each and how rare it is; the library on the right; what you're looking at, its price if you
 * don't have it, and the way to the store under the stage.
 */
public final class CosmeticsScreen extends Screen {
    private static final int TILE_H = 78, GAP = 6;
    private final Sleek.Hits hits = new Sleek.Hits();
    private String slot = "outfit";
    /** What's on show (picked in the library; starts as what you wear). */
    private String showing;
    private int scroll;
    private float spin = 0, spinVel;
    private boolean dragging, hudWas;
    private long lastFrame;
    private int railW, libX, libW, top, bottom;

    public CosmeticsScreen() {
        super(Text.literal("Cosmetics"));
    }

    /** Opened on one category with one piece on show (from the store's "try it on"). */
    public CosmeticsScreen(String slot, String id) {
        this();
        this.slot = slot;
        this.showing = id;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() { }

    /** What you wear in a slot (the free default if nothing is chosen). */
    static String selected(String slot) {
        String s = ClientState.worn.get(slot);
        if (s != null && !s.isEmpty()) return s;
        return CosmeticFx.category(slot).entries().get(0).id();
    }

    private static boolean owned(CosmeticFx.Entry e, int index) {
        // A 1 of 1 is only yours if it was given to you.
        if (e.tier() >= 5) return ClientState.cosmetics.contains(e.id());
        return ClientState.cosmeticsAll || ClientState.cosmetics.contains(e.id()) || index == 0;
    }

    private static boolean op() {
        var p = net.minecraft.client.MinecraftClient.getInstance().player;
        return p != null && p.hasPermissionLevel(2);
    }

    /** What this player may see in a category: 1 of 1s stay secret, except to their owner and operators. */
    private static java.util.List<CosmeticFx.Entry> visible(CosmeticFx.Category cat) {
        java.util.List<CosmeticFx.Entry> out = new java.util.ArrayList<>();
        boolean op = op();
        for (CosmeticFx.Entry e : cat.entries()) if (e.tier() < 5 || op || ClientState.cosmetics.contains(e.id())) out.add(e);
        return out;
    }

    /** The colours a pressure can burn. */
    private static final int[] TINTS = {0x6AC8FF, 0x2A6AFF, 0x8A5AFF, 0xC84AFF, 0xFF4AA8, 0xFF2A3A, 0xFF7A2A, 0xFFD24A, 0xB8FF4A, 0x3AFF8A,
        0x2AE8D8, 0xFFFFFF, 0x8A0A1A, 0x101018};

    private String shown() {
        return showing != null ? showing : selected(slot);
    }

    @Override
    protected void init() {
        railW = Math.min(170, width / 6 + 40);
        libW = Math.min(372, (int) (width * 0.34f));
        libX = width - libW - 12;
        top = 44;
        bottom = height - 12;
        hudWas = client.options.hudHidden;
        client.options.hudHidden = true;
        if (StoreScreen.view == null) ClientPlayNetworking.send(new Net.StoreAction("open", ""));
    }

    @Override
    public void removed() {
        client.options.hudHidden = hudWas;
        CosmeticFx.previewSlot = null;
        CosmeticFx.previewId = null;
        Showcase.clear();
    }

    @Override
    public void tick() {
        // What's on show is worn for real while you look at it, and its effect plays around you.
        CosmeticFx.previewSlot = slot;
        CosmeticFx.previewId = shown();
        Showcase.tick(slot, shown());
    }

    private int cols() {
        return Math.max(2, Math.min(3, (libW - 16 + GAP) / (104 + GAP)));
    }

    private int tileW() {
        return (libW - 16 - GAP * (cols() - 1)) / cols();
    }

    private int gridTop() {
        return top + 34;
    }

    private int visibleRows() {
        return Math.max(1, (bottom - 8 - gridTop()) / (TILE_H + GAP));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        if (button == 0 && mx > railW + 24 && mx < libX - 12 && my > top && my < bottom - 70) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            spin += (float) dx * 1.4f;
            spinVel = (float) dx * 1.4f;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (mx > libX) {
            CosmeticFx.Category cat = CosmeticFx.category(slot);
            int rows = (visible(cat).size() + cols() - 1) / cols();
            scroll = Math.max(0, Math.min(rows - visibleRows(), scroll - (int) Math.signum(v)));
        }
        return true;
    }

    /** The camera while the showroom is open. */
    public com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td) {
        float stageMid = (railW + 24 + libX - 12) / 2f;
        return Showcase.camera(td, spin, Showcase.distance(slot), stageMid, width);
    }

    /** Where to buy a piece, and for how much: the store's offer, or null if it isn't sold. */
    static Net.StoreOffer offer(String id) {
        if (StoreScreen.view == null) return null;
        for (Net.StoreOffer o : StoreScreen.view.offers()) if (o.id().equals("cosmetic:" + id)) return o;
        return null;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        // No backdrop: the stage is the world. Only the edges darken, under the panels.
        c.fillGradient(0, 0, width, 40, 0xC0000000, 0x00000000);
        c.fillGradient(0, height - 90, width, height, 0x00000000, 0xC0000000);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (!dragging) {
            spinVel *= (float) Math.exp(-dt * 3);
            spin += spinVel + dt * 9;
        }
        renderBackground(c, mouseX, mouseY, delta);
        CosmeticFx.Category cat = CosmeticFx.category(slot);

        // Header.
        Ui.text(c, Ui.title("COSMETICS"), 14, 14, 1.2f, Sleek.GOLD, false);
        String purse = String.format(java.util.Locale.ROOT, "%,d", ClientState.gold);
        float pw = Ui.font().getWidth(purse) * 0.85f;
        Sleek.coin(c, width - 52 - pw, 19, 4);
        Ui.text(c, Text.literal(purse), width - 46 - pw, 15, 0.85f, Sleek.GOLD, false);
        Sleek.button(c, hits, width - 32, 10, 20, 18, "✕", false, true, this::close);
        Sleek.button(c, hits, width - 120 - (int) pw, 10, 60, 18, "Store", false, true, () -> client.setScreen(new StoreScreen(this)));

        // The rail: categories, what you wear in each, and its rarity.
        Sleek.panel(c, 12, top, railW, bottom - top);
        int n = CosmeticFx.CATEGORIES.size();
        int rh = Math.max(22, Math.min(32, (bottom - top - 8) / n));
        for (int k = 0; k < n; k++) {
            CosmeticFx.Category cc = CosmeticFx.CATEGORIES.get(k);
            int y = top + 4 + k * rh;
            boolean sel = cc.slot().equals(slot), hov = Sleek.over(13, y, railW - 2, rh);
            if (sel) {
                c.fill(13, y, 12 + railW - 1, y + rh, 0x22E8C36F);
                c.fill(13, y, 15, y + rh, Sleek.GOLD);
            } else if (hov) c.fill(13, y, 12 + railW - 1, y + rh, 0x12FFFFFF);
            CosmeticFx.Entry worn = CosmeticFx.entry(selected(cc.slot()));
            Ui.text(c, Ui.heading(cc.title()), 22, y + rh / 2f - 7.5f, 0.72f, sel ? Sleek.GOLD : hov ? Ui.CREAM : 0xFFB8B0A0, false);
            if (worn != null) {
                int tc = Sleek.TIER[Math.min(5, worn.tier())];
                Smooth.disc(c, 24, y + rh / 2f + 5.5f, 2, tc);
                Ui.text(c, Text.literal(fit(worn.title(), railW - 40, 0.58f)), 29, y + rh / 2f + 3, 0.58f, tc, false);
            }
            int ks = k;
            hits.add(13, y, railW - 2, rh, () -> {
                slot = CosmeticFx.CATEGORIES.get(ks).slot();
                showing = null;
                scroll = 0;
                spin = slot.equals("back") ? 180 : slot.equals("trail") || slot.equals("kill") ? 70 : 0;
            });
        }

        // The library.
        Sleek.panel(c, libX, top, libW, bottom - top);
        int have = 0;
        for (int k = 0; k < visible(cat).size(); k++) if (owned(visible(cat).get(k), k)) have++;
        Ui.text(c, Ui.heading(cat.title()), libX + 8, top + 8, 0.9f, Ui.CREAM, false);
        String cnt = have + " / " + visible(cat).size() + " owned";
        Ui.text(c, Text.literal(cnt), libX + libW - 8 - Ui.font().getWidth(cnt) * 0.6f, top + 10, 0.6f, 0xFF9A958A, false);
        Ui.text(c, Text.literal(fit(cat.blurb(), libW - 16, 0.56f)), libX + 8, top + 20, 0.56f, 0xFF8A857A, false);
        int cols = cols(), tw = tileW();
        for (int i = scroll * cols; i < visible(cat).size() && i < (scroll + visibleRows()) * cols; i++) {
            CosmeticFx.Entry e = visible(cat).get(i);
            int r = i / cols - scroll, col = i % cols;
            int x = libX + 8 + col * (tw + GAP), y = gridTop() + r * (TILE_H + GAP);
            boolean own = owned(e, i), worn = e.id().equals(selected(slot)), on = e.id().equals(shown());
            int tc = Sleek.TIER[Math.min(5, e.tier())];
            Sleek.tile(c, x, y, tw, TILE_H, tc, Sleek.over(x, y, tw, TILE_H), on, !own);
            Showcase.thumb(c, x + 1, y + 3, tw - 2, 44, slot, e, !own);
            if (!own) c.fill(x + 1, y + 3, x + tw - 1, y + 47, 0x66000000);
            Ui.text(c, Text.literal(fit(e.title(), tw - 10, 0.66f)), x + 5, y + 50, 0.66f, own ? Ui.CREAM : 0xFF9A958A, false);
            Sleek.chip(c, x + 5, y + 61, Sleek.TIER_NAME[Math.min(5, e.tier())].toUpperCase(), tc);
            if (worn) Sleek.chip(c, x + tw - 5 - (Ui.font().getWidth("WORN") * 0.55f + 8), y + 61, "WORN", Sleek.GOLD);
            else if (!own) {
                Net.StoreOffer o = offer(e.id());
                if (o != null) Sleek.price(c, x + tw - 6 - Ui.font().getWidth(String.format(java.util.Locale.ROOT, "%,d", o.price())) * 0.55f - 10, y + 62, o.price(), 0, 0.55f);
                else Sleek.chip(c, x + tw - 5 - (Ui.font().getWidth("LOCKED") * 0.55f + 8), y + 61, "LOCKED", 0xFF9A958A);
            }
            String id = e.id();
            hits.add(x, y, tw, TILE_H, () -> showing = id);
        }
        int rows = (visible(cat).size() + cols - 1) / cols;
        if (rows > visibleRows()) {
            int gy = gridTop(), gh = visibleRows() * (TILE_H + GAP) - GAP;
            c.fill(libX + libW - 4, gy, libX + libW - 2, gy + gh, 0x20FFFFFF);
            int bh = Math.max(14, gh * visibleRows() / rows), by = gy + (gh - bh) * scroll / Math.max(1, rows - visibleRows());
            c.fill(libX + libW - 4, by, libX + libW - 2, by + bh, Sleek.GOLD);
        }

        // Under the stage: what's on show, and what you can do with it.
        CosmeticFx.Entry show = CosmeticFx.entry(shown());
        int sx0 = railW + 24, sx1 = libX - 12, bw = sx1 - sx0, by = bottom - 62;
        if (show != null) {
            int idx = visible(cat).indexOf(show);
            boolean own = owned(show, Math.max(0, idx)), worn = show.id().equals(selected(slot));
            int tc = Sleek.TIER[Math.min(5, show.tier())];
            c.fill(sx0, by, sx1, bottom, 0xD00B0E0D);
            c.fill(sx0, by, sx1, by + 2, tc);
            c.drawBorder(sx0, by, bw, bottom - by, Sleek.LINE);
            Ui.text(c, Ui.heading(show.title()), sx0 + 10, by + 9, 1.05f, 0xFF000000 | show.color(), false);
            float nw = Ui.font().getWidth(show.title()) * 1.05f;
            Sleek.chip(c, sx0 + 16 + nw, by + 10, Sleek.TIER_NAME[Math.min(5, show.tier())].toUpperCase(), tc);
            Ui.text(c, Text.literal(fit(show.desc(), bw - 200, 0.64f)), sx0 + 10, by + 25, 0.64f, 0xFFC8BEA6, false);
            int bx = sx1 - 10;
            if (worn) {
                Sleek.chip(c, bx - 52, by + 12, "EQUIPPED", Sleek.GOLD);
            } else if (own) {
                Sleek.button(c, hits, bx - 96, by + 9, 96, 20, "Equip", true, true, () -> ClientPlayNetworking.send(new Net.SelectCosmetic(show.id())));
            } else {
                Net.StoreOffer o = offer(show.id());
                if (o != null) {
                    Sleek.price(c, bx - 200, by + 14, o.price(), o.was(), 0.85f);
                    Sleek.button(c, hits, bx - 96, by + 9, 96, 20, "Buy in store", true, true,
                        () -> client.setScreen(new StoreScreen(this, "cosmetic:" + show.id())));
                } else if (show.tier() >= 5) {
                    String where = "1 of 1: given, never sold";
                    Ui.text(c, Text.literal(where), bx - Ui.font().getWidth(where) * 0.62f, by + 15, 0.62f, 0xFFF4F0FF, false);
                } else {
                    String where = com.pglol.aotrpg.Store.MYTHIC_COSMETICS.contains(show.id()) ? "Mythic: found only in crates"
                        : slot.equals("outfit") || slot.equals("dye") ? "Found in the world and in crates" : "From crates and the battle pass";
                    Ui.text(c, Text.literal(where), bx - Ui.font().getWidth(where) * 0.62f, by + 15, 0.62f, 0xFF9A958A, false);
                    Sleek.button(c, hits, bx - 96, by + 32, 96, 18, "See crates", false, true, () -> client.setScreen(new StoreScreen(this, "crates")));
                }
            }
            Ui.text(c, Text.literal(own ? "Previewing on you  ·  drag to turn" : "Try it on  ·  drag to turn"), sx0 + 10, by + 40, 0.55f, 0xFF7A766C, false);
            // Pieces that take a colour: pick it here (it changes the whole set).
            if (own && OneOfOne.sp(show.id()) && client.player != null) {
                int cur = OneOfOne.tint(client.player.getUuid()), sw = 14, gap = 4;
                int total = TINTS.length * (sw + gap) - gap, sx = sx0 + (bw - total) / 2, sy = by - 26;
                c.fill(sx - 8, sy - 12, sx + total + 8, sy + sw + 4, 0xC00B0E0D);
                Ui.text(c, Text.literal("YOUR COLOUR"), sx0 + bw / 2f, sy - 9, 0.5f, 0xFFB8B0A0, true);
                for (int i = 0; i < TINTS.length; i++) {
                    int x = sx + i * (sw + gap), col = TINTS[i];
                    boolean sel = col == cur, hov = Sleek.over(x, sy, sw, sw);
                    Smooth.disc(c, x + sw / 2f, sy + sw / 2f, sw / 2f + (sel ? 2.5f : hov ? 1.5f : 0), sel ? 0xFFFFFFFF : hov ? 0xA0FFFFFF : 0x50FFFFFF);
                    Smooth.disc(c, x + sw / 2f, sy + sw / 2f, sw / 2f - 1, 0xFF000000 | col);
                    String hex = String.format("%06X", col);
                    hits.add(x, sy, sw, sw, () -> ClientPlayNetworking.send(new Net.SelectCosmetic("tint:" + hex)));
                }
            }
        }
    }

    private static String fit(String s, int w, float scale) {
        if (Ui.font().getWidth(s) * scale <= w) return s;
        while (s.length() > 1 && Ui.font().getWidth(s + "…") * scale > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
