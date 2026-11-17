package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Setting up a duel: ring size, and whether ODM gear, abilities and food are allowed. */
public final class DuelSetupScreen extends Screen {
    private static final String[] RINGS = {"Small", "Medium", "Large", "Huge"};
    private static int ring, arena;
    private static final String[] ARENAS = {"Right here", "High Tree Grove", "Skyline"};
    private static boolean odm = true, abilities = true, food = true;
    private final Net.DuelSetup setup;

    public DuelSetupScreen(Net.DuelSetup setup) {
        super(Text.literal("Duel"));
        this.setup = setup;
    }

    private int pw() { return 300; }
    private int ph() { return arena > 0 ? 130 : 212; }
    private int px() { return width / 2 - pw() / 2; }
    private int py() { return height / 2 - ph() / 2; }

    @Override
    protected void init() {
        int x = px(), y = py(), w = pw();
        addDrawableChild(new AotButton(x + w - 20, y, 20, 20, Text.literal("✕"), this::close));
        int aw = (w - 20 - 2 * 4) / 3;
        for (int i = 0; i < ARENAS.length; i++) {
            int k = i;
            addDrawableChild(new AotButton(x + 10 + i * (aw + 4), y + 56, aw, 18, Text.literal(ARENAS[i]), () -> {
                arena = k;
                clearAndInit();
            })).selected(arena == i).textScale = 0.85f;
        }
        int bw = (w - 20 - 3 * 4) / 4;
        for (int i = 0; i < RINGS.length && arena == 0; i++) {
            int k = i;
            addDrawableChild(new AotButton(x + 10 + i * (bw + 4), y + 96, bw, 18, Text.literal(RINGS[i]), () -> {
                ring = k;
                clearAndInit();
            })).selected(ring == i);
        }
        if (arena == 0) {
            toggle(x + 10, y + 124, "ODM Gear", odm, v -> odm = v);
            toggle(x + 10, y + 146, "Abilities", abilities, v -> abilities = v);
            toggle(x + 10, y + 168, "Food", food, v -> food = v);
        }
        AotButton go = addDrawableChild(new AotButton(x + w / 2 - 70, y + ph() - 26, 140, 20, Ui.heading("Challenge"), () -> {
            ClientPlayNetworking.send(new Net.DuelChallenge(setup.target(), ring + 10 * arena, odm, abilities, food));
            close();
        }));
        go.accent = Ui.RED;
    }

    private void toggle(int x, int y, String label, boolean on, java.util.function.Consumer<Boolean> set) {
        int w = pw() - 20, half = (w - 100 - 4) / 2;
        addDrawableChild(new AotButton(x + 100, y, half, 18, Text.literal("On"), () -> {
            set.accept(true);
            clearAndInit();
        })).selected(on);
        AotButton off = addDrawableChild(new AotButton(x + 104 + half, y, half, 18, Text.literal("Off"), () -> {
            set.accept(false);
            clearAndInit();
        }));
        off.accent = Ui.RED;
        off.selected(!on);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        int x = px(), y = py(), w = pw();
        Ui.panel(c, x, y, w, ph());
        Ui.text(c, Ui.title("DUEL"), x + 12, y + 7, 1.3f, Ui.GOLD, false);
        Ui.text(c, Text.literal("vs " + setup.name()), x + 12, y + 26, 0.9f, Ui.CREAM, false);
        Ui.text(c, Ui.heading("Where"), x + 10, y + 44, 0.8f, Ui.GOLD, false);
        if (arena > 0) {
            Ui.text(c, Text.literal("First to 2 rounds · others can watch"), x + 10, y + 84, 0.75f, Ui.CREAM, false);
            return;
        }
        Ui.text(c, Ui.heading("Ring"), x + 10, y + 84, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("ODM Gear"), x + 10, y + 129, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("Abilities"), x + 10, y + 151, 0.8f, Ui.GOLD, false);
        Ui.text(c, Ui.heading("Food"), x + 10, y + 173, 0.8f, Ui.GOLD, false);
    }

    @Override
    public boolean shouldPause() { return false; }
}
