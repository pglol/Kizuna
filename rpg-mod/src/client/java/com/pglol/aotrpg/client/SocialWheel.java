package com.pglol.aotrpg.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Hold the social key (Left Alt), point at an option, let go. Clicking also works.
 * Empty slots are reserved for future actions.
 */
public final class SocialWheel extends Screen {
    private record Option(String name, String hint, ItemStack icon, boolean ready, Runnable run) { }

    private final Option[] options;
    private int hovered = -1;
    private final long opened = System.currentTimeMillis();

    public SocialWheel() {
        super(Text.literal("Social"));
        MinecraftClient mc = MinecraftClient.getInstance();
        options = new Option[] {
            new Option("Party", "Invite, leave, see your squad", new ItemStack(Items.WHITE_BANNER), true,
                () -> mc.setScreen(new PartyScreen())),
            new Option("People", "Friends and everyone online", new ItemStack(Items.PLAYER_HEAD), true, () -> {
                mc.setScreen(new SocialScreen());
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.SocialAction("open", null));
            }),
            new Option("Trade", "Swap items and Marks with a player", new ItemStack(Items.EMERALD), true, () -> {
                mc.setScreen(new TradeScreen());
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.TradeAction("open", null, ""));
            }),
            new Option("Market", "Buy and sell (by courier away from town)", new ItemStack(Items.GOLD_NUGGET), true, () -> {
                mc.setScreen(null);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.MarketAction("open", "", 0, 0));
            }),
            new Option("Store", "Cosmetics, titles, crates", new ItemStack(Items.DIAMOND), true,
                () -> mc.setScreen(new StoreScreen(null))),
            new Option("Cosmetics", "Your looks and effects", new ItemStack(Items.AMETHYST_SHARD), true,
                () -> mc.setScreen(new CosmeticsScreen())),
            new Option("Battle Pass", "Season rewards", new ItemStack(Items.NETHER_STAR), true, () -> {
                mc.setScreen(null);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.PassAction("open", 0));
            }),
            new Option("Factions", "Sectors and work orders", new ItemStack(Items.SHIELD), true, () -> {
                mc.setScreen(null);
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new com.pglol.aotrpg.Net.FactionAction("open", ""));
            }),
        };
    }

    private static boolean rpOn() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        var r = ClientState.roster.get(mc.player.getUuid());
        return r != null && r.rp();
    }

    private static void soon(MinecraftClient mc, String what) {
        mc.setScreen(null);
        if (mc.player != null) mc.player.sendMessage(Text.literal(what + " are coming soon.").formatted(Formatting.GRAY), true);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        // Released the key: pick what the cursor points at.
        InputUtil.Key key = InputUtil.fromTranslationKey(AotRpgClient.socialKey().getBoundKeyTranslationKey());
        if (key.getCategory() == InputUtil.Type.KEYSYM
            && !InputUtil.isKeyPressed(client.getWindow().getHandle(), key.getCode())
            && System.currentTimeMillis() - opened > 120) {
            choose();
        }
    }

    private void choose() {
        if (hovered >= 0 && (options[hovered].ready() || !options[hovered].name().isEmpty())) options[hovered].run().run();
        else close();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hovered >= 0) {
            choose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        float cx = width / 2f, cy = height / 2f;
        c.fillGradient(0, 0, width, height, 0x50000000, 0x80000000);
        Smooth.glow(c, cx, cy, 190, 0x70000000);
    }

    private float open() {
        float k = Math.min(1, (System.currentTimeMillis() - opened) / 160f);
        return 1 - (1 - k) * (1 - k);
    }

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        super.render(c, mouseX, mouseY, delta);
        float cx = width / 2f, cy = height / 2f, in = open();
        int n = options.length;
        float r0 = 54 * (0.85f + 0.15f * in), r1 = 112 * (0.85f + 0.15f * in), mid = (r0 + r1) / 2;
        double dx = mouseX - cx, dy = mouseY - cy;
        hovered = -1;
        if (dx * dx + dy * dy > 30 * 30) {
            double ang = Math.atan2(dy, dx) + Math.PI / 2; // 0 = straight up
            if (ang < 0) ang += Math.PI * 2;
            hovered = (int) Math.round(ang / (Math.PI * 2 / n)) % n;
        }
        double step = Math.PI * 2 / n, gap = 0.035;
        int alpha = (int) (255 * in);
        // The ring: one wedge per option, the one you point at lifted out and lit.
        Smooth.ring(c, cx, cy, r1 + 3, 1, (alpha / 3 << 24) | 0xE0B96A);
        for (int i = 0; i < n; i++) {
            double a = i * step - Math.PI / 2;
            boolean h = i == hovered;
            Option o = options[i];
            int fill = h ? 0xE6322A1A : 0xD80C100E;
            float lift = h ? 5 : 0;
            Smooth.arc(c, cx, cy, r0 + lift, r1 + lift, a - step / 2 + gap, a + step / 2 - gap, (Math.min(alpha, fill >>> 24) << 24) | (fill & 0xFFFFFF));
            if (h) Smooth.arc(c, cx, cy, r1 + lift - 2.5f, r1 + lift, a - step / 2 + gap, a + step / 2 - gap, (alpha << 24) | 0xE0B96A);
            float ix = cx + (float) Math.cos(a) * (mid + lift), iy = cy + (float) Math.sin(a) * (mid + lift);
            if (!o.icon().isEmpty()) {
                var ms = c.getMatrices();
                ms.push();
                float sc = h ? 1.35f : 1.1f;
                ms.translate(ix, iy - 5, 0);
                ms.scale(sc, sc, 1);
                c.drawItem(o.icon(), -8, -8);
                ms.pop();
                if (!o.ready()) Smooth.disc(c, ix, iy - 5, 11, 0x900C100E);
            }
            int lc = !o.ready() ? Ui.DIM : h ? Ui.GOLD : Ui.CREAM;
            Ui.text(c, Text.literal(o.name()), ix, iy + 9, 0.62f, (Math.min(alpha, lc >>> 24) << 24) | (lc & 0xFFFFFF), true);
        }
        // The hub: what you're pointing at, and what it does.
        Smooth.disc(c, cx, cy, r0 - 6, (Math.min(alpha, 0xE8) << 24) | 0x0A0D0C);
        Smooth.ring(c, cx, cy, r0 - 6, 1.2f, (Math.min(alpha, 0x90) << 24) | 0xB8955A);
        if (hovered >= 0) {
            Option o = options[hovered];
            Ui.text(c, Ui.heading(o.name()), cx, cy - 9, 1f, o.ready() ? Ui.GOLD : Ui.MUTED, true);
            var lines = Ui.font().wrapLines(Text.literal(o.hint()), (int) ((r0 - 14) * 2 / 0.6f));
            float yy = cy + 4;
            for (int k = 0; k < Math.min(2, lines.size()); k++) {
                var ln = lines.get(k);
                float w = Ui.font().getWidth(ln) * 0.6f;
                var ms = c.getMatrices();
                ms.push();
                ms.translate(cx - w / 2, yy, 0);
                ms.scale(0.6f, 0.6f, 1);
                c.drawText(Ui.font(), ln, 0, 0, Ui.MUTED, false);
                ms.pop();
                yy += 7;
            }
        } else {
            Ui.crest(c, (int) cx - 14, (int) cy - 14, 28, 0.85f * in);
        }
        Ui.text(c, Text.literal("Point and release"), cx, cy + r1 + 18, 0.65f, (Math.min(alpha, 0xA0) << 24) | 0xB8B0A0, true);
    }
}
