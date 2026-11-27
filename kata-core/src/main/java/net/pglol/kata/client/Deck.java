package net.pglol.kata.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.pglol.kata.Fighting;
import net.pglol.kata.Fighting.Move;
import net.pglol.kata.Fighting.Style;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Your combat deck: for each stance quadrant (and the air), which moves sit in the three light slots
 * (the chain, in order) and the heavy slot. Moves can come from any style you know. Also what you know:
 * the styles you've learned and their moves (everyone starts with their first style's starter moves;
 * operators know everything). Kept in config/kizuna/kata_deck.json.
 */
public final class Deck {
    private Deck() {}

    private static final class Saved {
        String style;
        /** Per row (top right, top left, back right, back left, air): four move ids, null for empty. */
        String[][] slots;
        List<String> known, learned;
    }

    /** The style shown (the fight tag, the accent colour): the last one picked in the deck. */
    private static Style style = Fighting.STYLES[0];
    /** Slots hold moves by their global number (Fighting.MOVES), -1 for empty. */
    private static int[][] slots = starter(Fighting.STYLES[0]);
    private static final Set<String> KNOWN = new LinkedHashSet<>(), LEARNED = new LinkedHashSet<>();
    private static boolean loaded;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kizuna").resolve("kata_deck.json");
    }

    /** A style's starter deck, as global move numbers. */
    static int[][] starter(Style s) {
        int[][] o = new int[Fighting.ROWS][Fighting.SLOTS];
        for (int q = 0; q < Fighting.ROWS; q++) for (int i = 0; i < Fighting.SLOTS; i++) {
            Move m = s.starter() != null && q < s.starter().length ? s.move(s.starter()[q][i]) : null;
            o[q][i] = m == null ? -1 : Fighting.id(m);
        }
        return o;
    }

    static void load() {
        if (loaded) return;
        loaded = true;
        // While the styles are being tried out, everyone knows every style and its starter moves.
        for (Style st : Fighting.STYLES) {
            KNOWN.add(st.id());
            for (int[] row : starter(st)) for (int id : row) if (id >= 0) LEARNED.add(Fighting.MOVES[id].id());
        }
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Saved s = new Gson().fromJson(Files.readString(f), Saved.class);
            style = Fighting.style(s.style);
            if (s.known != null) KNOWN.addAll(s.known);
            if (s.learned != null) LEARNED.addAll(s.learned);
            if (s.slots != null && s.slots.length >= 4) {
                int[][] in = starter(style);
                for (int q = 0; q < Math.min(Fighting.ROWS, s.slots.length); q++) {
                    if (s.slots[q] == null) continue;
                    for (int i = 0; i < Fighting.SLOTS && i < s.slots[q].length; i++) {
                        Move m = Fighting.byId(s.slots[q][i]);
                        in[q][i] = m != null && m.fits(q, i) ? Fighting.id(m) : -1;
                    }
                    if (lights(in[q]) == 0 && lights(starter(style)[q]) > 0) in[q] = starter(style)[q].clone();
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
            s.slots = new String[Fighting.ROWS][Fighting.SLOTS];
            for (int q = 0; q < Fighting.ROWS; q++) for (int i = 0; i < Fighting.SLOTS; i++) {
                Move m = move(slots[q][i]);
                s.slots[q][i] = m == null ? null : m.id();
            }
            s.known = new ArrayList<>(KNOWN);
            s.learned = new ArrayList<>(LEARNED);
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(s));
        } catch (Exception ignored) {
        }
    }

    static Move move(int id) {
        return id >= 0 && id < Fighting.MOVES.length ? Fighting.MOVES[id] : null;
    }

    /** Operators know every style and every move. */
    static boolean op() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.player.hasPermissionLevel(2);
    }

    /** The styles you know (operators: all). */
    public static List<Style> known() {
        load();
        List<Style> out = new ArrayList<>();
        for (Style s : Fighting.STYLES) if (op() || KNOWN.contains(s.id())) out.add(s);
        return out;
    }

    /** The moves of a style you've learned (operators: all of them). */
    public static List<Move> learned(Style s) {
        load();
        List<Move> out = new ArrayList<>();
        for (Move m : s.pool()) if (op() || LEARNED.contains(m.id())) out.add(m);
        return out;
    }

    public static Style style() {
        load();
        return style;
    }

    /** The style shown (picked in the deck). */
    public static void show(Style s) {
        load();
        if (s == style) return;
        style = s;
        save();
    }

    /** The move in a slot (global number), -1 for empty. */
    public static int slot(int stance, int i) {
        load();
        return slots[stance][i];
    }

    /** Put a move (global number, or -1 to empty it) in a slot. False if it doesn't fit there. */
    public static boolean set(int stance, int i, int id) {
        load();
        Move m = move(id);
        if (id >= 0 && (m == null || !m.fits(stance, i))) return false;
        int keep = slots[stance][i];
        slots[stance][i] = id;
        if (lights(slots[stance]) == 0 && lights(starter(style)[stance]) > 0) {
            slots[stance][i] = keep; // never leave a row with nothing to throw
            return false;
        }
        save();
        return true;
    }

    /** Swap two slots of a row (dragging one onto another), if each fits where it goes. */
    public static boolean swap(int stance, int a, int b) {
        load();
        Move ma = move(slots[stance][a]), mb = move(slots[stance][b]);
        if ((ma != null && !ma.fits(stance, b)) || (mb != null && !mb.fits(stance, a))) return false;
        int t = slots[stance][a];
        slots[stance][a] = slots[stance][b];
        slots[stance][b] = t;
        save();
        return true;
    }

    public static void reset() {
        load();
        slots = starter(style);
        save();
    }

    /** The move a press throws from this stance: the heavy, or step `step` of the light chain (skipping empty slots). */
    public static Move pick(int stance, boolean heavy, int step) {
        load();
        if (stance < 0 || stance >= slots.length) return null;
        int[] q = slots[stance];
        if (heavy) {
            Move h = move(q[Fighting.HEAVY_SLOT]);
            return h != null ? h : pick(stance, false, step);
        }
        int n = lights(q);
        if (n == 0) return null;
        int want = Math.floorMod(step, Math.max(1, n));
        for (int i = 0, seen = 0; i < Fighting.HEAVY_SLOT; i++) {
            if (q[i] < 0) continue;
            if (seen++ == want) return move(q[i]);
        }
        return null;
    }

    static int lights(int[] q) {
        int n = 0;
        for (int i = 0; i < Fighting.HEAVY_SLOT; i++) if (q[i] >= 0) n++;
        return n;
    }
}
