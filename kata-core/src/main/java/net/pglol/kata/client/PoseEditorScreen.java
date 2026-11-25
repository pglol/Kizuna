package net.pglol.kata.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.pglol.kata.rig.Bone;
import net.pglol.kata.rig.Pose;

import java.util.ArrayList;
import java.util.List;

/**
 * Test screen for the skeleton: a slider for every joint grouped by body part, your character large on
 * the right, and starting poses (guard, kick, crouch, hang) that blend in rather than snap. The pose
 * stays on you after closing, laid over your footwork as you move; Reset puts you back to normal.
 */
public final class PoseEditorScreen extends Screen {
    private record Joint(Bone bone, int axis, String label, float min, float max) { }
    private record Group(String title, Joint... joints) { }

    /** Two columns of body parts; short labels so they fit at any GUI scale. */
    private static final Group[][] COLUMNS = {
        {
            new Group("Head", new Joint(Bone.HEAD, Pose.PITCH, "Nod", -60, 60), new Joint(Bone.HEAD, Pose.YAW, "Turn", -80, 80)),
            new Group("Waist", new Joint(Bone.CHEST, Pose.PITCH, "Bend", -45, 80), new Joint(Bone.CHEST, Pose.YAW, "Twist", -60, 60),
                new Joint(Bone.CHEST, Pose.ROLL, "Lean", -35, 35)),
            new Group("Right arm", new Joint(Bone.RIGHT_ARM, Pose.PITCH, "Raise", -180, 60), new Joint(Bone.RIGHT_ARM, Pose.YAW, "Turn", -90, 90),
                new Joint(Bone.RIGHT_ARM, Pose.ROLL, "Out", -10, 170), new Joint(Bone.RIGHT_FOREARM, Pose.PITCH, "Elbow", 0, 150)),
        },
        {
            new Group("Left arm", new Joint(Bone.LEFT_ARM, Pose.PITCH, "Raise", -180, 60), new Joint(Bone.LEFT_ARM, Pose.YAW, "Turn", -90, 90),
                new Joint(Bone.LEFT_ARM, Pose.ROLL, "Out", -170, 10), new Joint(Bone.LEFT_FOREARM, Pose.PITCH, "Elbow", 0, 150)),
            new Group("Right leg", new Joint(Bone.RIGHT_LEG, Pose.PITCH, "Raise", -120, 60), new Joint(Bone.RIGHT_LEG, Pose.ROLL, "Out", -10, 70),
                new Joint(Bone.RIGHT_SHIN, Pose.PITCH, "Knee", 0, 150)),
            new Group("Left leg", new Joint(Bone.LEFT_LEG, Pose.PITCH, "Raise", -120, 60), new Joint(Bone.LEFT_LEG, Pose.ROLL, "Out", -70, 10),
                new Joint(Bone.LEFT_SHIN, Pose.PITCH, "Knee", 0, 150)),
        }};

    private record Heading(String text, int x, int y) { }

    private final List<JointSlider> sliders = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private float turn = 30;
    private int panelW;

    public PoseEditorScreen() {
        super(Text.literal("Pose editor"));
    }

    @Override
    protected void init() {
        sliders.clear();
        headings.clear();
        panelW = Math.max(220, Math.min(width / 2, 360));
        int col = (panelW - 26) / 2, top = 24, head = 11, bottom = 48;
        // Fit the taller column in the height there is.
        int rows = 0, heads = 0;
        for (Group[] c : COLUMNS) {
            int r = 0;
            for (Group g : c) r += g.joints.length;
            if (r > rows) {
                rows = r;
                heads = c.length;
            }
        }
        int h = Math.max(10, Math.min(16, (height - top - bottom - heads * head) / rows - 2));
        for (int ci = 0; ci < COLUMNS.length; ci++) {
            int x = 8 + ci * (col + 10), y = top;
            for (Group g : COLUMNS[ci]) {
                headings.add(new Heading(g.title, x, y + 1));
                y += head;
                for (Joint j : g.joints) {
                    sliders.add(addDrawableChild(new JointSlider(x, y, col, h, j)));
                    y += h + 2;
                }
            }
        }
        int by = height - bottom + 6, bw = (panelW - 16 - 4 * 3) / 5;
        String[] names = {"Reset", "Guard", "Kick", "Crouch", "Hang"};
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(n), b -> preset(n)).dimensions(8 + i * (bw + 3), by, bw, 16).build());
        }
        addDrawableChild(new SliderWidget(8, by + 20, panelW - 16, 14, Text.empty(), (turn + 180) / 360.0) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Text.literal("Turn preview: " + Math.round(value * 360 - 180) + "°"));
            }

            @Override
            protected void applyValue() {
                turn = (float) (value * 360 - 180);
            }
        });
    }

    private void preset(String name) {
        Pose p = PoseStore.mine;
        p.clear();
        Pose preset = Presets.of(name);
        if (preset != null) {
            Pose c = preset.copy();
            for (Bone b : Bone.values()) p.set(b, c.get(b, Pose.PITCH), c.get(b, Pose.YAW), c.get(b, Pose.ROLL));
        }
        for (JointSlider s : sliders) s.sync();
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fill(0, 0, panelW, height, 0xC0101010);
        c.drawTextWithShadow(textRenderer, Text.literal("POSE EDITOR").formatted(net.minecraft.util.Formatting.GOLD), 8, 8, 0xFFFFFFFF);
        for (Heading hd : headings) c.drawTextWithShadow(textRenderer, Text.literal(hd.text).formatted(net.minecraft.util.Formatting.YELLOW), hd.x + 1, hd.y, 0xFFFFFFFF);
        super.render(c, mouseX, mouseY, delta);
        if (client != null && client.player != null) {
            int x0 = panelW + 10, x1 = width - 10, y0 = 10, y1 = height - 10;
            int size = (int) Math.min((y1 - y0) * 0.36f, (x1 - x0) * 0.5f);
            float cx = (x0 + x1) / 2f, cy = y0 + (y1 - y0) * 0.35f;
            float fx = cx + (float) Math.sin(Math.toRadians(turn)) * 400;
            InventoryScreen.drawEntity(c, x0, y0, x1, y1, size, 0.0625f, fx, cy, client.player);
        }
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static final class JointSlider extends SliderWidget {
        private final Joint j;

        JointSlider(int x, int y, int w, int h, Joint j) {
            super(x, y, w, h, Text.empty(), 0);
            this.j = j;
            sync();
        }

        void sync() {
            value = (PoseStore.mine.get(j.bone, j.axis) - j.min) / (j.max - j.min);
            value = Math.max(0, Math.min(1, value));
            updateMessage();
        }

        private float degrees() {
            return Math.round(j.min + (float) value * (j.max - j.min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(j.label + "  " + (int) degrees() + "°"));
        }

        @Override
        protected void applyValue() {
            PoseStore.mine.set(j.bone, j.axis, degrees());
        }
    }
}
