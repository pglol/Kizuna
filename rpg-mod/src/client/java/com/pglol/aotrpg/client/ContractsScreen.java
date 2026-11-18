package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The mercenary board: wanted criminals, titan contracts and bounties on players, each with its
 * pay and time left; take up to three; and post a bounty of your own.
 */
public final class ContractsScreen extends Screen {
    private static Net.ContractView view;
    private final Sleek.Hits hits = new Sleek.Hits();
    private int tab;
    private double scroll;
    private TextFieldWidget who, marks;

    public ContractsScreen() {
        super(Text.literal("Contracts"));
    }

    public static void on(Net.ContractView v) {
        view = v;
        var mc = MinecraftClient.getInstance();
        if (v.open() && !(mc.currentScreen instanceof ContractsScreen)) mc.setScreen(new ContractsScreen());
    }

    public static void open() {
        MinecraftClient.getInstance().setScreen(new ContractsScreen());
        ClientPlayNetworking.send(new Net.ContractAction("open", ""));
    }

    private void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.ContractAction(a, arg));
    }

    @Override
    protected void init() {
        int w = Math.min(520, width - 40), x = (width - w) / 2;
        who = new TextFieldWidget(textRenderer, x + 12, height - 44, 150, 16, Text.literal("Player"));
        who.setPlaceholder(Text.literal("Player name"));
        who.setMaxLength(32);
        marks = new TextFieldWidget(textRenderer, x + 170, height - 44, 90, 16, Text.literal("Marks"));
        marks.setPlaceholder(Text.literal("Marks (1,000+)"));
        marks.setMaxLength(7);
        marks.setTextPredicate(s -> s.chars().allMatch(Character::isDigit));
        addDrawableChild(who);
        addDrawableChild(marks);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hits.click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - v * 24);
        return true;
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xC0060807, 0xE0030404);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        int w = Math.min(520, width - 40), x = (width - w) / 2, top = 18;
        Ui.text(c, Ui.title("MERCENARY BOARD"), width / 2f, top, 1.4f, Sleek.GOLD, true);
        int held = view == null ? 0 : view.taken();
        Ui.text(c, Text.literal(held + " / 3 contracts taken · the board turns over every 20 minutes"), width / 2f, top + 18, 0.6f, Ui.MUTED, true);
        String[] tabs = {"All", "Criminals", "Titans", "Players", "Taken"};
        Sleek.tabs(c, hits, x, top + 30, tabs, tab, k -> {
            tab = k;
            scroll = 0;
        });
        int y0 = top + 52, y1 = height - 56;
        Sleek.panel(c, x, y0, w, y1 - y0);
        List<String[]> rows = new ArrayList<>();
        if (view != null) for (String r : view.rows()) {
            String[] f = r.split("\\|", -1);
            if (f.length < 9) continue;
            boolean ok = switch (tab) {
                case 1 -> f[1].equals("CRIMINAL");
                case 2 -> f[1].equals("TITAN");
                case 3 -> f[1].equals("PLAYER");
                case 4 -> f[6].equals("1");
                default -> true;
            };
            if (ok) rows.add(f);
        }
        c.enableScissor(x + 1, y0 + 1, x + w - 1, y1 - 1);
        int y = y0 + 8 - (int) scroll, rh = 44;
        for (String[] f : rows) {
            boolean mine = f[6].equals("1");
            int accent = switch (f[1]) {
                case "TITAN" -> 0xFFB06AE0;
                case "PLAYER" -> 0xFFE8433A;
                default -> Sleek.GOLD;
            };
            if (y + rh > y0 && y < y1) {
                boolean hov = Sleek.over(x + 8, y, w - 16, rh - 4);
                Sleek.tile(c, x + 8, y, w - 16, rh - 4, accent, hov, mine, false);
                String kind = switch (f[1]) {
                    case "TITAN" -> "TITAN";
                    case "PLAYER" -> "BOUNTY";
                    default -> "CRIMINAL";
                };
                int cw = Sleek.chip(c, x + 16, y + 7, kind, accent);
                Sleek.chip(c, x + 20 + cw, y + 7, "Lv " + f[5], 0xFF9A958A);
                Ui.text(c, Ui.heading(f[2]), x + 16, y + 19, 0.85f, Ui.CREAM, false);
                Ui.text(c, Text.literal(f[3]), x + 16, y + 29, 0.55f, Ui.MUTED, false);
                Sleek.price(c, x + w - 170, y + 8, Long.parseLong(f[4]), 0, 0.8f);
                Ui.text(c, Text.literal(f[7] + " min left"), x + w - 170, y + 20, 0.55f, 0xFF7A766C, false);
                String id = f[0];
                if (mine) Sleek.button(c, hits, x + w - 88, y + 10, 70, 18, "Drop", false, true, () -> act("drop", id));
                else Sleek.button(c, hits, x + w - 88, y + 10, 70, 18, "Take", true, held < 3, () -> act("take", id));
            }
            y += rh;
        }
        if (rows.isEmpty()) Ui.text(c, Text.literal(tab == 4 ? "No contracts taken yet" : "Nothing on the board here right now"), width / 2f, y0 + 30, 0.7f, Ui.MUTED, true);
        c.disableScissor();
        // Post a bounty of your own.
        Ui.text(c, Text.literal("POST A BOUNTY · the Marks are held until someone collects"), x + 12, height - 54, 0.55f, 0xFF9A958A, false);
        who.render(c, mouseX, mouseY, delta);
        marks.render(c, mouseX, mouseY, delta);
        Sleek.button(c, hits, x + 268, height - 45, 90, 18, "Post", true, !who.getText().isBlank() && !marks.getText().isBlank(), () -> {
            act("post", who.getText().trim() + "|" + marks.getText().trim());
            marks.setText("");
        });
    }
}
