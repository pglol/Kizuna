package com.pglol.aotrpg.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The keys you actually play with, grouped by what they're for (gear, fighting, moving, menus,
 * shifting), with plain names. Click a key and press the new one to rebind it (mouse buttons too);
 * Backspace clears it, right click puts it back to its default. Every other Minecraft key is one
 * button away. Shown once by itself the first time you play, as the controls primer.
 */
public final class ControlsScreen extends Screen {
    private record Bind(String id, String label) { }
    private record Group(String title, Bind... binds) { }

    private static final Group[] GROUPS = {
        new Group("ODM gear",
            new Bind("key.dannys-aot.left_hook", "Left hook"),
            new Bind("key.dannys-aot.right_hook", "Right hook"),
            new Bind("key.dannys-aot.reload_blade", "Reload blades"),
            new Bind("key.aot_rpg.sheath", "Draw / sheathe grips"),
            new Bind("key.dannys-aot.thunder_spear_load", "Load thunder spear"),
            new Bind("key.dannys-aot.flare_load", "Load flare"),
            new Bind("key.dannys-aot.flare_cycle", "Change flare colour"),
            new Bind("key.dannys-aot.apg_reload", "Reload APG")),
        new Group("Fighting",
            new Bind("key.attack", "Attack / slash"),
            new Bind("key.use", "Use · guard with blades"),
            new Bind("key.aot_rpg.lockon", "Lock on (drag to switch)"),
            new Bind("key.aot_rpg.guard", "Guard (optional key)"),
            new Bind("key.aot_rpg.ability1", "Role ability 1"),
            new Bind("key.aot_rpg.ability2", "Role ability 2"),
            new Bind("key.aot_rpg.ultimate", "Role ultimate"),
            new Bind("key.aot_rpg.technique", "Mentor technique"),
            new Bind("key.aot_rpg.heal", "Quick heal"),
            new Bind("key.aot_rpg.shoulder_swap", "Swap camera shoulder")),
        new Group("Moving",
            new Bind("key.forward", "Forward"),
            new Bind("key.back", "Back"),
            new Bind("key.left", "Left"),
            new Bind("key.right", "Right"),
            new Bind("key.jump", "Jump"),
            new Bind("key.sprint", "Sprint"),
            new Bind("key.sneak", "Crouch"),
            new Bind("key.aot_rpg.horse", "Call / send away horse")),
        new Group("Menus",
            new Bind("key.inventory", "Inventory"),
            new Bind("key.aot_rpg.character", "Character & skills"),
            new Bind("key.aot_rpg.satchel", "Satchel"),
            new Bind("key.aot_rpg.map", "World map"),
            new Bind("key.aot_rpg.journal", "Quest journal"),
            new Bind("key.aot_rpg.social", "Social wheel (hold)"),
            new Bind("key.aot_rpg.property", "Property menu"),
            new Bind("key.chat", "Chat"),
            new Bind("key.playerlist", "Player list")),
        new Group("Extraction",
            new Bind("key.aot_rpg.ready", "Ready up"),
            new Bind("key.aot_rpg.lobby", "Open the lobby")),
        new Group("Titan shifting",
            new Bind("key.dannys-aot.titan_shift", "Shift"),
            new Bind("key.dannys-aot.titan_roar", "Roar"),
            new Bind("key.dannys-aot.shifter_dodge", "Dodge"),
            new Bind("key.dannys-aot.awakened_power", "Awakened power"),
            new Bind("key.dannys-aot.stealth_mode", "Stealth"),
            new Bind("key.dannys-aot.shifter_ability_1", "Ability 1"),
            new Bind("key.dannys-aot.shifter_ability_2", "Ability 2"),
            new Bind("key.dannys-aot.shifter_ability_3", "Ability 3"),
            new Bind("key.dannys-aot.shifter_ability_4", "Ability 4"),
            new Bind("key.dannys-aot.shifter_ability_5", "Ability 5"),
            new Bind("key.dannys-aot.shifter_ability_6", "Ability 6"),
            new Bind("key.dannys-aot.shifter_ability_7", "Ability 7"),
            new Bind("key.dannys-aot.shifter_ability_8", "Ability 8"),
            new Bind("key.dannys-aot.shifter_ability_9", "Ability 9"),
            new Bind("key.dannys-aot.hide_shifter_ui", "Hide shifter HUD"),
            new Bind("key.dannys-aot.hood_toggle", "Hood up / down"))};

    /** Groups whose keys only work in a different situation, so sharing a key with them is fine. */
    private static final Set<String> APART = Set.of("Extraction", "Titan shifting");
    /** Pairs that are meant to share a key (each only works with its own weapon). */
    private static final Set<String> SHARED_OK = Set.of("key.dannys-aot.reload_blade", "key.dannys-aot.apg_reload");

    private record Line(String group, KeyBinding key, String label) { }

    private final Screen parent;
    private final boolean intro;
    private final List<Line> lines = new ArrayList<>();
    private final Map<Line, String> clash = new HashMap<>();
    private KeyBinding listening;
    private int scroll, left, w, listTop, viewH, contentH;
    private static final int ROW = 22, HEAD = 18, BOX = 96;

    public ControlsScreen(Screen parent, boolean intro) {
        super(Text.literal("Controls"));
        this.parent = parent;
        this.intro = intro;
    }

    private KeyBinding find(String id) {
        for (KeyBinding k : client.options.allKeys) if (k.getTranslationKey().equals(id)) return k;
        return null;
    }

    @Override
    protected void init() {
        lines.clear();
        for (Group g : GROUPS) {
            List<Line> found = new ArrayList<>();
            for (Bind b : g.binds) {
                KeyBinding k = find(b.id);
                if (k != null) found.add(new Line(g.title, k, b.label));
            }
            if (found.isEmpty()) continue;
            lines.add(new Line(g.title, null, g.title));
            lines.addAll(found);
        }
        findClashes();
        w = Math.min(460, width - 24);
        left = (width - w) / 2;
        listTop = intro ? 54 : 46;
        viewH = height - listTop - 40;
        contentH = 0;
        for (Line l : lines) contentH += l.key == null ? HEAD : ROW;
        scroll = MathHelper.clamp(scroll, 0, Math.max(0, contentH - viewH));
        int bw = 120;
        addDrawableChild(new AotButton(width / 2 - bw - 4, height - 30, bw, 20, Text.literal("All Minecraft keys"),
            () -> client.setScreen(new KeybindsScreen(this, client.options))));
        addDrawableChild(new AotButton(width / 2 + 4, height - 30, bw, 20, Ui.heading(intro ? "Got it" : "Done"), this::close));
    }

    private void findClashes() {
        clash.clear();
        for (Line a : lines) {
            if (a.key == null || a.key.isUnbound() || APART.contains(a.group)) continue;
            for (Line b : lines) {
                if (b == a || b.key == null || b.key.isUnbound() || APART.contains(b.group)) continue;
                if (SHARED_OK.contains(a.key.getTranslationKey()) && SHARED_OK.contains(b.key.getTranslationKey())) continue;
                if (a.key.equals(b.key)) continue;
                if (a.key.getBoundKeyTranslationKey().equals(b.key.getBoundKeyTranslationKey())) {
                    clash.put(a, "Same key as " + b.label);
                    break;
                }
            }
        }
    }

    private int boxX() {
        return left + w - BOX - 8;
    }

    /** The binding whose key box is under the mouse, if any. */
    private KeyBinding boxAt(double mx, double my) {
        if (my < listTop || my > listTop + viewH || mx < boxX() || mx > boxX() + BOX) return null;
        int y = listTop - scroll;
        for (Line l : lines) {
            int rh = l.key == null ? HEAD : ROW;
            if (l.key != null && my >= y + 2 && my < y + 20) return l.key;
            y += rh;
        }
        return null;
    }

    private void bind(InputUtil.Key key) {
        client.options.setKeyCode(listening, key);
        KeyBinding.updateKeysByCode();
        listening = null;
        findClashes();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (listening != null) {
            bind(InputUtil.Type.MOUSE.createFromCode(button));
            return true;
        }
        KeyBinding k = boxAt(mx, my);
        if (k != null) {
            click();
            if (button == 1) {
                listening = k;
                bind(k.getDefaultKey());
            } else {
                listening = k;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) listening = null;
            else if (keyCode == GLFW.GLFW_KEY_BACKSPACE || keyCode == GLFW.GLFW_KEY_DELETE) bind(InputUtil.UNKNOWN_KEY);
            else bind(InputUtil.fromKeyCode(keyCode, scanCode));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (contentH <= viewH) return false;
        scroll = MathHelper.clamp(scroll - (int) (v * 22), 0, contentH - viewH);
        return true;
    }

    private void click() {
        client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        renderBackground(c, mouseX, mouseY, delta);
        Ui.glass(c, left - 8, 8, w + 16, height - 16);
        Ui.text(c, Ui.title(intro ? "YOUR CONTROLS" : "CONTROLS"), width / 2f, 16, 1.2f, Ui.GOLD, true);
        String sub = listening != null ? "Press the new key or mouse button · Esc to cancel · Backspace to clear"
            : "Click a key to change it · right click resets it";
        Ui.text(c, Text.literal(sub), width / 2f, 31, 0.6f, listening != null ? Ui.GOLD : Ui.MUTED, true);
        if (intro) Ui.text(c, Text.literal("You can find this any time in Options › Controls."), width / 2f, 40, 0.55f, Ui.MUTED, true);

        c.enableScissor(left - 8, listTop - 2, left + w + 8, listTop + viewH + 2);
        int y = listTop - scroll;
        for (Line l : lines) {
            if (l.key == null) {
                Ui.text(c, Ui.heading(l.label.toUpperCase(Locale.ROOT)), left + 6, y + 6, 0.75f, Ui.GOLD, false);
                c.fill(left + 6, y + 15, left + w - 6, y + 16, 0x30E0B96A);
                y += HEAD;
                continue;
            }
            String problem = clash.get(l);
            Ui.text(c, Text.literal(l.label), left + 10, y + (problem == null ? 7 : 3), 0.75f, Ui.CREAM, false);
            if (problem != null) Ui.text(c, Text.literal(problem), left + 10, y + 13, 0.55f, 0xFFE8A040, false);
            int bx = boxX(), by = y + 2;
            boolean hov = listening == null && mouseX >= bx && mouseX < bx + BOX && mouseY >= by && mouseY < by + 18 && mouseY >= listTop && mouseY <= listTop + viewH;
            boolean wait = listening == l.key;
            c.fill(bx, by, bx + BOX, by + 18, wait ? 0xD8221E14 : hov ? 0xD01A1E1E : 0xB80D1012);
            c.drawBorder(bx, by, BOX, 18, wait ? 0xC0E0B96A : problem != null ? 0x90E8A040 : hov ? 0x60E0B96A : 0x26FFFFFF);
            String label = wait ? "> ? <" : l.key.isUnbound() ? "—" : l.key.getBoundKeyLocalizedText().getString();
            int col = wait ? Ui.GOLD : l.key.isUnbound() ? Ui.DIM : problem != null ? 0xFFE8A040 : Ui.CREAM;
            Ui.text(c, Text.literal(label), bx + BOX / 2f, by + 5, 0.75f, col, true);
            y += ROW;
        }
        c.disableScissor();
        if (contentH > viewH) {
            int bar = Math.max(20, viewH * viewH / contentH), sy = listTop + (viewH - bar) * scroll / Math.max(1, contentH - viewH);
            c.fill(left + w + 2, sy, left + w + 4, sy + bar, 0x80E0B96A);
        }
        for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(c, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return listening == null;
    }

    @Override
    public void close() {
        client.options.write();
        client.setScreen(parent);
    }
}
