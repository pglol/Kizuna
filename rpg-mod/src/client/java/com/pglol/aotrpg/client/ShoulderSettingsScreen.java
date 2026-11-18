package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** The combat camera's settings: when it comes in, where it sits, and how it moves. */
public final class ShoulderSettingsScreen extends Screen {
    private final Screen parent;

    public ShoulderSettingsScreen(Screen parent) {
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
        var c = ShoulderCam.cfg;
        // Two columns: the camera on the left, how you move and fight in the air on the right.
        int w = 220, x = width / 2 - w - 6, y = 56, gap = 24;
        int mx = width / 2 + 6, my = 56;
        moveY = my - 14;
        moveX = mx;
        addDrawableChild(new AotButton(mx, my, w, 20, Text.literal("Run style: " + Poses.name(c.runStyle)), () -> {
            c.runStyle = Poses.next(c.runStyle);
            Poses.announce();
            clearAndInit();
        }));
        my += gap;
        addDrawableChild(new AotButton(mx, my, w, 20, Text.literal("Idle: " + Gestures.idleName(c.idleStyle)), () -> {
            c.idleStyle = Gestures.nextIdle(c.idleStyle);
            Poses.announce();
            clearAndInit();
        }));
        my += gap;
        String[] slams = {"Crescent Slam", "Spiral Drop", "Piercing Dive"};
        addDrawableChild(new AotButton(mx, my, w, 20, Text.literal("Air slam: " + slams[Math.max(0, Math.min(2, c.slam - 3))]), () -> {
            c.slam = c.slam >= 5 ? 3 : c.slam + 1;
            clearAndInit();
        }));
        my += gap;
        addDrawableChild(new AotButton(mx, my, w, 20, Text.literal("Dash at targets: " + (c.dashHoming ? "On" : "Off")), () -> {
            c.dashHoming = !c.dashHoming;
            clearAndInit();
        })).selected(c.dashHoming);
        addDrawableChild(new AotButton(x, y, w, 20, Text.literal("On lock-on: " + (c.onLockOn ? "On" : "Off")), () -> {
            c.onLockOn = !c.onLockOn;
            clearAndInit();
        })).selected(c.onLockOn);
        y += gap;
        addDrawableChild(new AotButton(x, y, w, 20, Text.literal("Always in third person: " + (c.inThirdPerson ? "On" : "Off")), () -> {
            c.inThirdPerson = !c.inThirdPerson;
            clearAndInit();
        })).selected(c.inThirdPerson);
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
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Speed lens", 0f, 25f, c.fovKick, v -> c.fovKick = v, v -> Math.round(v) + "°"));
        y += gap;
        addDrawableChild(new Slider(x, y, w, "Banking", 0f, 2f, c.bank, v -> c.bank = v, pct));
        y += gap + 6;
        addDrawableChild(new AotButton(x, y, w / 2 - 2, 20, Text.literal("Defaults"), () -> {
            String run = ShoulderCam.cfg.runStyle, idle = ShoulderCam.cfg.idleStyle;
            int slam = ShoulderCam.cfg.slam;
            boolean dash = ShoulderCam.cfg.dashHoming;
            ShoulderCam.cfg = new ShoulderCam.Settings();
            ShoulderCam.cfg.runStyle = run;
            ShoulderCam.cfg.idleStyle = idle;
            ShoulderCam.cfg.slam = slam;
            ShoulderCam.cfg.dashHoming = dash;
            clearAndInit();
        }));
        addDrawableChild(new AotButton(x + w / 2 + 2, y, w / 2 - 2, 20, Text.literal("Done"), this::close));
    }

    private int moveX, moveY;

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        Ui.backdrop(c, width, height);
        Ui.text(c, Ui.heading("MOVEMENT & MOVES"), moveX, moveY, 0.7f, Ui.GOLD, false);
        Ui.text(c, Ui.title("COMBAT & MOVEMENT"), width / 2f, 22, 1.3f, Ui.GOLD, true);
        String key = ShoulderCam.swapKey == null ? "Caps Lock" : ShoulderCam.swapKey.getBoundKeyLocalizedText().getString();
        Ui.text(c, Text.literal("Swap shoulders: " + key), width / 2f, 38, 0.75f, 0xFFB8B0A0, true);
    }

    @Override
    public void close() {
        ShoulderCam.save();
        if (client != null) client.setScreen(parent);
    }
}
