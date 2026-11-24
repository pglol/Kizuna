package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * One page of options in the game's own style: a title, rows with a name and a line on what it does,
 * and a working control on the right (switch, slider or a link to another page). Controls change
 * Minecraft's real settings straight away and are saved when the page closes. Scrolls when it's long.
 */
public class OptionsPage extends Screen {
    private interface Control {
        ClickableWidget make(int x, int y, int w, int h);
    }

    private record Row(String label, String desc, Control control) {
        boolean header() {
            return control == null;
        }
    }

    protected final Screen parent;
    private final String heading, subtitle;
    private final Consumer<OptionsPage> build;
    private final List<Row> rows = new ArrayList<>();
    private int scroll, left, w, listTop, viewH, contentH;
    private static final int ROW = 26, HEAD = 18;

    public OptionsPage(Screen parent, String heading, String subtitle, Consumer<OptionsPage> build) {
        super(Text.literal(heading));
        this.parent = parent;
        this.heading = heading;
        this.subtitle = subtitle;
        this.build = build;
    }

    // ---- Building a page ----

    public OptionsPage section(String title) {
        rows.add(new Row(title, null, null));
        return this;
    }

    public OptionsPage toggle(String label, String desc, SimpleOption<Boolean> o) {
        return toggle(label, desc, o::getValue, o::setValue);
    }

    public OptionsPage toggle(String label, String desc, Supplier<Boolean> get, Consumer<Boolean> set) {
        rows.add(new Row(label, desc, (x, y, cw, h) -> {
            AotButton b = new AotButton(x + cw - 64, y, 64, h, Text.literal(get.get() ? "On" : "Off"), null);
            b.selected = get.get();
            b.setAction(() -> {
                set.accept(!get.get());
                b.selected = get.get();
                b.setMessage(Text.literal(get.get() ? "On" : "Off"));
            });
            return b;
        }));
        return this;
    }

    /** A slider over a number option; min..max is the option's range, fmt how the value reads. */
    public OptionsPage slider(String label, String desc, SimpleOption<Double> o, double min, double max, DoubleFunction<String> fmt) {
        rows.add(new Row(label, desc, (x, y, cw, h) ->
            new Slider(x + cw - 150, y, 150, h, (o.getValue() - min) / (max - min), v -> o.setValue(min + v * (max - min)),
                v -> fmt.apply(min + v * (max - min)))));
        return this;
    }

    public OptionsPage intSlider(String label, String desc, SimpleOption<Integer> o, int min, int max, DoubleFunction<String> fmt) {
        rows.add(new Row(label, desc, (x, y, cw, h) ->
            new Slider(x + cw - 150, y, 150, h, (o.getValue() - min) / (double) (max - min),
                v -> o.setValue((int) Math.round(min + v * (max - min))), v -> fmt.apply(Math.round(min + v * (max - min))))));
        return this;
    }

    /** A row that opens another screen. */
    public OptionsPage link(String label, String desc, Supplier<Screen> open) {
        rows.add(new Row(label, desc, (x, y, cw, h) -> new AotButton(x + cw - 96, y, 96, h, Text.literal("Open  ›"), () -> client.setScreen(open.get()))));
        return this;
    }

    // ---- Screen ----

    @Override
    protected void init() {
        if (rows.isEmpty()) build.accept(this);
        w = Math.min(440, width - 24);
        left = (width - w) / 2;
        listTop = 46;
        viewH = height - listTop - 40;
        contentH = 0;
        for (Row r : rows) contentH += r.header() ? HEAD : ROW;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentH - viewH));
        int y = listTop - scroll;
        for (Row r : rows) {
            int rh = r.header() ? HEAD : ROW;
            if (!r.header() && y >= listTop - 2 && y + 20 <= listTop + viewH + 2) addDrawableChild(r.control.make(left + 6, y + 2, w - 12, 20));
            y += rh;
        }
        addDrawableChild(new AotButton(width / 2 - 50, height - 30, 100, 20, Ui.heading("Done"), this::close));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (contentH <= viewH) return false;
        scroll = MathHelper.clamp(scroll - (int) (v * 22), 0, contentH - viewH);
        clearAndInit();
        return true;
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        Ui.glass(c, left - 8, 8, w + 16, height - 16);
        Ui.text(c, Ui.title(heading), width / 2f, 16, 1.2f, Ui.GOLD, true);
        if (subtitle != null && !subtitle.isEmpty()) Ui.text(c, Text.literal(subtitle), width / 2f, 31, 0.6f, Ui.MUTED, true);
        c.enableScissor(left - 8, listTop - 2, left + w + 8, listTop + viewH + 2);
        int y = listTop - scroll;
        for (Row r : rows) {
            if (r.header()) {
                Ui.text(c, Ui.heading(r.label.toUpperCase(java.util.Locale.ROOT)), left + 6, y + 6, 0.75f, Ui.GOLD, false);
                c.fill(left + 6, y + 15, left + w - 6, y + 16, 0x30E0B96A);
                y += HEAD;
            } else {
                Ui.text(c, Text.literal(r.label), left + 10, y + 4, 0.75f, Ui.CREAM, false);
                if (r.desc != null) Ui.text(c, Text.literal(r.desc), left + 10, y + 14, 0.55f, Ui.MUTED, false);
                y += ROW;
            }
        }
        c.disableScissor();
        if (contentH > viewH) {
            int bar = Math.max(20, viewH * viewH / contentH), by = listTop + (viewH - bar) * scroll / Math.max(1, contentH - viewH);
            c.fill(left + w + 2, by, left + w + 4, by + bar, 0x80E0B96A);
        }
        for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(c, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.options.write();
        client.setScreen(parent);
    }

    /** A slider drawn like the rest of our UI: a dark track, a gold fill, the value in the middle. */
    public static final class Slider extends SliderWidget {
        private final java.util.function.DoubleConsumer apply;
        private final DoubleFunction<String> fmt;

        public Slider(int x, int y, int w, int h, double value, java.util.function.DoubleConsumer apply, DoubleFunction<String> fmt) {
            super(x, y, w, h, Text.empty(), MathHelper.clamp(value, 0, 1));
            this.apply = apply;
            this.fmt = fmt;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(fmt.apply(value)));
        }

        @Override
        protected void applyValue() {
            apply.accept(value);
        }

        @Override
        public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
            int x = getX(), y = getY();
            boolean hov = isHovered() || isFocused();
            c.fill(x, y, x + width, y + height, 0xB80D1012);
            c.fill(x + 1, y + 1, x + 1 + (int) ((width - 2) * value), y + height - 1, hov ? 0x70E0B96A : 0x50E0B96A);
            c.drawBorder(x, y, width, height, hov ? 0x80E0B96A : 0x26FFFFFF);
            int kx = x + (int) ((width - 4) * value);
            c.fill(kx, y, kx + 4, y + height, Ui.GOLD);
            Ui.text(c, getMessage(), x + width / 2f, y + (height - 7) / 2f, 0.75f, Ui.CREAM, true);
        }
    }
}
