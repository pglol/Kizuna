package com.pglol.aotrpg;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

/**
 * Giant trees: trunks several blocks across, bare for the first twenty-odd blocks (where the titans
 * walk) and branching well above any titan's head, so there's always something high to swing from.
 * Grown by the world generator on the High Tree island and in the rainforest, and by the arenas.
 */
public final class GiantTrees {
    private GiantTrees() { }

    /** How a tree looks: its wood and leaves, and whether moss and vines hang off it. */
    public enum Wood {
        FOREST(Blocks.SPRUCE_LOG.getDefaultState(), Blocks.OAK_LEAVES.getDefaultState(), Blocks.DARK_OAK_LEAVES.getDefaultState(), false),
        JUNGLE(Blocks.JUNGLE_LOG.getDefaultState(), Blocks.JUNGLE_LEAVES.getDefaultState(), Blocks.AZALEA_LEAVES.getDefaultState(), true);

        final BlockState log, leaves, leaves2;
        final boolean vines;

        Wood(BlockState log, BlockState leaves, BlockState leaves2, boolean vines) {
            this.log = log;
            this.leaves = leaves.with(LeavesBlock.PERSISTENT, true);
            this.leaves2 = leaves2.with(LeavesBlock.PERSISTENT, true);
            this.vines = vines;
        }
    }

    public static void register() {
        Registry.register(Registries.FEATURE, Identifier.of("aot_rpg", "giant_tree"), new GiantTreeFeature());
        // Added to these biomes everywhere, but the tree only grows in our dimensions (see generate).
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(BiomeKeys.PLAINS, BiomeKeys.MEADOW, BiomeKeys.SUNFLOWER_PLAINS, BiomeKeys.FOREST, BiomeKeys.FLOWER_FOREST),
            GenerationStep.Feature.VEGETAL_DECORATION, RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("aot_rpg", "giant_tree_hightree")));
        BiomeModifications.addFeature(BiomeSelectors.includeByKey(BiomeKeys.JUNGLE, BiomeKeys.SPARSE_JUNGLE),
            GenerationStep.Feature.VEGETAL_DECORATION, RegistryKey.of(RegistryKeys.PLACED_FEATURE, Identifier.of("aot_rpg", "giant_tree_verdant")));
    }

    static final class GiantTreeFeature extends Feature<DefaultFeatureConfig> {
        GiantTreeFeature() {
            super(DefaultFeatureConfig.CODEC);
        }

        @Override
        public boolean generate(FeatureContext<DefaultFeatureConfig> ctx) {
            StructureWorldAccess w = ctx.getWorld();
            Identifier dim = w.toServerWorld().getRegistryKey().getValue();
            if (!dim.getNamespace().equals("aot_rpg")) return false;
            Wood wood;
            if (dim.getPath().equals("hightree")) wood = Wood.FOREST;
            else if (dim.getPath().startsWith("verdant")) wood = Wood.JUNGLE;
            else return false;
            BlockPos at = ctx.getOrigin();
            if (!w.getBlockState(at.down()).isIn(BlockTags.DIRT) || at.getY() <= w.getSeaLevel()) return false;
            // On steep ground a trunk would float: only where the land is fairly level.
            int lo = at.getY(), hi = at.getY();
            for (Direction d : Direction.Type.HORIZONTAL) {
                int y = w.getTopY(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR_WG, at.getX() + d.getOffsetX() * 4, at.getZ() + d.getOffsetZ() * 4);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            if (hi - lo > 5) return false;
            grow(w, at, ctx.getRandom(), wood, 52, 78, 14);
            return true;
        }
    }

    /**
     * Grows one giant tree with its foot at base. reach is how far anything may stick out from the
     * trunk (world generation may only write within its chunk's neighbours).
     */
    public static void grow(StructureWorldAccess w, BlockPos base, Random r, Wood wood, int minH, int maxH, int reach) {
        int h = minH + r.nextInt(Math.max(1, maxH - minH));
        int bx = base.getX(), by = base.getY(), bz = base.getZ();
        BlockPos.Mutable m = new BlockPos.Mutable();
        // The trunk, flaring at the foot and sunk into the ground so it never floats.
        for (int y = -5; y <= h; y++) {
            float t = Math.max(0, y) / (float) h;
            float rad = MathHelper.lerp(t, 3.3f, 1.3f) + (y < 6 ? (6 - Math.max(0, y)) * 0.45f : 0);
            int ri = MathHelper.ceil(rad);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                if (dx * dx + dz * dz > rad * rad) continue;
                m.set(bx + dx, by + y, bz + dz);
                if (y < 0 && !w.getBlockState(m).isAir() && !w.getBlockState(m).isReplaceable()) continue;
                log(w, m, wood, Direction.Axis.Y);
            }
        }
        // Roots: thick ridges out across the ground.
        int roots = 4 + r.nextInt(3);
        for (int i = 0; i < roots; i++) {
            double a = (i + r.nextDouble() * 0.5) / roots * Math.PI * 2;
            int len = 5 + r.nextInt(4);
            for (int s = 2; s < len + 3; s++) {
                int x = bx + (int) Math.round(Math.cos(a) * s), z = bz + (int) Math.round(Math.sin(a) * s);
                int y = by + Math.max(-1, 2 - s / 2);
                Direction.Axis ax = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
                for (int k = 0; k < 2; k++) log(w, m.set(x, y - k, z), wood, ax);
            }
        }
        // Branches only above titan height: the first one at least 24 up.
        int branches = 6 + r.nextInt(4);
        int lowest = Math.max(24, (int) (h * 0.38));
        double spin = r.nextDouble() * Math.PI * 2;
        for (int i = 0; i < branches; i++) {
            int y0 = lowest + (int) ((h - 4 - lowest) * (i / (double) branches)) + r.nextInt(3);
            double a = spin + i * 2.4 + r.nextDouble() * 0.6;
            int len = Math.min(reach - 4, 7 + r.nextInt(5));
            double cx = bx + 0.5, cy = by + y0, cz = bz + 0.5;
            Direction.Axis ax = Math.abs(Math.cos(a)) > Math.abs(Math.sin(a)) ? Direction.Axis.X : Direction.Axis.Z;
            for (int s = 0; s <= len; s++) {
                cx += Math.cos(a);
                cz += Math.sin(a);
                cy += 0.32;
                int x = MathHelper.floor(cx), y = MathHelper.floor(cy), z = MathHelper.floor(cz);
                log(w, m.set(x, y, z), wood, ax);
                // Thick near the trunk: something solid to land a hook in and stand on.
                if (s < len * 0.6) {
                    log(w, m.set(x, y - 1, z), wood, ax);
                    if (ax == Direction.Axis.X) log(w, m.set(x, y, z + 1), wood, ax);
                    else log(w, m.set(x + 1, y, z), wood, ax);
                }
            }
            blob(w, MathHelper.floor(cx), MathHelper.floor(cy) + 1, MathHelper.floor(cz), 2.6f + r.nextFloat() * 1.0f, 2.2f, r, wood, m);
        }
        // The crown: a broad, flattened mass of leaves on top.
        float cr = Math.min(reach - 2, 7 + r.nextInt(3));
        blob(w, bx, by + h + 1, bz, cr, 4.5f + r.nextFloat(), r, wood, m);
    }

    private static void blob(StructureWorldAccess w, int cx, int cy, int cz, float rx, float ry, Random r, Wood wood, BlockPos.Mutable m) {
        int ix = MathHelper.ceil(rx), iy = MathHelper.ceil(ry);
        for (int dx = -ix; dx <= ix; dx++) for (int dy = -iy; dy <= iy; dy++) for (int dz = -ix; dz <= ix; dz++) {
            double d = (dx * dx + dz * dz) / (double) (rx * rx) + dy * dy / (double) (ry * ry);
            if (d > 1 || d > 0.7 && r.nextFloat() < 0.35f) continue;
            m.set(cx + dx, cy + dy, cz + dz);
            BlockState st = w.getBlockState(m);
            if (!st.isAir() && !st.isReplaceable()) continue;
            w.setBlockState(m, r.nextFloat() < 0.25f ? wood.leaves2 : wood.leaves, 2);
            // Vines and moss hang from the rainforest's canopy.
            if (wood.vines && dy == -iy + 1 && r.nextFloat() < 0.12f) {
                int len = 2 + r.nextInt(5);
                for (int k = 1; k <= len; k++) {
                    m.set(cx + dx, cy + dy - k, cz + dz);
                    if (!w.getBlockState(m).isAir()) break;
                    w.setBlockState(m, wood.leaves2, 2);
                }
            }
        }
    }

    private static void log(StructureWorldAccess w, BlockPos p, Wood wood, Direction.Axis ax) {
        BlockState st = w.getBlockState(p);
        if (st.isOf(Blocks.BEDROCK)) return;
        w.setBlockState(p, wood.log.with(PillarBlock.AXIS, ax), 2);
    }
}
