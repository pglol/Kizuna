package net.pglol.kata.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class KataClient implements ClientModInitializer {
    private static KeyBinding editorKey;
    public static KeyBinding lockKey, deckKey, dodgeKey;
    private static long lastFrame;
    private static boolean openEditor;

    @Override
    public void onInitializeClient() {
        editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.pose_editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.kata_core"));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("kpose").executes(c -> {
            openEditor = true;
            return 1;
        })));
        deckKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.deck", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.kata_core"));
        lockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.lock_on", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.kata_core"));
        dodgeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.dodge", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "category.kata_core"));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(net.pglol.kata.KataNet.FightOf.ID,
            (payload, ctx) -> Fight.remote(payload.entity(), payload.kind(), payload.a()));
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.START.register(ctx -> {
            long now = System.nanoTime();
            float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
            lastFrame = now;
            Fight.frame(MinecraftClient.getInstance(), ctx.tickCounter().getTickDelta(false), dt);
        });
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(Stance::render);
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(net.pglol.kata.KataEntities.DUMMY, DummyRenderer::new);
        ClientTickEvents.START_CLIENT_TICK.register(KataClient::early);
        ClientTickEvents.END_CLIENT_TICK.register(KataClient::tick);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(net.pglol.kata.KataNet.ActOf.ID,
            (payload, ctx) -> Parkour.remote(payload.entity(), payload.mode(), payload.side()));
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(Marker::render);
        HudRenderCallback.EVENT.register((c, tick) -> Hud.render(c, tick.getTickDelta(false)));
    }

    /** The number keys don't pick hotbar slots any more (no hotbar; they're for moves later). */
    private static void early(MinecraftClient mc) {
        if (mc.player == null) return;
        for (KeyBinding k : mc.options.hotbarKeys) while (k.wasPressed()) { }
    }

    private static void tick(MinecraftClient mc) {
        Move.tick(mc);
        Parkour.tick(mc);
        Fight.tick(mc);
        // Kata is played in third person, always (the body is the point).
        if (mc.player != null && mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        while (editorKey.wasPressed()) if (mc.currentScreen == null) openEditor = true;
        while (deckKey.wasPressed()) if (mc.currentScreen == null && mc.player != null) mc.setScreen(new DeckScreen());
        if (openEditor && mc.player != null) {
            openEditor = false;
            mc.setScreen(new PoseEditorScreen());
        }
    }
}
