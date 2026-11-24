package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.ChatOptionsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.SkinOptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.sound.SoundCategory;

/**
 * Options, in the game's own style, in place of Minecraft's: the pages players actually need
 * (Controls, Mouse & camera, Sound, HUD & effects) are ours; video, chat, accessibility, language
 * and skin open Minecraft's own pages, and everything else is under "All Minecraft options".
 */
public final class AotOptionsScreen {
    private AotOptionsScreen() {}

    /** Set while we open Minecraft's own options screen on purpose, so it isn't swapped for ours. */
    public static boolean vanilla;

    public static Screen create(Screen parent) {
        MinecraftClient mc = MinecraftClient.getInstance();
        GameOptions o = mc.options;
        return new OptionsPage(parent, "OPTIONS", "", p -> p
            .section("Play")
            .link("Controls", "Every key for gear, fighting, moving and menus", () -> new ControlsScreen(p, false))
            .link("Mouse & camera", "Sensitivity, field of view, toggles and the combat camera", () -> mouse(p))
            .link("Sound", "Volume for music, titans, players and everything else", () -> sound(p))
            .link("HUD & effects", "Markers, compass, hints, screen shake and impact frames", () -> new SettingsScreen(p))
            .section("Minecraft")
            .link("Video settings", "Graphics, render distance, shaders, GUI scale", () -> new VideoOptionsScreen(p, mc, o))
            .link("Chat", "Chat size, colours and filters", () -> new ChatOptionsScreen(p, o))
            .link("Accessibility", "Narrator, subtitles, contrast, motion", () -> new AccessibilityOptionsScreen(p, o))
            .link("Language", "Game language", () -> new LanguageOptionsScreen(p, o, mc.getLanguageManager()))
            .link("Skin parts", "Which layers of your skin show", () -> new SkinOptionsScreen(p, o))
            .link("All Minecraft options", "Resource packs, telemetry and the rest", () -> {
                vanilla = true;
                return new OptionsScreen(p, o);
            }));
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }

    static Screen mouse(Screen parent) {
        GameOptions o = MinecraftClient.getInstance().options;
        return new OptionsPage(parent, "MOUSE & CAMERA", "", p -> p
            .section("Mouse")
            .slider("Sensitivity", "How fast the camera turns", o.getMouseSensitivity(), 0, 1, v -> Math.round(v * 200) + "%")
            .toggle("Invert mouse", "Mouse up looks down", o.getInvertYMouse())
            .toggle("Raw input", "Ignore Windows mouse acceleration", o.getRawMouseInput())
            .section("Camera")
            .intSlider("Field of view", "How wide you see (70 is normal)", o.getFov(), 30, 110, v -> String.valueOf((int) v))
            .slider("FOV effects", "Zoom when sprinting, flying on gear or using items", o.getFovEffectScale(), 0, 1, AotOptionsScreen::pct)
            .slider("Distortion effects", "Nausea and portal wobble", o.getDistortionEffectScale(), 0, 1, AotOptionsScreen::pct)
            .toggle("View bobbing", "The camera bobs as you walk", o.getBobView())
            .link("Combat camera", "Over-the-shoulder camera and lock-on options", () -> new ShoulderSettingsScreen(p))
            .section("Movement")
            .toggle("Toggle sprint", "Press once to sprint instead of holding", o.getSprintToggled())
            .toggle("Toggle crouch", "Press once to crouch instead of holding", o.getSneakToggled())
            .toggle("Auto-jump", "Step up blocks without jumping", o.getAutoJump()));
    }

    static Screen sound(Screen parent) {
        GameOptions o = MinecraftClient.getInstance().options;
        return new OptionsPage(parent, "SOUND", "", p -> {
            p.section("Volume")
                .slider("Master", "Everything", o.getSoundVolumeOption(SoundCategory.MASTER), 0, 1, AotOptionsScreen::pct)
                .slider("Music", "Soundtrack", o.getSoundVolumeOption(SoundCategory.MUSIC), 0, 1, AotOptionsScreen::pct)
                .slider("Titans & monsters", "Footsteps, roars, hostile mobs", o.getSoundVolumeOption(SoundCategory.HOSTILE), 0, 1, AotOptionsScreen::pct)
                .slider("Players", "Gear, blades and other players", o.getSoundVolumeOption(SoundCategory.PLAYERS), 0, 1, AotOptionsScreen::pct)
                .slider("Blocks", "Doors, building, breaking", o.getSoundVolumeOption(SoundCategory.BLOCKS), 0, 1, AotOptionsScreen::pct)
                .slider("Friendly creatures", "Horses and animals", o.getSoundVolumeOption(SoundCategory.NEUTRAL), 0, 1, AotOptionsScreen::pct)
                .slider("Weather", "Rain and thunder", o.getSoundVolumeOption(SoundCategory.WEATHER), 0, 1, AotOptionsScreen::pct)
                .slider("Ambient", "Wind, caves, the world around you", o.getSoundVolumeOption(SoundCategory.AMBIENT), 0, 1, AotOptionsScreen::pct)
                .slider("Voice", "Voices and speech", o.getSoundVolumeOption(SoundCategory.VOICE), 0, 1, AotOptionsScreen::pct)
                .slider("Jukebox", "Music discs and note blocks", o.getSoundVolumeOption(SoundCategory.RECORDS), 0, 1, AotOptionsScreen::pct)
                .section("Other")
                .toggle("Subtitles", "Captions for sounds", o.getShowSubtitles())
                .toggle("Directional audio", "3D sound for headphones", o.getDirectionalAudio());
        });
    }
}
