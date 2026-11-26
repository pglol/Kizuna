package net.pglol.kata.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** The camera's settings: when the shoulder view comes in, where it sits, and how it moves. */
public final class KataCamScreen extends Screen {
    private final Screen parent;

    public KataCamScreen(Screen parent) {
        super(Text.literal("Camera"));
        this.parent = parent;
    }

    private static final class Slider extends SliderWidget {
        private final float min, max;
        private final String label;
        private final Consumer<Float> set;
        private final Function<Float, String> fmt;

        Slider(int x, int y, int w, String label, float min, float max, float now, Consumer<Float> set, Function<Float, String> fmt) {
            super(x, y, w, 20, Text.empty(), (now - min) / (max - min));
            this.min = min;
            this.max = max;
            this.label = label;
            this.set = set;
            this.fmt = fmt;
            updateMessage();
        }

        private float val() {
            return (float) (min + (max - min) * value);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(label + ": " + fmt.apply(val())));
        }

        @Override
        protected void applyValue() {
            set.accept(val());
        }
    }

    @Override
    protected void init() {
        KataCam.load();
        var c = KataCam.cfg;
        int w = 220, x = width / 2 - w / 2, y = 52, gap = 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Third person: " + (c.thirdPerson ? "Over the shoulder" : "Minecraft's")), b -> {
            c.thirdPerson = !c.thirdPerson;
            clearAndInit();
        }).dimensions(x, y, w, 20).build());
        y += gap;
        addDrawableChild(ButtonWidget.builder(Text.literal("Shoulder view when locked on: " + (c.onLockOn ? "On" : "Off")), b -> {
            c.onLockOn = !c.onLockOn;
            clearAndInit();
        }).dimensions(x, y, w, 20).build());
        y += gap + 6;
        Function<Float, String> blocks = v -> String.format(Locale.ROOT, "%.1f", v);
        Function<Float, String> pct = v -> Math.round(v * 100) + "%";
        addDrawableChild(new Slider(x, y, w, "Distance", 1.5f, 7f, c.distance, v -> c.distance = v, blocks));
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Shoulder offset", 0f, 1.6f, c.side, v -> c.side = v, blocks));
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Height", -0.5f, 1f, c.height, v -> c.height = v, blocks));
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Smoothing", 0f, 1f, c.smoothing, v -> c.smoothing = v, pct));
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Speed trail", 0f, 1f, c.lag, v -> c.lag = v, pct));
        y += gap + 6;
        addDrawableChild(ButtonWidget.builder(Text.literal("Defaults"), b -> {
            KataCam.cfg = new KataCam.Settings();
            clearAndInit();
        }).dimensions(x, y, w / 2 - 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(x + w / 2 + 2, y, w / 2 - 2, 20).build());
    }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float delta) {
        c.fill(0, 0, width, height, 0xC0141210);
        int w = 250;
        Ui.panel(c, width / 2 - w / 2, 14, w, height - 28);
        String t = "CAMERA";
        Ui.text(c, textRenderer, t, width / 2f - Ui.width(textRenderer, t, 1f) / 2, 24, 1f, Ui.TEXT);
        String key = KataClient.shoulderKey == null ? "Caps Lock" : KataClient.shoulderKey.getBoundKeyLocalizedText().getString();
        String sub = "Swap shoulders: " + key;
        Ui.text(c, textRenderer, sub, width / 2f - Ui.width(textRenderer, sub, 0.6f) / 2, 37, 0.6f, Ui.SOFT);
    }

    @Override
    public void close() {
        KataCam.save();
        if (client != null) client.setScreen(parent);
    }
}
