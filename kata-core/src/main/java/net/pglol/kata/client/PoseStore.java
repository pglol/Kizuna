package net.pglol.kata.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.pglol.kata.rig.Pose;

/** Who is posed. For now just you, from the pose editor; animations and other players come next. */
public final class PoseStore {
    private PoseStore() {}

    /** Your pose from the editor (all zeros = Minecraft's own). */
    public static final Pose mine = new Pose();

    public static Pose of(Entity e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && e != null && e.getId() == mc.player.getId()) return mine;
        return null;
    }
}
