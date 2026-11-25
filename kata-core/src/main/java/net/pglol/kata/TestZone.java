package net.pglol.kata;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LadderBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * A training ground for trying the body and movement, built next to you: a high school's gym yard.
 * The dojo mat with sparring dummies, a vault lane (walls from knee to head height), a climbing wall
 * with ledges at different heights and a classroom block with window sills to climb, rooftops with
 * growing gaps, a long wall beside a pit for wall runs, and a bar to hang from. Signs float over each.
 *
 * Laid out on a 44 x 44 plot starting 3 blocks in front of you (towards +X and +Z).
 */
public final class TestZone {
    private TestZone() {}

    private static ServerWorld w;
    private static BlockPos o;
    private static int count;

    public static int build(ServerPlayerEntity p) {
        w = p.getServerWorld();
        o = p.getBlockPos().add(3, 0, 3);
        count = 0;
        // Clear the plot and lay the yard: smooth stone with painted lines every 8 blocks.
        fill(0, 0, 0, 43, 24, 43, Blocks.AIR.getDefaultState());
        fill(0, -1, 0, 43, -1, 43, Blocks.SMOOTH_STONE.getDefaultState());
        for (int i = 0; i <= 43; i += 8) {
            fill(i, -1, 0, i, -1, 43, Blocks.YELLOW_CONCRETE.getDefaultState());
            fill(0, -1, i, 43, -1, i, Blocks.YELLOW_CONCRETE.getDefaultState());
        }

        // Dojo mat: bamboo mosaic inside a dark wood border, three dummies.
        fill(2, -1, 2, 13, -1, 13, Blocks.DARK_OAK_PLANKS.getDefaultState());
        fill(3, -1, 3, 12, -1, 12, Blocks.BAMBOO_MOSAIC.getDefaultState());
        for (int i = 0; i < 3; i++) dummy(5 + i * 3, 0, 10);
        label(7, 3, 7, "Dojo mat", Formatting.GOLD);

        // Vault lane: walls of rising height across a running lane.
        fill(16, -1, 2, 41, -1, 6, Blocks.GRAY_CONCRETE.getDefaultState());
        wall(19, 2, 6, 1, Blocks.STONE_BRICKS.getDefaultState());
        wall(23, 2, 6, 1, Blocks.STONE_BRICKS.getDefaultState());
        fill(23, 1, 2, 23, 1, 6, slab(Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM));
        wall(27, 2, 6, 2, Blocks.STONE_BRICKS.getDefaultState());
        fill(31, 0, 2, 31, 0, 6, Blocks.OAK_FENCE.getDefaultState());
        // A desk to slide over and a gap to leap.
        fill(35, 0, 2, 36, 0, 6, slab(Blocks.OAK_SLAB, SlabType.TOP));
        fill(39, -1, 2, 40, -1, 6, Blocks.AIR.getDefaultState());
        label(17, 3, 4, "Vault lane  (1 · 1.5 · 2 high, rail, desk, gap)", Formatting.AQUA);

        // Climbing wall: heights 3, 5, 8 with a lip to hang from at the top of each.
        climb(16, 3);
        climb(21, 5);
        climb(26, 8);
        // Classroom block: 12 high, windows with sills every 3 blocks to climb between.
        fill(31, 0, 14, 41, 11, 14, Blocks.WHITE_CONCRETE.getDefaultState());
        for (int y = 2; y <= 11; y += 3) {
            for (int x = 32; x <= 40; x += 4) {
                fill(x, y, 14, x + 1, y + 1, 14, Blocks.GLASS_PANE.getDefaultState());
                fill(x, y - 1, 13, x + 1, y - 1, 13, slab(Blocks.QUARTZ_SLAB, SlabType.TOP));
            }
        }
        fill(31, 12, 14, 41, 12, 14, slab(Blocks.QUARTZ_SLAB, SlabType.BOTTOM));
        label(17, 10, 11, "Climbing wall  (3 · 5 · 8 high, ledges)", Formatting.GREEN);
        label(36, 14, 11, "Classroom block  (window sills)", Formatting.GREEN);

        // Rooftops: four buildings 6 high with gaps of 2, 3 and 4.
        int[][] roofs = {{2, 9}, {12, 17}, {21, 26}, {31, 38}};
        for (int[] r : roofs) building(r[0], r[1], 20, 28, 6);
        for (int y = 0; y < 6; y++) w.setBlockState(at(5, y, 19), Blocks.LADDER.getDefaultState().with(LadderBlock.FACING, Direction.NORTH), Block.NOTIFY_LISTENERS);
        label(20, 9, 24, "Rooftop run  (gaps 2 · 3 · 4)", Formatting.LIGHT_PURPLE);

        // Wall run: a long wall beside a pit with water at the bottom.
        fill(2, 0, 33, 22, 4, 33, Blocks.BRICKS.getDefaultState());
        fill(2, -4, 34, 22, -1, 37, Blocks.AIR.getDefaultState());
        fill(2, -5, 34, 22, -5, 37, Blocks.STONE.getDefaultState());
        fill(2, -4, 34, 22, -4, 37, Blocks.WATER.getDefaultState());
        label(12, 6, 35, "Wall run  (along the wall, over the pit)", Formatting.RED);

        // Hanging bar: a beam at 4 high between two posts.
        fill(26, 0, 36, 26, 3, 36, Blocks.OAK_FENCE.getDefaultState());
        fill(36, 0, 36, 36, 3, 36, Blocks.OAK_FENCE.getDefaultState());
        fill(26, 4, 36, 36, 4, 36, Blocks.OAK_FENCE.getDefaultState());
        label(31, 6, 36, "Hanging bar", Formatting.YELLOW);

        w = null;
        return count;
    }

    private static BlockPos at(int x, int y, int z) {
        return o.add(x, y, z);
    }

    private static void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    w.setBlockState(at(x, y, z), s, Block.NOTIFY_LISTENERS);
                    count++;
                }
    }

    private static BlockState slab(Block b, SlabType t) {
        return b.getDefaultState().with(SlabBlock.TYPE, t);
    }

    /** A wall across the vault lane (z 2..6). */
    private static void wall(int x, int z0, int z1, int h, BlockState s) {
        fill(x, 0, z0, x, h - 1, z1, s);
    }

    /** A 4-wide climbing wall facing the yard, with a lip at the top to grab. */
    private static void climb(int x, int h) {
        fill(x, 0, 14, x + 3, h - 1, 14, Blocks.STONE_BRICKS.getDefaultState());
        fill(x, h - 1, 13, x + 3, h - 1, 13, slab(Blocks.STONE_BRICK_SLAB, SlabType.TOP));
        if (h >= 8) fill(x, 3, 13, x + 3, 3, 13, slab(Blocks.STONE_BRICK_SLAB, SlabType.TOP));
    }

    private static void building(int x0, int x1, int z0, int z1, int h) {
        fill(x0, 0, z0, x1, h - 1, z1, Blocks.WHITE_CONCRETE.getDefaultState());
        fill(x0 + 1, 0, z0 + 1, x1 - 1, h - 2, z1 - 1, Blocks.AIR.getDefaultState());
        fill(x0, h - 1, z0, x1, h - 1, z1, Blocks.SMOOTH_STONE.getDefaultState());
        fill(x0, h, z0, x1, h, z0, Blocks.IRON_BARS.getDefaultState());
        fill(x0, h, z1, x1, h, z1, Blocks.IRON_BARS.getDefaultState());
    }

    private static void dummy(int x, int y, int z) {
        Dummy d = new Dummy(KataEntities.DUMMY, w);
        // Facing into the mat.
        d.refreshPositionAndAngles(o.getX() + x + 0.5, o.getY() + y, o.getZ() + z + 0.5, 180, 0);
        d.setCustomName(Text.literal("Training dummy"));
        w.spawnEntity(d);
    }

    private static void label(int x, int y, int z, String text, Formatting color) {
        ArmorStandEntity a = new ArmorStandEntity(w, o.getX() + x + 0.5, o.getY() + y, o.getZ() + z + 0.5);
        a.setInvisible(true);
        a.setNoGravity(true);
        a.setInvulnerable(true);
        a.setCustomName(Text.literal(text).formatted(color, Formatting.BOLD));
        a.setCustomNameVisible(true);
        w.spawnEntity(a);
    }
}
