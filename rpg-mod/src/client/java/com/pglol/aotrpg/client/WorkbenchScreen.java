package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.Locale;

/** The balloon's workbench: field kit for the next run, a card per item, bought with Marks and Salvage. */
public final class WorkbenchScreen extends Screen {
    private static Net.BenchView v;
    private final long opened = Util.getMeasuringTimeMs();
    private int left, top, w, h;
    private static final int CARD_W = 96, CARD_H = 92;

    public WorkbenchScreen(Net.BenchView view) {
        super(Text.literal("Workbench"));
        v = view;
    }

    public static void update(Net.BenchView view) {
        v = view;
        if (MinecraftClient.getInstance().currentScreen instanceof WorkbenchScreen s) s.clearAndInit();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static ItemStack icon(String id) {
        var item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.CRAFTING_TABLE : item);
    }

    private final Sleek.Hits hits = new Sleek.Hits();

    @Override
    protected void init() {
        int cols = 3, rows = (v.recipes().size() + cols - 1) / cols;
        w = cols * (CARD_W + 8) + 24;
        h = rows * (CARD_H + 8) + 56;
        left = (width - w) / 2;
        top = Math.max(10, (height - h) / 2);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xC0050706, 0xE0030404);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        float k = Math.min(1, (Util.getMeasuringTimeMs() - opened) / 200f);
        int slide = (int) ((1 - k) * 10);
        Sleek.panel(c, left, top + slide, w, h);
        c.fill(left, top + slide, left + w, top + slide + 2, Sleek.GOLD);
        Ui.text(c, Ui.title("WORKBENCH"), left + 12, top + 10 + slide, 1.2f, Sleek.GOLD, false);
        Ui.text(c, Text.literal("Field kit for the next run"), left + 12, top + 27 + slide, 0.58f, 0xFF9A958A, false);
        String m = String.format(Locale.ROOT, "%,d Marks", v.marks()), sv = String.format(Locale.ROOT, "%,d Salvage", v.salvage());
        Ui.text(c, Text.literal(m), left + w - 12 - Ui.font().getWidth(m) * 0.75f, top + 11 + slide, 0.75f, Sleek.GOLD, false);
        Ui.text(c, Text.literal(sv), left + w - 12 - Ui.font().getWidth(sv) * 0.65f, top + 22 + slide, 0.65f, 0xFF9AD0FF, false);
        Sleek.button(c, hits, left + w - 20, top - 22, 20, 18, "\u2715", false, true, this::close);
        long t = Util.getMeasuringTimeMs();
        ItemStack tip = ItemStack.EMPTY;
        for (int i = 0; i < v.recipes().size(); i++) {
            Net.BenchRecipe r = v.recipes().get(i);
            int x = left + 12 + (i % 3) * (CARD_W + 8), y = top + 44 + (i / 3) * (CARD_H + 8) + slide;
            boolean hov = Sleek.over(x, y, CARD_W, CARD_H);
            Sleek.tile(c, x, y, CARD_W, CARD_H, r.salvage() > 0 ? 0xFF9AD0FF : Sleek.GOLD, hov, false, !r.ok());
            float bob = r.ok() ? (float) Math.sin(t * 0.004 + i) * 1.5f : 0;
            if (r.ok()) Smooth.glow(c, x + CARD_W / 2f, y + 24, 22, 0x30E8C36F);
            ItemStack st = icon(r.icon());
            Ui.item(c, st, x + CARD_W / 2 - 16, (int) (y + 8 + bob), 2f);
            if (hov) tip = st;
            if (r.count() > 1) Ui.text(c, Text.literal("x" + r.count()), x + CARD_W / 2f + 16, y + 30, 0.7f, Ui.CREAM, false);
            Ui.text(c, Text.literal(r.title()), x + CARD_W / 2f, y + 44, 0.72f, r.ok() ? Ui.CREAM : 0xFF7A766C, true);
            String price = (r.marks() > 0 ? String.format(Locale.ROOT, "%,d M", r.marks()) : "")
                + (r.marks() > 0 && r.salvage() > 0 ? " + " : "") + (r.salvage() > 0 ? r.salvage() + " S" : "");
            Ui.text(c, Text.literal(price), x + CARD_W / 2f, y + 55, 0.62f, r.salvage() > 0 ? 0xFF9AD0FF : Sleek.GOLD, true);
            Sleek.button(c, hits, x + 8, y + CARD_H - 22, CARD_W - 16, 16, r.ok() ? "Craft" : "Can't afford", true, r.ok(),
                () -> ClientPlayNetworking.send(new Net.ExtractionAction("bench", r.id())));
        }
        if (!tip.isEmpty()) c.drawItemTooltip(Ui.font(), tip, mouseX, mouseY);
    }
}
