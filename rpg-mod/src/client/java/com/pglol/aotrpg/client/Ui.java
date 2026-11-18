package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Colours, fonts and drawing helpers for the AoT look: dark cloak green, worn leather, gold trim. */
public final class Ui {
    private Ui() {}

    public static final int BG = 0xE20D110E, BG_LIGHT = 0xE8171D18, BORDER = 0xFF7A6139, TRIM = 0xFFB8955A;
    public static final int GOLD = 0xFFE0B96A, CREAM = 0xFFEDE3C8, MUTED = 0xFF8F8A7A, DIM = 0xFF55524A;
    public static final int RED = 0xFFC0463A, HP = 0xFFB3362C, STAMINA = 0xFF7FB24A, XP = 0xFFD9A441, FOOD = 0xFF9C6A3A, AIR = 0xFF5A8FD0;

    private static final Identifier TITLE = Identifier.of("aot_rpg", "title"), HEADING = Identifier.of("aot_rpg", "heading"),
        BODY = Identifier.of("aot_rpg", "body");

    /** Smooth body text (a book face, not the block font). */
    public static MutableText body(String s) {
        return Text.literal(s).styled(st -> st.withFont(BODY));
    }

    /** Any text, re-set in the smooth body face (keeping its colour). */
    public static MutableText body(Text t) {
        return Text.literal(t.getString()).setStyle(t.getStyle().withFont(BODY));
    }

    public static MutableText title(String s) {
        return Text.literal(s).styled(st -> st.withFont(TITLE));
    }

    public static MutableText heading(String s) {
        return Text.literal(s).styled(st -> st.withFont(HEADING));
    }

    public static TextRenderer font() {
        return MinecraftClient.getInstance().textRenderer;
    }

    public static final Identifier PANEL = Identifier.of("aot_rpg", "textures/gui/panel.png");
    public static final Identifier SLOT = Identifier.of("aot_rpg", "textures/gui/slot.png");
    public static final Identifier CREST = Identifier.of("aot_rpg", "textures/gui/crest.png");

    public static void slot(DrawContext c, int x, int y) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.drawTexture(SLOT, x, y, 0, 0, 18, 18, 18, 18);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    public static void crest(DrawContext c, int x, int y, int size, float alpha) {
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        c.setShaderColor(1f, 1f, 1f, alpha);
        c.drawTexture(CREST, x, y, size, size, 0, 0, 128, 128, 128, 128);
        c.setShaderColor(1f, 1f, 1f, 1f);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** Cloth texture fill (tiled in 512 px pieces). */
    public static void cloth(DrawContext c, int x, int y, int w, int h) {
        // Clean glass now: no woven texture, just a faint warm sheen from the top.
        c.fillGradient(x, y, x + w, y + Math.max(1, h / 3), 0x10E0B96A, 0x00000000);
    }

    /** A framed panel with a gold outline and a faint top highlight. */
    public static void panel(DrawContext c, int x, int y, int w, int h) {
        glass(c, x, y, w, h);
    }

    /**
     * The house style: dark smoked glass over the world, a hairline edge, and a thin gold rule
     * across the top (the Survey Corps' trim, not a carnival).
     */
    public static void glass(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0xC80B0E10);
        c.fillGradient(x, y, x + w, y + Math.max(1, h / 2), 0x14FFFFFF, 0x00000000);
        c.drawBorder(x, y, w, h, 0x30FFFFFF);
        c.fill(x + 1, y, x + w - 1, y + 1, 0xA0E0B96A);
    }

    // ------------------------------------------------------------------ leather, wood and iron

    public static final int LEATHER = 0xF2221A14, LEATHER_DARK = 0xF2150F0B, STITCH = 0x60C8A878, IRON = 0xFF8A8478;

    /** Oiled leather: a dark hide, a hairline edge, and a stitched seam a few pixels in. */
    public static void leather(DrawContext c, int x, int y, int w, int h) {
        glass(c, x, y, w, h);
    }

    /** A dashed seam around a rectangle. */
    public static void stitch(DrawContext c, int x, int y, int w, int h) {
        // (Seams are gone with the leather look.)
    }

    /** A small iron rivet. */
    public static void rivet(DrawContext c, int x, int y) {
        // (No rivets in the glass look.)
    }

    /** A pressed-in area: darker, shadow on the top and left, light catching the bottom and right. */
    public static void well(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, 0x58000000);
        c.drawBorder(x, y, w, h, 0x1CFFFFFF);
    }

    /** An item socket (18x18 by default): a well with a faint rim. */
    public static void socket(DrawContext c, int x, int y, int size) {
        c.fill(x, y, x + size, y + size, 0x60000000);
        c.drawBorder(x, y, size, size, 0x24FFFFFF);
    }

    /** Dark planks behind a whole screen: long grain lines and a vignette. */
    public static void planks(DrawContext c, int w, int h) {
        backdrop(c, w, h);
    }

    /** Muted rarity colours, like enamel on steel rather than candy. */
    public static int rarityTone(int q) {
        return switch (q) {
            case 1 -> 0xFF6F8F5A;
            case 2 -> 0xFF5E7C9E;
            case 3 -> 0xFF8A6A9E;
            case 4 -> 0xFFC89A48;
            case 5 -> 0xFFC0302A;
            default -> 0xFF6A6258;
        };
    }

    /** Gold frame without a background. */
    public static void border(DrawContext c, int x, int y, int w, int h) {
        c.drawBorder(x, y, w, h, BORDER);
        int s = 2;
        c.fill(x - 1, y - 1, x + s, y + s, TRIM);
        c.fill(x + w - s, y - 1, x + w + 1, y + s, TRIM);
        c.fill(x - 1, y + h - s, x + s, y + h + 1, TRIM);
        c.fill(x + w - s, y + h - s, x + w + 1, y + h + 1, TRIM);
    }

    /** A horizontal rule with a small diamond in the middle. */
    public static void divider(DrawContext c, int x, int y, int w) {
        c.fill(x, y, x + w, y + 1, 0x807A6139);
        int m = x + w / 2;
        c.fill(m - 1, y - 2, m + 2, y + 3, TRIM);
        c.fill(m - 2, y - 1, m + 3, y + 2, TRIM);
    }

    public static void bar(DrawContext c, int x, int y, int w, int h, float frac, int color) {
        frac = Math.max(0, Math.min(1, frac));
        c.fill(x, y, x + w, y + h, 0xC0000000);
        c.drawBorder(x, y, w, h, 0xFF2A2620);
        int fw = Math.round((w - 2) * frac);
        if (fw > 0) {
            c.fill(x + 1, y + 1, x + 1 + fw, y + h - 1, color);
            c.fill(x + 1, y + 1, x + 1 + fw, y + 2, 0x40FFFFFF);
            if (h > 4) c.fill(x + 1, y + h - 2, x + 1 + fw, y + h - 1, 0x30000000);
        }
    }

    public static void text(DrawContext c, Text t, float x, float y, float scale, int color, boolean center) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        c.drawTextWithShadow(font(), t, center ? -font().getWidth(t) / 2 : 0, 0, color);
        m.pop();
    }

    /** Text with a dark ink outline, readable on the parchment map. */
    public static void inked(DrawContext c, Text t, float x, float y, float scale, int color, boolean center) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        int dx = center ? -font().getWidth(t) / 2 : 0;
        Text plain = Text.literal(t.getString()).setStyle(t.getStyle().withColor((net.minecraft.text.TextColor) null));
        int ink = 0xE0201408;
        // A full 8-way ink outline so the lettering reads as solid, heavy type.
        for (int[] o : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
            c.drawText(font(), plain, dx + o[0], o[1], ink, false);
        }
        c.drawText(font(), t, dx, 0, color, false);
        m.pop();
    }

    public static void item(DrawContext c, ItemStack stack, int x, int y, float scale) {
        MatrixStack m = c.getMatrices();
        m.push();
        m.translate(x, y, 0);
        m.scale(scale, scale, 1);
        c.drawItem(stack, 0, 0);
        m.pop();
    }

    /** Draws wrapped text and returns the y below it. */
    public static int wrapped(DrawContext c, Text t, int x, int y, int w, int color) {
        for (var line : font().wrapLines(t, w)) {
            c.drawTextWithShadow(font(), line, x, y, color);
            y += 10;
        }
        return y;
    }

    /** Full-screen backdrop: dark vignette with a warm glow at the top. */
    public static void backdrop(DrawContext c, int w, int h) {
        // The world dimmed behind smoked glass, a warm light from above, darker at the edges.
        c.fillGradient(0, 0, w, h, 0xD80A0D0C, 0xEC050606);
        c.fillGradient(0, 0, w, h / 3, 0x22E0B96A, 0x00000000);
        c.fillGradient(0, h - 50, w, h, 0x00000000, 0x70000000);
        int side = Math.min(120, w / 6);
        c.fillGradient(0, 0, side, h, 0x50000000, 0x50000000);
        c.fillGradient(w - side, 0, w, h, 0x50000000, 0x50000000);
    }

    /** Title colour for an area theme (matches the entry titles). */
    public static int lookColor(String look) {
        return switch (look) {
            case "town" -> 0xFFE3C07A;
            case "safe" -> 0xFFA8B87A;
            case "cave" -> 0xFFB0493C;
            case "camp" -> 0xFF93A468;
            case "landmark" -> 0xFFC9A98A;
            case "marley" -> 0xFFB4BCC2;
            case "sea" -> 0xFF8AA4BC;
            default -> 0xFFC8604A;
        };
    }

    public static int disciplineColor(int ordinal) {
        return switch (ordinal) {
            case 0 -> 0xFF6FB04F; // Scout
            case 1 -> 0xFFD0513F; // Vanguard
            case 2 -> 0xFF5B86C9; // Guardian
            case 3 -> 0xFFE0C04A; // Marksman
            default -> 0xFFD07CC0; // Medic
        };
    }
}
