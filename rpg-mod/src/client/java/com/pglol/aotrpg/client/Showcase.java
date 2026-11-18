package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Showing cosmetics for real. Wearables (outfits, dyes, head and back pieces) are drawn on your
 * own character wearing them; effects are played for real in the world around you (the very
 * slashes, shots, kills, wards and trails the game uses) while a camera circles you.
 */
public final class Showcase {
    private Showcase() {}

    public static boolean wearable(String slot) {
        return slot.equals("outfit") || slot.equals("dye") || slot.equals("head") || slot.equals("back");
    }

    /** A small picture for an effect's slot. */
    public static ItemStack icon(String slot) {
        return new ItemStack(switch (slot) {
            case "slash" -> Items.IRON_SWORD;
            case "trail" -> Items.SPECTRAL_ARROW;
            case "body" -> Items.GLOWSTONE_DUST;
            case "odm" -> Items.FEATHER;
            case "horse" -> Items.SADDLE;
            case "kill" -> Items.WITHER_SKELETON_SKULL;
            case "block" -> Items.SHIELD;
            case "clash" -> Items.NETHERITE_SWORD;
            case "head" -> Items.GOLDEN_HELMET;
            case "back" -> Items.ELYTRA;
            case "dye" -> Items.MAGENTA_DYE;
            default -> Items.LEATHER_CHESTPLATE;
        });
    }

    /** Your character at (x, feetY), turned to yaw, wearing `id` in `slot` (null: as you are). */
    public static void model(DrawContext c, float x, float feetY, float size, float yaw, String slot, String id) {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        float by = p.bodyYaw, yw = p.getYaw(), pt = p.getPitch(), ph = p.prevHeadYaw, hy = p.headYaw, pb = p.prevBodyYaw;
        String os = CosmeticFx.previewSlot, oi = CosmeticFx.previewId;
        p.bodyYaw = p.prevBodyYaw = 180 + yaw;
        p.setYaw(180 + yaw);
        p.setPitch(0);
        p.headYaw = p.prevHeadYaw = p.bodyYaw;
        if (slot != null) {
            CosmeticFx.previewSlot = slot;
            CosmeticFx.previewId = id;
        }
        try {
            org.joml.Quaternionf q = new org.joml.Quaternionf().rotateZ((float) Math.PI);
            org.joml.Quaternionf tilt = new org.joml.Quaternionf().rotateX((float) Math.toRadians(-6));
            q.mul(tilt);
            InventoryScreen.drawEntity(c, x, feetY - p.getHeight() / 2 * size, size, new org.joml.Vector3f(0, p.getHeight() / 2, 0), q, tilt, p);
        } finally {
            CosmeticFx.previewSlot = os;
            CosmeticFx.previewId = oi;
            p.bodyYaw = by;
            p.prevBodyYaw = pb;
            p.setYaw(yw);
            p.setPitch(pt);
            p.prevHeadYaw = ph;
            p.headYaw = hy;
        }
    }

    /** A tile's picture: you wearing it, or the effect's emblem over a band of its colours. */
    public static void thumb(DrawContext c, int x, int y, int w, int h, String slot, CosmeticFx.Entry e, boolean dim) {
        if (wearable(slot)) {
            c.enableScissor(x, y, x + w, y + h);
            model(c, x + w / 2f, y + h - 2, h * 0.42f, slot.equals("back") ? 25 : 205, slot, e.id());
            c.disableScissor();
        } else {
            int cy = y + h / 2;
            c.fillGradient(x + 6, cy + 9, x + w - 6, cy + 11, 0xFF000000 | e.color(), 0xFF000000 | e.color2());
            for (int k = 0; k < w - 12; k++) {
                float u = k / (float) (w - 12);
                int col = mix(e.color(), e.color2(), u);
                c.fill(x + 6 + k, cy + 9, x + 7 + k, cy + 11, (dim ? 0x60 : 0xFF) << 24 | col);
            }
            Ui.item(c, icon(slot), x + w / 2 - 12, cy - 15, 1.5f);
        }
    }

    static int mix(int a, int b, float u) {
        int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
        int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
        int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------ the live showroom

    private static int beat;

    /** Each client tick while the showroom is open: the shown effect, played for real on you. */
    public static void tick(String slot, String id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var p = mc.player;
        if (p == null || mc.world == null || id == null) return;
        beat++;
        double yaw = Math.toRadians(p.bodyYaw);
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d chest = p.getPos().add(0, 1.2, 0);
        GasSwap.busy++;
        try {
            switch (slot) {
                case "slash" -> {
                    if (beat % 16 == 0) {
                        p.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                        Vec3d at = chest.add(fwd.multiply(1.5));
                        CosmeticFx.slash(new Net.SlashFx(id, at.x, at.y, at.z, p.bodyYaw));
                    }
                }
                case "trail" -> {
                    if (beat % 14 == 0) {
                        Vec3d from = chest.add(right.multiply(-0.35)).add(fwd.multiply(0.6)).add(0, 0.1, 0);
                        Vec3d to = from.add(fwd.multiply(28)).add(right.multiply(Math.sin(beat * 0.3) * 2));
                        Trails.spawn(new Net.Trail(id, from.x, from.y, from.z, to.x, to.y, to.z));
                        p.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                        mc.world.playSound(p.getX(), p.getY(), p.getZ(), net.minecraft.sound.SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST,
                            net.minecraft.sound.SoundCategory.PLAYERS, 0.4f, 1.6f, false);
                    }
                }
                case "kill" -> {
                    if (beat % 60 == 1) {
                        Vec3d at = p.getPos().add(fwd.multiply(3.5)).add(0, 1, 0);
                        CosmeticFx.slash(new Net.SlashFx(id, at.x, at.y, at.z, 1.9f));
                    }
                }
                case "block", "clash" -> {
                    if (beat % 22 == 0) {
                        Vec3d at = chest.add(fwd.multiply(0.9));
                        CombatUi.onGuardFx(new Net.GuardFx(slot.equals("clash") ? 1 : 0, at.x, at.y, at.z, id));
                        if (slot.equals("clash")) p.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                    }
                }
                case "odm" -> {
                    // Venting as if boosting: puffs from the gear, blown back.
                    for (int s = -1; s <= 1; s += 2) {
                        Vec3d at = p.getPos().add(0, 0.95, 0).add(right.multiply(0.32 * s)).add(fwd.multiply(-0.25));
                        Vec3d v = fwd.multiply(-0.25).add(0, 0.02, 0);
                        CosmeticFx.odmPuff(id, at.x, at.y, at.z, v.x, v.y, v.z);
                    }
                }
                case "horse" -> {
                    if (beat % 2 == 0) CosmeticFx.horseTrail(id, p.getX() + (Math.random() - 0.5) * 0.9, p.getY() + 0.1, p.getZ() + (Math.random() - 0.5) * 0.9,
                        -fwd.x * 0.1, -fwd.z * 0.1);
                }
                default -> { }
            }
        } finally {
            GasSwap.busy--;
        }
    }

    /** How far the showroom camera stands back for a slot (effects need room). */
    static float distance(String slot) {
        return switch (slot) {
            case "trail" -> 6.5f;
            case "kill" -> 7f;
            case "slash", "block", "clash", "odm", "horse" -> 4.4f;
            default -> 3.3f;
        };
    }

    /**
     * The showroom camera: circling you at `spin`, shifted so you stand in the middle of the
     * screen's stage (stage centre `sx` of `screenW` across).
     */
    public static com.pglol.aotrpg.client.story.CutscenePlayer.Cam camera(float td, float spin, float dist, float sx, float screenW) {
        MinecraftClient mc = MinecraftClient.getInstance();
        var p = mc.player;
        if (p == null || mc.world == null) return null;
        Vec3d target = p.getLerpedPos(td).add(0, 1.0, 0);
        float yaw = p.bodyYaw + 180 + spin, pitch = 10;
        Vec3d f = Vec3d.fromPolar(pitch, yaw);
        float yr = yaw * MathHelper.RADIANS_PER_DEGREE;
        Vec3d right = new Vec3d(-MathHelper.cos(yr), 0, -MathHelper.sin(yr));
        double fov = Math.toRadians(mc.options.getFov().getValue());
        double aspect = mc.getWindow().getFramebufferWidth() / (double) Math.max(1, mc.getWindow().getFramebufferHeight());
        double tanH = Math.tan(fov / 2) * aspect;
        double off = (sx - screenW / 2.0) / (screenW / 2.0);
        double side = -off * dist * tanH;
        Vec3d want = target.subtract(f.multiply(dist)).add(right.multiply(side));
        var hit = mc.world.raycast(new net.minecraft.world.RaycastContext(target, want, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE, p));
        if (hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) want = target.lerp(hit.getPos(), 0.9);
        return new com.pglol.aotrpg.client.story.CutscenePlayer.Cam(want.x, want.y, want.z, yaw, pitch);
    }
}
