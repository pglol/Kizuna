package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Trading with another player. With no trade on: pick who to trade with. Asked: accept or not.
 * Under way: click items in your bag to put them up (click again to take them back), add
 * Marks, and press Ready when you're happy with both sides. Any change takes both Readys back.
 */
public final class TradeScreen extends Screen {
    public static Net.TradeView view;
    private final Sleek.Hits hits = new Sleek.Hits();
    private TextFieldWidget marks;
    private final List<Integer> offered = new ArrayList<>();
    private long lastMarks = -1;

    public TradeScreen() {
        super(Text.literal("Trade"));
    }

    public static void on(Net.TradeView v) {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        view = v.state() == 0 || v.state() == 4 ? null : v;
        if (mc.currentScreen instanceof TradeScreen s) {
            if (view == null) s.close();
            else s.sync();
        } else if (view != null && v.open() && mc.currentScreen == null) mc.setScreen(new TradeScreen());
    }

    private void sync() {
        if (view == null) return;
        offered.clear();
        offered.addAll(view.mySlots());
        if (marks != null && !marks.isFocused()) marks.setText(view.myMarks() > 0 ? String.valueOf(view.myMarks()) : "");
    }

    @Override
    protected void init() {
        marks = new TextFieldWidget(Ui.font(), 0, 0, 80, 16, Text.literal("Marks"));
        marks.setMaxLength(12);
        marks.setPlaceholder(Text.literal("0").withColor(0xFF7A766C));
        marks.setTextPredicate(t -> t.matches("\\d*"));
        addSelectableChild(marks);
        sync();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void sendOffer() {
        StringBuilder sb = new StringBuilder();
        var inv = client.player.getInventory();
        for (int s : offered) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s).append(':').append(inv.getStack(s).getCount());
        }
        sb.append(';').append(marks.getText().isBlank() ? "0" : marks.getText());
        ClientPlayNetworking.send(new Net.TradeAction("offer", null, sb.toString()));
    }

    @Override
    public void tick() {
        // Marks typed: offered once you stop typing for a moment.
        if (view != null && view.state() == 3 && marks != null) {
            long m = marks.getText().isBlank() ? 0 : Long.parseLong(marks.getText());
            if (m != view.myMarks() && m != lastMarks) {
                lastMarks = m;
                sendOffer();
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (marks.isMouseOver(mx, my)) {
            marks.setFocused(true);
            setFocused(marks);
            return marks.mouseClicked(mx, my, button);
        }
        marks.setFocused(false);
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (marks.isFocused() && key != 256) return marks.keyPressed(key, scan, mods);
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char ch, int mods) {
        if (marks.isFocused()) return marks.charTyped(ch, mods);
        return super.charTyped(ch, mods);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xD0060807, 0xE0030404);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        int w = Math.min(520, width - 24), h = Math.min(300, height - 40), x = (width - w) / 2, y = (height - h) / 2;
        Sleek.panel(c, x, y, w, h);
        c.fill(x, y, x + w, y + 2, Sleek.GOLD);
        Ui.text(c, Ui.title("TRADE"), x + 12, y + 10, 1.1f, Sleek.GOLD, false);
        Sleek.button(c, hits, x + w - 26, y + 8, 18, 16, "✕", false, true, () -> {
            if (view != null) ClientPlayNetworking.send(new Net.TradeAction("cancel", null, ""));
            close();
        });
        if (view == null) {
            picker(c, x, y, w, h);
            return;
        }
        if (view.state() == 1) {
            Ui.text(c, Ui.heading(view.partner() + " wants to trade with you"), x + w / 2f, y + h / 2f - 20, 1f, Ui.CREAM, true);
            Sleek.button(c, hits, x + w / 2 - 104, y + h / 2, 100, 20, "Accept", true, true, () -> ClientPlayNetworking.send(new Net.TradeAction("accept", null, "")));
            Sleek.button(c, hits, x + w / 2 + 4, y + h / 2, 100, 20, "No thanks", false, true, () -> ClientPlayNetworking.send(new Net.TradeAction("decline", null, "")));
            return;
        }
        if (view.state() == 2) {
            Ui.text(c, Text.literal("Waiting for " + view.partner() + " to accept…"), x + w / 2f, y + h / 2f - 14, 0.9f, Ui.CREAM, true);
            Sleek.button(c, hits, x + w / 2 - 50, y + h / 2 + 4, 100, 20, "Call it off", false, true, () -> ClientPlayNetworking.send(new Net.TradeAction("cancel", null, "")));
            return;
        }
        // Under way: two offers side by side, your bag underneath.
        Ui.text(c, Text.literal("with " + view.partner()), x + 70, y + 13, 0.75f, 0xFFB8B0A0, false);
        int ow = (w - 36) / 2, oy = y + 32, oh = 76;
        offerBox(c, x + 12, oy, ow, oh, "You give", view.mine(), view.myMarks(), view.myReady());
        offerBox(c, x + 24 + ow, oy, ow, oh, view.partner() + " gives", view.theirs(), view.theirMarks(), view.theirReady());
        Ui.text(c, Text.literal("Marks"), x + 12, oy + oh + 9, 0.62f, 0xFFB8B0A0, false);
        marks.setX(x + 46);
        marks.setY(oy + oh + 4);
        marks.render(c, mouseX, mouseY, delta);
        Sleek.button(c, hits, x + w - 12 - 120, oy + oh + 3, 120, 18, view.myReady() ? "Ready ✔ (undo)" : "Ready", !view.myReady(), true,
            () -> ClientPlayNetworking.send(new Net.TradeAction("ready", null, "")));
        // Your bag: click to put up or take back.
        int bx = x + 12, by = oy + oh + 28, cell = 20;
        Ui.text(c, Text.literal("Your bag  ·  click to put up or take back"), bx, by, 0.58f, 0xFF9A958A, false);
        by += 10;
        var inv = client.player.getInventory();
        ItemStack hover = null;
        for (int i = 0; i < 36; i++) {
            int slot = i < 27 ? i + 9 : i - 27;
            int cx = bx + (i % 9) * cell, cy = by + (i / 9) * cell + (i >= 27 ? 4 : 0);
            ItemStack st = inv.getStack(slot);
            boolean on = offered.contains(slot), hov = Sleek.over(cx, cy, cell - 2, cell - 2);
            c.fill(cx, cy, cx + cell - 2, cy + cell - 2, on ? 0x40E8C36F : hov ? 0x22FFFFFF : 0x14FFFFFF);
            c.drawBorder(cx, cy, cell - 2, cell - 2, on ? Sleek.GOLD : Sleek.LINE);
            if (!st.isEmpty()) {
                c.drawItem(st, cx + 1, cy + 1);
                c.drawStackOverlay(Ui.font(), st, cx + 1, cy + 1);
                if (hov) hover = st;
                hits.add(cx, cy, cell - 2, cell - 2, () -> {
                    if (offered.contains(slot)) offered.remove((Integer) slot);
                    else if (offered.size() < 9) offered.add(slot);
                    sendOffer();
                });
            }
        }
        if (hover != null) c.drawItemTooltip(Ui.font(), hover, mouseX, mouseY);
    }

    private void offerBox(DrawContext c, int x, int y, int w, int h, String title, List<ItemStack> items, long m, boolean ready) {
        c.fill(x, y, x + w, y + h, 0x30000000);
        c.drawBorder(x, y, w, h, ready ? 0xFF5BD35B : Sleek.LINE);
        Ui.text(c, Ui.heading(title), x + 6, y + 5, 0.72f, Ui.CREAM, false);
        if (ready) Sleek.chip(c, x + w - 48, y + 4, "READY", 0xFF5BD35B);
        int cell = 20;
        for (int i = 0; i < 9; i++) {
            int cx = x + 6 + (i % 9) * cell, cy = y + 18;
            if (cx + cell > x + w) break;
            c.fill(cx, cy, cx + cell - 2, cy + cell - 2, 0x18FFFFFF);
            if (i < items.size()) {
                c.drawItem(items.get(i), cx + 1, cy + 1);
                c.drawStackOverlay(Ui.font(), items.get(i), cx + 1, cy + 1);
                if (Sleek.over(cx, cy, cell - 2, cell - 2)) c.drawItemTooltip(Ui.font(), items.get(i), Sleek.mx, Sleek.my);
            }
        }
        if (m > 0) Ui.text(c, Text.literal(String.format(java.util.Locale.ROOT, "+ %,d Marks", m)), x + 6, y + h - 14, 0.7f, Sleek.GOLD, false);
        else if (items.isEmpty()) Ui.text(c, Text.literal("Nothing yet"), x + 6, y + h - 14, 0.62f, 0xFF7A766C, false);
    }

    /** No trade on: everyone online, click one to ask. */
    private void picker(DrawContext c, int x, int y, int w, int h) {
        Ui.text(c, Text.literal("Who do you want to trade with?"), x + 12, y + 32, 0.75f, 0xFFB8B0A0, false);
        var nh = client.getNetworkHandler();
        if (nh == null) return;
        int rx = x + 12, ry = y + 46, rw = (w - 30) / 2, rh = 20, i = 0;
        for (var e : nh.getPlayerList()) {
            if (e.getProfile().getId().equals(client.player.getUuid())) continue;
            int cx = rx + (i % 2) * (rw + 6), cy = ry + (i / 2) * (rh + 4);
            if (cy + rh > y + h - 8) break;
            boolean hov = Sleek.over(cx, cy, rw, rh);
            c.fill(cx, cy, cx + rw, cy + rh, hov ? 0x26FFFFFF : 0x12FFFFFF);
            c.drawBorder(cx, cy, rw, rh, hov ? Sleek.LINE2 : Sleek.LINE);
            String name = e.getProfile().getName();
            Ui.text(c, Text.literal(name), cx + 8, cy + 6, 0.8f, Ui.CREAM, false);
            Ui.text(c, Text.literal("Ask ›"), cx + rw - 30, cy + 7, 0.62f, Sleek.GOLD, false);
            java.util.UUID id = e.getProfile().getId();
            hits.add(cx, cy, rw, rh, () -> ClientPlayNetworking.send(new Net.TradeAction("request", id, "")));
            i++;
        }
        if (i == 0) Ui.text(c, Text.literal("Nobody else is online right now."), x + w / 2f, y + h / 2f, 0.8f, 0xFF7A766C, true);
    }
}
