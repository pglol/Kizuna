package net.pglol.katamod;

import net.pglol.kata.KataCore;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What players have changed in the town since it was built, block for block. Every block set in the
 * town world that isn't the builder's own is remembered (by position, kept in the world folder), and
 * /kata town export writes the current state at all of them to town_edits.txt in the server folder:
 * exact positions and block states (and sign/chest contents). Send that file to be folded into the
 * town. Files in the mod (data/kata_core/town_edits/*.txt) and the world's last export are laid back
 * over the town whenever it's (re)built, so nothing built by hand is lost to a rebuild.
 */
public final class TownEdits {
    private TownEdits() {}

    /** True while the town builder itself is writing (those aren't edits). */
    static boolean ours;
    private static final LongOpenHashSet TOUCHED = new LongOpenHashSet();
    private static Path dir;
    private static boolean dirty;
    private static int sinceSave;

    /** Called for every block set in any world (see TownEditMixin): cheap unless it's a player's change in town. */
    public static void changed(World world, BlockPos pos) {
        if (ours || world.isClient || world.getRegistryKey() != Town.WORLD) return;
        if (TOUCHED.add(pos.asLong())) dirty = true;
    }

    public static int count() {
        return TOUCHED.size();
    }

    static void open(MinecraftServer s) {
        dir = s.getSavePath(WorldSavePath.ROOT).resolve("kata_core");
        TOUCHED.clear();
        Path f = dir.resolve("town_touched.bin");
        if (!Files.exists(f)) return;
        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
            int n = in.readInt();
            for (int i = 0; i < n; i++) TOUCHED.add(in.readLong());
        } catch (Exception e) {
            KataCore.LOG.warn("Could not read town_touched.bin", e);
        }
    }

    static void tick() {
        if (++sinceSave >= 6000) {
            sinceSave = 0;
            save();
        }
    }

    static void save() {
        if (!dirty || dir == null) return;
        try {
            Files.createDirectories(dir);
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(dir.resolve("town_touched.bin")))) {
                out.writeInt(TOUCHED.size());
                for (long l : TOUCHED) out.writeLong(l);
            }
            dirty = false;
        } catch (Exception e) {
            KataCore.LOG.error("Could not save town_touched.bin", e);
        }
    }

    /** Writes every edited block (as it is now) to the world folder and to town_edits.txt in the server folder. */
    static int export(ServerWorld w) {
        save();
        StringBuilder sb = new StringBuilder();
        sb.append("# Kizuna town edits: P <index> <block state>, then B <x> <y> <z> <index> [| <block entity snbt>]\n");
        Map<BlockState, Integer> palette = new HashMap<>();
        List<String> blocks = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        long[] all = TOUCHED.toLongArray();
        java.util.Arrays.sort(all);
        for (long l : all) {
            m.set(BlockPos.unpackLongX(l), BlockPos.unpackLongY(l), BlockPos.unpackLongZ(l));
            BlockState st = w.getBlockState(m);
            Integer idx = palette.get(st);
            if (idx == null) {
                idx = palette.size();
                palette.put(st, idx);
                sb.append("P ").append(idx).append(' ').append(BlockArgumentParser.stringifyBlockState(st)).append('\n');
            }
            String line = "B " + m.getX() + " " + m.getY() + " " + m.getZ() + " " + idx;
            BlockEntity be = w.getBlockEntity(m);
            if (be != null) {
                NbtCompound nbt = be.createNbtWithIdentifyingData(w.getRegistryManager());
                nbt.remove("x");
                nbt.remove("y");
                nbt.remove("z");
                line += " | " + nbt.asString();
            }
            blocks.add(line);
        }
        for (String b : blocks) sb.append(b).append('\n');
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("town_edits.txt"), sb, StandardCharsets.UTF_8);
            Files.writeString(Path.of("town_edits.txt"), sb, StandardCharsets.UTF_8);
        } catch (Exception e) {
            KataCore.LOG.error("Could not write town_edits.txt", e);
            return -1;
        }
        return blocks.size();
    }

    /** Lays the edit files (the mod's, then this world's last export) back over the town: queued as a build job. */
    static void applyAll(MinecraftServer s, ServerWorld w) {
        List<String> texts = new ArrayList<>();
        try {
            Map<Identifier, Resource> res = s.getResourceManager().findResources("town_edits", id -> id.getPath().endsWith(".txt"));
            List<Identifier> ids = new ArrayList<>(res.keySet());
            ids.sort(java.util.Comparator.comparing(Identifier::toString));
            for (Identifier id : ids) {
                try (BufferedReader r = res.get(id).getReader()) {
                    StringBuilder b = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) b.append(line).append('\n');
                    texts.add(b.toString());
                }
            }
            Path own = dir == null ? null : dir.resolve("town_edits.txt");
            if (own != null && Files.exists(own)) texts.add(Files.readString(own, StandardCharsets.UTF_8));
        } catch (Exception e) {
            KataCore.LOG.error("Could not read the town edit files", e);
        }
        int n = 0;
        for (String t : texts) n += apply(w, t);
        if (n > 0) KataCore.LOG.info("Laid {} hand-built blocks back over the town", n);
    }

    private static int apply(ServerWorld w, String text) {
        Map<Integer, BlockState> palette = new HashMap<>();
        int n = 0;
        try (BufferedReader r = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("P ")) {
                    int sp = line.indexOf(' ', 2);
                    int idx = Integer.parseInt(line.substring(2, sp));
                    try {
                        palette.put(idx, BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), line.substring(sp + 1).trim(), false).blockState());
                    } catch (Exception e) {
                        KataCore.LOG.warn("Unknown block in town edits: {}", line);
                    }
                } else if (line.startsWith("B ")) {
                    String nbt = null;
                    int bar = line.indexOf(" | ");
                    if (bar > 0) {
                        nbt = line.substring(bar + 3);
                        line = line.substring(0, bar);
                    }
                    String[] p = line.split(" ");
                    int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]), z = Integer.parseInt(p[3]);
                    BlockState st = palette.get(Integer.parseInt(p[4]));
                    if (st == null) continue;
                    Town.setExact(x, y, z, st);
                    n++;
                    if (nbt != null) {
                        String snbt = nbt;
                        BlockPos at = new BlockPos(x, y, z);
                        Town.after(() -> {
                            BlockEntity be = w.getBlockEntity(at);
                            if (be == null) return;
                            try {
                                NbtCompound c = StringNbtReader.parse(snbt);
                                be.read(c, w.getRegistryManager());
                                be.markDirty();
                                w.updateListeners(at, be.getCachedState(), be.getCachedState(), 2);
                            } catch (Exception e) {
                                KataCore.LOG.warn("Could not restore block entity at {}", at);
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            KataCore.LOG.error("Could not apply town edits", e);
        }
        return n;
    }
}
