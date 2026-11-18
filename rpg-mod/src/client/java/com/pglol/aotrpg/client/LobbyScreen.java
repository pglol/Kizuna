package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import com.pglol.aotrpg.client.story.CutscenePlayer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

/**
 * The Extraction lobby, over your character on the balloon's bench (the camera frames you while
 * it's open): the island picked and squad fill across the top, your loadout on the left, the squad
 * and who's ready on the right, and Ready, which becomes the countdown once everyone is.
 * You stay seated while it's up; Walk Around gets you on your feet to use the workbench and forge.
 */
public final class LobbyScreen extends Screen {
    private static Net.LobbyView view;
    private static long viewAt, suppressUntil;
    private static int lastSeat = -1;
    private static boolean socialTab;
    private final long opened = Util.getMeasuringTimeMs();
    private boolean bagAsked;

    public LobbyScreen() {
        super(Text.literal("Lobby"));
    }

    public static void reset() {
        view = null;
        lastSeat = -1;
        suppressUntil = 0;
    }

    public static void on(Net.LobbyView v) {
        view = v;
        viewAt = Util.getMeasuringTimeMs();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen instanceof LobbyScreen s) s.clearAndInit();
    }

    private static boolean aboard(MinecraftClient mc) {
        return mc.player != null && mc.world != null && mc.player.getX() < -399_744
            && BalloonStations.skyWorld(mc.world);
    }

    private static boolean walkHidden;

    /**
     * Start of every client tick aboard: fighting and gear keys swallowed (attack, use, abilities,
     * ODM), the ready key handled, the HUD tucked away while walking about the deck.
     */
    public static void guardKeys(MinecraftClient mc) {
        boolean on = aboard(mc) && view != null;
        boolean walking = on && mc.currentScreen == null;
        if (walking != walkHidden) {
            walkHidden = walking;
            if (walking) HudHide.hide("lobby_walk");
            else HudHide.show("lobby_walk");
        }
        if (!on) return;
        var keep = java.util.Set.of(AotRpgClient.socialKey(), AotRpgClient.readyKey(), AotRpgClient.mapKey(), mc.options.chatKey, mc.options.commandKey,
            mc.options.inventoryKey, mc.options.playerListKey, mc.options.screenshotKey, mc.options.togglePerspectiveKey, mc.options.fullscreenKey);
        for (var k : mc.options.allKeys) {
            String cat = k.getCategory();
            boolean vanillaMove = cat.equals("key.categories.movement") || cat.equals("key.categories.multiplayer") || cat.equals("key.categories.misc")
                || cat.equals("key.categories.ui");
            if (keep.contains(k) || vanillaMove || k == mc.options.inventoryKey) continue;
            // Right-click still works on blocks and people (the workbench, the forge, the benches),
            // just not on thin air (where an item would be used: a bite, a flare, a spear).
            if (k == mc.options.useKey && mc.crosshairTarget != null && mc.crosshairTarget.getType() != net.minecraft.util.hit.HitResult.Type.MISS) continue;
            // Attack, use, pick, swap hands, hotbar keys, and every mod's own (abilities, ODM gear).
            while (k.wasPressed()) { }
            k.setPressed(false);
        }
        if (mc.currentScreen == null) while (AotRpgClient.readyKey().wasPressed()) ClientPlayNetworking.send(new Net.ExtractionAction("ready", ""));
    }

    /** Seated in the balloon with nothing else open: the lobby comes back up. */
    public static void tick(MinecraftClient mc) {
        if (!aboard(mc)) {
            view = null;
            lastSeat = -1;
            return;
        }
        // Just sat down: face the way the bench faces (into the basket).
        var seat = mc.player.getVehicle();
        if (seat == null) lastSeat = -1;
        else if (seat.getId() != lastSeat) {
            lastSeat = seat.getId();
            float yaw = seat.getYaw();
            mc.player.setYaw(yaw);
            mc.player.setHeadYaw(yaw);
            mc.player.setBodyYaw(yaw);
            mc.player.prevYaw = yaw;
            mc.player.setPitch(0);
        }
        if (view == null || mc.currentScreen != null || !mc.player.hasVehicle() || Autopilot.locked()) return;
        if (Util.getMeasuringTimeMs() < suppressUntil) return;
        mc.setScreen(new LobbyScreen());
    }

    /**
     * On your feet aboard the balloon: a clean overlay (the rest of the HUD steps aside): the
     * island, the squad and who's ready, the countdown, and the keys that matter here.
     */
    public static void hud(DrawContext c, net.minecraft.client.render.RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (view == null || !aboard(mc) || mc.currentScreen != null || CutscenePlayer.active()) return;
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        Net.LobbyIsland is = null;
        for (Net.LobbyIsland i : view.islands()) if (i.id().equals(view.island())) is = i;
        int col = is == null ? Sleek.GOLD : 0xFF000000 | is.color();
        // Top centre: where you're bound.
        c.fillGradient(0, 0, w, 46, 0x90000000, 0x00000000);
        Ui.text(c, Text.literal("EXTRACTION"), w / 2f, 6, 0.55f, 0xFFE8433A, true);
        if (is != null) {
            Ui.text(c, Ui.title(is.name().toUpperCase(Locale.ROOT)), w / 2f, 14, 1.2f, col, true);
            Ui.text(c, Text.literal("Lv " + is.min() + "-" + is.max() + "  \u00B7  " + is.condition()), w / 2f, 30, 0.58f, 0xFFC8BEA6, true);
        }
        // Right: the squad and who's ready.
        int x = w - 150, y = 50;
        c.fill(x - 8, y - 6, w - 10, y + 16 + view.squad().size() * 13 + 18, 0x90000000);
        c.fill(x - 8, y - 6, w - 10, y - 5, col);
        Ui.text(c, Ui.heading("Squad"), x, y, 0.75f, Ui.CREAM, false);
        int ready = 0;
        for (Net.LobbyMember m : view.squad()) if (m.ready()) ready++;
        String rc = ready + " / " + view.squad().size() + " ready";
        Ui.text(c, Text.literal(rc), w - 16 - Ui.font().getWidth(rc) * 0.58f, y + 1, 0.58f, ready == view.squad().size() ? 0xFF5BD35B : 0xFF9A958A, false);
        int yy = y + 14;
        for (Net.LobbyMember m : view.squad()) {
            Smooth.disc(c, x + 3, yy + 4, 2.5f, m.ready() ? 0xFF5BD35B : 0xFF55524A);
            Ui.text(c, Text.literal((m.leader() ? "\u2605 " : "") + m.name()), x + 10, yy, 0.68f, m.you() ? Sleek.GOLD : Ui.CREAM, false);
            String lv = "Lv " + m.level();
            Ui.text(c, Text.literal(lv), w - 16 - Ui.font().getWidth(lv) * 0.55f, yy + 1, 0.55f, 0xFF8A857A, false);
            yy += 13;
        }
        long cd = view.countdown() < 0 ? -1 : Math.max(0, view.countdown() - (Util.getMeasuringTimeMs() - viewAt));
        if (cd >= 0) {
            // The countdown, big and centred, a ring running down.
            float cx = w / 2f, cy = h / 2f - 40, k = cd / (float) COUNTDOWN_MS;
            Smooth.ring(c, cx, cy, 22, 2, 0x40FFFFFF);
            Smooth.arc(c, cx, cy, 20.5f, 23.5f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * k, 0xFFE8433A);
            Ui.text(c, Ui.title(String.valueOf((int) Math.ceil(cd / 1000.0))), cx, cy - 9, 1.6f, Ui.CREAM, true);
            Ui.text(c, Ui.heading("DEPLOYING"), cx, cy + 28, 0.75f, 0xFFE8433A, true);
        }
        // Bottom: the keys.
        boolean me = false;
        for (Net.LobbyMember m : view.squad()) if (m.you()) me = m.ready();
        String[][] keys = {{AotRpgClient.readyKey().getBoundKeyLocalizedText().getString(), me ? "Stand down" : "Ready up"},
            {"Sit", "Open the lobby"}, {AotRpgClient.socialKey().getBoundKeyLocalizedText().getString(), "Menu"}};
        float kx = w / 2f - 150;
        for (String[] k : keys) {
            float kw = Ui.font().getWidth(k[0]) * 0.65f + 10;
            c.fill((int) kx, h - 26, (int) (kx + kw), h - 13, 0x90000000);
            c.drawBorder((int) kx, h - 26, (int) kw, 13, Sleek.LINE2);
            Ui.text(c, Text.literal(k[0]), kx + kw / 2f, h - 23, 0.65f, Sleek.GOLD, true);
            Ui.text(c, Text.literal(k[1]), kx + kw + 5, h - 23, 0.65f, Ui.CREAM, false);
            kx += kw + Ui.font().getWidth(k[1]) * 0.65f + 22;
        }
    }

    private static final long COUNTDOWN_MS = 5000;

    /** The lobby camera: in front of you on the bench, drifting a little. */
    public static CutscenePlayer.Cam camera(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.currentScreen instanceof LobbyScreen) || mc.player == null || !mc.player.hasVehicle()) return null;
        float t = (Util.getMeasuringTimeMs() % 1_000_000L) / 1000f;
        Vec3d at = mc.player.getLerpedPos(tickDelta).add(0, 0.95, 0);
        var seat = mc.player.getVehicle();
        double yaw = Math.toRadians(seat != null ? seat.getYaw() : mc.player.getBodyYaw());
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), side = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d cam = at.add(fwd.multiply(3.3)).add(side.multiply(0.35 * Math.sin(t * 0.25))).add(0, 0.25 + 0.08 * Math.sin(t * 0.4), 0);
        Vec3d look = at.add(side.multiply(0.05 * Math.sin(t * 0.3)));
        Vec3d d = look.subtract(cam);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        float cy = (float) (MathHelper.atan2(d.z, d.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float cp = (float) -(MathHelper.atan2(d.y, h) * MathHelper.DEGREES_PER_RADIAN);
        return new CutscenePlayer.Cam(cam.x, cam.y, cam.z, cy, cp);
    }

    private void act(String a, String arg) {
        ClientPlayNetworking.send(new Net.ExtractionAction(a, arg));
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if ((button == 0 || button == 1) && (button == 0 ? hits : rightHits).click(mx, my)) return true;
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        stashScroll = Math.max(0, stashScroll - (int) Math.signum(v));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (AotRpgClient.readyKey().matchesKey(key, scan)) {
            act("ready", "");
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    /** Escape opens the game menu (the lobby returns after). */
    @Override
    public void close() {
        if (client != null) client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
    }

    @Override
    public void removed() {
        HudHide.show("lobby");
        ClientPlayNetworking.send(new Net.StashAction("close", 0));
    }

    private Net.LobbyIsland island() {
        if (view == null) return null;
        for (Net.LobbyIsland i : view.islands()) if (i.id().equals(view.island())) return i;
        return view.islands().isEmpty() ? null : view.islands().get(0);
    }

    private boolean meReady() {
        if (view == null) return false;
        for (Net.LobbyMember m : view.squad()) if (m.you()) return m.ready();
        return false;
    }

    private long countdown() {
        if (view == null || view.countdown() < 0) return -1;
        return Math.max(0, view.countdown() - (Util.getMeasuringTimeMs() - viewAt));
    }

    /** The stash's contents (sent while the lobby is open). */
    public static Net.StashView stash;
    private final Sleek.Hits hits = new Sleek.Hits(), rightHits = new Sleek.Hits();
    private int stashScroll;

    @Override
    protected void init() {
        HudHide.hide("lobby");
        if (!bagAsked) {
            bagAsked = true;
            act("bag", "");
            act("stash", "");
        }
    }

    private void cycle(int d) {
        var list = view.islands();
        int i = 0;
        for (int k = 0; k < list.size(); k++) if (list.get(k).id().equals(view.island())) i = k;
        act("island", list.get(Math.floorMod(i + d, list.size())).id());
    }

    @Override
    public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
        c.fillGradient(0, 0, width, height, 0xB0050706, 0xD0030404);
    }

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] ARMOR_NAMES = {"Head", "Chest", "Legs \u00B7 ODM", "Feet"};

    @Override
    public void render(DrawContext c, int mouseX, int mouseY, float delta) {
        Sleek.mx = mouseX;
        Sleek.my = mouseY;
        hits.clear();
        rightHits.clear();
        renderBackground(c, mouseX, mouseY, delta);
        if (view == null || client == null || client.player == null) return;
        long t = Util.getMeasuringTimeMs();
        Net.LobbyIsland is = island();
        int col = is == null ? Sleek.GOLD : 0xFF000000 | is.color();
        ItemStack[] tip = {ItemStack.EMPTY};

        // ---- Top: mode, island (the leader turns it), conditions, squad fill; salvage and stash on the right.
        Ui.text(c, Text.literal("EXTRACTION"), 16, 12, 0.7f, 0xFFE8433A, false);
        if (is != null) {
            Ui.text(c, Ui.title(is.name().toUpperCase(Locale.ROOT)), width / 2f, 10, 1.5f, col, true);
            String sub = "Lv " + is.min() + "-" + is.max() + "   \u00B7   " + is.condition() + (is.bonus() ? "  \u00B7  BONUS LOOT" : "");
            Ui.text(c, Text.literal(sub), width / 2f, 28, 0.62f, (is.conditionColor() & 0xFFFFFF) | 0xFF000000, true);
            Ui.text(c, Text.literal(is.next() + " in " + is.minutesLeft() + "m"), width / 2f, 37, 0.55f, 0xFF8A857A, true);
            if (view.leader() && view.islands().size() > 1 && view.countdown() < 0) {
                Sleek.button(c, hits, width / 2 - 140, 12, 18, 18, "\u2039", false, true, () -> cycle(-1));
                Sleek.button(c, hits, width / 2 + 122, 12, 18, 18, "\u203A", false, true, () -> cycle(1));
            }
        }
        boolean canFill = view.leader() && view.countdown() < 0;
        Sleek.button(c, hits, width / 2 - 50, 46, 100, 14, view.fill() ? "Squad fill: on" : "Squad fill: off", view.fill(), canFill, () -> act("fill", ""));
        String sv = String.format(Locale.ROOT, "%,d Salvage", view.salvage());
        Ui.text(c, Text.literal(sv), width - 16 - Ui.font().getWidth(sv) * 0.8f, 12, 0.8f, 0xFF9AD0FF, false);
        c.fill(12, 64, width - 12, 65, Sleek.LINE);

        int top = 72, bottom = height - 14;
        // ---- Left: equipment (you, what you wear, your loadout bar) and your numbers.
        int lw = Math.min(210, width / 4 + 30), lx = 12;
        Sleek.panel(c, lx, top, lw, bottom - top);
        Ui.text(c, Ui.heading("Equipment"), lx + 8, top + 7, 0.8f, Ui.CREAM, false);
        int mh = 108;
        c.enableScissor(lx + 50, top + 18, lx + lw - 50, top + 18 + mh);
        Showcase.model(c, lx + lw / 2f, top + 16 + mh, mh * 0.42f, (t % 16000) / 16000f * 360, null, null);
        c.disableScissor();
        var p = client.player;
        for (int k = 0; k < 4; k++) {
            int sx = k % 2 == 0 ? lx + 8 : lx + lw - 8 - 38, sy = top + 22 + (k / 2) * 50 + (k == 1 || k == 3 ? 0 : 0);
            if (k >= 2) sy = top + 22 + 50;
            if (k == 1) sy = top + 22;
            if (k == 0) sy = top + 22;
            sx = k == 0 || k == 2 ? lx + 8 : lx + lw - 46;
            ItemStack st = p.getEquippedStack(ARMOR[k]);
            int target = 103 - k;
            if (cell(c, sx, sy, 38, st, ARMOR_NAMES[k], tip)) {
                if (!st.isEmpty()) hits.add(sx, sy, 38, 38, () -> ClientPlayNetworking.send(new Net.StashAction("unequip", target)));
            }
        }
        // The loadout bar: what each of the nine places is for.
        int by = top + mh + 26;
        Ui.text(c, Text.literal("Loadout"), lx + 8, by, 0.62f, 0xFF9A958A, false);
        by += 9;
        int cw = (lw - 16 - 8 * 2) / 9;
        for (int k = 0; k < 9; k++) {
            int sx = lx + 8 + k * (cw + 2);
            ItemStack st = p.getInventory().getStack(k);
            int slot = k;
            cell(c, sx, by, cw, st, "", tip);
            if (!st.isEmpty()) hits.add(sx, by, cw, cw, () -> ClientPlayNetworking.send(new Net.StashAction("unequip", slot)));
            if (Sleek.over(sx, by, cw, cw) && st.isEmpty()) {
                var kind = com.pglol.aotrpg.Loadout.SLOTS[k];
                c.drawTooltip(Ui.font(), java.util.List.of(Text.literal(kind.title).withColor(Sleek.GOLD), Text.literal(kind.hint).withColor(0xFF9A958A)), mouseX, mouseY);
            }
        }
        for (int k = 0; k < 9; k++) {
            String n = com.pglol.aotrpg.Loadout.SLOTS[k].title;
            String sh = n.length() > 5 ? n.substring(0, 5) : n;
            Ui.text(c, Text.literal(sh), lx + 8 + k * (cw + 2) + cw / 2f, by + cw + 2, 0.42f, 0xFF7A766C, true);
        }
        // Your numbers.
        int ny = by + cw + 14;
        String[][] stats = {
            {"Health", String.format(Locale.ROOT, "%.0f", p.getMaxHealth())},
            {"Armor", String.valueOf(p.getArmor())},
            {"Damage", String.format(Locale.ROOT, "%.1f", p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE))},
            {"Speed", String.format(Locale.ROOT, "%.0f%%", p.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.GENERIC_MOVEMENT_SPEED) / 0.1 * 100)}};
        int sw = (lw - 16) / 4;
        for (int k = 0; k < 4; k++) {
            int sx = lx + 8 + k * sw;
            Ui.text(c, Text.literal(stats[k][1]), sx + sw / 2f, ny, 0.85f, Ui.CREAM, true);
            Ui.text(c, Text.literal(stats[k][0].toUpperCase(Locale.ROOT)), sx + sw / 2f, ny + 10, 0.45f, 0xFF8A857A, true);
        }
        Sleek.button(c, hits, lx + 8, bottom - 26, (lw - 20) / 2, 18, "Walk around", false, true, () -> {
            suppressUntil = Util.getMeasuringTimeMs() + 1500;
            act("walk", "");
            client.setScreen(null);
        });
        Sleek.button(c, hits, lx + 12 + (lw - 20) / 2, bottom - 26, (lw - 20) / 2, 18, "Leave", false, true, () -> act("leave", ""));

        // ---- Right: the squad, invites, ready and the countdown.
        int rw = Math.min(190, width / 5 + 30), rx = width - 12 - rw;
        Sleek.panel(c, rx, top, rw, bottom - top);
        Sleek.tabs(c, hits, rx + 4, top + 4, new String[] {"Squad", "Invite"}, socialTab ? 1 : 0, k -> socialTab = k == 1);
        int ry = top + 26;
        if (!socialTab) {
            for (int i = 0; i < 3; i++) {
                Net.LobbyMember m = i < view.squad().size() ? view.squad().get(i) : null;
                c.fill(rx + 6, ry, rx + rw - 6, ry + 26, m != null && m.you() ? 0x24E8C36F : 0x14FFFFFF);
                if (m != null) {
                    c.fill(rx + 6, ry, rx + 8, ry + 26, m.ready() ? 0xFF5BD35B : 0xFF55524A);
                    Ui.text(c, Text.literal((m.leader() ? "\u2605 " : "") + m.name()), rx + 13, ry + 5, 0.78f, Ui.CREAM, false);
                    Ui.text(c, Text.literal("Lv " + m.level()), rx + 13, ry + 16, 0.55f, 0xFF8A857A, false);
                    Sleek.chip(c, rx + rw - 12 - (Ui.font().getWidth(m.ready() ? "READY" : "NOT READY") * 0.55f + 8), ry + 9,
                        m.ready() ? "READY" : "NOT READY", m.ready() ? 0xFF5BD35B : 0xFF7A766C);
                } else if (view.fill() && (meReady() || view.countdown() >= 0)) {
                    Ui.text(c, Text.literal("Searching" + ".".repeat((int) ((t / 400) % 4))), rx + 13, ry + 9, 0.72f, 0xFF9AD0FF, false);
                } else Ui.text(c, Text.literal("Open seat"), rx + 13, ry + 9, 0.72f, 0xFF55524A, false);
                ry += 30;
            }
        } else {
            if (view.social().isEmpty()) Ui.text(c, Text.literal("No friends or teammates yet"), rx + 10, ry + 4, 0.62f, 0xFF7A766C, false);
            for (Net.LobbyFriend f : view.social()) {
                if (ry > bottom - 110) break;
                int dot = switch (f.where()) {
                    case "aboard" -> 0xFFE0B96A;
                    case "online" -> 0xFF5BD35B;
                    case "in a run" -> 0xFFE0823A;
                    default -> 0xFF55524A;
                };
                Smooth.disc(c, rx + 12, ry + 9, 2.5f, dot);
                Ui.text(c, Text.literal(f.name()), rx + 20, ry + 3, 0.7f, Ui.CREAM, false);
                Ui.text(c, Text.literal(f.where()), rx + 20, ry + 12, 0.5f, dot, false);
                if (!f.where().equals("aboard") && !f.where().equals("offline") && !f.where().equals("in a run"))
                    Sleek.button(c, hits, rx + rw - 56, ry + 2, 48, 15, "Invite", true, true, () -> act("invite", f.id().toString()));
                else if (!f.friend()) Sleek.button(c, hits, rx + rw - 30, ry + 2, 22, 15, "+", false, true, () -> act("friend", f.id().toString()));
                ry += 24;
            }
        }
        // Ready, the countdown, and how many are set.
        int ready = 0;
        for (Net.LobbyMember m : view.squad()) if (m.ready()) ready++;
        String rc = ready + " of " + view.squad().size() + " ready";
        Ui.text(c, Text.literal(rc), rx + rw / 2f, bottom - 78, 0.62f, ready == view.squad().size() ? 0xFF5BD35B : 0xFF9A958A, true);
        long cd = countdown();
        if (cd >= 0) {
            float cx = rx + rw / 2f, cy = bottom - 46, k = cd / (float) COUNTDOWN_MS;
            Smooth.ring(c, cx, cy, 17, 2, 0x40FFFFFF);
            Smooth.arc(c, cx, cy, 15.5f, 18.5f, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * k, 0xFFE8433A);
            Ui.text(c, Ui.title(String.valueOf((int) Math.ceil(cd / 1000.0))), cx, cy - 7, 1.3f, Ui.CREAM, true);
            Sleek.button(c, hits, rx + 8, bottom - 22, rw - 16, 14, "Stand down", false, true, () -> act("ready", ""));
        } else {
            boolean me = meReady();
            String key = AotRpgClient.readyKey().getBoundKeyLocalizedText().getString();
            Sleek.button(c, hits, rx + 8, bottom - 64, rw - 16, 32, me ? "READY  \u2714" : "READY UP", !me, true, () -> act("ready", ""));
            Ui.text(c, Text.literal("or press " + key + " anywhere aboard"), rx + rw / 2f, bottom - 26, 0.55f, 0xFF7A766C, true);
        }

        // ---- Middle: the stash (and your satchel under it). Click to move; right-click a stash item to put it in the satchel.
        int mx0 = lx + lw + 8, mx1 = rx - 8, mw = mx1 - mx0;
        Sleek.panel(c, mx0, top, mw, bottom - top);
        int used = stash == null ? view.stashUsed() : stash.items().size(), cap = stash == null ? view.stashCap() : stash.capacity();
        Ui.text(c, Ui.heading("Stash"), mx0 + 8, top + 7, 0.8f, Ui.CREAM, false);
        Ui.text(c, Text.literal(used + " / " + cap), mx0 + 52, top + 9, 0.6f, used >= cap ? 0xFFE8433A : 0xFF9A958A, false);
        Sleek.button(c, hits, mx1 - 46, top + 5, 40, 14, "Sort", false, stash != null, () -> ClientPlayNetworking.send(new Net.StashAction("sort", 0)));
        if (view.rowCost() >= 0) Sleek.button(c, hits, mx1 - 150, top + 5, 100, 14, "+9 slots \u00B7 " + view.rowCost(), false, view.salvage() >= view.rowCost(),
            () -> act("expand", ""));
        int cell = 20, cols = Math.max(1, (mw - 16) / cell);
        int satRows = 2, gridTop = top + 24, gridBottom = bottom - 16 - satRows * cell - 22;
        int rows = Math.max(1, (gridBottom - gridTop) / cell);
        java.util.Map<Integer, ItemStack> items = new java.util.HashMap<>();
        if (stash != null) for (Net.BagEntry e : stash.items()) items.put(e.slot(), e.stack());
        int total = (Math.max(cap, 1) + cols - 1) / cols;
        stashScroll = Math.max(0, Math.min(stashScroll, total - rows));
        for (int r = 0; r < rows; r++) for (int ccol = 0; ccol < cols; ccol++) {
            int idx = (stashScroll + r) * cols + ccol;
            if (idx >= cap) break;
            int sx = mx0 + 8 + ccol * cell, sy = gridTop + r * cell;
            ItemStack st = items.getOrDefault(idx, ItemStack.EMPTY);
            cell(c, sx, sy, cell - 2, st, "", tip);
            if (!st.isEmpty()) {
                hits.add(sx, sy, cell - 2, cell - 2, () -> ClientPlayNetworking.send(new Net.StashAction("equip", idx)));
                rightHits.add(sx, sy, cell - 2, cell - 2, () -> ClientPlayNetworking.send(new Net.StashAction("take", idx)));
            }
        }
        if (total > rows) {
            int gh = rows * cell, bh = Math.max(10, gh * rows / total), bby = gridTop + (gh - bh) * stashScroll / Math.max(1, total - rows);
            c.fill(mx1 - 5, gridTop, mx1 - 3, gridTop + gh, 0x20FFFFFF);
            c.fill(mx1 - 5, bby, mx1 - 3, bby + bh, Sleek.GOLD);
        }
        // The satchel: what you carry besides your loadout.
        int sy0 = bottom - 12 - satRows * cell;
        Ui.text(c, Ui.heading("Satchel"), mx0 + 8, sy0 - 11, 0.7f, Ui.CREAM, false);
        Ui.text(c, Text.literal("click: equip or stash  \u00B7  right-click a stash item: to satchel"), mx0 + 60, sy0 - 10, 0.5f, 0xFF7A766C, false);
        for (int i = 0; i < satRows * cols; i++) {
            int sx = mx0 + 8 + (i % cols) * cell, sy = sy0 + (i / cols) * cell;
            ItemStack st = ClientState.bag.getOrDefault(i, ItemStack.EMPTY);
            cell(c, sx, sy, cell - 2, st, "", tip);
            int slot = i;
            if (!st.isEmpty()) {
                hits.add(sx, sy, cell - 2, cell - 2, () -> ClientPlayNetworking.send(new Net.StashAction("put", slot)));
                rightHits.add(sx, sy, cell - 2, cell - 2, () -> ClientPlayNetworking.send(new Net.BagAction("equip", slot, -1)));
            }
        }
        if (!tip[0].isEmpty()) c.drawItemTooltip(Ui.font(), tip[0], mouseX, mouseY);
    }

    /** One slot: a dark square, the item, its count; returns whether it's hovered (and offers its tooltip). */
    private boolean cell(DrawContext c, int x, int y, int size, ItemStack st, String label, ItemStack[] tip) {
        boolean hov = Sleek.over(x, y, size, size);
        int tone = st.isEmpty() ? 0 : Ui.rarityTone(BagScreen.quality(st));
        c.fill(x, y, x + size, y + size, hov ? 0x30FFFFFF : 0x18FFFFFF);
        if (!st.isEmpty() && tone != 0) c.fill(x, y + size - 2, x + size, y + size, tone);
        c.drawBorder(x, y, size, size, hov ? Sleek.LINE2 : Sleek.LINE);
        if (!st.isEmpty()) {
            float sc = size >= 30 ? 1.5f : 1f;
            Ui.item(c, st, x + (int) (size / 2f - 8 * sc), y + (int) (size / 2f - 8 * sc), sc);
            if (st.getCount() > 1) {
                String n = String.valueOf(st.getCount());
                Ui.text(c, Text.literal(n), x + size - 2 - Ui.font().getWidth(n) * 0.6f, y + size - 8, 0.6f, Ui.CREAM, false);
            }
            if (hov) tip[0] = st;
        }
        if (!label.isEmpty()) Ui.text(c, Text.literal(label), x + size / 2f, y + size + 2, 0.5f, st.isEmpty() ? 0xFF55524A : 0xFF9A958A, true);
        return hov;
    }

    private static ItemStack icon(String id) {
        var item = Registries.ITEM.get(Identifier.of(id));
        return new ItemStack(item == Items.AIR ? Items.IRON_NUGGET : item);
    }
}
