package net.pglol.kata.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.pglol.kata.Kit;
import net.pglol.kata.KataNet;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fighter's kit on this side (survival and adventure; creative keeps Minecraft's own controls):
 *  - F does every world action: open a door or chest, use a lever, read a sign, rest in a bed, talk to
 *    someone, and pick up an item you're looking at (nothing is vacuumed up by walking over it). A
 *    single prompt by the crosshair says what F will do.
 *  - E opens the meditation menu, not the inventory.
 *  - The hands stay empty (slot 8): 1, 2, 3 use the quick slots (eat, drink, throw), then the hands go
 *    back to the stance. X draws or sheathes a weapon, and puts away a block readied to place.
 */
public final class KitClient {
    private KitClient() {}

    public static KeyBinding interactKey, drawKey;
    /** Kata's own gear slots (shoulder, elbow, gloves, belt, weapon I, weapon II), as the server last said. */
    static List<ItemStack> gear = new ArrayList<>();

    static void init() {
        interactKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.interact", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F, "category.kata_core"));
        drawKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.draw", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.kata_core"));
        ClientPlayNetworking.registerGlobalReceiver(KataNet.GearOf.ID, (payload, ctx) -> gear = new ArrayList<>(payload.items()));
    }

    public static boolean kata(PlayerEntity p) {
        return p != null && !p.isCreative() && !p.isSpectator();
    }

    static ItemStack doll(PlayerEntity p, int d) {
        return switch (d) {
            case Kit.MASK -> p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
            case Kit.CHEST -> p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
            case Kit.TROUSERS -> p.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS);
            case Kit.SHOES -> p.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET);
            default -> {
                int i = switch (d) { case Kit.SHOULDER -> 0; case Kit.ELBOW -> 1; case Kit.GLOVES -> 2; case Kit.BELT -> 3; case Kit.WEAPON_1 -> 4; default -> 5; };
                yield i < gear.size() ? gear.get(i) : ItemStack.EMPTY;
            }
        };
    }

    static void send(int op, int arg, int slot) {
        Fight.send(KataNet.KIT, op | arg << 8, slot);
    }

    // ---- start of tick: keys Minecraft would otherwise act on ----

    /** Before Minecraft reads its keys: E, F (swap hands) and the number keys are Kata's. Returns true if it took them. */
    static boolean early(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!kata(p)) return false;
        while (mc.options.inventoryKey.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new MeditationScreen());
        while (mc.options.swapHandsKey.wasPressed()) { }
        for (int i = 0; i < 3; i++) while (mc.options.hotbarKeys[i].wasPressed()) if (mc.currentScreen == null) quick(p, i);
        return true;
    }

    // ---- the quick slots ----

    private static int quick = -1, quickTicks;

    private static void quick(ClientPlayerEntity p, int q) {
        if (quick >= 0) return;
        if (p.getInventory().getStack(q).isEmpty()) {
            Hud.pop("QUICK SLOT " + (q + 1) + " EMPTY", 0xFFA79F8E);
            return;
        }
        p.getInventory().selectedSlot = q;
        quick = q;
        quickTicks = 0;
    }

    // ---- end of tick ----

    private static Target target;

    record Target(String label, Entity entity, BlockHitResult block) { }

    static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (p == null) return;
        if (!kata(p)) {
            target = null;
            while (interactKey.wasPressed()) { }
            return;
        }
        // A quick slot in use: hold the use until it's done (eaten, drunk), then hands back to the stance.
        if (quick >= 0) {
            quickTicks++;
            if (quickTicks == 1) mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
            else if (p.isUsingItem() && quickTicks < 200) mc.options.useKey.setPressed(true);
            else {
                mc.options.useKey.setPressed(false);
                p.getInventory().selectedSlot = Kit.HAND;
                quick = -1;
            }
        } else if (p.getInventory().selectedSlot != Kit.HAND) p.getInventory().selectedSlot = Kit.HAND;
        while (drawKey.wasPressed()) if (mc.currentScreen == null) send(Kit.DRAW, 0, 0);
        target = mc.currentScreen == null ? find(mc, p) : null;
        while (interactKey.wasPressed()) {
            if (mc.currentScreen != null || target == null) continue;
            act(mc, p, target);
        }
    }

    private static void act(MinecraftClient mc, ClientPlayerEntity p, Target t) {
        if (t.entity instanceof ItemEntity it) {
            Fight.send(KataNet.PICKUP, it.getId(), 0);
            grabbed(p.getId());
        } else if (t.entity != null) {
            mc.interactionManager.interactEntity(p, t.entity, Hand.MAIN_HAND);
        } else if (t.block != null) {
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, t.block);
            grabbed(p.getId());
        }
    }

    /** What F would do now: an item close in front, else whatever's under the crosshair, if it does anything. */
    private static Target find(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getCameraPosVec(1), look = p.getRotationVec(1);
        ItemEntity bestItem = null;
        double best = 1e9;
        for (ItemEntity e : p.getWorld().getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(5), e -> e.isAlive())) {
            Vec3d to = e.getPos().add(0, 0.2, 0).subtract(eye);
            double d = to.length();
            if (d > 4.5 || d < 1e-3) continue;
            double cos = to.dotProduct(look) / d;
            if (cos < 0.93) continue;
            double score = (1 - cos) * 12 + d * 0.15;
            if (score < best) {
                best = score;
                bestItem = e;
            }
        }
        if (bestItem != null) {
            ItemStack s = bestItem.getStack();
            return new Target("Pick up " + s.getName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : ""), bestItem, null);
        }
        HitResult h = mc.crosshairTarget;
        if (h instanceof EntityHitResult eh) {
            Entity e = eh.getEntity();
            if (e instanceof MerchantEntity || (e instanceof LivingEntity && !(e instanceof PlayerEntity) && !(e instanceof HostileEntity)
                && !(e instanceof net.pglol.kata.Dummy) && e.hasCustomName())) return new Target("Talk", e, null);
            return null;
        }
        if (h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK) {
            String l = label(mc, bh);
            return l == null ? null : new Target(l, null, bh);
        }
        return null;
    }

    private static String label(MinecraftClient mc, BlockHitResult bh) {
        BlockState st = mc.world.getBlockState(bh.getBlockPos());
        Block b = st.getBlock();
        String name = b.getName().getString();
        if (b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock)
            return st.contains(Properties.OPEN) && st.get(Properties.OPEN) ? "Close" : "Open";
        if (b instanceof ButtonBlock || b instanceof LeverBlock) return "Use";
        if (b instanceof AbstractSignBlock) return "Edit sign";
        if (b instanceof BedBlock) return "Rest";
        if (b instanceof CraftingTableBlock || b instanceof AnvilBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
            || b instanceof LoomBlock || b instanceof CartographyTableBlock || b instanceof SmithingTableBlock || b instanceof EnchantingTableBlock) return "Use " + name;
        if (st.createScreenHandlerFactory(mc.world, bh.getBlockPos()) != null) return "Open " + name;
        if (b instanceof BellBlock || b instanceof NoteBlock || b instanceof RepeaterBlock || b instanceof ComparatorBlock || b instanceof JukeboxBlock
            || b instanceof LecternBlock || b instanceof CakeBlock || b instanceof DaylightDetectorBlock) return "Use";
        return null;
    }

    /** The prompt by the crosshair: [F] and what it does. */
    static void hud(DrawContext c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (target == null || mc.options.hudHidden || mc.currentScreen != null) return;
        var tr = mc.textRenderer;
        String key = interactKey.getBoundKeyLocalizedText().getString().toUpperCase(java.util.Locale.ROOT);
        String label = target.label;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int kw = tr.getWidth(key) + 8, lw = tr.getWidth(label);
        int total = kw + 6 + lw, x = w / 2 - total / 2, y = h / 2 + 18;
        c.fill(x - 4, y - 3, x + total + 4, y + 12, 0x9A0E0F11);
        c.fill(x, y - 1, x + kw, y + 10, 0xFFE0A64A);
        c.drawText(tr, key, x + 4, y + 1, 0xFF111111, false);
        c.drawText(tr, label, x + kw + 6, y + 1, 0xFFE8E2D6, false);
    }

    // ---- the grab: a quick reach down and in with the right hand ----

    private static final Map<Integer, Long> GRABS = new HashMap<>();

    static void grabbed(int entity) {
        GRABS.put(entity, System.nanoTime());
        if (GRABS.size() > 128) GRABS.clear();
    }

    static void grabPose(PlayerEntity p, Pose out) {
        Long at = GRABS.get(p.getId());
        if (at == null) return;
        float u = (System.nanoTime() - at) / 1e9f / 0.42f;
        if (u >= 1) {
            GRABS.remove(p.getId());
            return;
        }
        float k = MathHelper.sin(MathHelper.PI * u);
        out.set(Bone.RIGHT_ARM, MathHelper.lerp(k, out.get(Bone.RIGHT_ARM, Pose.PITCH), -62), MathHelper.lerp(k, out.get(Bone.RIGHT_ARM, Pose.YAW), 8), out.get(Bone.RIGHT_ARM, Pose.ROLL));
        out.set(Bone.RIGHT_FOREARM, Pose.PITCH, MathHelper.lerp(k, out.get(Bone.RIGHT_FOREARM, Pose.PITCH), 18));
        out.set(Bone.CHEST, Pose.PITCH, out.get(Bone.CHEST, Pose.PITCH) + 14 * k);
        out.set(Bone.HEAD, Pose.PITCH, out.get(Bone.HEAD, Pose.PITCH) + 10 * k);
    }
}
