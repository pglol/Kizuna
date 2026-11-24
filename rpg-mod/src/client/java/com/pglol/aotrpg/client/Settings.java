package com.pglol.aotrpg.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Your own display settings (this computer only): what of the HUD and the effects you want. Kept in
 * config/aot_rpg_settings.properties.
 */
public final class Settings {
    private Settings() {}

    public static boolean markers = true, compass = true, tracker = true, statCards = true, hints = true, cutscenes = true,
        screenShake = true, impactFrames = true, beams = true;
    /** HUD scale for our own panels (0.75 - 1.25). */
    public static float hudScale = 1f;

    private static Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aot_rpg_settings.properties");
    }

    public static void load() {
        Properties p = new Properties();
        try (var in = Files.newInputStream(file())) {
            p.load(in);
        } catch (Exception ignored) {
            return;
        }
        markers = b(p, "markers", markers);
        compass = b(p, "compass", compass);
        tracker = b(p, "tracker", tracker);
        statCards = b(p, "statCards", statCards);
        hints = b(p, "hints", hints);
        cutscenes = b(p, "cutscenes", cutscenes);
        screenShake = b(p, "screenShake", screenShake);
        impactFrames = b(p, "impactFrames", impactFrames);
        beams = b(p, "beams", beams);
        try {
            hudScale = Math.max(0.75f, Math.min(1.25f, Float.parseFloat(p.getProperty("hudScale", "1"))));
        } catch (NumberFormatException ignored) {
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("markers", "" + markers);
        p.setProperty("compass", "" + compass);
        p.setProperty("tracker", "" + tracker);
        p.setProperty("statCards", "" + statCards);
        p.setProperty("hints", "" + hints);
        p.setProperty("cutscenes", "" + cutscenes);
        p.setProperty("screenShake", "" + screenShake);
        p.setProperty("impactFrames", "" + impactFrames);
        p.setProperty("beams", "" + beams);
        p.setProperty("hudScale", "" + hudScale);
        try (var out = Files.newOutputStream(file())) {
            p.store(out, "AoT RPG display settings");
        } catch (Exception ignored) {
        }
    }

    private static boolean b(Properties p, String k, boolean d) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d)));
    }
}
