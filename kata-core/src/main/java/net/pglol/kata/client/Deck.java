package net.pglol.kata.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Style;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Your combat deck: the style you fight in and, for each stance quadrant, which of its moves sit in
 * the three light slots (the chain, in order) and the heavy slot. Kept in config/kizuna/kata_deck.json.
 */
public final class Deck {
    private Deck() {}

    private static final class Saved {
        String style;
        /** Per stance (top right, top left, back right, back left): four move ids, null for empty. */
        String[][] slots;
    }

    private static Style style = Fighting.STYLES[0];
    private static int[][] slots = copy(Fighting.STYLES[0].starter());
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kizuna").resolve("kata_deck.json");
    }

    static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Saved s = new Gson().fromJson(Files.readString(f), Saved.class);
            style = Fighting.style(s.style);
            slots = copy(style.starter());
            if (s.slots != null && s.slots.length == 4) {
                int[][] in = copy(style.starter());
                for (int q = 0; q < 4; q++) {
                    if (s.slots[q] == null) continue;
                    for (int i = 0; i < Fighting.SLOTS && i < s.slots[q].length; i++) {
                        int idx = s.slots[q][i] == null ? -1 : style.index(s.slots[q][i]);
                        Move m = style.move(idx);
                        in[q][i] = m != null && m.fits(q, i) ? idx : -1;
                    }
                    if (lights(in[q]) == 0) in[q] = style.starter()[q].clone(); // a quadrant always has a light
                }
                slots = in;
            }
        } catch (Exception ignored) {
        }
    }

    static void save() {
        try {
            Saved s = new Saved();
            s.style = style.id();
            s.slots = new String[4][Fighting.SLOTS];
            for (int q = 0; q < 4; q++) for (int i = 0; i < Fighting.SLOTS; i++) {
                Move m = style.move(slots[q][i]);
                s.slots[q][i] = m == null ? null : m.id();
            }
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(s));
        } catch (Exception ignored) {
        }
    }

    public static Style style() {
        load();
        return style;
    }

    public static int slot(int stance, int i) {
        load();
        return slots[stance][i];
    }

    /** Put move (an index into the style's pool, or -1 to empty it) in a slot. False if it doesn't fit there. */
    public static boolean set(int stance, int i, int move) {
        load();
        Move m = style.move(move);
        if (move >= 0 && (m == null || !m.fits(stance, i))) return false;
        int keep = slots[stance][i];
        slots[stance][i] = move;
        if (lights(slots[stance]) == 0) {
            slots[stance][i] = keep; // never leave a quadrant with nothing to throw
            return false;
        }
        save();
        return true;
    }

    /** Switch to another style (its starter deck, until you change it). */
    public static void use(Style s) {
        load();
        if (s == style) return;
        style = s;
        slots = copy(s.starter());
        save();
    }

    public static void reset() {
        load();
        slots = copy(style.starter());
        save();
    }

    /** The move a press throws from this stance: the heavy, or step `step` of the light chain (skipping empty slots). */
    public static Move pick(int stance, boolean heavy, int step) {
        load();
        int[] q = slots[stance];
        if (heavy) {
            Move h = style.move(q[Fighting.HEAVY_SLOT]);
            return h != null ? h : pick(stance, false, step);
        }
        int n = lights(q);
        int want = Math.floorMod(step, Math.max(1, n));
        for (int i = 0, seen = 0; i < Fighting.HEAVY_SLOT; i++) {
            if (q[i] < 0) continue;
            if (seen++ == want) return style.move(q[i]);
        }
        return style.pool()[0];
    }

    static int lights(int[] q) {
        int n = 0;
        for (int i = 0; i < Fighting.HEAVY_SLOT; i++) if (q[i] >= 0) n++;
        return n;
    }

    private static int[][] copy(int[][] a) {
        int[][] o = new int[a.length][];
        for (int i = 0; i < a.length; i++) o[i] = a[i].clone();
        return o;
    }
}
