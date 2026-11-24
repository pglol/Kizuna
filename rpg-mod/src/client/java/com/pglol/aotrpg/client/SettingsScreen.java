package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Display settings: what of the HUD and the effects you want on your screen (this computer only).
 * Every row is a switch; changes apply at once and are saved on close.
 */
public final class SettingsScreen extends Screen {
    private final Screen parent;

    public SettingsScreen(Screen parent) {
        super(Text.literal("Display"));
        this.parent = parent;
    }

    private record Row(String title, String desc, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) { }

    private static final Row[] ROWS = {
        new Row("Waypoint markers", "Quest, activity and party markers over the world", () -> Settings.markers, v -> Settings.markers = v),
        new Row("Light columns", "Beams of light over quests and activities", () -> Settings.beams, v -> Settings.beams = v),
        new Row("Compass", "The bearing ribbon under the top bar", () -> Settings.compass, v -> Settings.compass = v),
        new Row("Quest tracker", "Your quest and activities under the minimap", () -> Settings.tracker, v -> Settings.tracker = v),
        new Row("Stat cards", "Numbers turning over on Tab and in the pause menu", () -> Settings.statCards, v -> Settings.statCards = v),
        new Row("Hints", "Tips over the hotbar and move lessons", () -> Settings.hints, v -> Settings.hints = v),
        new Row("Intro cutscenes", "Off: just the title card when something big turns up", () -> Settings.cutscenes, v -> Settings.cutscenes = v),
        new Row("Screen shake", "Shake on heavy hits, landings and quakes", () -> Settings.screenShake, v -> Settings.screenShake = v),
        new Row("Impact frames", "The speed lines and freeze on big blows", () -> Settings.impactFrames, v -> Settings.impactFrames = v)};

    private int left, top, w;

    @Override
    protected void init() {
        w = Math.min(380, width - 24);
        left = (width - w) / 2;
        top = Math.max(30, height / 2 - (ROWS.length * 24 + 50) / 2);
        for (int i = 0; i < ROWS.length; i++) {
            Row r = ROWS[i];
            int y = top + 30 + i * 24;
            AotButton b = addDrawableChild(new AotButton(left + w - 64, y + 2, 56, 18, Text.literal(r.get().get() ? "On" : "Off"), null));
            b.selected = r.get().get();
            AotButton bb = b;
            bb.setAction(() -> {
                r.set().accept(!r.get().get());
                bb.selected = r.get().get();
                bb.setMessage(Text.literal(r.get().get() ? "On" : "Off"));
                Settings.save();
            });
        }
        addDrawableChild(new AotButton(left + w / 2 - 50, top + 34 + ROWS.length * 24, 100, 20, Ui.heading("Done"), this::close));
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        int h = ROWS.length * 24 + 64;
        Ui.glass(c, left - 8, top - 4, w + 16, h);
        Ui.text(c, Ui.title("DISPLAY"), width / 2f, top + 4, 1.2f, Ui.GOLD, true);
        for (int i = 0; i < ROWS.length; i++) {
            int y = top + 30 + i * 24;
            Ui.text(c, Text.literal(ROWS[i].title()), left + 4, y + 2, 0.75f, Ui.CREAM, false);
            Ui.text(c, Text.literal(ROWS[i].desc()), left + 4, y + 12, 0.55f, Ui.MUTED, false);
        }
        for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(c, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        Settings.save();
        if (client != null) client.setScreen(parent);
    }
}
