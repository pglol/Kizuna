package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Someone in front of you is down and nearly done: a prompt to finish them. */
public final class Finish {
    private Finish() {}

    /** The finishable foe in front of you, if any. */
    public static LivingEntity target(MinecraftClient mc) {
        var pl = mc.player;
        if (pl == null || mc.world == null) return null;
        var held = pl.getMainHandStack();
        if (!com.pglol.aotrpg.Guard.melee(held)) return null;
        Vec3d look = pl.getRotationVec(1f).multiply(1, 0, 1).normalize();
        LivingEntity best = null;
        double bd = 4.2 * 4.2;
        for (Entity e : mc.world.getOtherEntities(pl, pl.getBoundingBox().expand(4.5, 2, 4.5))) {
            if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
            if (Registries.ENTITY_TYPE.getId(e.getType()).getPath().contains("titan")) continue;
            boolean down = AdrenalineClient.stunned(e.getId()) || AdrenalineClient.pinned(e.getId()) || AdrenalineClient.faceplant(e.getId()) > 0;
            if (!down) continue;
            float limit = e instanceof PlayerEntity ? 0.25f : 0.2f;
            if (le.getHealth() > le.getMaxHealth() * limit) continue;
            Vec3d to = e.getPos().subtract(pl.getPos()).multiply(1, 0, 1);
            double d = to.lengthSquared();
            if (d > bd || d > 1e-4 && to.normalize().dotProduct(look) < 0.3) continue;
            bd = d;
            best = le;
        }
        return best;
    }

    public static void render(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null || FinishScene.mine() || target(mc) == null) return;
        float pulse = 0.6f + 0.4f * MathHelper.sin(Util.getMeasuringTimeMs() / 80f);
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int col = ((int) (255 * pulse) << 24) | 0xFF3A2A;
        Ui.text(c, Ui.title("FINISH"), w / 2f, h / 2f + 14, 1.1f, col, true);
        Ui.text(c, Ui.heading("[" + mc.options.attackKey.getBoundKeyLocalizedText().getString() + "]"), w / 2f, h / 2f + 28, 0.6f, 0xE0FFFFFF, true);
    }
}
