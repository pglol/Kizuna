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
    public static KeyBinding lockKey, deckKey, dodgeKey, shoulderKey;
    private static long lastFrame;
    private static boolean openEditor;

    @Override
    public void onInitializeClient() {
        editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.pose_editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.kata_core"));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("kdebug").executes(c -> {
            Fight.debug = !Fight.debug;
            Fight.send(net.pglol.kata.KataNet.DEBUG, Fight.debug ? 1 : 0, 0);
            c.getSource().sendFeedback(net.minecraft.text.Text.literal("Kata strike debug " + (Fight.debug ? "on: your limb's path shows, and every strike reports what happened" : "off")));
            return 1;
        })));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("kpose").executes(c -> {
            openEditor = true;
            return 1;
        })));
        deckKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.deck", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.kata_core"));
        lockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.lock_on", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.kata_core"));
        shoulderKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.shoulder", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_CAPS_LOCK, "category.kata_core"));
        // A Camera button at the top left of the pause menu.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screen.GameMenuScreen)
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(net.minecraft.client.gui.widget.ButtonWidget.builder(
                    net.minecraft.text.Text.literal("Camera"), b -> client.setScreen(new KataCamScreen(screen))).dimensions(6, 6, 60, 20).build());
        });
        dodgeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.dodge", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, "category.kata_core"));
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(net.pglol.kata.KataNet.RagOf.ID,
            (payload, ctx) -> Ragdoll.heard(payload.entity(), payload.points()));
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
        HudRenderCallback.EVENT.register(ImpactFrames::render);
        // The striking limb's glow, on every player.
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof net.minecraft.client.render.entity.PlayerEntityRenderer pr) helper.register(new StrikeGlow<>(pr));
        });
        HudRenderCallback.EVENT.register((c, tick) -> AwarenessFx.hud(c));
        HudRenderCallback.EVENT.register((c, tick) -> KitClient.hud(c));
        KitClient.init();
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            if (ctx.consumers() != null) {
                Afterimages.render(ctx.matrixStack(), ctx.consumers(), ctx.camera().getPos());
                Trails.render(ctx.matrixStack(), ctx.consumers(), ctx.camera().getPos());
                HitFx.render(ctx.matrixStack(), ctx.consumers(), ctx.camera().getPos());
                Nametags.render(ctx.matrixStack(), ctx.consumers(), ctx.camera());
                AwarenessFx.render(ctx.matrixStack(), ctx.consumers(), ctx.camera());
            }
        });
    }

    /** The number keys don't pick hotbar slots any more (no hotbar; they're for moves later). */
    private static void early(MinecraftClient mc) {
        if (mc.player == null) return;
        if (!KitClient.early(mc) && mc.player.isCreative()) return; // creative builds with Minecraft's own hotbar
        for (KeyBinding k : mc.options.hotbarKeys) while (k.wasPressed()) { }
    }

    private static void tick(MinecraftClient mc) {
        Move.tick(mc);
        Parkour.tick(mc);
        Fight.tick(mc);
        KitClient.tick(mc);
        Meditate.tick(mc);
        KataCam.tick(mc);
        if (mc.player != null) Ragdoll.syncOut(mc.player);
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
