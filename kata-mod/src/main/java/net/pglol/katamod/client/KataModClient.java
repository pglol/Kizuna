package net.pglol.katamod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** The Kata interface: the locker (M) is the way into the game's menus. */
public final class KataModClient implements ClientModInitializer {
    private static KeyBinding lockerKey;

    @Override
    public void onInitializeClient() {
        lockerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.kata.locker", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.kata"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (lockerKey.wasPressed()) if (mc.currentScreen == null && mc.player != null) mc.setScreen(new LockerScreen());
        });
    }
}
