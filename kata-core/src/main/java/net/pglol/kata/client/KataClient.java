package net.pglol.kata.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class KataClient implements ClientModInitializer {
    private static KeyBinding editorKey;
    private static boolean openEditor;

    @Override
    public void onInitializeClient() {
        editorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata_core.pose_editor", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.kata_core"));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(ClientCommandManager.literal("kpose").executes(c -> {
            openEditor = true;
            return 1;
        })));
        ClientTickEvents.END_CLIENT_TICK.register(KataClient::tick);
    }

    private static void tick(MinecraftClient mc) {
        // Kata is played in third person, always (the body is the point).
        if (mc.player != null && mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        while (editorKey.wasPressed()) if (mc.currentScreen == null) openEditor = true;
        if (openEditor && mc.player != null) {
            openEditor = false;
            mc.setScreen(new PoseEditorScreen());
        }
    }
}
