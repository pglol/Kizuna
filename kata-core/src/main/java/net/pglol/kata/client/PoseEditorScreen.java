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
 * Test screen for the skeleton: a slider for every joint, your character large on the right, and a
 * few starting poses (guard, kick, crouch, ledge hang). The pose stays on you after you close it, so
 * you can walk around and look at it in the world; Reset puts you back to normal.
 */
public final class PoseEditorScreen extends Screen {
    private record Joint(Bone bone, int axis, String label, float min, float max) { }

    private static final Joint[] JOINTS = {
        new Joint(Bone.HEAD, Pose.PITCH, "Head nod", -60, 60),
        new Joint(Bone.HEAD, Pose.YAW, "Head turn", -80, 80),
        new Joint(Bone.CHEST, Pose.PITCH, "Waist bend", -45, 80),
        new Joint(Bone.CHEST, Pose.YAW, "Waist twist", -60, 60),
        new Joint(Bone.CHEST, Pose.ROLL, "Waist lean", -35, 35),
        new Joint(Bone.RIGHT_ARM, Pose.PITCH, "R shoulder raise", -180, 60),
        new Joint(Bone.RIGHT_ARM, Pose.YAW, "R shoulder turn", -90, 90),
        new Joint(Bone.RIGHT_ARM, Pose.ROLL, "R shoulder out", -10, 170),
        new Joint(Bone.RIGHT_FOREARM, Pose.PITCH, "R elbow bend", 0, 150),
        new Joint(Bone.LEFT_ARM, Pose.PITCH, "L shoulder raise", -180, 60),
        new Joint(Bone.LEFT_ARM, Pose.YAW, "L shoulder turn", -90, 90),
        new Joint(Bone.LEFT_ARM, Pose.ROLL, "L shoulder out", -170, 10),
        new Joint(Bone.LEFT_FOREARM, Pose.PITCH, "L elbow bend", 0, 150),
        new Joint(Bone.RIGHT_LEG, Pose.PITCH, "R hip raise", -120, 60),
        new Joint(Bone.RIGHT_LEG, Pose.ROLL, "R hip out", -10, 70),
        new Joint(Bone.RIGHT_SHIN, Pose.PITCH, "R knee bend", 0, 150),
        new Joint(Bone.LEFT_LEG, Pose.PITCH, "L hip raise", -120, 60),
        new Joint(Bone.LEFT_LEG, Pose.ROLL, "L hip out", -70, 10),
        new Joint(Bone.LEFT_SHIN, Pose.PITCH, "L knee bend", 0, 150)};

    private final List<JointSlider> sliders = new ArrayList<>();
    private float turn = 30;

    public PoseEditorScreen() {
        super(Text.literal("Pose editor"));
    }

    @Override
    protected void init() {
        sliders.clear();
        int col = Math.min(150, (width / 2 - 24) / 2), rows = (JOINTS.length + 1) / 2;
        int h = Math.max(14, Math.min(18, (height - 80) / rows - 2));
        for (int i = 0; i < JOINTS.length; i++) {
            int x = 10 + (i / rows) * (col + 6), y = 26 + (i % rows) * (h + 2);
            JointSlider s = new JointSlider(x, y, col, h, JOINTS[i]);
            sliders.add(addDrawableChild(s));
        }
        int by = 26 + rows * (h + 2) + 6, bw = (col * 2 + 6 - 3 * 4) / 5;
        String[] names = {"Reset", "Guard", "Kick", "Crouch", "Ledge hang"};
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(n), b -> preset(n)).dimensions(10 + i * (bw + 3), by, bw, 18).build());
        }
        addDrawableChild(new SliderWidget(10, by + 24, col * 2 + 6, 16, Text.empty(), (turn + 180) / 360.0) {
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
        switch (name) {
            case "Guard" -> {
                p.set(Bone.CHEST, 5, 20, 0);
                p.set(Bone.RIGHT_ARM, -75, -25, 10).set(Bone.RIGHT_FOREARM, 115, 0, 0);
                p.set(Bone.LEFT_ARM, -60, 20, -10).set(Bone.LEFT_FOREARM, 125, 0, 0);
                p.set(Bone.RIGHT_LEG, 15, 0, 8).set(Bone.RIGHT_SHIN, 25, 0, 0);
                p.set(Bone.LEFT_LEG, -20, 0, -6).set(Bone.LEFT_SHIN, 15, 0, 0);
                p.set(Bone.HEAD, 10, -15, 0);
            }
            case "Kick" -> {
                p.set(Bone.CHEST, -15, 0, 8);
                p.set(Bone.RIGHT_LEG, -95, 0, 10).set(Bone.RIGHT_SHIN, 10, 0, 0);
                p.set(Bone.LEFT_SHIN, 20, 0, 0);
                p.set(Bone.RIGHT_ARM, -30, 0, 40).set(Bone.RIGHT_FOREARM, 90, 0, 0);
                p.set(Bone.LEFT_ARM, -50, 0, -30).set(Bone.LEFT_FOREARM, 110, 0, 0);
            }
            case "Crouch" -> {
                p.set(Bone.CHEST, 30, 0, 0);
                p.set(Bone.RIGHT_LEG, -70, 0, 6).set(Bone.RIGHT_SHIN, 120, 0, 0);
                p.set(Bone.LEFT_LEG, -40, 0, -6).set(Bone.LEFT_SHIN, 90, 0, 0);
                p.set(Bone.RIGHT_ARM, -40, 0, 0).set(Bone.RIGHT_FOREARM, 60, 0, 0);
                p.set(Bone.LEFT_ARM, -40, 0, 0).set(Bone.LEFT_FOREARM, 60, 0, 0);
            }
            case "Ledge hang" -> {
                p.set(Bone.RIGHT_ARM, -170, 0, 10).set(Bone.RIGHT_FOREARM, 25, 0, 0);
                p.set(Bone.LEFT_ARM, -170, 0, -10).set(Bone.LEFT_FOREARM, 25, 0, 0);
                p.set(Bone.CHEST, -10, 0, 0);
                p.set(Bone.RIGHT_LEG, 10, 0, 0).set(Bone.RIGHT_SHIN, 35, 0, 0);
                p.set(Bone.LEFT_LEG, -5, 0, 0).set(Bone.LEFT_SHIN, 20, 0, 0);
                p.set(Bone.HEAD, -30, 0, 0);
            }
            default -> { }
        }
        for (JointSlider s : sliders) s.sync();
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fill(0, 0, width / 2 + 10, height, 0xC0101010);
        c.drawTextWithShadow(textRenderer, Text.literal("POSE EDITOR").formatted(net.minecraft.util.Formatting.GOLD), 10, 10, 0xFFFFFFFF);
        c.drawTextWithShadow(textRenderer, Text.literal("Drag a slider to bend that joint").formatted(net.minecraft.util.Formatting.GRAY), 90, 10, 0xFFFFFFFF);
        super.render(c, mouseX, mouseY, delta);
        if (client != null && client.player != null) {
            int x0 = width / 2 + 20, x1 = width - 10, y0 = 10, y1 = height - 10;
            int size = (int) ((y1 - y0) * 0.36f);
            // The preview looks towards the "mouse": put that point to its side to turn it.
            float cx = (x0 + x1) / 2f, cy = y0 + (y1 - y0) * 0.35f;
            float fx = cx + (float) Math.sin(Math.toRadians(turn)) * 400, fy = cy;
            InventoryScreen.drawEntity(c, x0, y0, x1, y1, size, 0.0625f, fx, fy, client.player);
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
            setMessage(Text.literal(j.label + ": " + (int) degrees() + "°"));
        }

        @Override
        protected void applyValue() {
            PoseStore.mine.set(j.bone, j.axis, degrees());
        }
    }
}
