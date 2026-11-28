package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PotionItem;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.pglol.kata.Fighting;
import net.pglol.kata.KataNet;
import net.pglol.kata.Kit;

import java.util.ArrayList;
import java.util.List;

/**
 * The meditation menu (E). The world keeps running; the fighter settles into meditation where they
 * stand and the camera eases round to frame them on the left. On the right, a spine of round icons
 * (Equipment, Inventory, Combat deck, System) and a dark slate column for the chosen one; far right, the
 * details of whatever's picked (its lore, blunt and cut protection, weight, what it costs in mobility).
 * Moving, jumping or a click out on the world ends it.
 */
public class MeditationScreen extends Screen {
    private static final int AMBER = 0xFFE0A64A, AMBER_DIM = 0x66E0A64A, INK = 0xD8121416, INK_SOFT = 0xA0121416, LINE = 0x30FFFFFF;
    private static final int TEXT = 0xFFE8E2D6, SOFT = 0xFF9C978C, FAINT = 0xFF5E5A54;
    private static final String[] TABS = {"Equipment", "Inventory", "Combat Deck", "System"};
    private static int tab;

    private int selDoll = -1, selSlot = -1;
    private final List<Hot> hots = new ArrayList<>();
    private long openedAt;

    private record Hot(int x, int y, int w, int h, Runnable run) { }

    public MeditationScreen() {
        super(Text.literal("Meditation"));
    }

    @Override
    protected void init() {
        if (openedAt == 0) {
            openedAt = System.nanoTime();
            if (client != null && client.player != null) Meditate.open(client.player);
            Fight.send(KataNet.MEDITATE, 1, 0);
        }
    }

    @Override
    public void removed() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Meditate.close(mc.player);
        Fight.send(KataNet.MEDITATE, 0, 0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        var o = MinecraftClient.getInstance().options;
        if (o.forwardKey.matchesKey(key, scan) || o.backKey.matchesKey(key, scan) || o.leftKey.matchesKey(key, scan) || o.rightKey.matchesKey(key, scan)
            || o.jumpKey.matchesKey(key, scan) || o.inventoryKey.matchesKey(key, scan)) {
            close();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Hot h : hots) {
            if (mx >= h.x && mx < h.x + h.w && my >= h.y && my < h.y + h.h) {
                h.run.run();
                return true;
            }
        }
        // A click out on the world (where the fighter sits): that's an attack, and it ends the meditation.
        if (mx < spineX() - 20 && button == 0) {
            close();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        // No grey wash over the world: it stays live and clear on the left (where the fighter is), and
        // darkens towards the right behind the panels.
        int w = width, h = height, from = (int) (w * 0.38f);
        int steps = 40;
        for (int i = 0; i < steps; i++) {
            int x0 = from + (w - from) * i / steps, x1 = from + (w - from) * (i + 1) / steps;
            float k = (i + 0.5f) / steps;
            c.fill(x0, 0, x1, h, ((int) (0xB0 * k * k) << 24));
        }
        c.fillGradient(0, 0, w, 40, 0x60000000, 0x00000000);
        c.fillGradient(0, h - 40, w, h, 0x00000000, 0x70000000);
    }

    private int spineX() {
        return (int) (width * 0.47f);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        hots.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        float in = Math.min(1, (System.nanoTime() - openedAt) / 1e9f / 0.35f);
        c.getMatrices().push();
        c.getMatrices().translate((1 - in) * 24, 0, 0);
        sheet(c, mc);
        spine(c, mx, my);
        int colX = spineX() + 26, colW = Math.min(230, (int) (width * 0.3f)), detX = colX + colW + 8, detW = Math.max(110, width - detX - 12);
        int top = 40, colH = height - 80;
        panel(c, colX, top, colW, colH, TABS[tab]);
        switch (tab) {
            case 0 -> equipment(c, mc, colX, top + 24, colW, mx, my);
            case 1 -> inventory(c, mc, colX, top + 24, colW, mx, my);
            case 2 -> deck(c, mc, colX, top + 24, colW);
            default -> system(c, mc, colX, top + 24, colW, mx, my);
        }
        ItemStack shown = tab == 0 && selDoll >= 0 ? KitClient.doll(mc.player, selDoll) : tab == 1 && selSlot >= 0 ? mc.player.getInventory().getStack(selSlot) : ItemStack.EMPTY;
        if (detW >= 100 && (tab == 0 || tab == 1)) details(c, mc, shown, detX, top, detW, colH, mx, my);
        c.getMatrices().pop();
        // The hint along the bottom.
        String hint = "Move to rise   ·   Esc to close";
        c.drawText(textRenderer, hint, width / 2 - textRenderer.getWidth(hint) / 2, height - 16, FAINT, false);
    }

    // ---- the appraisal sheet, top left over the world ----

    private void sheet(DrawContext c, MinecraftClient mc) {
        var p = mc.player;
        int x = 18, y = 22;
        Ui.text(c, textRenderer, "M E D I T A T I O N", x, y, 1.0f, AMBER);
        int st = Meditate.state(p.getId());
        String state = st == 2 ? "Untouchable" : "Settling…";
        Ui.text(c, textRenderer, state, x, y + 13, 0.75f, st == 2 ? TEXT : SOFT);
        c.fill(x, y + 24, x + 120, y + 25, LINE);
        int yy = y + 32;
        stat(c, x, yy, "Style", Deck.style().name());
        stat(c, x, yy + 12, "Health", String.format("%.0f / %.0f", p.getHealth(), p.getMaxHealth()));
        float blunt = p.getArmor(), cut = (float) p.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
        float wt = weight(mc);
        stat(c, x, yy + 24, "Blunt", String.format("%.1f", blunt));
        stat(c, x, yy + 36, "Cut", String.format("%.1f", cut));
        stat(c, x, yy + 48, "Weight", String.format("%.1f", wt));
        stat(c, x, yy + 60, "Mobility", String.format("%d", mobility(wt)));
    }

    private void stat(DrawContext c, int x, int y, String k, String v) {
        Ui.text(c, textRenderer, k, x, y, 0.75f, SOFT);
        Ui.text(c, textRenderer, v, x + 120 - Ui.width(textRenderer, v, 0.75f), y, 0.75f, TEXT);
    }

    private static float weight(MinecraftClient mc) {
        float w = 0;
        for (int d = 0; d < Kit.DOLL; d++) w += weightOf(KitClient.doll(mc.player, d));
        return w;
    }

    static float weightOf(ItemStack s) {
        if (s.isEmpty()) return 0;
        if (s.getItem() instanceof ArmorItem a) return a.getProtection() * 0.6f + a.getToughness() * 1.2f + 0.8f;
        return 1.2f;
    }

    static int mobility(float weight) {
        return Math.max(40, Math.round(100 - weight * 2.5f));
    }

    // ---- the spine of round icons ----

    private void spine(DrawContext c, int mx, int my) {
        int x = spineX(), cy = height / 2 - 45;
        c.fill(x, cy - 20, x + 1, cy + 3 * 30 + 20, LINE);
        for (int i = 0; i < TABS.length; i++) {
            int y = cy + i * 30;
            boolean sel = tab == i, hover = Math.abs(mx - x) <= 11 && Math.abs(my - y) <= 11;
            circle(c, x, y, 11, sel ? AMBER : hover ? 0xFF8A7650 : 0xFF3A3A3C);
            circle(c, x, y, 9, 0xFF16181A);
            glyph(c, i, x, y, sel ? AMBER : hover ? TEXT : SOFT);
            final int t = i;
            hots.add(new Hot(x - 11, y - 11, 22, 22, () -> {
                tab = t;
                selDoll = selSlot = -1;
            }));
            if (sel) c.fill(x + 13, y, x + 24, y + 1, AMBER);
        }
    }

    private static void circle(DrawContext c, int cx, int cy, int r, int col) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt(r * r - dy * dy);
            c.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, col);
        }
    }

    /** The tab's mark: a mask, a bag, a stance diamond, a gear. */
    private static void glyph(DrawContext c, int i, int x, int y, int col) {
        switch (i) {
            case 0 -> {
                circle(c, x, y, 5, col);
                c.fill(x - 3, y - 1, x - 1, y + 1, 0xFF16181A);
                c.fill(x + 1, y - 1, x + 3, y + 1, 0xFF16181A);
            }
            case 1 -> {
                c.fill(x - 4, y - 2, x + 5, y + 5, col);
                c.fill(x - 2, y - 5, x + 3, y - 4, col);
                c.fill(x - 2, y - 4, x - 1, y - 2, col);
                c.fill(x + 2, y - 4, x + 3, y - 2, col);
            }
            case 2 -> {
                for (int d = 0; d <= 5; d++) c.fill(x - (5 - d), y - d, x + (5 - d) + 1, y - d + 1, col);
                for (int d = 1; d <= 5; d++) c.fill(x - (5 - d), y + d, x + (5 - d) + 1, y + d + 1, col);
            }
            default -> {
                circle(c, x, y, 4, col);
                circle(c, x, y, 2, 0xFF16181A);
                c.fill(x - 1, y - 6, x + 2, y - 4, col);
                c.fill(x - 1, y + 5, x + 2, y + 7, col);
                c.fill(x - 6, y - 1, x - 4, y + 2, col);
                c.fill(x + 5, y - 1, x + 7, y + 2, col);
            }
        }
    }

    // ---- panels ----

    private void panel(DrawContext c, int x, int y, int w, int h, String title) {
        c.fill(x, y, x + w, y + h, INK);
        Ui.text(c, textRenderer, title, x + 10, y + 8, 1f, TEXT);
        c.fill(x + 10, y + 19, x + 10 + Math.min(40, w - 20), y + 20, AMBER);
    }

    private void equipment(DrawContext c, MinecraftClient mc, int x, int y, int w, int mx, int my) {
        var p = mc.player;
        int rowH = 19;
        for (int d = 0; d < Kit.DOLL; d++) {
            int ry = y + d * rowH;
            ItemStack s = KitClient.doll(p, d);
            boolean sel = selDoll == d, hover = mx >= x && mx < x + w && my >= ry && my < ry + rowH;
            if (sel || hover) c.fill(x + 4, ry, x + w - 4, ry + rowH - 1, sel ? 0x40E0A64A : 0x18FFFFFF);
            if (sel) c.fill(x + 4, ry, x + 5, ry + rowH - 1, AMBER);
            c.fill(x + 9, ry + 1, x + 26, ry + 18, 0x40000000);
            if (!s.isEmpty()) c.drawItem(s, x + 10, ry + 2);
            Ui.text(c, textRenderer, Kit.DOLL_NAMES[d].toUpperCase(), x + 32, ry + 3, 0.6f, SOFT);
            Ui.text(c, textRenderer, s.isEmpty() ? "Empty" : s.getName().getString(), x + 32, ry + 10, 0.75f, s.isEmpty() ? FAINT : TEXT);
            final int dd = d;
            hots.add(new Hot(x, ry, w, rowH, () -> selDoll = dd));
        }
        // What could go in the picked slot, from storage.
        if (selDoll >= 0) {
            int gy = y + Kit.DOLL * rowH + 6;
            Ui.text(c, textRenderer, "FROM STORAGE", x + 10, gy, 0.6f, SOFT);
            int n = 0;
            var inv = p.getInventory();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getStack(i);
                if (s.isEmpty() || i == Kit.HAND || fitsDoll(p, s) != selDoll && !(selDoll == Kit.WEAPON_2 && fitsDoll(p, s) == Kit.WEAPON_1)) continue;
                int cx = x + 10 + (n % 9) * 20, cy = gy + 9 + (n / 9) * 20;
                if (cy + 18 > y + height - 80 - 30) break;
                cell(c, cx, cy, s, false, mx, my);
                final int slot = i;
                hots.add(new Hot(cx, cy, 18, 18, () -> KitClient.send(Kit.EQUIP, 0, slot)));
                n++;
            }
            if (n == 0) Ui.text(c, textRenderer, "Nothing that fits.", x + 10, gy + 11, 0.7f, FAINT);
        }
    }

    private static int fitsDoll(net.minecraft.entity.LivingEntity p, ItemStack s) {
        return Kit.fits(p, s);
    }

    private void inventory(DrawContext c, MinecraftClient mc, int x, int y, int w, int mx, int my) {
        var inv = mc.player.getInventory();
        Ui.text(c, textRenderer, "QUICK USE", x + 10, y, 0.6f, SOFT);
        for (int q = 0; q < 3; q++) {
            int cx = x + 10 + q * 22, cy = y + 8;
            cell(c, cx, cy, inv.getStack(q), selSlot == q, mx, my);
            Ui.text(c, textRenderer, String.valueOf(q + 1), cx + 1, cy + 1, 0.5f, AMBER);
            final int s = q;
            hots.add(new Hot(cx, cy, 18, 18, () -> selSlot = s));
        }
        Ui.text(c, textRenderer, "STORAGE", x + 10, y + 34, 0.6f, SOFT);
        int cols = Math.max(4, (w - 20) / 20), n = 0;
        for (int i = 3; i < 36; i++) {
            if (i == Kit.HAND) continue;
            int cx = x + 10 + (n % cols) * 20, cy = y + 42 + (n / cols) * 20;
            cell(c, cx, cy, inv.getStack(i), selSlot == i, mx, my);
            final int s = i;
            hots.add(new Hot(cx, cy, 18, 18, () -> selSlot = s));
            n++;
        }
    }

    private void cell(DrawContext c, int x, int y, ItemStack s, boolean sel, int mx, int my) {
        boolean hover = mx >= x && mx < x + 18 && my >= y && my < y + 18;
        c.fill(x, y, x + 18, y + 18, sel ? 0x50E0A64A : hover ? 0x30FFFFFF : 0x38000000);
        if (sel) {
            c.fill(x, y + 17, x + 18, y + 18, AMBER);
        }
        if (!s.isEmpty()) {
            c.drawItem(s, x + 1, y + 1);
            c.drawItemInSlot(textRenderer, s, x + 1, y + 1);
        }
    }

    private void deck(DrawContext c, MinecraftClient mc, int x, int y, int w) {
        var st = Deck.style();
        Ui.text(c, textRenderer, st.name(), x + 10, y, 1f, 0xFF000000 | st.color());
        String[] rows = {"Front right", "Front left", "Back right", "Back left", "Air"};
        int yy = y + 16;
        for (int q = 0; q < 5; q++) {
            Ui.text(c, textRenderer, rows[q].toUpperCase(), x + 10, yy, 0.6f, SOFT);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Fighting.SLOTS; i++) {
                int id = Deck.slot(q, i);
                if (id < 0) continue;
                if (sb.length() > 0) sb.append(i == Fighting.HEAVY_SLOT ? "  |  " : " · ");
                sb.append(Deck.move(id).name());
            }
            String line = sb.length() == 0 ? "—" : sb.toString();
            while (line.length() > 4 && Ui.width(textRenderer, line, 0.7f) > w - 20) line = line.substring(0, line.length() - 2);
            Ui.text(c, textRenderer, line, x + 10, yy + 8, 0.7f, TEXT);
            yy += 24;
        }
        button(c, x + 10, yy + 4, w - 20, "Edit combat deck", () -> MinecraftClient.getInstance().setScreen(new DeckScreen()));
    }

    private void system(DrawContext c, MinecraftClient mc, int x, int y, int w, int mx, int my) {
        int bw = w - 20, yy = y;
        button(c, x + 10, yy, bw, "Settings", () -> mc.setScreen(new net.minecraft.client.gui.screen.option.OptionsScreen(this, mc.options)));
        button(c, x + 10, yy += 24, bw, "Controls", () -> mc.setScreen(new net.minecraft.client.gui.screen.option.KeybindsScreen(this, mc.options)));
        button(c, x + 10, yy += 24, bw, "Camera", () -> mc.setScreen(new KataCamScreen(this)));
        button(c, x + 10, yy += 24, bw, "Game menu", () -> mc.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true)));
    }

    private void button(DrawContext c, int x, int y, int w, String label, Runnable run) {
        c.fill(x, y, x + w, y + 18, 0x30FFFFFF);
        c.fill(x, y + 17, x + w, y + 18, AMBER_DIM);
        c.drawText(textRenderer, label, x + 8, y + 5, TEXT, false);
        hots.add(new Hot(x, y, w, 18, run));
    }

    // ---- details ----

    private void details(DrawContext c, MinecraftClient mc, ItemStack s, int x, int y, int w, int h, int mx, int my) {
        c.fill(x, y, x + w, y + h, INK_SOFT);
        Ui.text(c, textRenderer, "Details", x + 10, y + 8, 1f, TEXT);
        c.fill(x + 10, y + 19, x + 50, y + 20, AMBER);
        var p = mc.player;
        int yy = y + 28;
        if (s.isEmpty()) {
            String what = tab == 0 && selDoll >= 0 ? Kit.DOLL_NAMES[selDoll] + ": nothing worn." : "Pick something to see it here.";
            Ui.text(c, textRenderer, what, x + 10, yy, 0.7f, FAINT);
            return;
        }
        c.fill(x + 10, yy, x + 30, yy + 20, 0x40000000);
        c.drawItem(s, x + 12, yy + 2);
        Ui.text(c, textRenderer, s.getName().getString(), x + 36, yy + 2, 0.85f, TEXT);
        Ui.text(c, textRenderer, s.getCount() > 1 ? "x" + s.getCount() : s.getRarity().name().toLowerCase(), x + 36, yy + 12, 0.6f, SOFT);
        yy += 28;
        if (s.getItem() instanceof ArmorItem a) {
            stat2(c, x + 10, yy, w - 20, "Blunt", String.valueOf(a.getProtection()));
            stat2(c, x + 10, yy += 11, w - 20, "Cut", String.format("%.1f", a.getToughness()));
        }
        float wt = weightOf(s);
        if (Kit.fits(p, s) >= 0) {
            stat2(c, x + 10, yy += 11, w - 20, "Weight", String.format("%.1f", wt));
            stat2(c, x + 10, yy += 11, w - 20, "Mobility", "-" + Math.round(wt * 2.5f));
        }
        var food = s.get(DataComponentTypes.FOOD);
        if (food != null) stat2(c, x + 10, yy += 11, w - 20, "Restores", food.nutrition() + " health");
        yy += 16;
        // The lore: its own tooltip lines, the name left off.
        List<Text> lines = s.getTooltip(Item.TooltipContext.create(mc.world), p, TooltipType.BASIC);
        for (int i = 1; i < lines.size() && i < 7; i++) {
            for (var l : textRenderer.wrapLines(lines.get(i), (int) ((w - 20) / 0.7f))) {
                c.getMatrices().push();
                c.getMatrices().translate(x + 10, yy, 0);
                c.getMatrices().scale(0.7f, 0.7f, 1);
                c.drawText(textRenderer, l, 0, 0, SOFT, false);
                c.getMatrices().pop();
                yy += 8;
            }
        }
        // What can be done with it.
        int by = y + h - 24, bw = w - 20;
        if (tab == 0) {
            final int d = selDoll;
            button(c, x + 10, by, bw, "Take off", () -> KitClient.send(Kit.UNEQUIP, d, 0));
            return;
        }
        final int slot = selSlot;
        button(c, x + 10, by, bw, "Drop", () -> KitClient.send(Kit.DROP, 0, slot));
        if (Kit.fits(p, s) >= 0) button(c, x + 10, by -= 22, bw, "Equip", () -> KitClient.send(Kit.EQUIP, 0, slot));
        if (s.getItem() instanceof BlockItem) button(c, x + 10, by -= 22, bw, "Ready to place", () -> {
            KitClient.send(Kit.READY, 0, slot);
            close();
        });
        if (food != null || s.getItem() instanceof PotionItem) button(c, x + 10, by -= 22, bw, food != null ? "Eat" : "Drink", () -> KitClient.send(Kit.USE, 0, slot));
        if (slot > 2) {
            int qw = (bw - 8) / 3;
            by -= 22;
            for (int q = 0; q < 3; q++) {
                final int qq = q;
                button(c, x + 10 + q * (qw + 4), by, qw, "Quick " + (q + 1), () -> KitClient.send(Kit.QUICK, qq, slot));
            }
        }
    }

    private void stat2(DrawContext c, int x, int y, int w, String k, String v) {
        Ui.text(c, textRenderer, k, x, y, 0.7f, SOFT);
        Ui.text(c, textRenderer, v, x + w - Ui.width(textRenderer, v, 0.7f), y, 0.7f, TEXT);
    }
}
