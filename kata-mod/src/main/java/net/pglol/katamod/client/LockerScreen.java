package net.pglol.katamod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Kata menu: your school locker, in a wall of them. It unlocks and swings open; inside, each thing
 * is a way into part of the game, and lifts and glows as the mouse comes over it:
 *  - the headband: fighting styles and moves (the deck)
 *  - the bag: your inventory
 *  - the books: camera and play settings
 *  - the manga: controls
 *  - the shuriken: call a sparring dummy
 * Choosing one (or Esc) slams the door shut, then goes there.
 */
public final class LockerScreen extends Screen {
    private static final Identifier TEX = Identifier.of("kata", "textures/locker/locker.png");
    private static final Identifier CREAM = Identifier.of("kata", "textures/locker/locker_cream.png");
    private static BbModel locker;
    private static BbModel[] decos;

    private record Item(String key, String title, String what) { }

    private static final Item[] ITEMS = {
        new Item("headband", "HEADBAND", "Fighting styles and moves"),
        new Item("bag", "SCHOOL BAG", "Inventory"),
        new Item("books", "TEXTBOOKS", "Camera and play settings"),
        new Item("manga", "MANGA", "Controls"),
        new Item("shuriken", "SHURIKEN", "Call a sparring dummy"),
    };

    private enum Door { OPENING, OPEN, CLOSING }

    private static final class ItemState {
        String anim = "rest";
        long at = System.nanoTime();
    }

    private Door door = Door.OPENING;
    private long doorAt = System.nanoTime();
    private final Map<String, ItemState> items = new LinkedHashMap<>();
    private final Map<String, float[]> bounds = new HashMap<>();
    private final Map<String, java.util.List<float[]>> faces = new HashMap<>();
    private String hovered, chosen;

    public LockerScreen() {
        super(Text.literal("Locker"));
        for (Item it : ITEMS) {
            items.put(it.key, new ItemState());
            bounds.put("item_" + it.key, new float[4]);
        }
    }

    private static void loadModels() {
        if (locker != null) return;
        locker = BbModel.load(Identifier.of("kata", "locker/locker.bbmodel"));
        decos = new BbModel[] {
            BbModel.load(Identifier.of("kata", "locker/locker_deco_shoes.bbmodel")),
            BbModel.load(Identifier.of("kata", "locker/locker_deco_sneakers.bbmodel")),
            BbModel.load(Identifier.of("kata", "locker/locker_deco_towel.bbmodel")),
            BbModel.load(Identifier.of("kata", "locker/locker_deco_pouch.bbmodel")),
            BbModel.load(Identifier.of("kata", "locker/locker_deco_broken.bbmodel")),
        };
    }

    @Override
    protected void init() {
        loadModels();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static float since(long t) {
        return (System.nanoTime() - t) / 1e9f;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xF0101216, 0xF0050607);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        float dt = since(doorAt);
        // The door: open, then the draft; slam shut when leaving.
        String doorAnim;
        if (door == Door.OPENING) {
            doorAnim = "animation.locker.open";
            if (dt >= 0.9f) {
                door = Door.OPEN;
                doorAt = System.nanoTime();
                dt = 0;
                doorAnim = "animation.locker.open_idle";
            }
        } else if (door == Door.OPEN) doorAnim = "animation.locker.open_idle";
        else {
            doorAnim = "animation.locker.close";
            if (dt >= 0.85f) {
                go();
                return;
            }
        }
        // The items: lift and glow on hover, settle back after.
        for (Map.Entry<String, ItemState> en : items.entrySet()) {
            ItemState s = en.getValue();
            String k = en.getKey();
            boolean over = door == Door.OPEN && k.equals(hovered);
            float t = since(s.at);
            switch (s.anim) {
                case "rest" -> { if (over) set(s, "hover_start"); }
                case "hover_start" -> { if (!over) set(s, "hover_end"); else if (t >= 0.35f) set(s, "hover"); }
                case "hover" -> { if (!over) set(s, "hover_end"); }
                default -> { if (t >= 0.3f) set(s, over ? "hover_start" : "rest"); }
            }
        }
        Map<String, BbModel.BonePose> pose = new HashMap<>();
        locker.sample(doorAnim, dt, pose);
        for (Map.Entry<String, ItemState> en : items.entrySet())
            locker.sample("animation.locker." + en.getKey() + "." + en.getValue().anim, since(en.getValue().at), pose);

        MatrixStack ms = c.getMatrices();
        VertexConsumerProvider.Immediate vcp = c.getVertexConsumers();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderLights(new Vector3f(0.4f, 0.7f, 0.6f).normalize(), new Vector3f(-0.4f, -0.7f, -0.6f).normalize());
        ms.push();
        float scale = height * 0.58f / 16f;
        // The camera: square on to the wall, a touch from above and to the side, easing in as it opens.
        float in = door == Door.OPENING ? MathHelper.clamp(dt / 0.9f, 0, 1) : 1;
        ms.translate(width / 2f, height * 0.5f + 0.5f * scale * 8, 400);
        ms.scale(-scale * (0.92f + 0.08f * in), -scale * (0.92f + 0.08f * in), -scale);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-6));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(10 - 4 * in));
        ms.translate(0, -8, 0);
        // The wall round it: a row above, a row below (closed), open ones either side.
        VertexConsumer steel = vcp.getBuffer(RenderLayer.getEntityTranslucent(TEX));
        int[][] wall = {{-2, 0}, {-1, 0}, {1, 0}, {2, 0}, {-2, 16}, {-1, 16}, {0, 16}, {1, 16}, {2, 16}, {-2, -16}, {-1, -16}, {0, -16}, {1, -16}, {2, -16}};
        int deco = 0;
        for (int[] w : wall) {
            ms.push();
            ms.translate(w[0] * 16, w[1], 0);
            int dim = w[1] == 0 ? 0xFFC8C8C8 : 0xFF909090;
            boolean open = w[1] == 0 || (w[0] == 2 && w[1] == -16);
            if (open && deco < decos.length) decos[deco++].render(ms, steel, null, dim, null);
            else locker.render(ms, steel, Map.of("door", new BbModel.BonePose()), dim, null);
            ms.pop();
        }
        vcp.draw();
        // Your locker, in the middle, with its items.
        VertexConsumer mine = vcp.getBuffer(RenderLayer.getEntityTranslucent(TEX));
        locker.render(ms, mine, pose, 0xFFFFFFFF, bounds, faces);
        vcp.draw();
        ms.pop();
        RenderSystem.disableDepthTest();

        // What's under the mouse.
        hovered = null;
        if (door == Door.OPEN) {
            // The item actually under the mouse (its faces, the frontmost where two overlap).
            String bone = BbModel.hit(faces, mouseX, mouseY);
            if (bone != null && bone.startsWith("item_")) hovered = bone.substring(5);
        }
        ms.push();
        ms.translate(0, 0, 900);
        if (hovered != null) {
            Item it = item(hovered);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(it.title).styled(s -> s.withBold(true)), width / 2, height - 46, 0xFFF4ECE2);
            c.drawCenteredTextWithShadow(textRenderer, Text.literal(it.what), width / 2, height - 33, 0xFFB8B2A2);
        } else if (door == Door.OPEN) {
            c.drawCenteredTextWithShadow(textRenderer, Text.literal("Your locker"), width / 2, height - 40, 0xFF8A857A);
        }
        c.drawCenteredTextWithShadow(textRenderer, Text.literal("ESC  close"), width / 2, height - 16, 0xFF5E5A52);
        ms.pop();
    }

    private static void set(ItemState s, String anim) {
        s.anim = anim;
        s.at = System.nanoTime();
    }

    private static Item item(String key) {
        for (Item it : ITEMS) if (it.key.equals(key)) return it;
        return ITEMS[0];
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && door == Door.OPEN && hovered != null) {
            chosen = hovered;
            shut();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (door == Door.CLOSING) return true;
            chosen = null;
            shut();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void shut() {
        door = Door.CLOSING;
        doorAt = System.nanoTime();
    }

    /** The door's shut: go where they chose (or back to the game). */
    private void go() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String to = chosen;
        if (to == null || mc.player == null) {
            mc.setScreen(null);
            return;
        }
        switch (to) {
            case "headband" -> mc.setScreen(new net.pglol.kata.client.DeckScreen());
            case "bag" -> mc.setScreen(new InventoryScreen(mc.player));
            case "books" -> mc.setScreen(new net.pglol.kata.client.KataCamScreen(null));
            case "manga" -> mc.setScreen(new KeybindsScreen(null, mc.options));
            default -> {
                mc.setScreen(null);
                mc.player.networkHandler.sendChatCommand("kata dummy");
            }
        }
    }
}
