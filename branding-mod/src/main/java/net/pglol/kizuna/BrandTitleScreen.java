package net.pglol.kizuna;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.PlayerSkinDrawer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The pack's title screen: its picture, its logo, and clear buttons: Play (straight to the server),
 * Options, Quit. No server list and no singleplayer: this game is the one server. On the right, your
 * account and characters (from config/kizuna/profile.json, which the server's mod keeps up to date);
 * clicking a character joins as that character.
 */
public class BrandTitleScreen extends Screen {
    private record Btn(int x, int y, int w, int h, String label, boolean primary, Runnable run) { }
    private record Line(String key, String value) { }
    private record Char(int slot, String name, String sub, boolean active) { }

    private final List<Btn> buttons = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Char> chars = new ArrayList<>();
    private long opened;

    public BrandTitleScreen() {
        super(Text.literal(Brand.name.isEmpty() ? "Minecraft" : Brand.name));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    protected void init() {
        if (opened == 0) opened = Util.getMeasuringTimeMs();
        buttons.clear();
        // A pick left over from a join that didn't go through shouldn't decide the next one.
        try {
            Files.deleteIfExists(Brand.DIR.resolve("pick.txt"));
        } catch (Exception ignored) {
        }
        int x = 40, w = Math.min(260, width / 3), y = height / 2;
        buttons.add(new Btn(x, y, w, 30, "PLAY", true, this::play));
        buttons.add(new Btn(x, y + 40, w, 22, "Options", false, () -> client.setScreen(new OptionsScreen(this, client.options))));
        buttons.add(new Btn(x, y + 68, w, 22, "Quit game", false, client::scheduleStop));
        readProfile();
        // Character cards down the right-hand side.
        int cw = Math.min(220, width / 3), cx = width - cw - 40, cy = height / 2 - 10;
        for (Char ch : chars) {
            if (cy + 34 > height - 30) break;
            buttons.add(new Btn(cx, cy, cw, 30, "", false, () -> playAs(ch.slot)));
            cy += 36;
        }
    }

    private boolean hasServer() {
        return !Brand.address.isEmpty() && !Brand.address.contains("CHANGE_ME");
    }

    private void readProfile() {
        lines.clear();
        chars.clear();
        try {
            var f = Brand.DIR.resolve("profile.json");
            if (!Files.exists(f)) return;
            JsonObject j = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            // Only show it for the account that saved it.
            if (j.has("account") && !j.get("account").getAsString().equalsIgnoreCase(client.getSession().getUsername())) return;
            if (j.has("lines")) for (JsonElement e : j.getAsJsonArray("lines")) {
                var a = e.getAsJsonArray();
                lines.add(new Line(a.get(0).getAsString(), a.get(1).getAsString()));
            }
            if (j.has("characters")) for (JsonElement e : j.getAsJsonArray("characters")) {
                JsonObject c = e.getAsJsonObject();
                chars.add(new Char(c.get("slot").getAsInt(), c.get("name").getAsString(),
                    c.has("sub") ? c.get("sub").getAsString() : "", c.has("active") && c.get("active").getAsBoolean()));
            }
        } catch (Exception e) {
            System.err.println("[kizuna] could not read profile.json: " + e);
        }
    }

    /** Join, and tell the server's mod which character to play as. */
    private void playAs(int slot) {
        if (!hasServer()) return;
        try {
            Files.createDirectories(Brand.DIR);
            Files.writeString(Brand.DIR.resolve("pick.txt"), String.valueOf(slot), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        play();
    }

    private void play() {
        if (!hasServer()) return;
        ServerInfo info = new ServerInfo(Brand.name.isEmpty() ? "Server" : Brand.name, Brand.address, ServerInfo.ServerType.OTHER);
        ConnectScreen.connect(this, client, ServerAddress.parse(Brand.address), info, false, null);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : buttons) {
                if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) {
                    client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
                    b.run.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        float in = Math.min(1f, (Util.getMeasuringTimeMs() - opened) / 400f);
        // Background: the pack's picture, covering the screen; otherwise its paper colour.
        c.fill(0, 0, width, height, Brand.background);
        Identifier bg = Brand.title();
        if (bg != null) {
            int[] sz = Brand.size(Brand.titleFile());
            float s = Math.max(width / (float) sz[0], height / (float) sz[1]);
            int w = (int) (sz[0] * s), h = (int) (sz[1] * s);
            c.drawTexture(bg, (width - w) / 2, (height - h) / 2, w, h, 0, 0, sz[0], sz[1], sz[0], sz[1]);
            c.fillGradient(0, 0, width * 2 / 3, height, 0xB0000000, 0x00000000);
        }
        // Left column: an ink panel edge, the logo, the buttons.
        int x = 40;
        Identifier logo = Brand.logo();
        int top = 40;
        if (logo != null) {
            int[] sz = Brand.size(Brand.logoFile());
            int w = Math.min(320, width / 3), h = (int) (sz[1] * (w / (float) sz[0]));
            if (h > height / 3) {
                h = height / 3;
                w = (int) (sz[0] * (h / (float) sz[1]));
            }
            c.drawTexture(logo, x, top, w, h, 0, 0, sz[0], sz[1], sz[0], sz[1]);
        } else {
            var ms = c.getMatrices();
            ms.push();
            ms.translate(x, top + 20, 0);
            ms.scale(3f, 3f, 1);
            c.drawText(textRenderer, title, 0, 0, 0xFFFFFFFF, true);
            ms.pop();
        }
        drawAccount(c, mouseX, mouseY);
        for (Btn b : buttons) {
            if (b.label.isEmpty()) continue;
            boolean hov = mouseX >= b.x && mouseX < b.x + b.w && mouseY >= b.y && mouseY < b.y + b.h;
            boolean off0 = b.primary && !hasServer();
            int off = hov ? -1 : 0;
            // Hard ink shadow, then the face.
            c.fill(b.x + 3, b.y + 3, b.x + b.w + 3, b.y + b.h + 3, 0xFF000000);
            int face = off0 ? 0xFF6B655C : b.primary ? Brand.accent : Brand.paper;
            c.fill(b.x + off, b.y + off, b.x + b.w + off, b.y + b.h + off, face);
            c.drawBorder(b.x + off, b.y + off, b.w, b.h, Brand.ink);
            int tc = b.primary ? 0xFFFFFFFF : Brand.ink;
            var ms = c.getMatrices();
            ms.push();
            float sc = b.primary ? 1.5f : 1f;
            int tw = (int) (textRenderer.getWidth(b.label) * sc);
            ms.translate(b.x + off + (b.w - tw) / 2f, b.y + off + (b.h - 8 * sc) / 2f + 1, 0);
            ms.scale(sc, sc, 1);
            c.drawText(textRenderer, b.label, 0, 0, tc, false);
            ms.pop();
        }
        if (!hasServer()) {
            String s = "No server address set for this build yet.";
            c.drawText(textRenderer, s, 40, height / 2 + 100, 0xFFE0B0A0, true);
        }
        String ver = "Minecraft 1.21.1";
        c.drawText(textRenderer, ver, width - textRenderer.getWidth(ver) - 8, height - 14, 0x90FFFFFF, false);
        if (in < 1) c.fill(0, 0, width, height, ((int) ((1 - in) * 255) << 24) | (Brand.background & 0xFFFFFF));
    }

    /** Right-hand side: your head and name, a few numbers, and your characters to join as. */
    private void drawAccount(DrawContext c, int mouseX, int mouseY) {
        int cw = Math.min(220, width / 3), x = width - cw - 40, y = height / 2 - 92;
        var profile = client.getGameProfile();
        PlayerSkinDrawer.draw(c, client.getSkinProvider().getSkinTextures(profile), x, y, 24);
        c.drawText(textRenderer, profile.getName(), x + 32, y + 3, 0xFFFFFFFF, true);
        c.drawText(textRenderer, lines.isEmpty() ? "Join once to see your characters here" : "Your account", x + 32, y + 14, 0xFFBDB7AC, true);
        int ly = y + 34;
        for (Line l : lines) {
            c.drawText(textRenderer, l.key, x, ly, 0xFFBDB7AC, true);
            c.drawText(textRenderer, l.value, x + cw - textRenderer.getWidth(l.value), ly, 0xFFFFFFFF, true);
            ly += 11;
        }
        if (chars.isEmpty()) return;
        c.drawText(textRenderer, "Play as", x, height / 2 - 22, 0xFFBDB7AC, true);
        int i = 0;
        for (Btn b : buttons) {
            if (!b.label.isEmpty()) continue;
            Char ch = chars.get(i++);
            boolean hov = hasServer() && mouseX >= b.x && mouseX < b.x + b.w && mouseY >= b.y && mouseY < b.y + b.h;
            int off = hov ? -1 : 0;
            c.fill(b.x + 3, b.y + 3, b.x + b.w + 3, b.y + b.h + 3, 0xFF000000);
            c.fill(b.x + off, b.y + off, b.x + b.w + off, b.y + b.h + off, Brand.paper);
            c.fill(b.x + off, b.y + off, b.x + off + 4, b.y + b.h + off, ch.active ? Brand.accent : Brand.ink);
            c.drawBorder(b.x + off, b.y + off, b.w, b.h, Brand.ink);
            c.drawText(textRenderer, ch.name, b.x + off + 10, b.y + off + 5, Brand.ink, false);
            c.drawText(textRenderer, ch.sub, b.x + off + 10, b.y + off + 17, 0xFF6B655C, false);
            if (hov) {
                String go = "Join →";
                c.drawText(textRenderer, go, b.x + off + b.w - textRenderer.getWidth(go) - 8, b.y + off + 11, Brand.accent, false);
            }
        }
    }
}
