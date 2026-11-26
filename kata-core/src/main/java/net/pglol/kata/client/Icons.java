package net.pglol.kata.client;

import net.minecraft.client.gui.DrawContext;
import net.pglol.kata.Fighting.From;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Path;

/**
 * Move icons, drawn pixel by pixel: the limb that strikes (a fist, an open palm, an elbow, a foot, a
 * knee) and, in the corner, the way it travels (straight in, hooking round, rising, coming down, low, a
 * spin). Ink outline, filled with the move's colour.
 */
public final class Icons {
    private Icons() {}

    // '#' outline, '+' fill.
    private static final String[] FIST = {
        "  ####    ",
        " #++++##  ",
        "#+++++++# ",
        "#+#+#+#+##",
        "#++++++++#",
        "#+++++++# ",
        " #######  ",
        "  #+++#   ",
        "  #+++#   ",
        "  #####   "};
    private static final String[] PALM = {
        " # # #    ",
        "#+#+#+#   ",
        "#+#+#+#   ",
        "#+++++# # ",
        "#+++++##+#",
        "#++++++++#",
        "#+++++++# ",
        " #+++++#  ",
        "  #+++#   ",
        "  #####   "};
    private static final String[] ELBOW = {
        "     #### ",
        "    #+++# ",
        "   #+++#  ",
        "  #+++#   ",
        " #++##    ",
        "#++#      ",
        "#+++####  ",
        " #++++++# ",
        "  ####### ",
        "          "};
    private static final String[] FOOT = {
        "  ###     ",
        "  #+#     ",
        "  #+#     ",
        "  #+#     ",
        "  #+#     ",
        "  #++#    ",
        "  #+++### ",
        "  #++++++#",
        "  ########",
        "          "};
    private static final String[] KNEE = {
        "######    ",
        "#+++++##  ",
        "#######+# ",
        "     #++# ",
        "     #+#  ",
        "     #+#  ",
        "     #+#  ",
        "     #++# ",
        "     #### ",
        "          "};

    private static String[] glyph(Path p) {
        return switch (p) {
            case PALM -> PALM;
            case ELBOW -> ELBOW;
            case FRONT_KICK, ROUNDHOUSE, SIDE_KICK, BACK_KICK, LOW_KICK, DROPKICK -> FOOT;
            case KNEE -> KNEE;
            default -> FIST;
        };
    }

    /** Draw a move's icon, its top-left at x, y, each icon pixel `px` screen pixels. */
    public static void draw(DrawContext c, Move m, int x, int y, int px, int ink, int fill) {
        if (m == null) return;
        String[] g = glyph(m.path());
        for (int r = 0; r < g.length; r++) for (int k = 0; k < g[r].length(); k++) {
            char ch = g[r].charAt(k);
            if (ch == ' ') continue;
            c.fill(x + k * px, y + r * px, x + (k + 1) * px, y + (r + 1) * px, ch == '#' ? ink : fill);
        }
        // The way it travels, small, bottom right.
        int ax = x + 7 * px, ay = y + 7 * px, s = Math.max(1, px - 1);
        int a = ink;
        switch (m.path()) {
            case STRAIGHT, PALM, SUPERMAN, FRONT_KICK, SIDE_KICK, BACK_KICK, DROPKICK -> { // straight in: →
                for (int i = 0; i < 4; i++) dot(c, ax + i * s, ay + s, s, a);
                dot(c, ax + 2 * s, ay, s, a);
                dot(c, ax + 2 * s, ay + 2 * s, s, a);
            }
            case HOOK, ROUNDHOUSE, BACKFIST -> { // round: ↪
                dot(c, ax, ay + 2 * s, s, a);
                dot(c, ax, ay + s, s, a);
                dot(c, ax + s, ay, s, a);
                dot(c, ax + 2 * s, ay, s, a);
                dot(c, ax + 3 * s, ay + s, s, a);
            }
            case UPPER, KNEE -> { // rising: ↑
                for (int i = 0; i < 4; i++) dot(c, ax + s, ay + i * s, s, a);
                dot(c, ax, ay + s, s, a);
                dot(c, ax + 2 * s, ay + s, s, a);
            }
            case OVERHAND, ELBOW -> { // coming down: ↓
                for (int i = 0; i < 4; i++) dot(c, ax + s, ay + i * s, s, a);
                dot(c, ax, ay + 2 * s, s, a);
                dot(c, ax + 2 * s, ay + 2 * s, s, a);
            }
            case LOW_KICK -> { // low: ↘
                for (int i = 0; i < 3; i++) dot(c, ax + i * s, ay + i * s, s, a);
                dot(c, ax + 2 * s, ay + s, s, a);
                dot(c, ax + s, ay + 2 * s, s, a);
            }
            default -> { }
        }
        // A spin: a curl at the top right.
        if (m.spin() || m.turns()) {
            int sx = x + 7 * px, sy = y;
            dot(c, sx + s, sy, s, a);
            dot(c, sx + 2 * s, sy, s, a);
            dot(c, sx + 3 * s, sy + s, s, a);
            dot(c, sx + 2 * s, sy + 2 * s, s, a);
            dot(c, sx, sy + s, s, a);
        }
        // In the air: two wing strokes at the top left.
        if (m.from() == From.AIR) {
            dot(c, x, y + s, s, a);
            dot(c, x + s, y, s, a);
            dot(c, x, y + 3 * s, s, a);
            dot(c, x + s, y + 2 * s, s, a);
        }
    }

    private static void dot(DrawContext c, int x, int y, int s, int col) {
        c.fill(x, y, x + s, y + s, col);
    }
}
