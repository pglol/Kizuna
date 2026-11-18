package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Combat feedback on the client: floating damage numbers over what you hit, the XP bar under the
 * hotbar (with "+XP" popups), the guard key, and block / clash / broken-guard effects.
 */
public final class CombatUi {
    private CombatUi() {}

    public static KeyBinding guardKey;
    private static boolean guardSent;

    // ------------------------------------------------------------------ guard key

    private static boolean wasSwinging, wasAttackDown;
    private static long lastSlashAt;

    /** A blade swing that meets something in reach is reported for the slash effect. */
    private static void tickSwing(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            wasSwinging = false;
            return;
        }
        boolean sw = mc.player.handSwinging;
        boolean down = mc.options.attackKey.isPressed() && mc.currentScreen == null;
        // A left click or the start of a swing, whichever comes first (some blades animate their own way).
        boolean struck = (down && !wasAttackDown) || (sw && !wasSwinging);
        wasAttackDown = down;
        var held = mc.player.getMainHandStack();
        long nowMs = Util.getMeasuringTimeMs();
        // No slash while the blade is raised to block (holding right click): you aren't swinging.
        boolean blocking = guarding() || mc.options.useKey.isPressed() || mc.player.isUsingItem();
        if (struck && !blocking && nowMs - lastSlashAt > 150 && com.pglol.aotrpg.Guard.melee(held) && !com.pglol.aotrpg.AotItems.isApgGun(held)) {
            Entity target = mc.targetedEntity;
            if (target == null) {
                Vec3d eye = mc.player.getEyePos(), look = mc.player.getRotationVec(1f);
                double reach = 6;
                Vec3d end = eye.add(look.multiply(reach));
                var hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, end,
                    mc.player.getBoundingBox().stretch(look.multiply(reach)).expand(1.5),
                    e -> e.isAlive() && e.canHit() && e != mc.player, reach * reach);
                if (hit != null) target = hit.getEntity();
            }
            if (target == null) {
                // A swing at air still counts for clashes (the server decides).
                lastSlashAt = nowMs;
                ClientPlayNetworking.send(new Net.SlashHit(-1));
            } else {
                lastSlashAt = nowMs;
                // Draw my own slash at once; the server shows it to everyone else.
                var box = target.getBoundingBox();
                double y = Math.max(target.getY() + 0.3, Math.min(mc.player.getEyeY(), box.maxY - 0.2) - 0.2);
                String style = ClientState.worn.getOrDefault("slash", "slash_steel");
                CosmeticFx.slash(new Net.SlashFx(style, box.getCenter().x, y, box.getCenter().z, mc.player.getYaw()));
                ClientPlayNetworking.send(new Net.SlashHit(target.getId()));
            }
        }
        wasSwinging = sw;
    }

    public static void tick(MinecraftClient mc) {
        tickSwing(mc);
        // Right click held with a blade is the guard (Danny's blades play their own block animation for it).
        // A guard key bound to the same button would steal right click from the blade, so it is unbound.
        if (guardKey != null && !guardKey.isUnbound() && guardKey.equals(mc.options.useKey)) {
            guardKey.setBoundKey(net.minecraft.client.util.InputUtil.UNKNOWN_KEY);
            net.minecraft.client.option.KeyBinding.updateKeysByCode();
            mc.options.write();
        }
        boolean blade = mc.player != null && com.pglol.aotrpg.Guard.melee(mc.player.getMainHandStack())
            && !com.pglol.aotrpg.AotItems.isApgGun(mc.player.getMainHandStack());
        boolean want = mc.currentScreen == null && mc.player != null
            && ((guardKey != null && guardKey.isPressed()) || (blade && mc.options.useKey.isPressed()));
        if (want != guardSent) {
            guardSent = want;
            ClientPlayNetworking.send(new Net.GuardKey(want));
        }
    }

    /** Guarding: right click held with a blade raised (or the optional guard key). */
    public static boolean guarding() {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean raising = mc.player != null && mc.player.isUsingItem() && com.pglol.aotrpg.Guard.melee(mc.player.getActiveItem());
        return raising || guardSent;
    }

    // ------------------------------------------------------------------ damage numbers

    private record Num(Vec3d at, String text, int color, float scale, long born) { }

    private static final List<Num> nums = new ArrayList<>();

    public static void onHit(Net.HitMarker h) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity e = mc.world == null ? null : mc.world.getEntityById(h.entity());
        if (e == null) return;
        boolean kill = (h.kind() & 2) != 0, titan = (h.kind() & 8) != 0;
        float d = h.damage();
        String text = d >= 10 ? String.valueOf(Math.round(d)) : String.format(java.util.Locale.ROOT, "%.1f", d);
        int color = kill ? 0xFF4A3A : titan ? 0xFFD76A : 0xFFFFFF;
        double jitter = (Math.random() - 0.5) * Math.min(1.5, e.getWidth());
        Vec3d at = new Vec3d(e.getX() + jitter, e.getY() + Math.min(e.getHeight() * 0.75, e.getHeight() - 0.2) + 0.3, e.getZ() + jitter);
        // Hits near the camera float up in front of the target rather than inside it.
        nums.add(new Num(at, kill ? text + " ✖" : text, color, kill ? 1.4f : d >= 10 ? 1.2f : 1f, Util.getMeasuringTimeMs()));
        if (nums.size() > 40) nums.remove(0);
        if (!titan) struck.put(h.entity(), Util.getMeasuringTimeMs());
    }

    /** Who you hit lately (entity id -> when), and each bar's eased value (for the drain). */
    private static final java.util.Map<Integer, Long> struck = new java.util.HashMap<>();
    private static final java.util.Map<Integer, float[]> shown = new java.util.HashMap<>();

    /**
     * A slim health bar over whoever you're fighting: the one you hit in the last few seconds, or
     * the foe under your crosshair. Red, with a pale drain where the last hit was. Titans have
     * their own plates; party members don't get one.
     */
    private static void healthBars(MinecraftClient mc, MatrixStack ms, VertexConsumerProvider.Immediate vc, Vec3d cam, float td, long now) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        struck.entrySet().removeIf(en -> now - en.getValue() > 6000);
        ids.addAll(struck.keySet());
        if (mc.targetedEntity != null) ids.add(mc.targetedEntity.getId());
        // And any wounded foe close by, or one you're looking at further out.
        if (mc.player != null) {
            for (var en : mc.world.getOtherEntities(mc.player, mc.player.getBoundingBox().expand(20, 8, 20),
                x -> x instanceof net.minecraft.entity.LivingEntity le && le.isAlive() && le.getHealth() < le.getMaxHealth() - 0.01f)) ids.add(en.getId());
            Vec3d eye = mc.player.getEyePos(), look = mc.player.getRotationVec(1f);
            var hit = net.minecraft.entity.projectile.ProjectileUtil.raycast(mc.player, eye, eye.add(look.multiply(32)),
                mc.player.getBoundingBox().stretch(look.multiply(32)).expand(1), x -> x instanceof net.minecraft.entity.LivingEntity && !x.isSpectator(), 32 * 32);
            if (hit != null) ids.add(hit.getEntity().getId());
        }
        shown.keySet().retainAll(ids);
        for (int id : ids) {
            if (!(mc.world.getEntityById(id) instanceof net.minecraft.entity.LivingEntity e) || !e.isAlive() || e == mc.player) continue;
            String type = net.minecraft.registry.Registries.ENTITY_TYPE.getId(e.getType()).getPath();
            if (type.contains("titan") || e instanceof net.minecraft.entity.decoration.ArmorStandEntity) continue;
            boolean fighter = e instanceof net.minecraft.entity.player.PlayerEntity || e instanceof net.minecraft.entity.mob.Monster
                || e instanceof net.minecraft.entity.passive.VillagerEntity || e instanceof net.minecraft.entity.mob.HostileEntity;
            if (!fighter) continue;
            boolean mate = false;
            for (Net.PartyMember pm : ClientState.party) if (pm.id().equals(e.getUuid())) mate = true;
            if (mate) continue;
            Vec3d pos = e.getLerpedPos(td);
            if (pos.squaredDistanceTo(cam) > 32 * 32) continue;
            float frac = Math.max(0, Math.min(1, e.getHealth() / Math.max(1, e.getMaxHealth())));
            float[] sh = shown.computeIfAbsent(id, k -> new float[] {frac, frac});
            sh[0] = frac;
            sh[1] = sh[1] > frac ? Math.max(frac, sh[1] - 0.012f) : frac;
            Long hit = struck.get(id);
            float alpha = hit == null ? 0.8f : Math.min(1, 1.6f - (now - hit) / 6000f * 1.6f + 0.6f);
            alpha = Math.max(0.35f, Math.min(1, alpha));
            ms.push();
            ms.translate(pos.x - cam.x, pos.y + e.getHeight() + 0.32 - cam.y, pos.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            // As a nametag is drawn (only y flipped), so the quads face the camera and aren't culled.
            ms.scale(1, -1, 1);
            var m = ms.peek().getPositionMatrix();
            var buf = vc.getBuffer(net.minecraft.client.render.RenderLayer.getTextBackgroundSeeThrough());
            float w = 0.42f, h = 0.035f;
            int a = (int) (alpha * 255);
            quad(buf, m, -w - 0.015f, -h - 0.015f, w + 0.015f, h + 0.015f, (a * 3 / 4) << 24);
            quad(buf, m, -w, -h, -w + 2 * w * sh[1], h, (a * 3 / 4 << 24) | 0xE8D8C8);
            int col = frac > 0.5f ? 0xD84A3A : frac > 0.25f ? 0xE07A2A : 0xF03A2A;
            quad(buf, m, -w, -h, -w + 2 * w * frac, h, (a << 24) | col);
            ms.pop();
        }
    }

    private static void quad(net.minecraft.client.render.VertexConsumer b, org.joml.Matrix4f m, float x0, float y0, float x1, float y1, int argb) {
        int light = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        b.vertex(m, x0, y0, 0).color(argb).light(light);
        b.vertex(m, x0, y1, 0).color(argb).light(light);
        b.vertex(m, x1, y1, 0).color(argb).light(light);
        b.vertex(m, x1, y0, 0).color(argb).light(light);
    }

    public static void renderWorld(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        MatrixStack ms = ctx.matrixStack();
        if (ms == null || mc.world == null) return;
        VertexConsumerProvider.Immediate vc = mc.getBufferBuilders().getEntityVertexConsumers();
        Vec3d cam = ctx.camera().getPos();
        long now = Util.getMeasuringTimeMs();
        if (!mc.options.hudHidden) healthBars(mc, ms, vc, cam, ctx.tickCounter().getTickDelta(true), now);
        if (nums.isEmpty()) {
            vc.draw();
            return;
        }
        TextRenderer tr = mc.textRenderer;
        for (Iterator<Num> it = nums.iterator(); it.hasNext(); ) {
            Num n = it.next();
            float t = (now - n.born()) / 1100f;
            if (t >= 1) {
                it.remove();
                continue;
            }
            Vec3d at = n.at().add(0, t * 0.9, 0);
            double dist = at.distanceTo(cam);
            // Big enough to read at any range (the same size on screen near or far), punched in.
            float s = (float) (0.022 + dist * 0.0032) * n.scale() * (t < 0.1f ? 1.5f - t * 5f : 1f);
            int a = (int) (255 * (t < 0.7f ? 1 : (1 - t) / 0.3f));
            ms.push();
            ms.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            ms.multiply(mc.getEntityRenderDispatcher().getRotation());
            ms.scale(s, -s, s);
            // Plain, bold, with a solid dark outline: legible over sky, grass or a white flash.
            Text text = Text.literal(n.text()).formatted(net.minecraft.util.Formatting.BOLD);
            float tx = -tr.getWidth(text) / 2f;
            int col = (Math.max(8, a) << 24) | n.color(), edge = (Math.max(8, a * 3 / 4) << 24) | 0x140C08;
            var m = ms.peek().getPositionMatrix();
            for (int k = 0; k < 8; k++) {
                float ox = new float[] {-1, 1, 0, 0, -1, 1, -1, 1}[k], oy = new float[] {0, 0, -1, 1, -1, -1, 1, 1}[k];
                tr.draw(text, tx + ox, oy, edge, false, m, vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            ms.translate(0, 0, -0.01f);
            tr.draw(text, tx, 0, col, false, ms.peek().getPositionMatrix(), vc, TextRenderer.TextLayerType.SEE_THROUGH, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            ms.pop();
        }
        vc.draw();
    }

    // ------------------------------------------------------------------ XP bar

    private static long lastXp = -1, gainAt;
    private static int lastLevel;
    private static long gained;

    public static void renderHud(DrawContext c, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Net.Sync p = ClientState.profile;
        if (p == null || mc.player == null || mc.options.hudHidden || !CombatHotbar.active()) return;
        long now = Util.getMeasuringTimeMs();
        if (lastXp >= 0 && (p.xp() != lastXp || p.level() != lastLevel)) {
            long diff = p.level() > lastLevel ? p.xp() + 1 : p.xp() - lastXp;
            if (diff > 0) {
                gained = now - gainAt < 2500 ? gained + diff : diff;
                gainAt = now;
            }
        }
        lastXp = p.xp();
        lastLevel = p.level();
        int w = c.getScaledWindowWidth(), h = c.getScaledWindowHeight();
        int x0 = CombatHotbar.slotX(w, 0) - 3, x1 = CombatHotbar.slotX(w, 8) + 20 + 3;
        float frac = p.need() > 0 ? Math.min(1, (float) p.xp() / p.need()) : 1;
        int y = h - 2;
        c.fill(x0, y, x1, y + 2, 0xC0101410);
        c.fill(x0, y, x0 + Math.round((x1 - x0) * frac), y + 2, Ui.XP);
        String lv = "Lv " + p.level();
        Ui.text(c, Text.literal(lv), x1 + 4, h - 10, 0.7f, Ui.GOLD, false);
        // Progress to the next level, in numbers, sitting on the bar.
        String prog = String.format(java.util.Locale.ROOT, "%,d / %,d XP  ·  %d%% to Lv %d", p.xp(), p.need(), Math.round(frac * 100), p.level() + 1);
        float ps = 0.55f;
        int pw = Math.round(mc.textRenderer.getWidth(prog) * ps);
        int px = (x0 + x1) / 2;
        // The numbers only show for a moment after you gain some: the thin bar is enough the rest of the time.
        float showK = now - gainAt < 2600 ? Math.min(1, (2600 - (now - gainAt)) / 500f) : 0;
        if (showK > 0.02f) {
            int ba = (int) (0xB0 * showK), ta = (int) (255 * showK);
            c.fill(px - pw / 2 - 3, y - 6, px + pw / 2 + 3, y, (ba << 24) | 0x101410);
            Ui.text(c, Text.literal(prog), px, y - 5, ps, (ta << 24) | 0xE8D9A8, true);
        }
        // (Gains show as a quiet pop by the crosshair: see HudPops.)
        if (guarding()) {
            int cx = w / 2, cy = h / 2;
            Ui.text(c, Text.literal("GUARD"), cx, cy + 10, 0.6f, 0xC0E0B96A, true);
        }
    }

    // ------------------------------------------------------------------ block, clash, broken guard

    public static void onGuardFx(Net.GuardFx fx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        Style s = Style.of(fx.style(), fx.kind());
        int n = fx.kind() == 1 ? 36 : fx.kind() == 2 ? 22 : 16;
        double spread = fx.kind() == 1 ? 0.55 : 0.3;
        for (int i = 0; i < n; i++) {
            double vx = (Math.random() - 0.5) * spread, vy = (Math.random() - 0.2) * spread, vz = (Math.random() - 0.5) * spread;
            mc.world.addParticle(i % 3 == 0 ? s.second : s.main, fx.x(), fx.y(), fx.z(), vx, vy, vz);
        }
        if (fx.kind() == 1) mc.world.addParticle(Fxs.FLASH, fx.x(), fx.y(), fx.z(), 0, 0, 0);
        if (fx.kind() == 2) for (int i = 0; i < 8; i++) mc.world.addParticle(Fxs.CAMPFIRE_COSY_SMOKE, fx.x(), fx.y(), fx.z(), 0, 0.05, 0);
    }

    /** Particle pairs for block and clash styles (cosmetics); the defaults are steel sparks. */
    record Style(ParticleEffect main, ParticleEffect second) {
        static Style of(String id, int kind) {
            if (id.isEmpty() || id.endsWith("_steel")) {
                return kind == 1 ? new Style(Fxs.ELECTRIC_SPARK, Fxs.CRIT) : new Style(Fxs.CRIT, Fxs.ELECTRIC_SPARK);
            }
            ParticleEffect[] p = CosmeticFx.particles(id);
            return new Style(p[0], p[p.length > 1 ? 1 : 0]);
        }
    }
}
