package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * People: everyone online and your friends, searchable. Pick someone to see who they are (level,
 * title, faction, where they are) and do something with them: party up, trade, duel, message,
 * send a gift or some Marks, or add them as a friend. Nothing else lives here.
 */
public final class SocialScreen extends Screen {
    private static final int ROW = 26;
    private final Sleek.Hits hits = new Sleek.Hits();
    private int tab, scroll;
    private java.util.UUID picked;
    private TextFieldWidget search;

    public SocialScreen() {
        super(Text.literal("Social"));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    public void refresh() { }

    @Override
    protected void init() {
        search = new TextFieldWidget(Ui.font(), 0, 0, 140, 16, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search players").withColor(0xFF7A766C));
        addSelectableChild(search);
    }

    private List<Net.SocialPlayer> rows() {
        List<Net.SocialPlayer> out = new ArrayList<>();
        Net.SocialView v = ClientState.social;
        if (v == null) return out;
        String q = search == null ? "" : search.getText().trim().toLowerCase(java.util.Locale.ROOT);
        for (Net.SocialPlayer p : v.players()) {
            if (tab == 0 && !p.online() || tab == 1 && !p.friend()) continue;
            if (!q.isEmpty() && !p.name().toLowerCase(java.util.Locale.ROOT).contains(q) && !p.account().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            out.add(p);
        }
        out.sort((a, b) -> a.online() != b.online() ? (a.online() ? -1 : 1) : a.friend() != b.friend() ? (a.friend() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (search.isMouseOver(mx, my)) {
            search.setFocused(true);
            setFocused(search);
            return search.mouseClicked(mx, my, button);
        }
        search.setFocused(false);
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search.isFocused() && key != 256) return search.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (search.isFocused()) return search.charTyped(ch, mods);
        return super.charTyped(ch, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v));
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xE0060807, 0xF0030404);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        int w = Math.min(620, width - 24), h = Math.min(340, height - 40), x = (width - w) / 2, y = (height - h) / 2;
        Ui.text(c, Ui.title("PEOPLE"), x, y - 2, 1.2f, Sleek.GOLD, false);
        Sleek.tabs(c, hits, x + 96, y - 2, new String[] {"Online", "Friends"}, tab, k -> {
            tab = k;
            scroll = 0;
        });
        Sleek.button(c, hits, x + w - 20, y - 4, 20, 18, "✕", false, true, this::close);
        search.setX(x + w - 170);
        search.setY(y - 3);
        search.setWidth(144);
        search.render(c, mouseX, mouseY, delta);
        int ly = y + 20, listW = (int) (w * 0.56f), lh = h - 20;
        Sleek.panel(c, x, ly, listW, lh);
        List<Net.SocialPlayer> rows = rows();
        int vis = Math.max(1, (lh - 8) / ROW);
        scroll = Math.max(0, Math.min(scroll, rows.size() - vis));
        if (rows.isEmpty()) Ui.text(c, Text.literal(tab == 1 ? "No friends yet: add people from Online" : ClientState.social == null ? "Loading…" : "Nobody here"),
            x + listW / 2f, ly + lh / 2f, 0.75f, 0xFF7A766C, true);
        Net.SocialPlayer sel = null;
        for (int i = scroll; i < rows.size() && i < scroll + vis; i++) {
            Net.SocialPlayer p = rows.get(i);
            int ry = ly + 4 + (i - scroll) * ROW;
            boolean on = p.uuid().equals(picked), hov = Sleek.over(x + 2, ry, listW - 4, ROW - 2);
            if (on) sel = p;
            c.fill(x + 2, ry, x + listW - 2, ry + ROW - 2, on ? 0x26E8C36F : hov ? 0x16FFFFFF : 0x00000000);
            if (on) c.fill(x + 2, ry, x + 4, ry + ROW - 2, Sleek.GOLD);
            Smooth.disc(c, x + 12, ry + ROW / 2f - 1, 2.5f, p.online() ? 0xFF5BD35B : 0xFF55524A);
            Ui.text(c, Text.literal(p.name()), x + 20, ry + 4, 0.8f, p.online() ? Ui.CREAM : 0xFF8A857A, false);
            String sub = (p.level() > 0 ? "Lv " + p.level() + "  ·  " : "") + p.where();
            Ui.text(c, Text.literal(sub), x + 20, ry + 14, 0.56f, 0xFF8A857A, false);
            float bx = x + listW - 8;
            if (p.party()) bx -= Sleek.chip(c, bx - (Ui.font().getWidth("PARTY") * 0.55f + 8), ry + 8, "PARTY", 0xFF5BD35B) + 4;
            if (p.friend()) Sleek.chip(c, bx - (Ui.font().getWidth("FRIEND") * 0.55f + 8), ry + 8, "FRIEND", Sleek.GOLD);
            java.util.UUID id = p.uuid();
            hits.add(x + 2, ry, listW - 4, ROW - 2, () -> picked = id);
        }
        // The one picked, and what you can do.
        int dx = x + listW + 8, dw = w - listW - 8;
        Sleek.panel(c, dx, ly, dw, lh);
        if (sel == null) {
            Ui.text(c, Text.literal("Pick someone"), dx + dw / 2f, ly + lh / 2f, 0.75f, 0xFF7A766C, true);
            return;
        }
        Net.SocialPlayer p = sel;
        c.fill(dx, ly, dx + dw, ly + 2, p.online() ? 0xFF5BD35B : 0xFF55524A);
        Ui.text(c, Ui.heading(p.name()), dx + 10, ly + 10, 1.05f, Ui.CREAM, false);
        int iy = ly + 24;
        if (!p.title().isEmpty()) {
            Ui.text(c, Text.literal(p.title()), dx + 10, iy, 0.66f, 0xFF000000 | p.titleColor(), false);
            iy += 10;
        }
        String info = (p.level() > 0 ? "Level " + p.level() : "") + (p.faction().isEmpty() ? "" : "  ·  " + p.faction());
        if (!info.isBlank()) Ui.text(c, Text.literal(info.replaceFirst("^  ·  ", "")), dx + 10, iy, 0.62f, 0xFFB8B0A0, false);
        iy += 10;
        Ui.text(c, Text.literal(p.online() ? p.where() : "Offline"), dx + 10, iy, 0.62f, 0xFF8A857A, false);
        iy += 18;
        int bw = (dw - 24) / 2;
        boolean me = client.player != null && p.account().equals(client.player.getGameProfile().getName());
        if (p.online() && !me) {
            Sleek.button(c, hits, dx + 8, iy, bw, 18, p.party() ? "In your party" : "Invite to party", !p.party(), !p.party(),
                () -> ClientPlayNetworking.send(new Net.SocialAction("invite", p.uuid())));
            Sleek.button(c, hits, dx + 16 + bw, iy, bw, 18, "Trade", true, true, () -> {
                ClientPlayNetworking.send(new Net.TradeAction("request", p.uuid(), ""));
                client.setScreen(new TradeScreen());
            });
            iy += 22;
            Sleek.button(c, hits, dx + 8, iy, bw, 18, "Duel", false, true, () -> {
                client.player.networkHandler.sendChatCommand("duel " + p.account());
                close();
            });
            Sleek.button(c, hits, dx + 16 + bw, iy, bw, 18, "Message", false, true, () -> client.setScreen(new ChatScreen("/msg " + p.account() + " ")));
            iy += 22;
            Sleek.button(c, hits, dx + 8, iy, bw, 18, "Send a gift", false, true, () -> {
                StoreScreen.giftFor = p.account();
                client.setScreen(new StoreScreen(this));
            });
            Sleek.button(c, hits, dx + 16 + bw, iy, bw, 18, "Send Marks", false, true, () -> client.setScreen(new ChatScreen("/pay " + p.account() + " ")));
            iy += 22;
        }
        if (!me) Sleek.button(c, hits, dx + 8, iy, dw - 16, 18, p.friend() ? "Remove friend" : "Add friend", false, true,
            () -> ClientPlayNetworking.send(new Net.SocialAction(p.friend() ? "friend_remove" : "friend_add", p.uuid())));
    }
}
