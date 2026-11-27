package net.pglol.kata;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ScaffoldingBlock;
import net.minecraft.util.math.Direction;

import java.util.Random;

import static net.pglol.kata.Town.*;

/**
 * What Kizuna town is made of: the street grid and every building, each queued as a job (see
 * {@link Town}). Coordinates are world coordinates unless a builder sets a frame (Town.frame) and
 * works in u/v (along the front / back from it).
 */
final class TownWorks {
    private TownWorks() {}

    static void plan(boolean clear) {
        if (clear) {
            int c0 = Math.floorDiv(-(MAX + 4 + WILD) - 8, 16), c1 = Math.floorDiv(MAX + 4 + WILD + 8, 16);
            for (int cx = c0; cx <= c1; cx++)
                for (int cz = c0; cz <= c1; cz++) {
                    int a = cx, b = cz;
                    JOBS.add(() -> clearChunk(a, b));
                }
        }
        for (int x = MIN; x <= MAX; x += 16) {
            int a = x, b = Math.min(MAX, x + 15);
            JOBS.add(() -> streets(a, b));
        }
        // North: downtown (three blocks of towers), then industry down the east side.
        downtown(0, 1101L);
        downtown(1, 1102L);
        downtown(2, 1103L);
        warehouseLot(lx(3), lz(0), 2101L);
        factoryLot(lx(3), lz(1), 2102L);
        railLot(lx(3), lz(2), 2103L);
        // The middle: the school (spawn), and south of it the tournament hall and the shops.
        school(lx(1), lz(1));
        tournament(lx(1), lz(2));
        townCentre(lx(2), lz(2), 3101L);
        // Neighbourhoods, a dojo in each.
        neighbourhood(lx(0), lz(1), Direction.EAST, "Kaze", 4101L, false);
        neighbourhood(lx(0), lz(2), Direction.EAST, "Tora", 4102L, false);
        neighbourhood(lx(0), lz(3), Direction.NORTH, "Ishi", 4103L, false);
        neighbourhood(lx(1), lz(3), Direction.NORTH, "Kumo", 4104L, false);
        neighbourhood(lx(2), lz(3), Direction.NORTH, "Ryu", 4105L, false);
        neighbourhood(lx(3), lz(3), Direction.WEST, "Hana", 4106L, true);
        terrain();
    }

    // ================================================================== streets

    private static final BlockState ASPHALT = s(Blocks.GRAY_CONCRETE), PATCH = s(Blocks.GRAY_CONCRETE_POWDER),
        PAINT = s(Blocks.WHITE_CONCRETE), CURB = s(Blocks.STONE_BRICKS), WALK = slab(Blocks.SMOOTH_STONE_SLAB, false);

    /** The streets between x a and b: 13 wide (2 sidewalk, 9 asphalt, 2 sidewalk), crosswalks, lamps. */
    static void streets(int xa, int xb) {
        for (int x = xa; x <= xb; x++)
            for (int z = MIN; z <= MAX; z++) {
                int ix = (x - MIN) % P, iz = (z - MIN) % P;
                boolean ns = ix < R, ew = iz < R;
                if (!ns && !ew) continue;
                if (ns && ew) {
                    boolean corner = (ix < 2 || ix > 10) && (iz < 2 || iz > 10);
                    if (corner) {
                        sidewalk(x, z);
                        if (ix == 1 && iz == 1) signal(x, z, 1);
                        if (ix == 11 && iz == 11) signal(x, z, -1);
                    } else set(x, G - 1, z, asphalt(x, z));
                    continue;
                }
                int c = ns ? ix : iz, a = ns ? iz : ix;
                boolean wired = z >= lz(1) - R;
                if (c < 2 || c > 10) {
                    sidewalk(x, z);
                    if (c == 12 && wired && a >= R + 5 && a <= R + 86) {
                        boolean pole = a == R + 5 || a == R + 32 || a == R + 59 || a == R + 86;
                        if (pole) for (int y = G; y <= G + 7; y++) set(x, y, z, axis(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
                        else set(x, G + 7, z, axis(Blocks.CHAIN, ns ? Direction.Axis.Z : Direction.Axis.X));
                    }
                    if (c == 0 && a == R + 15) set(x, G, z, s(Blocks.CAULDRON));
                    if (c == 11 && a == R + 36) set(x, G, z, s(Blocks.RED_NETHER_BRICK_WALL));
                    if ((c == 0 || c == 12) && (a == R + 14 || a == R + 43 || a == R + 72)) {
                        Direction toRoad = ns ? (c == 0 ? Direction.EAST : Direction.WEST) : (c == 0 ? Direction.SOUTH : Direction.NORTH);
                        lamp(x, z, toRoad);
                    }
                    continue;
                }
                BlockState b = asphalt(x, z);
                if (c == 2 && (a == R + 20 || a == R + 48 || a == R + 76)) {
                    int hsh = (x * 7349 + z * 2917) & 7;
                    if (hsh < 4) parked(x, z, ns, hsh);
                }
                boolean cross = (a >= R && a < R + 3) || a >= P - 3;
                if (cross) {
                    if (c % 2 == 0) b = PAINT;
                } else if (c == 6 && (a / 3) % 2 == 0) b = s(Blocks.YELLOW_CONCRETE);
                set(x, G - 1, z, b);
            }
    }

    /** A car parked along the kerb, nose along the street. */
    private static void parked(int x, int z, boolean ns, int pick) {
        Block[] col = {Blocks.RED_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.BLACK_CONCRETE};
        Block body = col[pick & 3];
        if (ns) {
            car(x, G, z, body);
            return;
        }
        box(x, G, z, x + 3, G, z + 1, s(body));
        box(x + 1, G + 1, z, x + 2, G + 1, z + 1, s(Blocks.BLACK_STAINED_GLASS));
        set(x, G, z, s(Blocks.BLACK_CONCRETE));
        set(x + 3, G, z + 1, s(Blocks.BLACK_CONCRETE));
    }

    /** Traffic lights on a corner: a pole, an arm out over the road (east if dir 1, west if -1), the light hanging off it. */
    private static void signal(int x, int z, int dir) {
        for (int y = G; y <= G + 5; y++) set(x, y, z, s(Blocks.POLISHED_BLACKSTONE_WALL));
        for (int k = 0; k <= 4; k++) set(x + dir * k, G + 6, z, s(Blocks.POLISHED_BLACKSTONE));
        set(x + dir * 4, G + 5, z, s(Blocks.BLACK_CONCRETE));
        set(x + dir * 4, G + 4, z, s(Blocks.VERDANT_FROGLIGHT));
        set(x + dir * 2, G + 5, z, s(Blocks.BLACK_CONCRETE));
        set(x + dir * 2, G + 4, z, s(Blocks.OCHRE_FROGLIGHT));
    }

    private static BlockState asphalt(int x, int z) {
        int h = (x * 73856093) ^ (z * 19349663);
        return (h & 31) == 0 ? PATCH : ASPHALT;
    }

    private static void sidewalk(int x, int z) {
        set(x, G - 1, z, CURB);
        set(x, G, z, WALK);
    }

    /** A street lamp: a dark pole, an arm over the road, a lantern hanging from it. */
    static void lamp(int x, int z, Direction toRoad) {
        for (int y = G; y <= G + 4; y++) set(x, y, z, s(Blocks.POLISHED_BLACKSTONE_WALL));
        set(x, G + 5, z, s(Blocks.POLISHED_BLACKSTONE));
        int ax = x + toRoad.getOffsetX(), az = z + toRoad.getOffsetZ();
        set(ax, G + 5, az, s(Blocks.POLISHED_BLACKSTONE_SLAB));
        set(ax, G + 5, az, s(Blocks.POLISHED_BLACKSTONE));
        set(ax, G + 4, az, lantern(true));
    }

    /** A lamp in a plaza or park (no arm): a post with a lantern on top. */
    static void postLamp(int x, int z) {
        for (int y = G; y <= G + 2; y++) set(x, y, z, s(Blocks.POLISHED_BLACKSTONE_WALL));
        set(x, G + 3, z, lantern(false));
    }

    // ================================================================== trees and small things

    /** A tree standing on the ground at x z: 0 oak, 1 birch, 2 cherry, 3 spruce. */
    static void tree(int x, int z, int kind, Random r) {
        treeAt(x, G, z, kind, r);
    }

    /** A tree whose trunk starts at y g (the first air above its ground). */
    static void treeAt(int x, int g, int z, int kind, Random r) {
        Block log = switch (kind) {
            case 1 -> Blocks.BIRCH_LOG;
            case 2 -> Blocks.CHERRY_LOG;
            case 3 -> Blocks.SPRUCE_LOG;
            default -> Blocks.OAK_LOG;
        };
        Block lf = switch (kind) {
            case 1 -> Blocks.BIRCH_LEAVES;
            case 2 -> Blocks.CHERRY_LEAVES;
            case 3 -> Blocks.SPRUCE_LEAVES;
            default -> r.nextInt(5) == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.OAK_LEAVES;
        };
        BlockState leaf = leaves(lf);
        set(x, g - 1, z, s(Blocks.DIRT));
        if (kind == 3) {
            int h = 7 + r.nextInt(4);
            for (int y = 0; y < h; y++) set(x, g + y, z, s(log));
            for (int y = 2; y <= h; y++) {
                int rad = y == h ? 0 : Math.max(1, (h - y) / 2 % 3 + ((h - y) % 2));
                for (int dx = -rad; dx <= rad; dx++)
                    for (int dz = -rad; dz <= rad; dz++) {
                        if (dx * dx + dz * dz > rad * rad + 1 || (dx == 0 && dz == 0 && y < h)) continue;
                        set(x + dx, g + y, z + dz, leaf);
                    }
            }
            set(x, g + h, z, leaf);
            return;
        }
        int h = 4 + r.nextInt(3);
        int rad = kind == 2 ? 3 : 2;
        for (int y = 0; y < h; y++) set(x, g + y, z, s(log));
        int top = g + h - 1;
        for (int dy = -2; dy <= 1; dy++) {
            int rr = dy == 1 ? rad - 1 : dy == -2 && kind == 2 ? rad - 1 : rad;
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++) {
                    if (dx * dx + dz * dz > rr * rr + 1) continue;
                    if (dx == 0 && dz == 0 && dy < 1) continue;
                    boolean edge = Math.abs(dx) == rr && Math.abs(dz) == rr;
                    if (edge && r.nextBoolean()) continue;
                    set(x + dx, top + dy, z + dz, leaf);
                }
        }
    }

    /**
     * A climbing route up a wall: (x, z) is a block on the wall's face, out the way it faces, yTop the
     * height to reach (the roof's edge). A crate and a barrel at the foot for a leg-up, then pairs of
     * wooden lips every three blocks, zigzagging, each on a little bracket, the last within reach of the top.
     */
    static void climb(int x, int z, Direction out, int yTop) {
        Direction along = out.rotateYClockwise();
        int bx = x + out.getOffsetX(), bz = z + out.getOffsetZ(), ax = along.getOffsetX(), az = along.getOffsetZ();
        set(bx, G, bz, s(Blocks.STRIPPED_SPRUCE_WOOD));
        set(bx + ax, G, bz + az, s(Blocks.BARREL));
        BlockState lip = slab(Blocks.SPRUCE_SLAB, true), bracket = stair(Blocks.SPRUCE_STAIRS, out.getOpposite(), true);
        int k = 0, last = G - 1;
        for (int y = G + 2; y <= yTop - 2; y += 3) {
            lipPair(bx, bz, ax, az, (k % 2) * 2, y, lip, bracket);
            last = y;
            k++;
        }
        if (yTop - last >= 4) lipPair(bx, bz, ax, az, (k % 2) * 2, yTop - 2, lip, bracket);
    }

    private static void lipPair(int bx, int bz, int ax, int az, int o, int y, BlockState lip, BlockState bracket) {
        set(bx + ax * o, y, bz + az * o, lip);
        set(bx + ax * (o + 1), y, bz + az * (o + 1), lip);
        set(bx + ax * o, y - 1, bz + az * o, bracket);
    }

    /**
     * Two neighbouring towers joined for a rooftop run: plank boards out over the gap leave a three
     * block jump. Roofs of different heights: the board goes out from the lower roof and lips run up
     * the taller wall from just above it, so you jump to the wall and climb.
     */
    static void link(int[] a, int[] b, boolean alongX) {
        int ta = G - 1 + 4 * (a[4] / 4), tb = G - 1 + 4 * (b[4] / 4);
        int ea = alongX ? a[0] + a[2] - 1 : a[1] + a[3] - 1, eb = alongX ? b[0] : b[1];
        int gap = eb - ea - 1;
        if (gap < 3) return;
        int c = alongX ? Math.min(a[1] + a[3], b[1] + b[3]) - 5 : Math.min(a[0] + a[2], b[0] + b[2]) - 5;
        if (Math.abs(ta - tb) <= 2) {
            int la = (gap - 2) / 2, lb = Math.max(0, gap - 3 - la);
            board(ea, ta + 1, c, 1, la, alongX);
            board(eb, tb + 1, c, -1, lb, alongX);
            return;
        }
        boolean aLow = ta < tb;
        int lowE = aLow ? ea : eb, highE = aLow ? eb : ea, dir = aLow ? 1 : -1, lowT = Math.min(ta, tb), highT = Math.max(ta, tb);
        board(lowE, lowT + 1, c, dir, Math.max(0, gap - 4), alongX);
        BlockState lip = slab(Blocks.SPRUCE_SLAB, true), bracket = stair(Blocks.SPRUCE_STAIRS, alongX ? (dir > 0 ? Direction.WEST : Direction.EAST) : (dir > 0 ? Direction.NORTH : Direction.SOUTH), true);
        int at = highE - dir, last = lowT;
        for (int y = lowT + 2; y <= highT - 1; y += 3) {
            for (int cc = c; cc <= c + 1; cc++) {
                if (alongX) set(at, y, cc, lip);
                else set(cc, y, at, lip);
            }
            if (alongX) set(at, y - 1, c, bracket);
            else set(c, y - 1, at, bracket);
            last = y;
        }
    }

    /** A two-wide plank board at height y from the roof edge e out len blocks (k = 0 replaces the parapet). */
    private static void board(int e, int y, int c, int dir, int len, boolean alongX) {
        for (int k = 0; k <= len; k++) {
            int p = e + dir * k;
            for (int cc = c; cc <= c + 1; cc++) {
                if (alongX) set(p, y, cc, s(Blocks.SPRUCE_PLANKS));
                else set(cc, y, p, s(Blocks.SPRUCE_PLANKS));
            }
            if (k > 0) {
                Direction toWall = alongX ? (dir > 0 ? Direction.WEST : Direction.EAST) : (dir > 0 ? Direction.NORTH : Direction.SOUTH);
                if (alongX) set(p, y - 1, c, stair(Blocks.SPRUCE_STAIRS, toWall, true));
                else set(c, y - 1, p, stair(Blocks.SPRUCE_STAIRS, toWall, true));
            }
        }
    }

    static void bush(int x, int z, Random r) {
        BlockState l = leaves(r.nextBoolean() ? Blocks.AZALEA_LEAVES : Blocks.OAK_LEAVES);
        set(x, G, z, l);
        if (r.nextBoolean()) set(x + 1, G, z, l);
        if (r.nextBoolean()) set(x, G, z + 1, l);
        if (r.nextInt(3) == 0) set(x, G + 1, z, l);
    }

    /** A bench along x (or z), facing f: two stairs with trapdoor arms. */
    static void bench(int x, int z, Direction f) {
        Direction along = f.rotateYClockwise();
        BlockState seat = stair(Blocks.SPRUCE_STAIRS, f.getOpposite(), false);
        set(x, G, z, seat);
        set(x + along.getOffsetX(), G, z + along.getOffsetZ(), seat);
    }

    /** A vending machine facing f: white body, lit glass front. */
    static void vending(int x, int z, Direction f, Random r) {
        Block[] glass = {Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.RED_STAINED_GLASS, Blocks.LIME_STAINED_GLASS, Blocks.ORANGE_STAINED_GLASS};
        set(x, G, z, s(Blocks.WHITE_CONCRETE));
        set(x, G + 1, z, s(glass[r.nextInt(glass.length)]));
        set(x, G + 2, z, slab(Blocks.SMOOTH_QUARTZ_SLAB, false));
        set(x - f.getOffsetX(), G, z - f.getOffsetZ(), s(Blocks.SEA_LANTERN));
        set(x - f.getOffsetX(), G + 1, z - f.getOffsetZ(), s(Blocks.WHITE_CONCRETE));
    }

    /** A flat cylinder ring (radius from rin to rout) or disc (rin < 0) at y. */
    static void ring(int cx, int cz, int y, double rin, double rout, BlockState s) {
        int m = (int) Math.ceil(rout);
        for (int dx = -m; dx <= m; dx++)
            for (int dz = -m; dz <= m; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d <= rout && d > rin) set(cx + dx, y, cz + dz, s);
            }
    }

    // ================================================================== stairwells (frame coordinates)

    /**
     * A switchback stairwell in the current frame: 4 wide (u .. u+3), from v0-1 to v0+fh, flights
     * between levels base, base+fh, ... base+fh*levels, each flight fh steps. Clears what was there.
     */
    static void stairwell(int u, int v0, int base, int levels, int fh, Block stairs, BlockState floor) {
        lbox(u, base + 1, v0 - 1, u + 3, base + fh * levels + 3, v0 + fh, AIR);
        for (int L = 0; L <= levels; L++) {
            int y = base + fh * L;
            lbox(u, y, v0 - 1, u + 3, y, v0 - 1, floor);
            lbox(u, y, v0 + fh, u + 3, y, v0 + fh, floor);
            // The pair the next flight leaves from has floor under it; the pair the last one came up is open.
            boolean departA = L % 2 == 0;
            if (L == 0 || L < levels) {
                int du = departA ? u : u + 2;
                lbox(du, y, v0, du + 1, y, v0 + fh - 1, floor);
            }
            if (L == levels) {
                int du = departA ? u : u + 2;
                lbox(du, y, v0, du + 1, y, v0 + fh - 1, floor);
            }
        }
        for (int L = 0; L < levels; L++) {
            int y = base + fh * L;
            boolean a = L % 2 == 0;
            int du = a ? u : u + 2;
            for (int k = 0; k < fh; k++) {
                int v = a ? v0 + k : v0 + fh - 1 - k;
                BlockState st = stair(stairs, a ? back() : front, false);
                ls(du, y + 1 + k, v, st);
                ls(du + 1, y + 1 + k, v, st);
            }
        }
    }

    // ================================================================== downtown

    private record Style(BlockState frame, BlockState band, BlockState pane, BlockState roof, BlockState parapet, Block ledge, int bay, boolean ribbon) { }

    private static final Style[] STYLES = {
        new Style(s(Blocks.LIGHT_GRAY_CONCRETE), s(Blocks.SMOOTH_STONE), s(Blocks.LIGHT_BLUE_STAINED_GLASS), s(Blocks.GRAY_CONCRETE), s(Blocks.SMOOTH_STONE), Blocks.SMOOTH_STONE_SLAB, 4, false),
        new Style(s(Blocks.POLISHED_BLACKSTONE), s(Blocks.POLISHED_BLACKSTONE_BRICKS), s(Blocks.GRAY_STAINED_GLASS), s(Blocks.GRAY_CONCRETE), s(Blocks.POLISHED_BLACKSTONE), Blocks.POLISHED_BLACKSTONE_SLAB, 3, false),
        new Style(s(Blocks.BRICKS), s(Blocks.STONE_BRICKS), s(Blocks.GLASS), s(Blocks.GRAY_CONCRETE), s(Blocks.STONE_BRICKS), Blocks.STONE_BRICK_SLAB, 3, true),
        new Style(s(Blocks.WHITE_CONCRETE), s(Blocks.WHITE_CONCRETE), s(Blocks.GRAY_STAINED_GLASS), s(Blocks.LIGHT_GRAY_CONCRETE), s(Blocks.WHITE_CONCRETE), Blocks.SMOOTH_QUARTZ_SLAB, 6, true),
        new Style(s(Blocks.WAXED_OXIDIZED_CUT_COPPER), s(Blocks.WAXED_OXIDIZED_CUT_COPPER), s(Blocks.CYAN_STAINED_GLASS), s(Blocks.GRAY_CONCRETE), s(Blocks.WAXED_OXIDIZED_CUT_COPPER), Blocks.WAXED_OXIDIZED_CUT_COPPER_SLAB, 4, false),
        new Style(s(Blocks.TUFF_BRICKS), s(Blocks.POLISHED_TUFF), s(Blocks.BROWN_STAINED_GLASS), s(Blocks.GRAY_CONCRETE), s(Blocks.TUFF_BRICKS), Blocks.TUFF_BRICK_SLAB, 3, true),
    };

    private static final int[] HEIGHTS = {24, 32, 40, 48, 56, 64, 72, 84, 96};

    /** Kinds of cell in a downtown block. */
    private static final int TOWER = 0, GARAGE = 1, SITE = 2, SQUARE = 3;

    static void downtown(int i, long seed) {
        int x0 = lx(i), z0 = lz(0);
        Random r = new Random(seed);
        int[] off = {2, 31, 60};
        int[][] cell = new int[9][];
        int[] kind = new int[9];
        int[] roofKind = new int[9];
        for (int k = 0; k < 9; k++) {
            int wx = 24 + r.nextInt(2), wz = 24 + r.nextInt(2);
            cell[k] = new int[] {x0 + off[k % 3], z0 + off[k / 3], wx, wz, HEIGHTS[r.nextInt(HEIGHTS.length)], r.nextInt(STYLES.length)};
        }
        if (i == 0) {
            kind[4] = GARAGE;
            kind[6] = SQUARE;
        } else if (i == 1) {
            cell[4][4] = 120; // Kizuna Tower, the tallest roof in town, with a helipad
            cell[4][5] = 1;
            roofKind[4] = 1;
        } else {
            kind[8] = SITE;
            cell[0][4] = 16; // a low mall with a roof garden
            cell[0][5] = 3;
            roofKind[0] = 2;
        }
        JOBS.add(() -> plaza(x0, z0, kind, cell, seed));
        for (int k = 0; k < 9; k++) {
            int[] c = cell[k];
            long sd = seed * 31 + k;
            int rk = roofKind[k];
            switch (kind[k]) {
                case GARAGE -> JOBS.add(() -> garage(c[0], c[1], c[2], c[3], 5, sd));
                case SITE -> JOBS.add(() -> construction(c[0], c[1], c[2], c[3], 52, sd));
                case SQUARE -> { }
                default -> JOBS.add(() -> tower(c[0], c[1], c[2], c[3], c[4], STYLES[c[5]], sd, rk));
            }
        }
        // Skybridges between neighbours (glass tubes a few floors below the lower roof).
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 2; col++) {
                int a = row * 3 + col, b = a + 1;
                if (kind[a] != TOWER || kind[b] != TOWER || r.nextFloat() > 0.5f) continue;
                int lo = Math.min(cell[a][4], cell[b][4]) / 4;
                if (lo < 5) continue;
                int f = 2 + r.nextInt(lo - 3);
                int[] ca = cell[a], cb = cell[b];
                JOBS.add(() -> bridge(ca, cb, f, true));
            }
        links(kind, cell);
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 3; col++) {
                int a = row * 3 + col, b = a + 3;
                if (kind[a] != TOWER || kind[b] != TOWER || r.nextFloat() > 0.4f) continue;
                int lo = Math.min(cell[a][4], cell[b][4]) / 4;
                if (lo < 5) continue;
                int f = 2 + r.nextInt(lo - 3);
                int[] ca = cell[a], cb = cell[b];
                JOBS.add(() -> bridge(ca, cb, f, false));
            }
    }

    /** Downtown: boards and lips joining every pair of neighbouring towers. */
    private static void links(int[] kind, int[][] cell) {
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 2; col++) {
                int a = row * 3 + col, b = a + 1;
                if (kind[a] == TOWER && kind[b] == TOWER) {
                    int[] ca = cell[a], cb = cell[b];
                    JOBS.add(() -> link(ca, cb, true));
                }
            }
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 3; col++) {
                int a = row * 3 + col, b = a + 3;
                if (kind[a] == TOWER && kind[b] == TOWER) {
                    int[] ca = cell[a], cb = cell[b];
                    JOBS.add(() -> link(ca, cb, false));
                }
            }
    }

    /** Downtown paving: polished stone with a grid of lighter lines, planters and lamps in the alleys, a square where asked. */
    static void plaza(int x0, int z0, int[] kind, int[][] cell, long seed) {
        Random r = new Random(seed ^ 0x5EED);
        for (int x = x0; x < x0 + LOT; x++)
            for (int z = z0; z < z0 + LOT; z++)
                set(x, G - 1, z, ((x - x0) % 8 == 0 || (z - z0) % 8 == 0) ? s(Blocks.SMOOTH_STONE) : s(Blocks.POLISHED_ANDESITE));
        // Alleys: dumpsters, crates, pallets (cover, and a leg-up).
        for (int ax : new int[] {27, 56})
            for (int az : new int[] {5, 20, 34, 49, 63, 78}) {
                if (r.nextInt(3) == 0) continue;
                int dx = x0 + ax, dz = z0 + az;
                if (r.nextBoolean()) {
                    box(dx, G, dz, dx + 1, G, dz + 2, s(Blocks.GREEN_TERRACOTTA));
                    box(dx, G + 1, dz, dx + 1, G + 1, dz + 2, Blocks.DARK_OAK_TRAPDOOR.getDefaultState());
                } else {
                    set(dx, G, dz, crate(r));
                    set(dx + 1, G, dz, crate(r));
                    set(dx, G + 1, dz, crate(r));
                    set(dx, G, dz + 1, slab(Blocks.OAK_SLAB, false));
                }
            }
        for (int ax : new int[] {5, 20, 34, 49, 63, 78})
            for (int az : new int[] {27, 56}) {
                if (r.nextInt(3) != 0) continue;
                int dx = x0 + ax, dz = z0 + az;
                set(dx, G, dz, crate(r));
                set(dx, G, dz + 1, s(Blocks.BARREL));
            }
        // Lamps where the alleys cross.
        for (int a : new int[] {28, 57})
            for (int b : new int[] {28, 57}) postLamp(x0 + a, z0 + b);
        for (int k = 0; k < 9; k++) {
            if (kind[k] != SQUARE) continue;
            int[] c = cell[k];
            int cx = c[0] + c[2] / 2, cz = c[1] + c[3] / 2;
            // A small square: lawn, a fountain, trees and benches.
            box(c[0] + 2, G - 1, c[1] + 2, c[0] + c[2] - 3, G - 1, c[1] + c[3] - 3, s(Blocks.GRASS_BLOCK));
            ring(cx, cz, G - 1, -1, 5, s(Blocks.SMOOTH_STONE));
            ring(cx, cz, G, 3, 4, s(Blocks.STONE_BRICK_WALL));
            ring(cx, cz, G - 1, -1, 3, s(Blocks.STONE_BRICKS));
            ring(cx, cz, G, -1, 3, s(Blocks.WATER));
            set(cx, G, cz, s(Blocks.QUARTZ_PILLAR));
            set(cx, G + 1, cz, s(Blocks.QUARTZ_PILLAR));
            set(cx, G + 2, cz, s(Blocks.SEA_LANTERN));
            tree(c[0] + 4, c[1] + 4, 2, r);
            tree(c[0] + c[2] - 5, c[1] + 4, 0, r);
            tree(c[0] + 4, c[1] + c[3] - 5, 0, r);
            tree(c[0] + c[2] - 5, c[1] + c[3] - 5, 2, r);
            bench(cx - 1, cz + 7, Direction.NORTH);
            bench(cx - 1, cz - 7, Direction.SOUTH);
        }
    }

    /**
     * An office tower: floors every 4 blocks with a ladder up the core to a roof hatch, doors in the
     * middle of every side, window sills to climb on some faces, maybe a fire escape on the east face,
     * and things on the roof (or a helipad / garden).
     */
    static void tower(int x0, int z0, int wx, int wz, int h, Style st, long seed, int roofKind) {
        Random r = new Random(seed);
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1, floors = h / 4, top = base + floors * 4;
        int xm = x0 + wx / 2, zm = z0 + wz / 2;
        // Apron.
        box(x0 - 1, G - 1, z0 - 1, x1 + 1, G - 1, z1 + 1, s(Blocks.POLISHED_ANDESITE));
        // Shell.
        // Glass is set one block in from the face, so every floor line is a ledge and every window a recess.
        for (int y = base + 1; y <= top; y++) {
            int fy = (y - base) % 4;
            for (int x = x0; x <= x1; x++) {
                face(x, y, z0, x, z0 + 1, shell(st, fy, x - x0, x == x0 || x == x1), st);
                face(x, y, z1, x, z1 - 1, shell(st, fy, x - x0, x == x0 || x == x1), st);
            }
            for (int z = z0 + 1; z < z1; z++) {
                face(x0, y, z, x0 + 1, z, shell(st, fy, z - z0, false), st);
                face(x1, y, z, x1 - 1, z, shell(st, fy, z - z0, false), st);
            }
        }
        // Floors, with lights set into each ceiling; the roof.
        for (int f = 0; f <= floors; f++) {
            int y = base + f * 4;
            BlockState fl = f == 0 ? s(Blocks.POLISHED_ANDESITE) : f == floors ? st.roof() : s(Blocks.SMOOTH_STONE);
            for (int x = x0 + 1; x < x1; x++)
                for (int z = z0 + 1; z < z1; z++) {
                    boolean light = f > 0 && (x - x0) % 6 == 3 && (z - z0) % 6 == 3;
                    set(x, y, z, light ? s(Blocks.SEA_LANTERN) : fl);
                }
        }
        // A parapet round the roof.
        walls(x0, top + 1, z0, x1, top + 1, z1, st.parapet());
        // Doors on every side.
        box(xm - 1, base + 1, z0, xm, base + 3, z0 + 1, AIR);
        box(xm - 1, base + 1, z1 - 1, xm, base + 3, z1, AIR);
        box(x0, base + 1, zm - 1, x0 + 1, base + 3, zm, AIR);
        box(x1 - 1, base + 1, zm - 1, x1, base + 3, zm, AIR);
        // Ground floor lobby: a reception desk.
        box(xm - 2, base + 1, z0 + 4, xm + 1, base + 1, z0 + 4, slab(Blocks.SMOOTH_QUARTZ_SLAB, true));
        // Core: a ladder from the lobby to a hatch house on the roof.
        for (int y = base + 1; y <= top + 1; y++) {
            set(xm, y, zm - 1, st.frame());
            set(xm, y, zm, facing(Blocks.LADDER, Direction.SOUTH));
        }
        box(xm - 1, top + 1, zm - 1, xm + 1, top + 3, zm - 1, st.frame());
        box(xm - 1, top + 1, zm, xm - 1, top + 3, zm + 1, st.frame());
        box(xm + 1, top + 1, zm, xm + 1, top + 3, zm + 1, st.frame());
        box(xm - 1, top + 4, zm - 1, xm + 1, top + 4, zm + 1, st.frame());
        set(xm, top + 3, zm + 1, st.frame());
        set(xm, top + 2, zm, facing(Blocks.LADDER, Direction.SOUTH));
        set(xm, top + 2, zm - 1, st.frame());
        // Sills to climb, on a face or two.
        boolean[] face = {r.nextBoolean(), r.nextBoolean(), r.nextInt(3) == 0, r.nextInt(3) == 0};
        BlockState sill = slab(st.ledge(), true);
        for (int f = 1; f < floors; f++) {
            int y = base + 4 * f;
            if (face[0]) box(x0, y, z0 - 1, x1, y, z0 - 1, sill);
            if (face[1]) box(x0, y, z1 + 1, x1, y, z1 + 1, sill);
            if (face[2]) box(x0 - 1, y, z0, x0 - 1, y, z1, sill);
            if (face[3] && floors < 6) box(x1 + 1, y, z0, x1 + 1, y, z1, sill);
        }
        // A fire escape up the east face.
        if (floors >= 4 && r.nextInt(3) > 0) fireEscape(x1, zm, base, floors, top, st);
        // A route of wooden lips up the south face, from a crate at the foot to the roof.
        climb(x0 + 3, z1, Direction.SOUTH, top + 1);
        // The roof.
        if (roofKind == 1) helipad(xm, zm, top, wx, wz, x0, z0);
        else if (roofKind == 2) roofGarden(x0, z0, x1, z1, top, r);
        else roofProps(x0, z0, wx, wz, top, r);
    }

    /** A cell of a tower's face: windows go one block in (the face cell left open), the rest on the face. */
    private static void face(int x, int y, int z, int ix, int iz, BlockState b, Style st) {
        if (b == st.pane()) {
            set(x, y, z, AIR);
            set(ix, y, iz, b);
        } else set(x, y, z, b);
    }

    private static BlockState shell(Style st, int fy, int along, boolean corner) {
        if (fy == 0) return st.band();
        if (corner || along % st.bay() == 0) return st.frame();
        if (st.ribbon() && fy == 1) return st.frame();
        return st.pane();
    }

    /** Platforms of grating at each floor, joined by ladders, a window in at each, a gap in the parapet at the top. */
    static void fireEscape(int x1, int zc, int base, int floors, int top, Style st) {
        for (int f = 0; f < floors; f++) {
            int y = base + 4 * f;
            if (f > 0) {
                box(x1 + 1, y, zc - 2, x1 + 2, y, zc + 2, grate());
                box(x1 + 2, y + 1, zc - 2, x1 + 2, y + 1, zc + 2, s(Blocks.IRON_BARS));
                box(x1 + 1, y + 1, zc - 3, x1 + 2, y + 1, zc - 3, s(Blocks.IRON_BARS));
                box(x1 + 1, y + 1, zc + 3, x1 + 2, y + 1, zc + 3, s(Blocks.IRON_BARS));
                box(x1 - 1, y + 1, zc - 1, x1, y + 2, zc, AIR);
            }
            for (int k = 1; k <= 4; k++) {
                set(x1 + 1, y + k, zc + 2, facing(Blocks.LADDER, Direction.EAST));
                if (k < 4) set(x1, y + k, zc + 2, st.frame());
            }
        }
        set(x1, top + 1, zc + 2, AIR);
    }

    static void helipad(int xm, int zm, int top, int wx, int wz, int x0, int z0) {
        int y = top;
        int cx = x0 + wx / 2, cz = z0 + 4 + (wz - 8) * 3 / 4;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                boolean hBar = (Math.abs(dx) == 2 && Math.abs(dz) <= 2) || (dz == 0 && Math.abs(dx) <= 2);
                set(cx + dx, y, cz + dz, edge ? s(Blocks.YELLOW_CONCRETE) : hBar ? s(Blocks.WHITE_CONCRETE) : s(Blocks.BLACK_CONCRETE));
            }
        // A mast at the corner with a light on top.
        for (int k = 1; k <= 10; k++) set(x0 + 2, y + k, z0 + 2, s(Blocks.CHAIN));
        set(x0 + 2, y + 11, z0 + 2, s(Blocks.SHROOMLIGHT));
        set(x0 + 2, y + 12, z0 + 2, s(Blocks.LIGHTNING_ROD));
    }

    static void roofGarden(int x0, int z0, int x1, int z1, int top, Random r) {
        box(x0 + 1, top, z0 + 1, x1 - 1, top, z1 - 1, s(Blocks.GRASS_BLOCK));
        box(x0 + 2, top, z0 + (z1 - z0) / 2, x1 - 2, top, z0 + (z1 - z0) / 2 + 1, s(Blocks.SMOOTH_STONE));
        for (int k = 0; k < 6; k++) {
            int x = x0 + 3 + r.nextInt(Math.max(1, x1 - x0 - 6)), z = z0 + 3 + r.nextInt(Math.max(1, z1 - z0 - 6));
            BlockState l = leaves(r.nextBoolean() ? Blocks.AZALEA_LEAVES : Blocks.FLOWERING_AZALEA_LEAVES);
            set(x, top + 1, z, l);
            set(x + 1, top + 1, z, l);
        }
        for (int x = x0 + 4; x < x1 - 3; x += 6) set(x, top + 1, z0 + 3, lantern(false));
    }

    /** Air conditioners, a water tank, solar panels, an antenna or a billboard, scattered by quadrant. */
    static void roofProps(int x0, int z0, int wx, int wz, int top, Random r) {
        int y = top + 1;
        int[][] q = {{x0 + 3, z0 + 3}, {x0 + wx - 9, z0 + 3}, {x0 + 3, z0 + wz - 9}, {x0 + wx - 9, z0 + wz - 9}};
        int[] order = {0, 1, 2, 3};
        for (int k = 3; k > 0; k--) {
            int j = r.nextInt(k + 1), t = order[k];
            order[k] = order[j];
            order[j] = t;
        }
        int[] props = {0, 1, 2, 3};
        for (int k = 0; k < 4; k++) {
            int[] at = q[order[k]];
            int p = r.nextInt(6);
            switch (p) {
                case 0, 1 -> { // air conditioners
                    for (int n = 0; n < 2; n++) {
                        int ax = at[0] + n * 3, az = at[1] + r.nextInt(3);
                        box(ax, y, az, ax + 1, y, az + 1, s(Blocks.LIGHT_GRAY_CONCRETE));
                        set(ax, y + 1, az, Blocks.IRON_TRAPDOOR.getDefaultState());
                        set(ax + 1, y + 1, az + 1, Blocks.IRON_TRAPDOOR.getDefaultState());
                    }
                }
                case 2 -> { // water tank on legs
                    int cx = at[0] + 3, cz = at[1] + 3;
                    for (int dx : new int[] {-2, 2})
                        for (int dz : new int[] {-2, 2})
                            for (int k2 = 0; k2 < 3; k2++) set(cx + dx, y + k2, cz + dz, s(Blocks.SPRUCE_FENCE));
                    for (int k2 = 3; k2 <= 6; k2++) ring(cx, cz, y + k2, 1.4, 2.6, axis(Blocks.SPRUCE_LOG, Direction.Axis.Y));
                    ring(cx, cz, y + 3, -1, 1.4, s(Blocks.SPRUCE_PLANKS));
                    ring(cx, cz, y + 7, -1, 2.6, slab(Blocks.SPRUCE_SLAB, false));
                }
                case 3 -> { // solar panels
                    for (int dx = 0; dx < 6; dx += 2)
                        for (int dz = 0; dz < 5; dz++) set(at[0] + dx, y, at[1] + dz, s(Blocks.DAYLIGHT_DETECTOR));
                }
                case 4 -> { // antenna
                    int hgt = 6 + r.nextInt(8);
                    for (int k2 = 0; k2 < hgt; k2++) set(at[0] + 2, y + k2, at[1] + 2, s(Blocks.CHAIN));
                    set(at[0] + 2, y + hgt, at[1] + 2, s(Blocks.LIGHTNING_ROD));
                    set(at[0] + 2, y, at[1] + 2, s(Blocks.IRON_BLOCK));
                }
                default -> { // billboard
                    Block[] col = {Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE, Blocks.LIME_CONCRETE, Blocks.CYAN_CONCRETE};
                    Block c = col[r.nextInt(col.length)];
                    for (int k2 = 0; k2 < 3; k2++) {
                        set(at[0], y + k2, at[1] + 3, s(Blocks.POLISHED_BLACKSTONE_WALL));
                        set(at[0] + 6, y + k2, at[1] + 3, s(Blocks.POLISHED_BLACKSTONE_WALL));
                    }
                    box(at[0], y + 3, at[1] + 3, at[0] + 6, y + 6, at[1] + 3, s(Blocks.WHITE_CONCRETE));
                    box(at[0] + 1, y + 4, at[1] + 3, at[0] + 5, y + 5, at[1] + 3, s(c));
                }
            }
        }
    }

    /** A glass skybridge between two towers at floor f (along x if alongX, else along z), doorways cut into both. */
    static void bridge(int[] a, int[] b, int f, boolean alongX) {
        int y = G - 1 + 4 * f;
        if (alongX) {
            int xa = a[0] + a[2] - 1, xb = b[0];
            int zlo = Math.max(a[1], b[1]) + 2;
            int zc = zlo + 3;
            for (int x = xa; x <= xb; x++) {
                box(x, y, zc - 2, x, y, zc + 2, s(Blocks.SMOOTH_QUARTZ));
                box(x, y + 1, zc - 1, x, y + 3, zc + 1, AIR);
                if (x == xa) box(x - 1, y + 1, zc - 1, x - 1, y + 3, zc + 1, AIR);
                if (x == xb) box(x + 1, y + 1, zc - 1, x + 1, y + 3, zc + 1, AIR);
                if (x > xa && x < xb) {
                    box(x, y + 1, zc - 2, x, y + 3, zc - 2, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                    box(x, y + 1, zc + 2, x, y + 3, zc + 2, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                    box(x, y + 4, zc - 2, x, y + 4, zc + 2, s(Blocks.SMOOTH_QUARTZ_SLAB));
                    set(x, y + 4, zc, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                }
            }
        } else {
            int za = a[1] + a[3] - 1, zb = b[1];
            int xlo = Math.max(a[0], b[0]) + 2;
            int xc = xlo + 3;
            for (int z = za; z <= zb; z++) {
                box(xc - 2, y, z, xc + 2, y, z, s(Blocks.SMOOTH_QUARTZ));
                box(xc - 1, y + 1, z, xc + 1, y + 3, z, AIR);
                if (z == za) box(xc - 1, y + 1, z - 1, xc + 1, y + 3, z - 1, AIR);
                if (z == zb) box(xc - 1, y + 1, z + 1, xc + 1, y + 3, z + 1, AIR);
                if (z > za && z < zb) {
                    box(xc - 2, y + 1, z, xc - 2, y + 3, z, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                    box(xc + 2, y + 1, z, xc + 2, y + 3, z, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                    box(xc - 2, y + 4, z, xc + 2, y + 4, z, s(Blocks.SMOOTH_QUARTZ_SLAB));
                    set(xc, y + 4, z, s(Blocks.LIGHT_BLUE_STAINED_GLASS));
                }
            }
        }
    }

    /** An open parking garage: levels every 4, pillars, low walls round the edges, a stairwell, a few cars. */
    static void garage(int x0, int z0, int wx, int wz, int levels, long seed) {
        Random r = new Random(seed);
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1;
        for (int L = 0; L <= levels; L++) {
            int y = base + 4 * L;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    boolean light = L > 0 && (x - x0) % 6 == 3 && (z - z0) % 6 == 3;
                    boolean line = L < levels && (z - z0 == 6 || z1 - z == 6) && (x - x0) % 3 == 0;
                    set(x, y, z, light ? s(Blocks.SEA_LANTERN) : line ? PAINT : L == 0 ? ASPHALT : s(Blocks.LIGHT_GRAY_CONCRETE));
                }
            if (L > 0) walls(x0, y + 1, z0, x1, y + 1, z1, s(Blocks.STONE_BRICK_WALL));
        }
        for (int x = x0; x <= x1; x += 6)
            for (int z = z0; z <= z1; z += 6) {
                int px = Math.min(x, x1), pz = Math.min(z, z1);
                box(px, base + 1, pz, px, base + 4 * levels - 1, pz, s(Blocks.POLISHED_ANDESITE));
            }
        box(x0, base + 1, z0 + wz / 2 - 2, x0, base + 3, z0 + wz / 2 + 1, AIR);
        frame(Direction.NORTH, x0, z0, x1, z1);
        stairwell(wx - 6, 4, base, levels, 4, Blocks.STONE_BRICK_STAIRS, s(Blocks.LIGHT_GRAY_CONCRETE));
        Block[] col = {Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE, Blocks.YELLOW_CONCRETE};
        for (int L = 0; L < levels; L++)
            for (int n = 0; n < 4; n++) {
                if (r.nextInt(3) == 0) continue;
                int cx = x0 + 2 + n * 4, cz = r.nextBoolean() ? z0 + 2 : z1 - 5;
                car(cx, base + 4 * L + 1, cz, col[r.nextInt(col.length)]);
            }
    }

    /** A small car pointing along z: body, cabin of dark glass, wheels. */
    static void car(int x, int y, int z, Block body) {
        box(x, y, z, x + 1, y, z + 3, s(body));
        box(x, y + 1, z + 1, x + 1, y + 1, z + 2, s(Blocks.BLACK_STAINED_GLASS));
        set(x, y, z, s(Blocks.BLACK_CONCRETE));
        set(x + 1, y, z + 3, s(Blocks.BLACK_CONCRETE));
    }

    /** A tower going up: a steel frame, decks on the lower half, scaffolding to climb, and a tower crane. */
    static void construction(int x0, int z0, int wx, int wz, int h, long seed) {
        Random r = new Random(seed);
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1, levels = h / 4, top = base + 4 * levels;
        for (int x = x0 - 2; x <= x1 + 1; x++)
            for (int z = z0 - 2; z <= z1 + 1; z++) set(x, G - 1, z, r.nextInt(3) == 0 ? s(Blocks.GRAVEL) : s(Blocks.COARSE_DIRT));
        BlockState steel = s(Blocks.POLISHED_DEEPSLATE);
        for (int L = 1; L <= levels; L++) {
            int y = base + 4 * L;
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    boolean beam = x == x0 || x == x1 || z == z0 || z == z1 || (x - x0) % 6 == 0 || (z - z0) % 6 == 0;
                    if (beam) set(x, y, z, steel);
                    else if (L <= levels / 2) set(x, y, z, s(Blocks.SMOOTH_STONE));
                    else if (L <= levels * 3 / 4 && r.nextInt(3) > 0) set(x, y, z, s(Blocks.OAK_PLANKS));
                }
        }
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean col = ((x - x0) % 6 == 0 || x == x1) && ((z - z0) % 6 == 0 || z == z1);
                if (col) box(x, G, z, x, top, z, steel);
            }
        // Scaffolding up the west face.
        BlockState sc = Blocks.SCAFFOLDING.getDefaultState().with(ScaffoldingBlock.DISTANCE, 0).with(ScaffoldingBlock.BOTTOM, false);
        box(x0 - 2, G, z0 + 4, x0 - 1, top, z0 + 5, sc);
        box(x0 - 2, G, z1 - 5, x0 - 1, base + 4 * (levels / 2), z1 - 4, sc);
        // Site fence.
        walls(x0 - 3, G, z0 - 3, x1 + 2, G + 1, z1 + 2, s(Blocks.IRON_BARS));
        box(x0 - 3, G, z0 + 8, x0 - 3, G + 1, z0 + 10, AIR);
        // Pallets of materials.
        for (int n = 0; n < 5; n++) {
            int px = x0 + 2 + r.nextInt(wx - 4), pz = z0 + 2 + r.nextInt(wz - 4);
            set(px, G, pz, slab(Blocks.OAK_SLAB, false));
            set(px, G + 1, pz, r.nextBoolean() ? s(Blocks.BRICKS) : s(Blocks.STRIPPED_SPRUCE_WOOD));
        }
        crane(x1 - 2, z1 - 2, top + 16, 30);
    }

    /** A tower crane: a 2x2 mast with a ladder, the cab, a long jib west, the counterweight east, a hook hanging. */
    static void crane(int mx, int mz, int y, int jib) {
        BlockState yel = s(Blocks.YELLOW_CONCRETE);
        for (int k = G; k < y; k++) {
            boolean brace = (k - G) % 4 == 0;
            set(mx, k, mz, yel);
            set(mx + 1, k, mz + 1, yel);
            set(mx + 1, k, mz, brace ? yel : s(Blocks.IRON_BARS));
            set(mx, k, mz + 1, brace ? yel : s(Blocks.IRON_BARS));
            set(mx - 1, k, mz, facing(Blocks.LADDER, Direction.WEST));
        }
        box(mx, y, mz, mx + 1, y, mz + 1, yel);
        box(mx - jib, y, mz, mx + 9, y, mz + 1, yel);
        box(mx - jib, y + 1, mz, mx + 9, y + 1, mz, s(Blocks.IRON_BARS));
        box(mx - jib, y + 1, mz + 1, mx + 9, y + 1, mz + 1, s(Blocks.IRON_BARS));
        box(mx + 6, y - 2, mz, mx + 9, y - 1, mz + 1, s(Blocks.GRAY_CONCRETE));
        // The cab under the jib.
        box(mx + 2, y - 3, mz, mx + 3, y - 1, mz + 1, yel);
        box(mx + 2, y - 2, mz, mx + 2, y - 2, mz + 1, s(Blocks.GLASS));
        // The apex.
        for (int k = 1; k <= 5; k++) set(mx, y + k, mz, yel);
        set(mx, y + 6, mz, s(Blocks.SHROOMLIGHT));
        // A hook and a steel beam hanging off the jib.
        int hx = mx - jib + 8;
        for (int k = y - 1; k > y - 14; k--) set(hx, k, mz, s(Blocks.CHAIN));
        box(hx - 2, y - 14, mz, hx + 2, y - 14, mz, s(Blocks.POLISHED_DEEPSLATE));
    }

    // ================================================================== industry

    /** The big warehouse, its yard, trucks, a chain-link fence round it all. */
    static void warehouseLot(int x0, int z0, long seed) {
        JOBS.add(() -> {
            Random r = new Random(seed);
            for (int x = x0; x < x0 + LOT; x++)
                for (int z = z0; z < z0 + LOT; z++) set(x, G - 1, z, r.nextInt(24) == 0 ? PATCH : ASPHALT);
            for (int x = x0 + 4; x < x0 + LOT - 4; x += 4) box(x, G - 1, z0 + 64, x, G - 1, z0 + 70, PAINT);
            fence(x0, z0, x0 + LOT - 1, z0 + LOT - 1, 38, 48);
            bushes(x0, z0, r);
        });
        JOBS.add(() -> warehouse(x0 + 11, z0 + 12, 64, 40, 14, true, seed));
        JOBS.add(() -> {
            Random r = new Random(seed + 7);
            truck(x0 + 16, z0 + 58, r);
            truck(x0 + 48, z0 + 60, r);
            truck(x0 + 64, z0 + 72, r);
            for (int n = 0; n < 8; n++) {
                int px = x0 + 6 + r.nextInt(LOT - 12), pz = z0 + 74 + r.nextInt(8);
                set(px, G, pz, slab(Blocks.OAK_SLAB, false));
                if (r.nextBoolean()) set(px, G + 1, pz, s(Blocks.STRIPPED_SPRUCE_WOOD));
            }
            // Dumpsters.
            box(x0 + 78, G, z0 + 20, x0 + 79, G + 1, z0 + 22, s(Blocks.GREEN_TERRACOTTA));
            box(x0 + 78, G, z0 + 30, x0 + 79, G + 1, z0 + 32, s(Blocks.BLUE_TERRACOTTA));
        });
    }

    private static void bushes(int x0, int z0, Random r) {
        for (int n = 0; n < 6; n++) bush(x0 + 2 + r.nextInt(LOT - 4), z0 + LOT - 3, r);
    }

    /** Chain-link (iron bars) round a lot, open between along-x a and b on the south side. */
    static void fence(int x0, int z0, int x1, int z1, int gateA, int gateB) {
        walls(x0 + 1, G, z0 + 1, x1 - 1, G + 1, z1 - 1, s(Blocks.IRON_BARS));
        box(x0 + gateA, G, z1 - 1, x0 + gateB, G + 1, z1 - 1, AIR);
        box(x0 + 1, G, z0 + 40, x0 + 1, G + 1, z0 + 46, AIR);
    }

    /**
     * A warehouse: corrugated walls between steel pillars, clerestory windows, a sawtooth roof with
     * glazed north lights, roller doors on the south. Full ones get a mezzanine, a catwalk under the
     * roof, racking and crates; the rest just crates.
     */
    static void warehouse(int x0, int z0, int wx, int wz, int wallH, boolean full, long seed) {
        Random r = new Random(seed);
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1, top = base + wallH;
        box(x0, base, z0, x1, base, z1, s(Blocks.POLISHED_ANDESITE));
        for (int y = base + 1; y <= top; y++) {
            for (int x = x0; x <= x1; x++) {
                set(x, y, z0, wall(y, base, top, x - x0, x == x0 || x == x1));
                set(x, y, z1, wall(y, base, top, x - x0, x == x0 || x == x1));
            }
            for (int z = z0 + 1; z < z1; z++) {
                set(x0, y, z, wall(y, base, top, z - z0, false));
                set(x1, y, z, wall(y, base, top, z - z0, false));
            }
        }
        // The sawtooth: each 8-wide bay climbs east, then a wall of glass down to the next bay.
        for (int x = x0; x <= x1; x++) {
            int dx = (x - x0) % 8, hp = dx / 2;
            int y = top + 1 + hp;
            box(x, y, z0, x, y, z1, s(Blocks.CYAN_TERRACOTTA));
            for (int k = top + 1; k < y; k++) {
                set(x, k, z0, s(Blocks.LIGHT_GRAY_CONCRETE));
                set(x, k, z1, s(Blocks.LIGHT_GRAY_CONCRETE));
            }
            if (dx == 0 && x > x0) box(x, top + 2, z0 + 1, x, top + 4, z1 - 1, s(Blocks.GLASS));
            if (x == x1) box(x, top + 1, z0, x, y, z1, s(Blocks.LIGHT_GRAY_CONCRETE));
        }
        // Columns inside.
        for (int x = x0 + 8; x < x1; x += 8)
            for (int z = z0 + 10; z < z1; z += 10) box(x, base + 1, z, x, top, z, s(Blocks.POLISHED_BLACKSTONE));
        // Roller doors on the south, every other one open, hazard stripes in front.
        int doors = Math.max(1, wx / 16);
        for (int d = 0; d < doors; d++) {
            int dx0 = x0 + 5 + d * 16, dx1 = dx0 + 5;
            int dh = Math.min(6, wallH - 4);
            for (int x = dx0; x <= dx1; x++) {
                for (int y = base + 1; y <= base + dh; y++)
                    set(x, y, z1, d % 2 == 0 ? AIR : ((y - base) % 2 == 0 ? s(Blocks.LIGHT_GRAY_CONCRETE) : s(Blocks.SMOOTH_STONE)));
                for (int z = z1 + 1; z <= z1 + 2; z++) set(x, base, z, (x + z) % 2 == 0 ? s(Blocks.YELLOW_CONCRETE) : s(Blocks.BLACK_CONCRETE));
            }
            set(dx0 - 1, base + 1, z1 + 1, s(Blocks.BLACK_CONCRETE));
            set(dx1 + 1, base + 1, z1 + 1, s(Blocks.BLACK_CONCRETE));
        }
        door(x0, base + 1, z0 + wz / 2, Blocks.SPRUCE_DOOR, Direction.WEST);
        climb(x0, z0 + 2, Direction.WEST, top + 1);
        sign(x0 - 1, base + 3, z0 + wz / 2 + 1, Blocks.SPRUCE_WALL_SIGN, Direction.WEST, "KIZUNA", "LOGISTICS", "", "Staff only");
        // Lanterns hanging on chains under the roof.
        for (int x = x0 + 4; x < x1; x += 8)
            for (int z = z0 + 5; z < z1; z += 10) {
                for (int k = top - 3; k <= top; k++) set(x, k, z, s(Blocks.CHAIN));
                set(x, top - 4, z, lantern(true));
            }
        if (full) {
            // Mezzanine across the back, stairs up from the floor.
            box(x0 + 1, base + 5, z0 + 1, x1 - 1, base + 5, z0 + 9, s(Blocks.SMOOTH_STONE));
            box(x0 + 1, base + 6, z0 + 10, x1 - 1, base + 6, z0 + 10, s(Blocks.IRON_BARS));
            for (int k = 0; k < 5; k++) {
                box(x0 + 3, base + 1 + k, z0 + 14 - k, x0 + 4, base + 1 + k, z0 + 14 - k, stair(Blocks.STONE_BRICK_STAIRS, Direction.NORTH, false));
            }
            box(x0 + 3, base + 6, z0 + 10, x0 + 4, base + 6, z0 + 10, AIR);
            box(x0 + 3, base + 5, z0 + 10, x0 + 4, base + 5, z0 + 10, s(Blocks.SMOOTH_STONE));
            // Catwalk under the roof, across the middle, with a ladder up a column.
            int zc = z0 + 22, yc = top - 2;
            box(x0 + 1, yc, zc - 1, x1 - 1, yc, zc + 1, grate());
            box(x0 + 1, yc + 1, zc - 2, x1 - 1, yc + 1, zc - 2, s(Blocks.IRON_BARS));
            box(x0 + 1, yc + 1, zc + 2, x1 - 1, yc + 1, zc + 2, s(Blocks.IRON_BARS));
            for (int x = x0 + 2; x < x1; x += 6)
                for (int k = yc + 2; k <= top; k++) {
                    set(x, k, zc - 2, s(Blocks.CHAIN));
                    set(x, k, zc + 2, s(Blocks.CHAIN));
                }
            for (int k = base + 1; k <= yc + 1; k++) set(x0 + 17, k, z0 + 20, facing(Blocks.LADDER, Direction.EAST));
            box(x0 + 16, base + 1, z0 + 20, x0 + 16, yc + 1, z0 + 20, s(Blocks.POLISHED_BLACKSTONE));
            // Racking in rows, with crates and barrels on the shelves.
            for (int row = 0; row < 2; row++) {
                int rz = z1 - 13 - row * 7;
                for (int xs : new int[] {x0 + 4, x0 + 36}) {
                    int xe = Math.min(x1 - 4, xs + 22);
                    for (int x = xs; x <= xe; x++) {
                        for (int z = rz; z <= rz + 1; z++) {
                            if ((x - xs) % 4 == 0) box(x, base + 1, z, x, base + 7, z, axis(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
                            else {
                                set(x, base + 3, z, slab(Blocks.SPRUCE_SLAB, true));
                                set(x, base + 6, z, slab(Blocks.SPRUCE_SLAB, true));
                                if (r.nextInt(3) == 0) set(x, base + 1, z, crate(r));
                                if (r.nextInt(3) == 0) set(x, base + 4, z, crate(r));
                                if (r.nextInt(4) == 0) set(x, base + 7, z, crate(r));
                            }
                        }
                    }
                }
            }
        }
        // Stacks of crates on the floor (cover).
        for (int n = 0; n < (full ? 10 : 5); n++) {
            int cx = x0 + 3 + r.nextInt(wx - 7), cz = z0 + (full ? 12 : 3) + r.nextInt(Math.max(1, wz - (full ? 30 : 7)));
            int hgt = 1 + r.nextInt(3);
            box(cx, base + 1, cz, cx + 1, base + hgt, cz + 1, crate(r));
        }
    }

    private static BlockState wall(int y, int base, int top, int along, boolean corner) {
        if (corner || along % 8 == 0) return s(Blocks.POLISHED_BLACKSTONE);
        if (y <= base + 2) return s(Blocks.DEEPSLATE_BRICKS);
        if (y == top - 3 || y == top - 2) return s(Blocks.GLASS);
        return along % 2 == 0 ? s(Blocks.LIGHT_GRAY_CONCRETE) : s(Blocks.WHITE_CONCRETE);
    }

    private static BlockState crate(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> s(Blocks.BARREL);
            case 1 -> s(Blocks.HAY_BLOCK);
            default -> s(Blocks.STRIPPED_SPRUCE_WOOD);
        };
    }

    /** A box truck pointing north: white trailer, coloured cab, black wheels. */
    static void truck(int x, int z, Random r) {
        Block[] cab = {Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.GREEN_CONCRETE};
        box(x, G + 1, z + 3, x + 2, G + 3, z + 10, s(Blocks.WHITE_CONCRETE));
        box(x, G + 2, z + 3, x + 2, G + 2, z + 10, s(Blocks.LIGHT_GRAY_CONCRETE));
        Block c = cab[r.nextInt(cab.length)];
        box(x, G + 1, z, x + 2, G + 2, z + 2, s(c));
        box(x, G + 2, z, x + 2, G + 2, z, s(Blocks.BLACK_STAINED_GLASS));
        box(x, G + 3, z + 1, x + 2, G + 3, z + 2, s(c));
        for (int wz : new int[] {z + 1, z + 5, z + 9}) {
            set(x, G, wz, s(Blocks.BLACK_CONCRETE));
            set(x + 2, G, wz, s(Blocks.BLACK_CONCRETE));
        }
    }

    /** The factory hall with its chimneys and tanks; the container yard and its gantry crane. */
    static void factoryLot(int x0, int z0, long seed) {
        JOBS.add(() -> {
            Random r = new Random(seed);
            for (int x = x0; x < x0 + LOT; x++)
                for (int z = z0; z < z0 + LOT; z++) set(x, G - 1, z, r.nextInt(5) == 0 ? s(Blocks.STONE) : ASPHALT);
            fence(x0, z0, x0 + LOT - 1, z0 + LOT - 1, 30, 40);
        });
        JOBS.add(() -> factory(x0 + 5, z0 + 8, 44, 28, 12, seed));
        JOBS.add(() -> {
            chimney(x0 + 14, z0 + 3, 40);
            chimney(x0 + 30, z0 + 3, 34);
        });
        JOBS.add(() -> {
            tank(x0 + 68, z0 + 12);
            tank(x0 + 68, z0 + 28);
            for (int tz : new int[] {z0 + 12, z0 + 28}) {
                box(x0 + 49, G + 6, tz, x0 + 62, G + 6, tz, s(Blocks.IRON_BLOCK));
                for (int x = x0 + 52; x <= x0 + 62; x += 5) box(x, G, tz, x, G + 5, tz, s(Blocks.POLISHED_BLACKSTONE_WALL));
            }
        });
        JOBS.add(() -> containers(x0, z0 + 42, seed));
    }

    static void factory(int x0, int z0, int wx, int wz, int wallH, long seed) {
        Random r = new Random(seed);
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1, top = base + wallH;
        box(x0, base, z0, x1, base, z1, s(Blocks.SMOOTH_STONE));
        for (int y = base + 1; y <= top; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                    int along = (z == z0 || z == z1) ? x - x0 : z - z0;
                    boolean win = along % 4 >= 1 && along % 4 <= 2 && y >= base + 3 && y <= base + 9 && !(x == x0 || x == x1) == (z == z0 || z == z1);
                    BlockState b = y <= base + 1 ? s(Blocks.STONE_BRICKS) : win ? s(Blocks.GLASS) : s(Blocks.BRICKS);
                    set(x, y, z, b);
                }
        // Flat roof with a raised glazed monitor down the middle.
        box(x0, top + 1, z0, x1, top + 1, z1, s(Blocks.GRAY_CONCRETE));
        int zm = z0 + wz / 2;
        box(x0 + 2, top + 1, zm - 3, x1 - 2, top + 1, zm + 3, AIR);
        walls(x0 + 2, top + 1, zm - 4, x1 - 2, top + 3, zm + 4, s(Blocks.BRICKS));
        box(x0 + 3, top + 2, zm - 4, x1 - 3, top + 3, zm - 4, s(Blocks.GLASS));
        box(x0 + 3, top + 2, zm + 4, x1 - 3, top + 3, zm + 4, s(Blocks.GLASS));
        box(x0 + 2, top + 4, zm - 4, x1 - 2, top + 4, zm + 4, s(Blocks.GRAY_CONCRETE));
        walls(x0, top + 2, z0, x1, top + 2, z1, s(Blocks.STONE_BRICK_WALL));
        // Doors: a big one to the yard, a small one west.
        box(x0 + wx / 2 - 4, base + 1, z1, x0 + wx / 2 + 3, base + 7, z1, AIR);
        box(x0, base + 1, zm, x0, base + 3, zm + 1, AIR);
        // Machines in two rows, pipes overhead, lanterns.
        for (int x = x0 + 4; x < x1 - 4; x += 7)
            for (int z : new int[] {z0 + 5, z1 - 8}) {
                box(x, base + 1, z, x + 3, base + 3, z + 2, s(Blocks.LIGHT_GRAY_CONCRETE));
                set(x + 1, base + 2, z + (z < zm ? 3 : -1), facing(Blocks.BLAST_FURNACE, z < zm ? Direction.SOUTH : Direction.NORTH));
                box(x + 1, base + 4, z + 1, x + 1, top - 1, z + 1, s(Blocks.IRON_BLOCK));
                box(x, base + 1, z + (z < zm ? 3 : -1), x, base + 1, z + (z < zm ? 3 : -1), s(Blocks.SMITHING_TABLE));
            }
        box(x0 + 1, top - 1, z0 + 6, x1 - 1, top - 1, z0 + 6, s(Blocks.IRON_BLOCK));
        box(x0 + 1, top - 1, z1 - 6, x1 - 1, top - 1, z1 - 6, s(Blocks.IRON_BLOCK));
        for (int x = x0 + 5; x < x1; x += 8)
            for (int z = z0 + 10; z < z1 - 8; z += 6) {
                set(x, top, z, s(Blocks.CHAIN));
                set(x, top - 1, z, lantern(true));
            }
        sign(x0 - 1, base + 3, zm + 2, Blocks.SPRUCE_WALL_SIGN, Direction.WEST, "KIZUNA", "STEELWORKS", "", "No entry");
        climb(x0, z0 + 2, Direction.WEST, top + 2);
        if (r.nextBoolean()) set(x0 + 2, base + 1, z0 + 2, s(Blocks.ANVIL));
    }

    /** A round brick chimney with a ladder up it, a platform near the top and red and white bands. */
    static void chimney(int cx, int cz, int h) {
        for (int y = G - 1; y < G + h; y++) {
            boolean band = y > G + h - 9 && ((y - G) / 2) % 2 == 0;
            ring(cx, cz, y, 1.2, 2.5, band ? s(Blocks.WHITE_CONCRETE) : y > G + h - 9 ? s(Blocks.RED_CONCRETE) : s(Blocks.BRICKS));
        }
        for (int y = G; y < G + h; y++) set(cx, y, cz + 3, facing(Blocks.LADDER, Direction.SOUTH));
        int py = G + h - 12;
        ring(cx, cz, py, 2.5, 3.6, grate());
        ring(cx, cz, py + 1, 3.6, 4.5, s(Blocks.IRON_BARS));
        set(cx, py, cz + 3, facing(Blocks.LADDER, Direction.SOUTH));
        set(cx, py + 1, cz + 4, AIR);
    }

    /** A storage tank: white, a ladder up the west side, railing round its flat top. */
    static void tank(int cx, int cz) {
        for (int y = G - 1; y <= G + 10; y++) ring(cx, cz, y, 3.5, 5, s(Blocks.WHITE_CONCRETE));
        ring(cx, cz, G + 11, -1, 5, s(Blocks.SMOOTH_STONE));
        ring(cx, cz, G + 12, 4.5, 5.5, s(Blocks.IRON_BARS));
        for (int y = G; y <= G + 11; y++) set(cx - 6, y, cz, facing(Blocks.LADDER, Direction.WEST));
        set(cx - 5, G + 12, cz, AIR);
        set(cx - 6, G + 12, cz, AIR);
        box(cx - 1, G + 3, cz + 5, cx + 1, G + 3, cz + 5, s(Blocks.LIGHT_GRAY_CONCRETE));
    }

    /** Shipping containers in rows, stacked one to three high, and a gantry crane over them. */
    static void containers(int x0, int z0, long seed) {
        Random r = new Random(seed + 99);
        Block[][] col = {
            {Blocks.RED_CONCRETE, Blocks.RED_TERRACOTTA}, {Blocks.BLUE_CONCRETE, Blocks.BLUE_TERRACOTTA},
            {Blocks.GREEN_CONCRETE, Blocks.GREEN_TERRACOTTA}, {Blocks.ORANGE_CONCRETE, Blocks.ORANGE_TERRACOTTA},
            {Blocks.WHITE_CONCRETE, Blocks.WHITE_TERRACOTTA}, {Blocks.LIGHT_BLUE_CONCRETE, Blocks.LIGHT_BLUE_TERRACOTTA},
            {Blocks.BROWN_CONCRETE, Blocks.BROWN_TERRACOTTA}, {Blocks.GRAY_CONCRETE, Blocks.CYAN_TERRACOTTA}};
        for (int row = 0; row < 6; row++)
            for (int c = 0; c < 7; c++) {
                int cx = x0 + 4 + c * 11, cz = z0 + row * 7;
                int stack = r.nextInt(4);
                for (int s = 0; s < stack; s++) {
                    Block[] k = col[r.nextInt(col.length)];
                    int y = G + s * 3;
                    for (int x = cx; x < cx + 8; x++) box(x, y, cz, x, y + 2, cz + 2, s(x % 2 == 0 ? k[0] : k[1]));
                    box(cx, y, cz, cx, y + 2, cz + 2, s(k[1]));
                    box(cx, y + 1, cz + 1, cx, y + 1, cz + 1, s(Blocks.IRON_BARS));
                }
            }
        // The gantry crane straddling the yard.
        int gz = z0 + 20;
        BlockState yel = s(Blocks.YELLOW_CONCRETE);
        for (int lx : new int[] {x0 + 1, x0 + LOT - 3}) box(lx, G, gz, lx + 1, G + 17, gz + 1, yel);
        box(x0 + 1, G + 18, gz, x0 + LOT - 2, G + 19, gz + 1, yel);
        box(x0 + 40, G + 15, gz, x0 + 42, G + 17, gz + 1, s(Blocks.ORANGE_CONCRETE));
        for (int k = G + 9; k < G + 15; k++) set(x0 + 41, k, gz, s(Blocks.CHAIN));
        set(x0 + 41, G + 8, gz, s(Blocks.IRON_BLOCK));
        for (int k = G; k <= G + 18; k++) set(x0, k, gz, facing(Blocks.LADDER, Direction.WEST));
    }

    /** The rail yard: two tracks with wagons, a platform with a canopy, the water tower and two sheds. */
    static void railLot(int x0, int z0, long seed) {
        JOBS.add(() -> {
            Random r = new Random(seed);
            for (int x = x0; x < x0 + LOT; x++)
                for (int z = z0; z < z0 + LOT; z++) set(x, G - 1, z, x < x0 + 14 ? s(Blocks.GRAVEL) : r.nextInt(6) == 0 ? s(Blocks.STONE) : ASPHALT);
            for (int tx : new int[] {x0 + 6, x0 + 10})
                for (int z = z0; z < z0 + LOT; z++) {
                    set(tx, G - 1, z, s(Blocks.GRAVEL));
                    set(tx, G, z, s(Blocks.RAIL));
                }
            wagon(x0 + 6, z0 + 8, Blocks.BROWN_TERRACOTTA);
            wagon(x0 + 6, z0 + 22, Blocks.RED_TERRACOTTA);
            wagon(x0 + 10, z0 + 50, Blocks.GRAY_TERRACOTTA);
            // Platform and canopy.
            box(x0 + 14, G, z0 + 5, x0 + 18, G, z0 + 80, s(Blocks.SMOOTH_STONE));
            box(x0 + 14, G, z0 + 5, x0 + 14, G, z0 + 80, s(Blocks.YELLOW_CONCRETE));
            for (int z = z0 + 8; z <= z0 + 76; z += 8) {
                box(x0 + 17, G + 1, z, x0 + 17, G + 4, z, s(Blocks.POLISHED_BLACKSTONE_WALL));
                bench(x0 + 18, z + 3, Direction.WEST);
            }
            box(x0 + 14, G + 5, z0 + 6, x0 + 18, G + 5, z0 + 78, slab(Blocks.SMOOTH_STONE_SLAB, false));
            sign(x0 + 18, G + 3, z0 + 40, Blocks.SPRUCE_WALL_SIGN, Direction.WEST, "Kizuna", "Freight Yard", "", "Platform 1");
            set(x0 + 19, G + 3, z0 + 40, s(Blocks.POLISHED_BLACKSTONE));
        });
        JOBS.add(() -> waterTower(x0 + 34, z0 + 14));
        JOBS.add(() -> warehouse(x0 + 26, z0 + 30, 32, 22, 10, false, seed + 1));
        JOBS.add(() -> warehouse(x0 + 28, z0 + 58, 48, 24, 12, false, seed + 2));
    }

    /** A wagon on the track at x: a long box on wheels, ribbed. */
    static void wagon(int tx, int z, Block body) {
        for (int k = 0; k < 12; k++) {
            BlockState b = k % 3 == 0 ? s(Blocks.DARK_OAK_PLANKS) : s(body);
            box(tx - 1, G + 1, z + k, tx + 1, G + 4, z + k, b);
        }
        box(tx - 1, G + 5, z, tx + 1, G + 5, z + 11, s(Blocks.GRAY_CONCRETE));
        box(tx - 1, G + 2, z + 5, tx - 1, G + 3, z + 6, s(Blocks.SPRUCE_PLANKS));
        box(tx + 1, G + 2, z + 5, tx + 1, G + 3, z + 6, s(Blocks.SPRUCE_PLANKS));
        for (int wz : new int[] {z + 1, z + 10}) {
            set(tx - 1, G, wz, s(Blocks.BLACK_CONCRETE));
            set(tx + 1, G, wz, s(Blocks.BLACK_CONCRETE));
        }
    }

    /** A water tower: four legs, a ladder up one to a ring catwalk, the tank, a cone roof. */
    static void waterTower(int cx, int cz) {
        int legTop = G + 17;
        for (int dx : new int[] {-3, 3})
            for (int dz : new int[] {-3, 3}) box(cx + dx, G, cz + dz, cx + dx, legTop, cz + dz, s(Blocks.POLISHED_BLACKSTONE_WALL));
        for (int y = G + 4; y < legTop; y += 6) {
            walls(cx - 3, y, cz - 3, cx + 3, y, cz + 3, s(Blocks.IRON_BARS));
        }
        ring(cx, cz, legTop + 1, -1, 5.5, grate());
        ring(cx, cz, legTop + 2, 5.5, 6.5, s(Blocks.IRON_BARS));
        for (int y = legTop + 2; y <= legTop + 9; y++) ring(cx, cz, y, 3.5, 4.6, s(Blocks.LIGHT_GRAY_CONCRETE));
        ring(cx, cz, legTop + 1, -1, 4.6, s(Blocks.LIGHT_GRAY_CONCRETE));
        ring(cx, cz, legTop + 10, -1, 4.6, s(Blocks.LIGHT_GRAY_CONCRETE));
        ring(cx, cz, legTop + 11, -1, 3.2, s(Blocks.GRAY_CONCRETE));
        ring(cx, cz, legTop + 12, -1, 2.2, s(Blocks.GRAY_CONCRETE));
        ring(cx, cz, legTop + 13, -1, 1.2, s(Blocks.GRAY_CONCRETE));
        set(cx, legTop + 14, cz, s(Blocks.LIGHTNING_ROD));
        for (int y = G; y <= legTop + 1; y++) set(cx - 3, y, cz - 4, facing(Blocks.LADDER, Direction.NORTH));
        set(cx - 3, legTop + 1, cz - 4, facing(Blocks.LADDER, Direction.NORTH));
        set(cx - 3, legTop + 2, cz - 5, AIR);
        for (int y = legTop + 2; y <= legTop + 9; y += 7) box(cx - 1, y, cz - 5, cx + 1, y, cz - 5, s(Blocks.GRAY_CONCRETE));
    }

    // ================================================================== the school

    static void school(int x0, int z0) {
        int x1 = lx(2) + LOT - 1, z1 = z0 + LOT - 1;
        JOBS.add(() -> schoolGrounds(x0, z0, x1, z1));
        int base = G - 1;
        // The main building: a U of three wings round the courtyard, three floors of 5.
        JOBS.add(() -> wing(Direction.SOUTH, x0 + 4, z0 + 5, x0 + 96, z0 + 19, true));
        JOBS.add(() -> wing(Direction.EAST, x0 + 4, z0 + 20, x0 + 18, z0 + 60, false));
        JOBS.add(() -> wing(Direction.WEST, x0 + 82, z0 + 20, x0 + 96, z0 + 60, false));
        JOBS.add(() -> {
            // Corridors joined where the wings meet.
            for (int f = 0; f < 3; f++) {
                int y = base + 5 * f;
                box(x0 + 15, y + 1, z0 + 19, x0 + 17, y + 3, z0 + 20, AIR);
                box(x0 + 83, y + 1, z0 + 19, x0 + 85, y + 3, z0 + 20, AIR);
            }
            // Stairwells at both ends of the north wing, opening onto its corridor.
            for (int sx : new int[] {x0 + 6, x0 + 90}) {
                frame(Direction.NORTH, sx, z0 + 7, sx + 3, z0 + 13);
                stairwell(0, 1, base, 3, 5, Blocks.STONE_BRICK_STAIRS, s(Blocks.SMOOTH_STONE));
                for (int f = 0; f <= 3; f++) {
                    int y = base + 5 * f;
                    if (f < 3) box(sx, y + 1, z0 + 14, sx + 3, y + 3, z0 + 15, AIR);
                    box(sx, y, z0 + 14, sx + 3, y, z0 + 15, s(Blocks.SMOOTH_STONE));
                }
            }
        });
        JOBS.add(() -> clockTower(x0 + 46, z0 + 6));
        JOBS.add(() -> gym(x0 + 104, z0 + 4, 47, 30));
        JOBS.add(() -> field(x0 + 102, z0 + 40, x0 + 183, z0 + 83));
    }

    static void schoolGrounds(int x0, int z0, int x1, int z1) {
        Random r = new Random(5150);
        // The street that used to run through: gone (lamps and kerbs), grass in its place.
        box(lx(1) + LOT, G, z0, lx(2) - 1, G + 6, z1, AIR);
        box(lx(1) + LOT, G - 1, z0, lx(2) - 1, G - 1, z1, s(Blocks.GRASS_BLOCK));
        // Courtyard pavement, the path from the gate, the forecourt.
        for (int x = x0 + 19; x <= x0 + 81; x++)
            for (int z = z0 + 20; z <= z0 + 60; z++)
                set(x, G - 1, z, ((x + z) % 2 == 0) ? s(Blocks.SMOOTH_STONE) : s(Blocks.POLISHED_ANDESITE));
        box(x0 + 46, G - 1, z0 + 61, x0 + 54, G - 1, z1, s(Blocks.POLISHED_ANDESITE));
        box(x0 + 46, G - 1, z0 + 61, x0 + 46, G - 1, z1, s(Blocks.SMOOTH_STONE));
        box(x0 + 54, G - 1, z0 + 61, x0 + 54, G - 1, z1, s(Blocks.SMOOTH_STONE));
        for (int z = z0 + 64; z < z1 - 2; z += 6) {
            tree(x0 + 43, z, 2, r);
            tree(x0 + 57, z, 2, r);
        }
        // Courtyard: planters with trees, benches, a flagpole; the spawn marked in the middle.
        for (int[] t : new int[][] {{x0 + 26, z0 + 27}, {x0 + 74, z0 + 27}, {x0 + 26, z0 + 53}, {x0 + 74, z0 + 53}}) {
            box(t[0] - 2, G - 1, t[1] - 2, t[0] + 2, G - 1, t[1] + 2, s(Blocks.GRASS_BLOCK));
            walls(t[0] - 2, G, t[1] - 2, t[0] + 2, G, t[1] + 2, slab(Blocks.SMOOTH_STONE_SLAB, false));
            tree(t[0], t[1], t[0] < x0 + 50 ? 2 : 0, r);
            bench(t[0] - 1, t[1] + 4, Direction.SOUTH);
        }
        for (int k = 0; k <= 12; k++) set(x0 + 60, G + k, z0 + 34, s(Blocks.IRON_BARS));
        box(x0 + 61, G + 10, z0 + 34, x0 + 63, G + 12, z0 + 34, s(Blocks.WHITE_WOOL));
        set(x0 + 62, G + 11, z0 + 34, s(Blocks.RED_WOOL));
        set(spawn.getX(), G - 1, spawn.getZ(), s(Blocks.CHISELED_STONE_BRICKS));
        post(spawn.getX(), G, spawn.getZ() - 4, 0, "Kizuna", "Welcome", "Dojos all round", "town. Spar anywhere");
        // Hedges round the grounds; the front wall and gate.
        BlockState hedge = leaves(Blocks.OAK_LEAVES);
        box(x0, G, z0, x1, G + 1, z0, hedge);
        box(x0, G, z0, x0, G + 1, z1, hedge);
        box(x1, G, z0, x1, G + 1, z1, hedge);
        box(x0 + 101, G, z1, x1, G + 1, z1, hedge);
        for (int x = x0; x <= x0 + 100; x++) {
            if (x >= x0 + 46 && x <= x0 + 54) continue;
            set(x, G, z1, s(Blocks.STONE_BRICKS));
            set(x, G + 1, z1, s(Blocks.WHITE_CONCRETE));
            set(x, G + 2, z1, s(Blocks.WHITE_CONCRETE));
            set(x, G + 3, z1, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        }
        for (int gx : new int[] {x0 + 45, x0 + 55}) {
            box(gx, G, z1, gx, G + 4, z1, s(Blocks.STONE_BRICKS));
            set(gx, G + 5, z1, lantern(false));
        }
        sign(x0 + 45, G + 2, z1 + 1, Blocks.DARK_OAK_WALL_SIGN, Direction.SOUTH, "KIZUNA", "ACADEMY", "", "Est. today");
        // A bike shelter by the gym.
        for (int x = x0 + 100; x <= x0 + 110; x += 5) box(x, G, z0 + 36, x, G + 2, z0 + 36, s(Blocks.POLISHED_BLACKSTONE_WALL));
        box(x0 + 100, G + 3, z0 + 35, x0 + 110, G + 3, z0 + 37, slab(Blocks.SMOOTH_STONE_SLAB, false));
    }

    /**
     * A school wing in its own frame (front = the courtyard side): the corridor along the front, a row
     * of classrooms behind it with desks, chairs and a blackboard, windows both sides, a fenced roof.
     */
    static void wing(Direction toCourt, int ax, int az, int bx, int bz, boolean entrance) {
        frame(toCourt, ax, az, bx, bz);
        int len = (toCourt.getAxis() == Direction.Axis.Z ? bx - ax : bz - az) + 1;
        int dep = (toCourt.getAxis() == Direction.Axis.Z ? bz - az : bx - ax) + 1;
        int base = G - 1, top = base + 15;
        BlockState wallB = s(Blocks.WHITE_TERRACOTTA), band = s(Blocks.LIGHT_GRAY_CONCRETE), pane = s(Blocks.GLASS);
        for (int y = base + 1; y <= top; y++) {
            int fy = (y - base) % 5;
            for (int u = 0; u < len; u++)
                for (int v : new int[] {0, dep - 1}) {
                    boolean pillar = u == 0 || u == len - 1 || u % 4 == 0;
                    ls(u, y, v, fy == 0 ? band : pillar ? wallB : (fy == 2 || fy == 3) ? pane : wallB);
                }
            for (int v = 1; v < dep - 1; v++)
                for (int u : new int[] {0, len - 1}) ls(u, y, v, fy == 0 ? band : (fy == 2 || fy == 3) && v % 4 != 0 ? pane : wallB);
        }
        for (int f = 0; f <= 3; f++) {
            int y = base + 5 * f;
            for (int u = 1; u < len - 1; u++)
                for (int v = 1; v < dep - 1; v++) {
                    boolean light = f > 0 && u % 5 == 2 && v % 4 == 2;
                    BlockState fl = f == 3 ? s(Blocks.LIGHT_GRAY_CONCRETE) : v <= 3 ? s(Blocks.SMOOTH_STONE) : s(Blocks.OAK_PLANKS);
                    ls(u, y, v, light ? s(Blocks.SEA_LANTERN) : fl);
                }
        }
        // Classrooms behind the corridor, 10 long each.
        for (int f = 0; f < 3; f++) {
            int y = base + 5 * f;
            for (int u = 1; u < len - 1; u++)
                for (int k = 1; k <= 4; k++) ls(u, y + k, 4, (k == 3) && u % 3 == 1 ? pane : s(Blocks.WHITE_CONCRETE));
            for (int a = 0; a + 10 < len; a += 10) {
                if (a > 0) lbox(a, y + 1, 5, a, y + 4, dep - 2, s(Blocks.WHITE_CONCRETE));
                lbox(a + 2, y + 1, 4, a + 3, y + 3, 4, AIR);
                // Blackboard on the left wall (or the end wall), teacher's desk, twelve desks facing it.
                int bu = a == 0 ? 1 : a;
                lbox(bu, y + 2, 7, bu, y + 3, 11, s(Blocks.GREEN_TERRACOTTA));
                ls(a + 2, y + 1, 9, slab(Blocks.SPRUCE_SLAB, true));
                for (int du = a + 4; du <= a + 8; du += 2)
                    for (int dv = 6; dv <= dep - 3; dv += 2) {
                        ls(du, y + 1, dv, slab(Blocks.BIRCH_SLAB, true));
                        ls(du + 1, y + 1, dv, stair(Blocks.OAK_STAIRS, right, false));
                    }
            }
        }
        // The roof: a fence round it (the classic place to settle things), with a way up the courtyard face.
        lwalls(0, top + 1, 0, len - 1, top + 2, dep - 1, s(Blocks.IRON_BARS));
        climb(fx(3, 0), fz(3, 0), front, top + 1);
        lbox(3, top + 1, 0, 6, top + 2, 0, AIR);
        if (entrance) {
            int mu = len / 2;
            lbox(mu - 2, base + 1, 0, mu + 1, base + 3, 0, AIR);
            lbox(mu - 3, base + 4, -3, mu + 2, base + 4, -1, slab(Blocks.SMOOTH_STONE_SLAB, true));
            ls(mu - 3, base + 1, -3, s(Blocks.QUARTZ_PILLAR));
            ls(mu - 3, base + 2, -3, s(Blocks.QUARTZ_PILLAR));
            ls(mu - 3, base + 3, -3, s(Blocks.QUARTZ_PILLAR));
            ls(mu + 2, base + 1, -3, s(Blocks.QUARTZ_PILLAR));
            ls(mu + 2, base + 2, -3, s(Blocks.QUARTZ_PILLAR));
            ls(mu + 2, base + 3, -3, s(Blocks.QUARTZ_PILLAR));
            // Shoe lockers inside the door.
            lbox(mu - 6, base + 1, 2, mu - 4, base + 2, 2, s(Blocks.SPRUCE_PLANKS));
            lbox(mu + 3, base + 1, 2, mu + 5, base + 2, 2, s(Blocks.SPRUCE_PLANKS));
        } else {
            int mu = len / 2;
            lbox(mu - 1, base + 1, 0, mu, base + 3, 0, AIR);
        }
    }

    /** The clock tower on the north wing: faces on all four sides, a copper roof, a ladder up inside. */
    static void clockTower(int x0, int z0) {
        int base = G - 1, roof = base + 15, top = roof + 17;
        for (int y = roof + 1; y <= top; y++)
            for (int x = x0; x <= x0 + 6; x++)
                for (int z = z0; z <= z0 + 6; z++) {
                    if (x != x0 && x != x0 + 6 && z != z0 && z != z0 + 6) continue;
                    boolean corner = (x == x0 || x == x0 + 6) && (z == z0 || z == z0 + 6);
                    boolean bell = y >= top - 3 && y <= top - 1 && !corner && (x == x0 + 3 || z == z0 + 3 || x == x0 + 2 || x == x0 + 4 || z == z0 + 2 || z == z0 + 4);
                    set(x, y, z, corner ? s(Blocks.QUARTZ_PILLAR) : bell ? AIR : s(Blocks.WHITE_TERRACOTTA));
                }
        // Clock faces.
        int cy = roof + 9, mx = x0 + 3, mz = z0 + 3;
        for (int a = -2; a <= 2; a++)
            for (int b = -2; b <= 2; b++) {
                if (a * a + b * b > 5) continue;
                set(x0 + 3 + a, cy + b, z0 - 1, s(Blocks.WHITE_CONCRETE));
                set(x0 + 3 + a, cy + b, z0 + 7, s(Blocks.WHITE_CONCRETE));
                set(x0 - 1, cy + b, z0 + 3 + a, s(Blocks.WHITE_CONCRETE));
                set(x0 + 7, cy + b, z0 + 3 + a, s(Blocks.WHITE_CONCRETE));
            }
        for (int k = 0; k <= 2; k++) {
            set(mx, cy + k, z0 - 1, s(Blocks.BLACK_CONCRETE));
            set(mx, cy + k, z0 + 7, s(Blocks.BLACK_CONCRETE));
            set(x0 - 1, cy + k, mz, s(Blocks.BLACK_CONCRETE));
            set(x0 + 7, cy + k, mz, s(Blocks.BLACK_CONCRETE));
        }
        for (int k = 1; k <= 1; k++) {
            set(mx + k, cy, z0 - 1, s(Blocks.BLACK_CONCRETE));
            set(mx - k, cy, z0 + 7, s(Blocks.BLACK_CONCRETE));
            set(x0 - 1, cy, mz - k, s(Blocks.BLACK_CONCRETE));
            set(x0 + 7, cy, mz + k, s(Blocks.BLACK_CONCRETE));
        }
        // Floors: the belfry floor near the top; a ladder from the roof.
        box(x0 + 1, top - 4, z0 + 1, x0 + 5, top - 4, z0 + 5, s(Blocks.DARK_OAK_PLANKS));
        for (int y = roof + 1; y <= top - 3; y++) set(x0 + 3, y, z0 + 1, facing(Blocks.LADDER, Direction.SOUTH));
        set(x0 + 3, top - 2, z0 + 3, s(Blocks.BELL));
        set(x0 + 3, top - 1, z0 + 3, s(Blocks.DARK_OAK_PLANKS));
        box(x0 + 3, roof + 1, z0 + 6, x0 + 3, roof + 2, z0 + 6, AIR);
        // Copper roof.
        for (int k = 0; k <= 3; k++) box(x0 - 1 + k, top + 1 + k, z0 - 1 + k, x0 + 7 - k, top + 1 + k, z0 + 7 - k, s(Blocks.WAXED_OXIDIZED_COPPER));
        set(x0 + 3, top + 5, z0 + 3, s(Blocks.LIGHTNING_ROD));
    }

    /** The gym: an arched green roof, a wooden court with hoops, a stage with curtains. */
    static void gym(int x0, int z0, int wx, int wz) {
        int x1 = x0 + wx - 1, z1 = z0 + wz - 1, base = G - 1, top = base + 10;
        box(x0, base, z0, x1, base, z1, s(Blocks.OAK_PLANKS));
        for (int y = base + 1; y <= top; y++)
            for (int x = x0; x <= x1; x++)
                for (int z = z0; z <= z1; z++) {
                    if (x != x0 && x != x1 && z != z0 && z != z1) continue;
                    int along = (z == z0 || z == z1) ? x - x0 : z - z0;
                    boolean win = y >= base + 6 && y <= base + 8 && along % 4 != 0;
                    set(x, y, z, y <= base + 1 ? s(Blocks.STONE_BRICKS) : win ? s(Blocks.GLASS) : s(Blocks.WHITE_TERRACOTTA));
                }
        // The arch, along x, with its ends closed.
        int[] hgt = new int[wz];
        for (int dz = 0; dz < wz; dz++) {
            double t = (dz - (wz - 1) / 2.0) / (wz / 2.0 + 0.5);
            hgt[dz] = (int) Math.round(7 * (1 - t * t));
        }
        for (int dz = 0; dz < wz; dz++) {
            int lo = Math.min(hgt[dz], Math.min(dz > 0 ? hgt[dz - 1] : hgt[dz], dz < wz - 1 ? hgt[dz + 1] : hgt[dz]));
            for (int k = lo; k <= hgt[dz]; k++) box(x0, top + 1 + k, z0 + dz, x1, top + 1 + k, z0 + dz, s(Blocks.GREEN_TERRACOTTA));
            for (int k = 0; k < lo; k++) {
                set(x0, top + 1 + k, z0 + dz, s(Blocks.WHITE_TERRACOTTA));
                set(x1, top + 1 + k, z0 + dz, s(Blocks.WHITE_TERRACOTTA));
            }
        }
        // Court lines, hoops at both ends, a stage at the east end.
        int cz = z0 + wz / 2;
        for (int x = x0 + 4; x <= x1 - 12; x++)
            for (int z = cz - 8; z <= cz + 8; z++) {
                boolean line = x == x0 + 4 || x == x1 - 12 || z == cz - 8 || z == cz + 8 || x == (x0 + x1 - 8) / 2;
                if (line) set(x, base, z, s(Blocks.BIRCH_PLANKS));
            }
        for (int hx : new int[] {x0 + 1, x1 - 9}) {
            boolean west = hx == x0 + 1;
            int bx = west ? x0 + 1 : x1 - 9;
            box(bx, base + 5, cz - 1, bx, base + 6, cz + 1, s(Blocks.WHITE_CONCRETE));
            if (!west) box(bx, base + 1, cz, bx, base + 4, cz, s(Blocks.POLISHED_BLACKSTONE_WALL));
            set(bx + (west ? 1 : -1), base + 5, cz, s(Blocks.IRON_BARS));
        }
        box(x1 - 7, base + 1, z0 + 1, x1 - 1, base + 2, z1 - 1, s(Blocks.OAK_PLANKS));
        box(x1 - 8, base + 1, cz - 2, x1 - 8, base + 1, cz + 2, stair(Blocks.OAK_STAIRS, Direction.EAST, false));
        box(x1 - 7, base + 3, z0 + 1, x1 - 7, base + 9, z0 + 4, s(Blocks.RED_WOOL));
        box(x1 - 7, base + 3, z1 - 4, x1 - 7, base + 9, z1 - 1, s(Blocks.RED_WOOL));
        for (int x = x0 + 4; x < x1; x += 8)
            for (int z = z0 + 6; z < z1; z += 9) {
                for (int k = 0; k < 4; k++) set(x, top + 1 - k + 4, z, s(Blocks.CHAIN));
                set(x, top + 1, z, lantern(true));
            }
        climb(x0 + 3, z1, Direction.SOUTH, top + 1 + hgt[wz - 1]);
        // Doors: two to the field, one to the school.
        box(x0 + 11, base + 1, z1, x0 + 13, base + 3, z1, AIR);
        box(x1 - 14, base + 1, z1, x1 - 12, base + 3, z1, AIR);
        box(x0, base + 1, cz - 1, x0, base + 3, cz + 1, AIR);
    }

    /** The sports field: a running track round a pitch with goals, a small stand on the north side. */
    static void field(int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                boolean track = x < x0 + 4 || x > x1 - 4 || z < z0 + 4 || z > z1 - 4;
                if (track) {
                    boolean lane = x == x0 + 2 || x == x1 - 2 || z == z0 + 2 || z == z1 - 2;
                    set(x, G - 1, z, lane ? PAINT : s(Blocks.RED_TERRACOTTA));
                }
            }
        int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        box(cx, G - 1, z0 + 4, cx, G - 1, z1 - 4, PAINT);
        ring(cx, cz, G - 1, 5.5, 6.5, PAINT);
        for (int gx : new int[] {x0 + 5, x1 - 5}) {
            box(gx, G, cz - 3, gx, G + 2, cz - 3, s(Blocks.WHITE_CONCRETE));
            box(gx, G, cz + 3, gx, G + 2, cz + 3, s(Blocks.WHITE_CONCRETE));
            box(gx, G + 2, cz - 3, gx, G + 2, cz + 3, s(Blocks.WHITE_CONCRETE));
        }
        for (int t = 0; t < 3; t++) {
            int z = z0 - 2 - t, y = G + t;
            box(x0 + 18, y, z, x0 + 60, y, z, stair(Blocks.QUARTZ_STAIRS, Direction.NORTH, false));
            if (t > 0) box(x0 + 18, G, z, x0 + 60, y - 1, z, s(Blocks.STONE_BRICKS));
        }
    }

    // ================================================================== the tournament hall

    static void tournament(int x0, int z0) {
        int bx0 = x0 + 8, bx1 = x0 + 78, bz0 = z0 + 10, bz1 = z0 + 76, cx = x0 + 43, cz = z0 + 43;
        JOBS.add(() -> {
            Random r = new Random(6161);
            for (int x = x0; x < x0 + LOT; x++)
                for (int z = z0; z < z0 + LOT; z++) {
                    double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    set(x, G - 1, z, ((int) d) % 6 == 0 ? s(Blocks.POLISHED_ANDESITE) : s(Blocks.SMOOTH_STONE));
                }
            for (int x = x0 + 4; x < x0 + LOT - 4; x += 10) {
                for (int k = 0; k < 8; k++) set(x, G + k, z0 + 82, s(Blocks.POLISHED_BLACKSTONE_WALL));
                Block f = (x / 10) % 2 == 0 ? Blocks.RED_WOOL : Blocks.WHITE_WOOL;
                box(x + 1, G + 6, z0 + 82, x + 3, G + 7, z0 + 82, s(f));
            }
            for (int[] t : new int[][] {{x0 + 3, z0 + 3}, {x0 + 83, z0 + 3}, {x0 + 3, z0 + 80}, {x0 + 83, z0 + 80}}) tree(t[0], t[1], 2, r);
        });
        JOBS.add(() -> {
            int base = G - 1, top = base + 20;
            box(bx0, base, bz0, bx1, base, bz1, s(Blocks.POLISHED_ANDESITE));
            for (int y = base + 1; y <= top; y++)
                for (int x = bx0; x <= bx1; x++)
                    for (int z = bz0; z <= bz1; z++) {
                        if (x != bx0 && x != bx1 && z != bz0 && z != bz1) continue;
                        boolean corner = (x == bx0 || x == bx1) && (z == bz0 || z == bz1);
                        int along = (z == bz0 || z == bz1) ? x - bx0 : z - bz0;
                        BlockState b;
                        if (y <= base + 4 || corner) b = s(Blocks.DEEPSLATE_TILES);
                        else if (along % 6 == 0) b = s(Blocks.RED_CONCRETE);
                        else if (y >= base + 14 && y <= base + 16) b = s(Blocks.GLASS);
                        else b = s(Blocks.WHITE_CONCRETE);
                        set(x, y, z, b);
                    }
            for (int x = bx0 + 1; x < bx1; x++)
                for (int z = bz0 + 1; z < bz1; z++) {
                    boolean sky = Math.abs(x - cx) < 18 && Math.abs(z - cz) < 18 && (x - cx) % 4 != 0 && (z - cz) % 4 != 0;
                    boolean lamp = !sky && (x - bx0) % 6 == 3 && (z - bz0) % 6 == 3;
                    set(x, top, z, sky ? s(Blocks.GLASS) : lamp ? s(Blocks.SEA_LANTERN) : s(Blocks.GRAY_CONCRETE));
                }
            walls(bx0, top + 1, bz0, bx1, top + 1, bz1, s(Blocks.DEEPSLATE_TILES));
            walls(bx0 - 1, top, bz0 - 1, bx1 + 1, top, bz1 + 1, slab(Blocks.DEEPSLATE_TILE_SLAB, true));
            // The main doors and canopy on the south, a fighters' door on the north.
            box(cx - 5, base + 1, bz1, cx + 4, base + 6, bz1, AIR);
            box(cx - 8, base + 8, bz1 + 1, cx + 7, base + 8, bz1 + 5, slab(Blocks.DEEPSLATE_TILE_SLAB, true));
            for (int px : new int[] {cx - 8, cx + 7}) box(px, base + 1, bz1 + 5, px, base + 7, bz1 + 5, s(Blocks.QUARTZ_PILLAR));
            sign(cx - 1, base + 9, bz1 + 1, Blocks.DARK_OAK_WALL_SIGN, Direction.SOUTH, "KIZUNA", "BUDOKAN", "", "");
            sign(cx, base + 9, bz1 + 1, Blocks.DARK_OAK_WALL_SIGN, Direction.SOUTH, "Karate", "Tournament", "", "All welcome");
            box(cx - 2, base + 1, bz0, cx + 1, base + 4, bz0, AIR);
            climb(bx0, bz0 + 6, Direction.WEST, top + 1);
            climb(bx1, bz1 - 10, Direction.EAST, top + 1);
        });
        JOBS.add(() -> {
            // The ring: a raised wooden platform, a red-bordered blue mat, a step all round.
            int base = G - 1;
            for (int x = cx - 8; x <= cx + 7; x++)
                for (int z = cz - 8; z <= cz + 7; z++) {
                    boolean step = x == cx - 8 || x == cx + 7 || z == cz - 8 || z == cz + 7;
                    boolean rim = x == cx - 7 || x == cx + 6 || z == cz - 7 || z == cz + 6;
                    boolean border = x <= cx - 5 || x >= cx + 4 || z <= cz - 5 || z >= cz + 4;
                    set(x, G, z, step ? slab(Blocks.DARK_OAK_SLAB, false) : rim ? s(Blocks.DARK_OAK_PLANKS) : border ? s(Blocks.RED_WOOL) : s(Blocks.BLUE_WOOL));
                }
            // Judges' corners.
            for (int[] c : new int[][] {{cx - 10, cz - 10}, {cx + 9, cz - 10}, {cx - 10, cz + 9}, {cx + 9, cz + 9}}) {
                set(c[0], G, c[1], stair(Blocks.DARK_OAK_STAIRS, c[1] < cz ? Direction.NORTH : Direction.SOUTH, false));
            }
            // Stands on all four sides, rising away from the ring, with tunnels under the north and south.
            for (int t = 0; t <= 10; t++) {
                int sz = 12 + t, y = G + t;
                Block seat = t % 2 == 0 ? Blocks.QUARTZ_STAIRS : Blocks.RED_NETHER_BRICK_STAIRS;
                for (int x = cx - sz; x <= cx + sz - 1; x++)
                    for (int z = cz - sz; z <= cz + sz - 1; z++) {
                        boolean wE = x == cx - sz, eE = x == cx + sz - 1, nE = z == cz - sz, sE = z == cz + sz - 1;
                        if (!(wE || eE || nE || sE)) continue;
                        BlockState b;
                        if ((wE || eE) && (nE || sE)) b = s(Blocks.DEEPSLATE_BRICKS);
                        else b = stair(seat, wE ? Direction.WEST : eE ? Direction.EAST : nE ? Direction.NORTH : Direction.SOUTH, false);
                        set(x, y, z, b);
                        if (t > 0) box(x, G, z, x, y - 1, z, s(Blocks.DEEPSLATE_BRICKS));
                    }
            }
            // A walkway behind the top row, with a railing.
            for (int sz = 23; sz <= 24; sz++)
                for (int x = cx - sz; x <= cx + sz - 1; x++)
                    for (int z = cz - sz; z <= cz + sz - 1; z++) {
                        if (x != cx - sz && x != cx + sz - 1 && z != cz - sz && z != cz + sz - 1) continue;
                        box(x, G, z, x, G + 10, z, s(Blocks.DEEPSLATE_BRICKS));
                        if (sz == 24) set(x, G + 11, z, s(Blocks.IRON_BARS));
                    }
            for (int zz = cz + 12; zz <= cz + 24; zz++) box(cx - 2, G, zz, cx + 1, G + 3, zz, AIR);
            for (int zz = cz - 25; zz <= cz - 12; zz++) box(cx - 2, G, zz, cx + 1, G + 3, zz, AIR);
            // Stairs up from the lobby to the walkway, along the back of the south stand, both sides.
            for (int k = 0; k <= 10; k++) {
                set(cx - 17 + k, G + k, cz + 25, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.EAST, false));
                set(cx + 16 - k, G + k, cz + 25, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.WEST, false));
            }
            box(cx - 6, G + 10, cz + 24, cx - 6, G + 10, cz + 25, s(Blocks.DEEPSLATE_BRICKS));
            box(cx + 5, G + 10, cz + 24, cx + 5, G + 10, cz + 25, s(Blocks.DEEPSLATE_BRICKS));
            set(cx - 6, G + 11, cz + 23, AIR);
            set(cx + 5, G + 11, cz + 23, AIR);
        });
        JOBS.add(() -> {
            int base = G - 1, top = base + 20;
            // The light rig over the ring, and the score box.
            walls(cx - 9, G + 15, cz - 9, cx + 8, G + 15, cz + 8, s(Blocks.POLISHED_BLACKSTONE));
            for (int x = cx - 9; x <= cx + 8; x += 2) {
                set(x, G + 14, cz - 9, s(Blocks.SEA_LANTERN));
                set(x, G + 14, cz + 8, s(Blocks.SEA_LANTERN));
            }
            for (int z = cz - 9; z <= cz + 8; z += 2) {
                set(cx - 9, G + 14, z, s(Blocks.SEA_LANTERN));
                set(cx + 8, G + 14, z, s(Blocks.SEA_LANTERN));
            }
            for (int[] c : new int[][] {{cx - 9, cz - 9}, {cx + 8, cz - 9}, {cx - 9, cz + 8}, {cx + 8, cz + 8}})
                for (int y = G + 16; y < top; y++) set(c[0], y, c[1], s(Blocks.CHAIN));
            box(cx - 2, G + 12, cz - 2, cx + 1, G + 15, cz + 1, s(Blocks.BLACK_CONCRETE));
            box(cx - 2, G + 13, cz - 2, cx + 1, G + 14, cz + 1, s(Blocks.SHROOMLIGHT));
            box(cx - 1, G + 13, cz - 2, cx, G + 14, cz + 1, s(Blocks.BLACK_CONCRETE));
            box(cx - 2, G + 13, cz - 1, cx + 1, G + 14, cz, s(Blocks.BLACK_CONCRETE));
            for (int y = G + 16; y < top; y++) set(cx, y, cz, s(Blocks.CHAIN));
            // Lobby: trophies and a ticket counter; banners on the side walls.
            for (int tx : new int[] {cx - 12, cx - 10, cx + 9, cx + 11}) {
                set(tx, G, bz1 - 3, s(Blocks.QUARTZ_PILLAR));
                set(tx, G + 1, bz1 - 3, s(Blocks.GOLD_BLOCK));
            }
            box(cx - 18, G, bz1 - 6, cx - 14, G, bz1 - 6, slab(Blocks.SMOOTH_QUARTZ_SLAB, true));
            for (int z = bz0 + 8; z < bz1 - 6; z += 10)
                for (int x : new int[] {bx0 + 1, bx1 - 1}) {
                    box(x, G + 8, z, x, G + 13, z + 1, s(Blocks.RED_WOOL));
                    box(x, G + 8, z, x, G + 8, z + 1, s(Blocks.WHITE_WOOL));
                }
        });
    }

    // ================================================================== the town centre

    private record Shop(String a, String b, Block wall, Block awning, Block trim) { }

    private static final Shop[] SHOPS = {
        new Shop("24/7", "MART", Blocks.WHITE_CONCRETE, Blocks.LIME_WOOL, Blocks.LIME_CONCRETE),
        new Shop("RAMEN", "ICHI", Blocks.DARK_OAK_PLANKS, Blocks.RED_WOOL, Blocks.RED_CONCRETE),
        new Shop("CAFE", "KUMO", Blocks.BIRCH_PLANKS, Blocks.BROWN_WOOL, Blocks.BROWN_CONCRETE),
        new Shop("ARCADE", "HOSHI", Blocks.BLACK_CONCRETE, Blocks.MAGENTA_WOOL, Blocks.MAGENTA_CONCRETE),
        new Shop("BOOKS", "& MAPS", Blocks.SPRUCE_PLANKS, Blocks.GREEN_WOOL, Blocks.GREEN_CONCRETE),
        new Shop("PHARMACY", "", Blocks.WHITE_CONCRETE, Blocks.CYAN_WOOL, Blocks.CYAN_CONCRETE),
        new Shop("BAKERY", "MOMO", Blocks.SMOOTH_SANDSTONE, Blocks.PINK_WOOL, Blocks.PINK_CONCRETE),
        new Shop("SPORTS", "GEAR", Blocks.LIGHT_GRAY_CONCRETE, Blocks.BLUE_WOOL, Blocks.BLUE_CONCRETE),
        new Shop("SUSHI", "NAMI", Blocks.STRIPPED_SPRUCE_WOOD, Blocks.WHITE_WOOL, Blocks.BLACK_CONCRETE),
        new Shop("BARBER", "", Blocks.WHITE_TERRACOTTA, Blocks.RED_WOOL, Blocks.BLUE_CONCRETE),
        new Shop("KARAOKE", "BOX", Blocks.PURPLE_CONCRETE, Blocks.YELLOW_WOOL, Blocks.YELLOW_CONCRETE),
        new Shop("LAUNDRY", "", Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.WHITE_WOOL, Blocks.LIGHT_BLUE_CONCRETE),
        new Shop("FLOWERS", "", Blocks.WHITE_CONCRETE, Blocks.LIME_WOOL, Blocks.PINK_CONCRETE),
        new Shop("HARDWARE", "", Blocks.BRICKS, Blocks.ORANGE_WOOL, Blocks.ORANGE_CONCRETE),
        new Shop("TEA", "HOUSE", Blocks.DARK_OAK_PLANKS, Blocks.GREEN_WOOL, Blocks.GREEN_CONCRETE),
        new Shop("GYM", "IRON", Blocks.GRAY_CONCRETE, Blocks.BLACK_WOOL, Blocks.RED_CONCRETE),
    };

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** A rectangle on one side of a city block: along a (0..86, west-east or north-south) and depth d from that side's street. */
    static int[] side(int x0, int z0, Direction s, int a0, int a1, int d0, int d1) {
        int e = LOT - 1;
        return switch (s) {
            case NORTH -> new int[] {x0 + a0, z0 + d0, x0 + a1, z0 + d1};
            case SOUTH -> new int[] {x0 + a0, z0 + e - d1, x0 + a1, z0 + e - d0};
            case WEST -> new int[] {x0 + d0, z0 + a0, x0 + d1, z0 + a1};
            default -> new int[] {x0 + e - d1, z0 + a0, x0 + e - d0, z0 + a1};
        };
    }

    static void townCentre(int x0, int z0, long seed) {
        int n = 0;
        for (Direction sd : SIDES)
            for (int a : new int[] {16, 29, 42, 55}) {
                int[] rc = side(x0, z0, sd, a, a + 12, 0, 15);
                Shop sh = SHOPS[n % SHOPS.length];
                long sdd = seed + n;
                boolean tall = (n % 3) == 1;
                n++;
                JOBS.add(() -> shop(rc, sd, sh, tall, sdd));
            }
        JOBS.add(() -> park(x0 + 16, z0 + 16, x0 + 70, z0 + 70, seed));
        JOBS.add(() -> corners(x0, z0, seed));
    }

    /** A shop facing the street: glass front, striped awning, its name over the door, shelves and a counter. */
    static void shop(int[] rc, Direction f, Shop sh, boolean tall, long seed) {
        Random r = new Random(seed);
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        int base = G - 1, h = tall ? 9 : 5, top = base + h;
        lbox(0, base, 0, 12, base, 1, s(Blocks.SMOOTH_STONE));
        lbox(1, base, 2, 11, base, 13, s(Blocks.POLISHED_ANDESITE));
        lwalls(1, base + 1, 2, 11, top, 13, s(sh.wall()));
        // Shopfront: glass with a door in the middle.
        lbox(2, base + 1, 2, 10, base + 3, 2, s(Blocks.GLASS));
        lbox(1, base + 1, 2, 1, base + 4, 2, s(sh.trim()));
        lbox(11, base + 1, 2, 11, base + 4, 2, s(sh.trim()));
        lbox(6, base + 1, 2, 6, base + 2, 2, AIR);
        set(fx(6, 2), base + 1, fz(6, 2), Blocks.BIRCH_DOOR.getDefaultState()
            .with(net.minecraft.block.DoorBlock.FACING, front.getOpposite())
            .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER));
        set(fx(6, 2), base + 2, fz(6, 2), Blocks.BIRCH_DOOR.getDefaultState()
            .with(net.minecraft.block.DoorBlock.FACING, front.getOpposite())
            .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));
        // Awning, sign board, the name.
        for (int u = 1; u <= 11; u++) ls(u, base + 4, 1, u % 2 == 0 ? s(Blocks.WHITE_WOOL) : s(sh.awning()));
        lbox(2, base + 5, 2, 10, base + 5, 2, s(sh.trim()));
        ls(6, base + 5, 1, facing(Blocks.DARK_OAK_WALL_SIGN, front));
        sign(fx(6, 1), base + 5, fz(6, 1), Blocks.DARK_OAK_WALL_SIGN, front, sh.a(), sh.b(), "", "Open");
        if (tall) {
            for (int u = 3; u <= 9; u += 3) ls(u, base + 7, 2, s(Blocks.GLASS));
            for (int v = 5; v <= 11; v += 3) {
                ls(1, base + 7, v, s(Blocks.GLASS));
                ls(11, base + 7, v, s(Blocks.GLASS));
            }
            lbox(2, base + 5, 3, 10, base + 5, 12, s(Blocks.SPRUCE_PLANKS));
            ls(6, base + 5, 8, s(Blocks.SEA_LANTERN));
        }
        // Roof and parapet; an AC unit.
        lbox(1, top + 1, 2, 11, top + 1, 13, s(Blocks.GRAY_CONCRETE));
        lwalls(1, top + 2, 2, 11, top + 2, 13, slab(Blocks.SMOOTH_STONE_SLAB, false));
        lbox(8, top + 2, 10, 9, top + 2, 11, s(Blocks.LIGHT_GRAY_CONCRETE));
        ls(6, (tall ? base + 5 : top + 1), 8, s(Blocks.SEA_LANTERN));
        ls(4, top + 1, 6, s(Blocks.SEA_LANTERN));
        // Inside: shelves down the sides, a counter at the back.
        for (int v = 5; v <= 11; v++) {
            ls(2, base + 1, v, s(Blocks.BARREL));
            ls(2, base + 2, v, slab(Blocks.SPRUCE_SLAB, false));
            ls(10, base + 1, v, r.nextBoolean() ? s(Blocks.BOOKSHELF) : s(Blocks.BARREL));
        }
        lbox(4, base + 1, 11, 8, base + 1, 11, slab(Blocks.SMOOTH_QUARTZ_SLAB, true));
        // Bins against the side wall and lips up it to the roof.
        climb(fx(11, 6), fz(11, 6), right, top + 2);
        lbox(5, base + 1, 7, 7, base + 1, 7, slab(Blocks.SPRUCE_SLAB, true));
    }

    /** The town park: lawn, crossing paths, a fountain in a round plaza, a pond with a bridge, cherry trees, benches, lamps. */
    static void park(int x0, int z0, int x1, int z1, long seed) {
        Random r = new Random(seed + 77);
        int cx = (x0 + x1) / 2, cz = (z0 + z1) / 2;
        box(x0, G - 1, z0, x1, G - 1, z1, s(Blocks.GRASS_BLOCK));
        box(cx - 1, G - 1, z0, cx + 1, G - 1, z1, s(Blocks.DIRT_PATH));
        box(x0, G - 1, cz - 1, x1, G - 1, cz + 1, s(Blocks.DIRT_PATH));
        ring(cx, cz, G - 1, -1, 7, s(Blocks.SMOOTH_STONE));
        ring(cx, cz, G - 1, 6, 7, s(Blocks.POLISHED_ANDESITE));
        ring(cx, cz, G - 1, -1, 3.5, s(Blocks.STONE_BRICKS));
        ring(cx, cz, G, 3.5, 4.5, s(Blocks.STONE_BRICK_WALL));
        ring(cx, cz, G, -1, 3.5, s(Blocks.WATER));
        box(cx, G, cz, cx, G + 2, cz, s(Blocks.QUARTZ_PILLAR));
        set(cx, G + 3, cz, s(Blocks.SEA_LANTERN));
        // The pond in the north-east quarter, with a little bridge.
        int px = cx + 14, pz = cz - 14;
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -5; dz <= 5; dz++) {
                double e = (dx * dx) / 64.0 + (dz * dz) / 25.0;
                if (e > 1.15) continue;
                if (e > 0.85) set(px + dx, G - 1, pz + dz, s(Blocks.MOSSY_COBBLESTONE));
                else {
                    set(px + dx, G - 3, pz + dz, s(Blocks.CLAY));
                    set(px + dx, G - 2, pz + dz, s(Blocks.WATER));
                    set(px + dx, G - 1, pz + dz, s(Blocks.WATER));
                    if (r.nextInt(9) == 0) set(px + dx, G, pz + dz, s(Blocks.LILY_PAD));
                }
            }
        for (int dz = -6; dz <= 6; dz++) {
            set(px, G, pz + dz, slab(Blocks.SPRUCE_SLAB, false));
            set(px - 1, G + 1, pz + dz, s(Blocks.SPRUCE_FENCE));
            set(px + 1, G + 1, pz + dz, s(Blocks.SPRUCE_FENCE));
        }
        // A gazebo in the south-west quarter.
        int gx = cx - 14, gz = cz + 14;
        ring(gx, gz, G - 1, -1, 4, s(Blocks.SPRUCE_PLANKS));
        for (int[] p : new int[][] {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) box(gx + p[0], G, gz + p[1], gx + p[0], G + 3, gz + p[1], axis(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y));
        box(gx - 4, G + 4, gz - 4, gx + 4, G + 4, gz + 4, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        box(gx - 2, G + 5, gz - 2, gx + 2, G + 5, gz + 2, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        set(gx, G + 6, gz, s(Blocks.DEEPSLATE_TILES));
        set(gx, G + 3, gz, lantern(true));
        // Trees, benches, lamps.
        for (int k = 0; k < 14; k++) {
            int tx = x0 + 3 + r.nextInt(x1 - x0 - 6), tz = z0 + 3 + r.nextInt(z1 - z0 - 6);
            if (Math.abs(tx - cx) < 9 && Math.abs(tz - cz) < 9) continue;
            if (Math.abs(tx - cx) < 3 || Math.abs(tz - cz) < 3) continue;
            if (Math.abs(tx - px) < 11 && Math.abs(tz - pz) < 8) continue;
            if (Math.abs(tx - gx) < 6 && Math.abs(tz - gz) < 6) continue;
            tree(tx, tz, r.nextInt(3) == 0 ? 0 : 2, r);
        }
        for (int d = 10; d <= 24; d += 14) {
            bench(cx + 3, cz + d, Direction.WEST);
            bench(cx - 3, cz - d, Direction.EAST);
            postLamp(cx + 2, cz - d);
            postLamp(cx - 2, cz + d);
            postLamp(cx + d, cz + 2);
            postLamp(cx - d, cz - 2);
        }
    }

    /** The four corners of a block: a tree, a vending machine or two, a bench. */
    static void corners(int x0, int z0, long seed) {
        Random r = new Random(seed + 5);
        int e = LOT - 1;
        int[][] c = {{x0 + 7, z0 + 7}, {x0 + e - 7, z0 + 7}, {x0 + 7, z0 + e - 7}, {x0 + e - 7, z0 + e - 7}};
        for (int[] p : c) {
            tree(p[0], p[1], r.nextInt(3) == 0 ? 2 : 0, r);
            Direction toStreet = p[1] < z0 + 40 ? Direction.NORTH : Direction.SOUTH;
            vending(p[0] + 4, p[1] + (toStreet == Direction.NORTH ? -5 : 5), toStreet, r);
            vending(p[0] + 5, p[1] + (toStreet == Direction.NORTH ? -5 : 5), toStreet, r);
            bench(p[0] - 4, p[1] + (toStreet == Direction.NORTH ? -5 : 5), toStreet);
        }
    }

    // ================================================================== neighbourhoods and dojos

    private static final Block[] H_WALL = {Blocks.WHITE_CONCRETE, Blocks.WHITE_TERRACOTTA, Blocks.LIGHT_GRAY_CONCRETE, Blocks.BIRCH_PLANKS, Blocks.SMOOTH_SANDSTONE, Blocks.CALCITE, Blocks.SPRUCE_PLANKS, Blocks.LIGHT_BLUE_TERRACOTTA};
    private static final Block[] H_ROOF = {Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DARK_OAK_STAIRS, Blocks.BRICK_STAIRS, Blocks.PRISMARINE_BRICK_STAIRS, Blocks.RED_NETHER_BRICK_STAIRS, Blocks.MUD_BRICK_STAIRS, Blocks.SPRUCE_STAIRS, Blocks.STONE_BRICK_STAIRS};
    private static final Block[] H_RIDGE = {Blocks.DEEPSLATE_TILES, Blocks.DARK_OAK_PLANKS, Blocks.BRICKS, Blocks.PRISMARINE_BRICKS, Blocks.RED_NETHER_BRICKS, Blocks.MUD_BRICKS, Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICKS};
    private static final Block[] H_POST = {Blocks.STRIPPED_DARK_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG, Blocks.STRIPPED_OAK_LOG, Blocks.DARK_OAK_LOG};
    private static final Block[] H_DOOR = {Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR, Blocks.DARK_OAK_DOOR, Blocks.BIRCH_DOOR};
    private static final Block[] H_FENCE = {Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.DARK_OAK_FENCE, Blocks.BIRCH_FENCE};

    /**
     * A neighbourhood block: houses facing the streets all round, the dojo compound in the middle,
     * reached through a forecourt with a torii on its entry side (houses give way there).
     */
    static void neighbourhood(int x0, int z0, Direction entry, String name, long seed, boolean apartments) {
        boolean nw = entry == Direction.NORTH || entry == Direction.WEST;
        int fa = nw ? 30 : 31, fb = fa + 25;
        int n = 0;
        for (Direction sd : SIDES) {
            if (apartments && (sd == Direction.NORTH || sd == Direction.EAST)) {
                int[] rc = side(x0, z0, sd, 16, 70, 0, 15);
                long sdd = seed + 50 + n++;
                JOBS.add(() -> apartment(rc, sd, sdd));
                continue;
            }
            if (sd == entry) {
                int[][] plots = {{16, fa - 1}, {fb + 1, 70}};
                for (int[] pl : plots) {
                    int[] rc = side(x0, z0, sd, pl[0], pl[1], 0, 15);
                    long sdd = seed + n++;
                    JOBS.add(() -> house(rc, sd, sdd));
                }
                continue;
            }
            for (int a : new int[] {16, 29, 42, 55}) {
                int[] rc = side(x0, z0, sd, a, a + 12, 0, 15);
                long sdd = seed + n++;
                JOBS.add(() -> house(rc, sd, sdd));
            }
        }
        int[] fc = side(x0, z0, entry, fa, fb, 0, 15);
        int ca = nw ? 19 : 20;
        int[] cp = side(x0, z0, entry, ca, ca + 47, 18, 57);
        JOBS.add(() -> forecourt(fc, entry, name, seed));
        JOBS.add(() -> dojoGrounds(cp, entry, name, seed));
        JOBS.add(() -> dojoHall(cp, entry, name, seed));
        JOBS.add(() -> dojoRoof(cp, entry));
        JOBS.add(() -> corners(x0, z0, seed + 9));
    }

    /** A house on its plot (13+ wide, 16 deep) facing the street: front garden, one or two floors, a gable roof. */
    static void house(int[] rc, Direction f, long seed) {
        Random r = new Random(seed);
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        int wallI = r.nextInt(H_WALL.length), roofI = r.nextInt(H_ROOF.length);
        BlockState wallB = s(H_WALL[wallI]);
        BlockState post = axis(H_POST[r.nextInt(H_POST.length)], Direction.Axis.Y);
        Block roof = H_ROOF[roofI];
        Block doorB = H_DOOR[r.nextInt(H_DOOR.length)];
        int floors = r.nextInt(10) < 7 ? 2 : 1;
        int base = G - 1, top = base + 4 * floors - 1;
        // Front garden: a path to the door, a fence with a gap, maybe a tree.
        lbox(0, base, 0, 12, base, 3, s(Blocks.GRASS_BLOCK));
        lbox(6, base, 0, 6, base, 3, s(Blocks.SMOOTH_STONE));
        Block fence = H_FENCE[r.nextInt(H_FENCE.length)];
        for (int u = 0; u <= 12; u++) if (u != 6) ls(u, G, 0, s(fence));
        if (r.nextBoolean()) tree(fx(10, 2), fz(10, 2), r.nextInt(3) == 0 ? 2 : r.nextInt(2), r);
        else {
            ls(2, G, 2, leaves(Blocks.AZALEA_LEAVES));
            ls(3, G, 2, leaves(Blocks.FLOWERING_AZALEA_LEAVES));
        }
        // Body.
        lbox(2, base, 4, 10, base, 13, s(Blocks.OAK_PLANKS));
        for (int y = base + 1; y <= top; y++) {
            int fy = (y - base) % 4;
            for (int u = 2; u <= 10; u++)
                for (int v = 4; v <= 13; v++) {
                    boolean edgeU = u == 2 || u == 10, edgeV = v == 4 || v == 13;
                    if (!edgeU && !edgeV) continue;
                    BlockState b;
                    if (edgeU && edgeV) b = post;
                    else if (fy == 0) b = s(H_RIDGE[roofI]);
                    else if ((fy == 1 || fy == 2) && ((edgeV && (u == 3 || u == 4 || u == 8 || u == 9)) || (edgeU && (v == 6 || v == 7 || v == 10 || v == 11)))) b = s(Blocks.GLASS);
                    else b = wallB;
                    ls(u, y, v, b);
                }
        }
        ls(6, base + 1, 4, AIR);
        ls(6, base + 2, 4, AIR);
        set(fx(6, 4), base + 1, fz(6, 4), doorB.getDefaultState().with(net.minecraft.block.DoorBlock.FACING, front.getOpposite())
            .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER));
        set(fx(6, 4), base + 2, fz(6, 4), doorB.getDefaultState().with(net.minecraft.block.DoorBlock.FACING, front.getOpposite())
            .with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER));
        ls(6, base + 3, 3, lantern(true));
        ls(6, base + 4, 3, slab(Blocks.SMOOTH_STONE_SLAB, false));
        lbox(5, base + 4, 3, 7, base + 4, 3, slab(Blocks.SMOOTH_STONE_SLAB, false));
        // Upper floor, ceiling with a light, a ladder in the back corner.
        if (floors == 2) {
            lbox(3, base + 4, 5, 9, base + 4, 12, s(Blocks.OAK_PLANKS));
            ls(6, base + 4, 8, s(Blocks.SEA_LANTERN));
            for (int y = base + 1; y <= base + 4; y++) ls(3, y, 12, facing(Blocks.LADDER, front));
        }
        lbox(3, top, 5, 9, top, 12, s(Blocks.SPRUCE_PLANKS));
        ls(6, top, 8, s(Blocks.SEA_LANTERN));
        // Gable roof, ridge running front to back.
        int rb = top + 1;
        for (int k = 0; k <= 5; k++) {
            int y = rb + k;
            for (int v = 3; v <= 14; v++) {
                if (1 + k < 11 - k) {
                    ls(1 + k, y, v, stair(roof, right, false));
                    ls(11 - k, y, v, stair(roof, left(), false));
                } else ls(6, y, v, s(H_RIDGE[roofI]));
            }
            for (int u = 2 + k; u <= 10 - k; u++) {
                if (u == 1 + k || u == 11 - k) continue;
                ls(u, y, 4, wallB);
                ls(u, y, 13, wallB);
            }
        }
        ls(6, rb + 2, 4, s(Blocks.GLASS));
        // A rain barrel and a woodpile by the side wall, lips up to the eaves.
        climb(fx(10, 7), fz(10, 7), right, top + 1);
        // A kitchen table and a bed-ish corner, so the windows show something.
        ls(8, base + 1, 7, slab(Blocks.SPRUCE_SLAB, true));
        ls(8, base + 1, 11, s(Blocks.RED_WOOL));
    }

    /** An apartment block along a side: balconies to the street, a stairwell to the roof, a water tank. */
    static void apartment(int[] rc, Direction f, long seed) {
        Random r = new Random(seed);
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        int len = 55, floors = 6, base = G - 1, top = base + 4 * floors;
        BlockState wallA = s(Blocks.LIGHT_GRAY_CONCRETE), wallB = s(Blocks.WHITE_CONCRETE);
        lbox(0, base, 0, len - 1, base, 2, s(Blocks.SMOOTH_STONE));
        for (int y = base + 1; y <= top; y++) {
            int fy = (y - base) % 4;
            for (int u = 0; u < len; u++)
                for (int v = 3; v <= 14; v++) {
                    boolean edgeU = u == 0 || u == len - 1, edgeV = v == 3 || v == 14;
                    if (!edgeU && !edgeV) continue;
                    boolean unitEdge = u % 7 == 0;
                    BlockState b;
                    if (fy == 0) b = s(Blocks.SMOOTH_STONE);
                    else if (unitEdge || edgeU) b = wallA;
                    else if (edgeV && (fy == 1 || fy == 2) && u % 7 >= 2 && u % 7 <= 5) b = s(Blocks.GLASS);
                    else b = wallB;
                    ls(u, y, v, b);
                }
        }
        for (int fl = 0; fl <= floors; fl++) {
            int y = base + 4 * fl;
            for (int u = 1; u < len - 1; u++)
                for (int v = 4; v <= 13; v++) ls(u, y, v, (fl > 0 && u % 7 == 3 && v == 8) ? s(Blocks.SEA_LANTERN) : fl == floors ? s(Blocks.GRAY_CONCRETE) : s(Blocks.SMOOTH_STONE));
            if (fl > 0 && fl < floors) {
                // Balconies.
                lbox(1, y, 1, len - 2, y, 2, s(Blocks.SMOOTH_STONE));
                for (int u = 1; u < len - 1; u++) ls(u, y + 1, 1, u % 7 == 0 ? wallA : s(Blocks.IRON_BARS));
                for (int u = 7; u < len - 1; u += 7) lbox(u, y + 1, 1, u, y + 3, 2, wallA);
                for (int u = 3; u < len - 1; u += 7) if (r.nextInt(3) == 0) ls(u, y + 1, 2, s(Blocks.FLOWER_POT));
            }
            if (fl < floors) for (int u = 7; u < len - 1; u += 7) {
                lbox(u, y + 1, 4, u, y + 3, 13, wallA);
                lbox(u, y + 1, 9, u, y + 2, 10, AIR);
            }
        }
        // Lobby door; the stairwell at the left end, all the way to the roof.
        int mu = len / 2;
        lbox(mu - 1, base + 1, 3, mu, base + 3, 3, AIR);
        lbox(mu - 2, base + 4, 1, mu + 1, base + 4, 2, slab(Blocks.SMOOTH_STONE_SLAB, true));
        stairwell(2, 5, base, floors, 4, Blocks.STONE_BRICK_STAIRS, s(Blocks.SMOOTH_STONE));
        lwalls(0, top + 1, 3, len - 1, top + 1, 14, slab(Blocks.SMOOTH_STONE_SLAB, false));
        // The roof: a water tank, AC units, a washing line.
        int tu = len - 8;
        for (int du : new int[] {-2, 2})
            for (int dv : new int[] {-2, 2})
                for (int k = 1; k <= 3; k++) ls(tu + du, top + k, 8 + dv, s(Blocks.SPRUCE_FENCE));
        for (int k = 4; k <= 7; k++) {
            for (int du = -2; du <= 2; du++)
                for (int dv = -2; dv <= 2; dv++) {
                    if (du * du + dv * dv > 6 || du * du + dv * dv < 2) continue;
                    ls(tu + du, top + k, 8 + dv, axis(Blocks.SPRUCE_LOG, Direction.Axis.Y));
                }
        }
        for (int u = 12; u < 30; u += 5) {
            lbox(u, top + 1, 10, u + 1, top + 1, 11, s(Blocks.LIGHT_GRAY_CONCRETE));
            ls(u, top + 2, 10, Blocks.IRON_TRAPDOOR.getDefaultState());
        }
        ls(1, top + 3, 1, AIR);
        // Dumpsters under the first balconies: from there it's railing to slab, floor by floor.
        for (int u : new int[] {10, 38}) {
            lbox(u, G, 0, u + 1, G, 0, s(Blocks.GREEN_TERRACOTTA));
            lbox(u, G + 1, 0, u + 1, G + 1, 0, Blocks.DARK_OAK_TRAPDOOR.getDefaultState());
        }
        climb(fx(len - 1, 6), fz(len - 1, 6), right, top + 1);
    }

    /** The forecourt on the entry side: a stone path, a torii, lanterns, cherry trees, the dojo's sign. */
    static void forecourt(int[] rc, Direction f, String name, long seed) {
        Random r = new Random(seed + 3);
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        lbox(0, G - 1, 0, 25, G - 1, 15, s(Blocks.GRASS_BLOCK));
        for (int v = 0; v <= 15; v++)
            for (int u = 11; u <= 14; u++) ls(u, G - 1, v, (u + v) % 3 == 0 ? s(Blocks.STONE_BRICKS) : s(Blocks.SMOOTH_STONE));
        BlockState red = s(Blocks.RED_CONCRETE);
        for (int k = 0; k <= 5; k++) {
            ls(9, G + k, 7, red);
            ls(16, G + k, 7, red);
        }
        lbox(8, G + 4, 7, 17, G + 4, 7, red);
        lbox(7, G + 6, 7, 18, G + 6, 7, s(Blocks.BLACK_CONCRETE));
        ls(6, G + 6, 7, stair(Blocks.BLACKSTONE_STAIRS, right, true));
        ls(19, G + 6, 7, stair(Blocks.BLACKSTONE_STAIRS, left(), true));
        ls(12, G + 5, 7, s(Blocks.BLACK_CONCRETE));
        ls(13, G + 5, 7, s(Blocks.BLACK_CONCRETE));
        for (int v : new int[] {2, 12})
            for (int u : new int[] {8, 17}) {
                ls(u, G, v, s(Blocks.STONE_BRICK_WALL));
                ls(u, G + 1, v, s(Blocks.STONE_BRICK_WALL));
                ls(u, G + 2, v, lantern(false));
            }
        tree(fx(3, 8), fz(3, 8), 2, r);
        tree(fx(22, 8), fz(22, 8), 2, r);
        int rot = switch (f) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
        post(fx(16, 2), G, fz(16, 2), rot, name + " Dojo", "", "Open training", "Style: to come");
    }

    /** The dojo compound: raked gravel, a white wall with a tiled cap, the gate house, a koi pond, cherry trees, lanterns. */
    static void dojoGrounds(int[] rc, Direction f, String name, long seed) {
        Random r = new Random(seed + 11);
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        lbox(0, G - 1, 0, 47, G - 1, 39, s(Blocks.GRAVEL));
        lbox(1, G - 1, 1, 8, G - 1, 38, s(Blocks.MOSS_BLOCK));
        lbox(39, G - 1, 1, 46, G - 1, 12, s(Blocks.GRASS_BLOCK));
        for (int v = 1; v <= 13; v++)
            for (int u = 22; u <= 25; u++) ls(u, G - 1, v, v % 2 == 0 ? s(Blocks.POLISHED_ANDESITE) : s(Blocks.SMOOTH_STONE));
        // The wall.
        for (int u = 0; u <= 47; u++)
            for (int v = 0; v <= 39; v++) {
                if (u != 0 && u != 47 && v != 0 && v != 39) continue;
                ls(u, G, v, s(Blocks.STONE_BRICKS));
                ls(u, G + 1, v, s(Blocks.WHITE_CONCRETE));
                ls(u, G + 2, v, s(Blocks.WHITE_CONCRETE));
                ls(u, G + 3, v, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
            }
        // The gate house.
        lbox(21, G, 0, 26, G + 3, 0, AIR);
        for (int u : new int[] {20, 27}) lbox(u, G, 0, u, G + 4, 0, axis(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
        lbox(20, G + 4, 0, 27, G + 4, 0, s(Blocks.DARK_OAK_PLANKS));
        lbox(19, G + 5, -1, 28, G + 5, -1, stair(Blocks.DEEPSLATE_TILE_STAIRS, back(), false));
        lbox(19, G + 5, 1, 28, G + 5, 1, stair(Blocks.DEEPSLATE_TILE_STAIRS, front, false));
        lbox(19, G + 5, 0, 28, G + 5, 0, s(Blocks.DEEPSLATE_TILES));
        lbox(19, G + 6, 0, 28, G + 6, 0, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        ls(23, G + 3, 0, lantern(true));
        ls(24, G + 3, 0, lantern(true));
        ls(23, G + 4, 0, s(Blocks.DARK_OAK_PLANKS));
        sign(fx(20, -1), G + 2, fz(20, -1), Blocks.DARK_OAK_WALL_SIGN, front, name, "Dojo", "", "Enter with respect");
        // Koi pond.
        for (int u = 2; u <= 7; u++)
            for (int v = 4; v <= 10; v++) {
                boolean rim = u == 2 || u == 7 || v == 4 || v == 10;
                if (rim) ls(u, G - 1, v, s(Blocks.MOSSY_COBBLESTONE));
                else {
                    ls(u, G - 3, v, s(Blocks.CLAY));
                    ls(u, G - 2, v, s(Blocks.WATER));
                    ls(u, G - 1, v, s(Blocks.WATER));
                    if (r.nextInt(4) == 0) ls(u, G, v, s(Blocks.LILY_PAD));
                }
            }
        // Lanterns along the path, practice posts, trees.
        for (int v : new int[] {4, 9})
            for (int u : new int[] {20, 27}) {
                ls(u, G, v, s(Blocks.STONE_BRICK_WALL));
                ls(u, G + 1, v, lantern(false));
            }
        for (int u = 38; u <= 44; u += 3) {
            ls(u, G, 5, axis(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            ls(u, G + 1, 5, axis(Blocks.STRIPPED_SPRUCE_LOG, Direction.Axis.Y));
            ls(u, G + 2, 5, s(Blocks.HAY_BLOCK));
        }
        tree(fx(4, 22), fz(4, 22), 2, r);
        tree(fx(4, 32), fz(4, 32), 2, r);
        tree(fx(43, 30), fz(43, 30), 2, r);
        for (int u = 12; u <= 36; u += 4) bush(fx(u, 37), fz(u, 37), r);
    }

    /**
     * The dojo hall: on a stone base, a veranda all round, dark posts and beams with white walls and
     * paper windows between, wide doors open to the front, tatami inside, a shrine shelf at the back.
     */
    static void dojoHall(int[] rc, Direction f, String name, long seed) {
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        lbox(9, G - 1, 15, 38, G - 1, 34, s(Blocks.STONE_BRICKS));
        lbox(8, G, 14, 39, G, 35, slab(Blocks.SPRUCE_SLAB, true));
        lbox(10, G, 16, 37, G, 33, s(Blocks.STONE_BRICKS));
        for (int u = 11; u <= 36; u++)
            for (int v = 17; v <= 32; v++) ls(u, G, v, ((u - 11) % 4 == 0 || (v - 17) % 4 == 0) ? s(Blocks.BAMBOO_PLANKS) : s(Blocks.BAMBOO_MOSAIC));
        BlockState post = axis(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
        for (int y = G + 1; y <= G + 5; y++)
            for (int u = 10; u <= 37; u++)
                for (int v = 16; v <= 33; v++) {
                    boolean eu = u == 10 || u == 37, ev = v == 16 || v == 33;
                    if (!eu && !ev) continue;
                    int along = ev ? u - 10 : v - 16;
                    boolean isPost = (eu && ev) || along % 3 == 0;
                    BlockState b;
                    if (isPost) b = post;
                    else if (y == G + 5) b = s(Blocks.DARK_OAK_PLANKS);
                    else if (y == G + 1) b = s(Blocks.DARK_OAK_PLANKS);
                    else if (y >= G + 3 && (along / 3) % 2 == 1 && !(ev && v == 16)) b = s(Blocks.WHITE_STAINED_GLASS);
                    else b = s(Blocks.WHITE_CONCRETE);
                    ls(u, y, v, b);
                }
        // Open front.
        lbox(19, G + 1, 16, 28, G + 3, 16, AIR);
        lbox(18, G + 1, 16, 18, G + 4, 16, post);
        lbox(29, G + 1, 16, 29, G + 4, 16, post);
        // Steps up onto the veranda, front and back.
        lbox(21, G, 13, 26, G, 13, stair(Blocks.STONE_BRICK_STAIRS, back(), false));
        // Ceiling with lanterns.
        lbox(11, G + 6, 17, 36, G + 6, 32, s(Blocks.DARK_OAK_PLANKS));
        for (int u = 14; u <= 34; u += 5)
            for (int v = 20; v <= 30; v += 5) ls(u, G + 5, v, lantern(true));
        // Kamiza: a shelf with lanterns and the dojo's name.
        lbox(20, G + 2, 32, 27, G + 2, 32, slab(Blocks.DARK_OAK_SLAB, true));
        ls(21, G + 3, 32, lantern(false));
        ls(26, G + 3, 32, lantern(false));
        sign(fx(23, 32), G + 4, fz(23, 32), Blocks.DARK_OAK_WALL_SIGN, front, name + " Dojo", "", "Bow in,", "bow out");
        ls(24, G + 4, 32, s(Blocks.WHITE_WOOL));
        // A woodpile on the back corner of the veranda: up onto the eaves from there.
        lbox(8, G + 1, 34, 9, G + 2, 35, axis(Blocks.SPRUCE_LOG, Direction.Axis.X));
        lbox(38, G + 1, 34, 39, G + 1, 35, axis(Blocks.SPRUCE_LOG, Direction.Axis.Z));
        // Racks along the side walls (empty for now).
        for (int v = 20; v <= 29; v += 3) {
            ls(11, G + 2, v, slab(Blocks.SPRUCE_SLAB, true));
            ls(36, G + 2, v, slab(Blocks.SPRUCE_SLAB, true));
        }
    }

    /** The big gable roof of dark tiles, deep eaves, lanterns hanging under them, the ridge capped. */
    static void dojoRoof(int[] rc, Direction f) {
        frame(f, rc[0], rc[1], rc[2], rc[3]);
        // Wall up to the roof all round.
        for (int v = 16; v <= 33; v++) {
            int ry = G + 5 + Math.min(v - 13, 36 - v);
            for (int y = G + 6; y < ry; y++) {
                ls(10, y, v, (y - G) % 3 == 0 ? s(Blocks.DARK_OAK_PLANKS) : s(Blocks.WHITE_CONCRETE));
                ls(37, y, v, (y - G) % 3 == 0 ? s(Blocks.DARK_OAK_PLANKS) : s(Blocks.WHITE_CONCRETE));
            }
        }
        lbox(10, G + 6, 16, 37, G + 7, 16, s(Blocks.DARK_OAK_PLANKS));
        lbox(10, G + 6, 33, 37, G + 7, 33, s(Blocks.DARK_OAK_PLANKS));
        for (int k = 0; k <= 11; k++) {
            int y = G + 5 + k;
            lbox(7, y, 13 + k, 40, y, 13 + k, stair(Blocks.DEEPSLATE_TILE_STAIRS, back(), false));
            lbox(7, y, 36 - k, 40, y, 36 - k, stair(Blocks.DEEPSLATE_TILE_STAIRS, front, false));
        }
        lbox(6, G + 17, 24, 41, G + 17, 25, s(Blocks.DEEPSLATE_TILES));
        lbox(6, G + 18, 24, 41, G + 18, 25, slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        ls(6, G + 18, 24, stair(Blocks.DEEPSLATE_TILE_STAIRS, right, false));
        ls(6, G + 18, 25, stair(Blocks.DEEPSLATE_TILE_STAIRS, right, false));
        ls(41, G + 18, 24, stair(Blocks.DEEPSLATE_TILE_STAIRS, left(), false));
        ls(41, G + 18, 25, stair(Blocks.DEEPSLATE_TILE_STAIRS, left(), false));
        // Upturned corners.
        for (int[] c : new int[][] {{7, 13}, {40, 13}, {7, 36}, {40, 36}}) ls(c[0], G + 6, c[1], slab(Blocks.DEEPSLATE_TILE_SLAB, false));
        // Lanterns under the front and back eaves.
        for (int u = 12; u <= 36; u += 6) {
            ls(u, G + 5, 14, lantern(true));
            ls(u, G + 5, 35, lantern(true));
        }
    }

    // ================================================================== the land round the town

    /** How far the land runs past the town's edge. */
    static final int WILD = 176;

    private static double hash(int x, int z) {
        long h = x * 341873128712L + z * 132897987541L + 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 29)) * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    private static double vnoise(double x, double z) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
        double a = hash(xi, zi), b = hash(xi + 1, zi), c = hash(xi, zi + 1), d = hash(xi + 1, zi + 1);
        return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
    }

    private static double fbm(int x, int z) {
        return vnoise(x / 96.0, z / 96.0) * 0.55 + vnoise(x / 40.0, z / 40.0) * 0.3 + vnoise(x / 15.0, z / 15.0) * 0.15;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double smooth(double v) {
        return v * v * (3 - 2 * v);
    }

    /** How much of the sea this column is (the coast is south of town). */
    private static double sea(int x, int z) {
        int m = Math.max(Math.abs(x), Math.abs(z));
        int d = m - (MAX + 4);
        if (d <= 0) return 0;
        double south = clamp(z / (double) m);
        return smooth(clamp((south - 0.55) / 0.25)) * smooth(clamp((d - 18) / 34.0));
    }

    /** Height of the land above the town's ground (negative: under the sea). Mountains north and east, hills west, the coast south. */
    static int land(int x, int z) {
        int m = Math.max(Math.abs(x), Math.abs(z));
        int d = m - (MAX + 4);
        if (d <= 0) return 0;
        double sx = x / (double) m, sz = z / (double) m;
        double north = clamp(-sz), east = clamp(sx), west = clamp(-sx);
        double amp = 12 + 78 * Math.max(north, east * 0.8) + 22 * west;
        double rise = smooth(clamp((d - 8) / 115.0));
        double h = rise * amp * (0.4 + 0.95 * fbm(x, z)) + (d > 3 ? 2.5 * vnoise(x / 11.0, z / 11.0) : 0);
        // Far out, the mountains ease back down to hills.
        h *= 1 - 0.55 * smooth(clamp((d - 140) / 36.0));
        double s = sea(x, z);
        double depth = 2 + 7 * clamp((d - 40) / 45.0);
        return (int) Math.round(h * (1 - s) - depth * s);
    }

    static void terrain() {
        int t = MAX + 4 + WILD;
        for (int xs = -t; xs <= t; xs += 16) {
            int a = xs, b = Math.min(t, xs + 15);
            JOBS.add(() -> terrainStrip(a, b, t));
        }
    }

    private static void terrainStrip(int xa, int xb, int t) {
        Random r = new Random(xa * 31L + 7);
        int in = MAX + 4;
        for (int x = xa; x <= xb; x++)
            for (int z = -t; z <= t; z++) {
                if (Math.abs(x) <= in && Math.abs(z) <= in) continue;
                int h = land(x, z);
                int n0 = land(x + 1, z), n1 = land(x - 1, z), n2 = land(x, z + 1), n3 = land(x, z - 1);
                int minN = Math.min(Math.min(n0, n1), Math.min(n2, n3)), maxN = Math.max(Math.max(n0, n1), Math.max(n2, n3));
                int top = G - 1 + h;
                double sea = sea(x, z);
                int d = Math.max(Math.abs(x), Math.abs(z)) - in;
                if (h < 0) {
                    for (int y = top - 2; y <= top; y++) set(x, y, z, s(Blocks.SAND));
                    for (int y = top + 1; y <= G - 1; y++) set(x, y, z, s(Blocks.WATER));
                    if (hash(x * 3, z * 5) < 0.04) set(x, top + 1, z, s(Blocks.SEAGRASS));
                    continue;
                }
                boolean beach = sea > 0.12 && h <= 2;
                boolean steep = maxN - minN >= 3;
                boolean snow = top > G + 66 + (int) (hash(x, z) * 6);
                BlockState surface = snow ? s(Blocks.SNOW_BLOCK) : beach ? s(Blocks.SAND) : steep ? (hash(x, z) < 0.5 ? s(Blocks.STONE) : s(Blocks.ANDESITE)) : s(Blocks.GRASS_BLOCK);
                BlockState under = beach ? s(Blocks.SAND) : steep || snow ? s(Blocks.STONE) : s(Blocks.DIRT);
                int from = Math.max(G - 1, G - 1 + minN - 3);
                if (h == 0) from = G - 1;
                from = Math.min(from, top);
                for (int y = from; y <= top; y++) set(x, y, z, y == top ? surface : y >= top - 3 ? under : s(Blocks.STONE));
                if (surface.isOf(Blocks.GRASS_BLOCK)) {
                    double roll = hash(x * 7 + 1, z * 13 + 5);
                    double p = d < 26 ? 0.055 : top < G + 40 ? 0.03 : 0.018;
                    if (roll < p && !steep) {
                        int kind = top > G + 28 ? 3 : roll < p * 0.3 ? 1 : roll < p * 0.4 && d < 40 ? 2 : 0;
                        treeAt(x, top + 1, z, kind, r);
                    } else if (roll > 0.82) set(x, top + 1, z, s(Blocks.SHORT_GRASS));
                    else if (roll > 0.8) set(x, top + 1, z, roll > 0.81 ? s(Blocks.POPPY) : s(Blocks.DANDELION));
                }
            }
    }
}
