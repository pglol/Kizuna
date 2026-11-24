package net.pglol.kizuna;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** The pack's title screen: its picture, its logo, and clear buttons: Play (straight to the server), Options, Quit. */
public class BrandTitleScreen extends Screen {
    private record Btn(int x, int y, int w, int h, String label, boolean primary, Runnable run) { }

    private final List<Btn> buttons = new ArrayList<>();
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
        int x = 40, w = Math.min(260, width / 3), y = height / 2;
        buttons.add(new Btn(x, y, w, 30, "PLAY", true, this::play));
        buttons.add(new Btn(x, y + 40, w, 22, "Options", false, () -> client.setScreen(new OptionsScreen(this, client.options))));
        buttons.add(new Btn(x, y + 68, w, 22, "Quit game", false, client::scheduleStop));
        buttons.add(new Btn(x, height - 24, 80, 14, "Servers", false, () -> client.setScreen(new MultiplayerScreen(this))));
        buttons.add(new Btn(x + 88, height - 24, 90, 14, "Singleplayer", false, () -> client.setScreen(new SelectWorldScreen(this))));
    }

    private void play() {
        if (Brand.address.isEmpty() || Brand.address.contains("CHANGE_ME")) {
            client.setScreen(new MultiplayerScreen(this));
            return;
        }
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
        for (Btn b : buttons) {
            boolean hov = mouseX >= b.x && mouseX < b.x + b.w && mouseY >= b.y && mouseY < b.y + b.h;
            boolean small = b.h < 20;
            if (small) {
                int col = hov ? 0xFFFFFFFF : 0xFFBDB7AC;
                c.drawText(textRenderer, b.label, b.x, b.y + 3, col, true);
                if (hov) c.fill(b.x, b.y + 12, b.x + textRenderer.getWidth(b.label), b.y + 13, col);
                continue;
            }
            int off = hov ? -1 : 0;
            // Hard ink shadow, then the face.
            c.fill(b.x + 3, b.y + 3, b.x + b.w + 3, b.y + b.h + 3, 0xFF000000);
            int face = b.primary ? Brand.accent : Brand.paper;
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
        String ver = "Minecraft 1.21.1";
        c.drawText(textRenderer, ver, width - textRenderer.getWidth(ver) - 8, height - 14, 0x90FFFFFF, false);
        if (in < 1) c.fill(0, 0, width, height, ((int) ((1 - in) * 255) << 24) | (Brand.background & 0xFFFFFF));
    }
}
