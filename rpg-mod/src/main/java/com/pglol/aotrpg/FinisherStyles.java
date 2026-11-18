package com.pglol.aotrpg;

import java.util.List;

/**
 * Every finisher there is: its name, rarity and colours (the cosmetic), and its timing, shared by
 * the server (when the blows land, when the foe falls, where the one finishing ends up) and the
 * client (which plays the scene: see FinisherMoves). Ticks; end spot relative to the foe, along
 * the line from the one finishing (positive: past them) and to its side.
 */
public final class FinisherStyles {
    private FinisherStyles() { }

    public record Style(String id, String title, String desc, int tier, int color, int color2,
                        int end, int kill, int[] cuts, double endAlong, double endSide) {
        public int lastCut() {
            return cuts[cuts.length - 1];
        }
    }

    public static final List<Style> ALL = List.of(
        new Style("fin_nape", "Nape Cleaver", "A spinning leap over them, an overhead drop and a slide through.", 0, 0xE8E0D0, 0xB0A890,
            56, 38, new int[] {20}, 1.9, 0),
        new Style("fin_cross", "Crossing Blades", "Both blades raised, crossed and drawn apart: an X through them.", 1, 0xD8E8F0, 0x7AA8C8,
            44, 30, new int[] {16}, 1.6, 0),
        new Style("fin_whirl", "Whirlwind", "Twice round them in a storm of steel, cutting on every pass.", 2, 0xC8F0FF, 0x5AB0E0,
            60, 42, new int[] {18, 26, 34}, 1.4, 0),
        new Style("fin_rising", "Rising Dragon", "Low under their guard, then up through them and into the air.", 2, 0xFFB070, 0xE04A2A,
            52, 36, new int[] {14, 22}, -1.6, 0),
        new Style("fin_flurry", "Thousand Cuts", "Too fast to follow: cut after cut, then the last one.", 2, 0xF0F0F0, 0xA0A0B0,
            54, 40, new int[] {10, 13, 16, 19, 22, 25, 31}, -1.2, 0),
        new Style("fin_exec", "Executioner", "They sink to their knees. One blade, raised high, comes down.", 1, 0xB8A890, 0x6A5A48,
            60, 40, new int[] {36}, -1.1, 0),
        new Style("fin_shadow", "Shadow Step", "Gone, and behind them. One quiet cut, the blade put away.", 3, 0x8A7AB0, 0x2A2040,
            50, 38, new int[] {14}, 1.4, 0.4),
        new Style("fin_grapple", "Hook and Reel", "A hook in them, reeled in onto your blades.", 2, 0xC8C0B0, 0x7A8090,
            50, 30, new int[] {24}, -0.9, 0),
        new Style("fin_heel", "Falling Star", "Up, a full flip at the top, and straight down blade first.", 3, 0xFFE8A0, 0xF0A030,
            54, 36, new int[] {28}, -0.7, 0),
        new Style("fin_spear", "Thunder Spear", "A spear driven home, a step back, and the blast.", 3, 0xFFD24A, 0xE0602A,
            58, 40, new int[] {16, 34}, -2.6, 0),
        new Style("fin_salute", "Shinzou wo Sasageyo", "Fist to the heart, a breath, and the draw.", 1, 0x5E8C4A, 0xE8E0D0,
            56, 42, new int[] {30}, 1.5, 0),
        new Style("fin_drill", "Spiral Drill", "Spinning like a drill along the line, through and out the other side.", 3, 0xE0E8FF, 0x8A9AC8,
            46, 30, new int[] {20}, 2.6, 0),
        new Style("fin_rumble", "The Rumbling", "The earth shakes under every blow. Mythic.", 4, 0xE02A2A, 0x7A0A0A,
            70, 52, new int[] {20, 32, 44}, 0, 1.8));

    public static Style of(String id) {
        for (Style s : ALL) if (s.id().equals(id)) return s;
        return ALL.get(0);
    }

    public static int index(String id) {
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).id().equals(id)) return i;
        return 0;
    }

    public static Style at(int i) {
        return i >= 0 && i < ALL.size() ? ALL.get(i) : ALL.get(0);
    }
}
