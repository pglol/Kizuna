package net.pglol.kizuna;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * A modpack's look in game, read from config/kizuna/brand.json (shipped by the pack):
 * <pre>
 * { "name": "Attack on Titan RPG", "address": "25.1.2.3:25565",
 *   "background": "#15120f", "paper": "#efe9dc", "accent": "#a8432a",
 *   "splash": "splash.png", "logo": "logo.png", "title": "title.png" }
 * </pre>
 * Images sit next to it. Anything missing falls back to plain colours; no file at all leaves Minecraft as it is.
 */
public final class Brand implements ClientModInitializer {
    public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("kizuna");
    public static boolean active;
    public static String name = "", address = "";
    public static int background = 0xFF15120F, paper = 0xFFEFE9DC, accent = 0xFFC42B1F, ink = 0xFF15120F;
    static String splash = "", logo = "", title = "";
    private static final Map<String, Identifier> textures = new HashMap<>();
    private static final Map<String, int[]> sizes = new HashMap<>();

    static {
        load();
    }

    @Override
    public void onInitializeClient() {
    }

    private static void load() {
        Path f = DIR.resolve("brand.json");
        if (!Files.exists(f)) return;
        try {
            JsonObject j = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            name = str(j, "name");
            address = str(j, "address");
            background = color(j, "background", background);
            paper = color(j, "paper", paper);
            accent = color(j, "accent", accent);
            ink = color(j, "ink", ink);
            splash = str(j, "splash");
            logo = str(j, "logo");
            title = str(j, "title");
            active = true;
            // The launcher writes the address of the build channel that was started (Public, Early Access, Development).
            Path a = DIR.resolve("address.txt");
            if (Files.exists(a)) {
                String v = Files.readString(a, StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) address = v;
            }
        } catch (Exception e) {
            System.err.println("[kizuna] could not read brand.json: " + e);
        }
    }

    private static String str(JsonObject j, String k) {
        return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
    }

    private static int color(JsonObject j, String k, int def) {
        String s = str(j, k).replace("#", "");
        if (s.length() != 6) return def;
        try {
            return 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static NativeImage image(String file) {
        if (file == null || file.isEmpty()) return null;
        Path p = DIR.resolve(file).normalize();
        if (!p.startsWith(DIR) || !Files.exists(p)) return null;
        try (InputStream in = Files.newInputStream(p)) {
            return NativeImage.read(in);
        } catch (Exception e) {
            System.err.println("[kizuna] could not read " + file + ": " + e);
            return null;
        }
    }

    /** A brand image as a texture (registered once), or null. */
    public static Identifier texture(String file) {
        if (file == null || file.isEmpty()) return null;
        if (textures.containsKey(file)) return textures.get(file);
        NativeImage img = image(file);
        Identifier id = null;
        if (img != null) {
            id = Identifier.of("kizuna_brand", "brand/" + file.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"));
            sizes.put(file, new int[] {img.getWidth(), img.getHeight()});
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(img));
        }
        textures.put(file, id);
        return id;
    }

    public static int[] size(String file) {
        return sizes.getOrDefault(file, new int[] {1, 1});
    }

    public static Identifier logo() {
        return texture(logo);
    }

    public static Identifier title() {
        return texture(title);
    }

    public static String logoFile() {
        return logo;
    }

    public static String titleFile() {
        return title;
    }

    /**
     * The loading screen draws its logo as two halves of one square texture: the top half on the
     * left, the bottom half on the right. Lay a normal (wide) logo out that way.
     */
    public static NativeImage splashTexture() {
        NativeImage src = image(splash.isEmpty() ? logo : splash);
        if (src == null) return null;
        int half = 512;
        NativeImage out = new NativeImage(half * 2, half * 2, true);
        out.fillRect(0, 0, half * 2, half * 2, 0);
        // Fit the logo into a 4:1 strip (2048 x 512), centred.
        double s = Math.min(2048.0 / src.getWidth(), 512.0 / src.getHeight());
        int w = (int) (src.getWidth() * s), h = (int) (src.getHeight() * s);
        int ox = (2048 - w) / 2, oy = (512 - h) / 2;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getColor(Math.min(src.getWidth() - 1, (int) (x / s)), Math.min(src.getHeight() - 1, (int) (y / s)));
                int sx = ox + x, sy = oy + y;
                if (sx < 1024) out.setColor(sx, sy, c);
                else out.setColor(sx - 1024, sy + 512, c);
            }
        }
        src.close();
        return out;
    }
}
