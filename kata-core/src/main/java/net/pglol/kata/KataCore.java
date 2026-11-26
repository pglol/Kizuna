package net.pglol.kata;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Kata Core: the body, movement and combat core for Kizuna's fighting games. */
public final class KataCore implements ModInitializer {
    public static final String ID = "kata_core";
    public static final Logger LOG = LoggerFactory.getLogger("Kata Core");
    /** No hunger: food stays full (so it never stops anyone running) and there's no hunger bar. */
    public static boolean noHunger = true;
    /** Body physics: a touch less gravity (falls a little slower) and a slightly stronger jump, so a
     *  jump has more air under it without clearing 1.5-block fences (Minecraft: 0.08 and 0.42). */
    public static final double GRAVITY = 0.072, JUMP = 0.48; // about 1.6 blocks: a fighter's spring, not a hop

    public static void body(ServerPlayerEntity p) {
        var g = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_GRAVITY);
        if (g != null) g.setBaseValue(GRAVITY);
        var j = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_JUMP_STRENGTH);
        if (j != null) j.setBaseValue(JUMP);
        // Falls only start to hurt past 4 blocks (Minecraft: 3).
        var f = p.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.GENERIC_SAFE_FALL_DISTANCE);
        if (f != null) f.setBaseValue(SAFE_FALL);
    }

    public static final double SAFE_FALL = 4;
    /** A caught landing (crouch just before touching down) takes this share of the fall damage. */
    public static final float CAUGHT_DAMAGE = 0.35f;
    private static final java.util.Map<java.util.UUID, Integer> BRACED = new java.util.HashMap<>();

    /** The player pressed crouch while falling (their game says so the moment it happens). */
    public static void brace(ServerPlayerEntity p) {
        BRACED.put(p.getUuid(), p.getServer().getTicks());
        if (BRACED.size() > 512) BRACED.clear();
    }

    private static final java.util.Map<java.util.UUID, Integer> SLAMMED = new java.util.HashMap<>();

    /**
     * The player hit a wall at speed (blocks a tick). Hurts like flying into one with elytra: nothing
     * at a jog, more the harder you hit. Only ever about the player themselves, once a half second.
     */
    public static void impact(ServerPlayerEntity p, double speed) {
        int now = p.getServer().getTicks();
        Integer last = SLAMMED.get(p.getUuid());
        if (last != null && now - last < 10) return;
        SLAMMED.put(p.getUuid(), now);
        if (SLAMMED.size() > 512) SLAMMED.clear();
        float dmg = (float) (Math.min(speed, 4) * 10 - 3);
        if (dmg > 0.5f && !p.isCreative() && !p.isSpectator()) p.damage(p.getDamageSources().flyIntoWall(), dmg);
    }

    /** Braced in the last half second: the landing is caught. */
    public static boolean braced(ServerPlayerEntity p) {
        Integer at = BRACED.get(p.getUuid());
        return at != null && p.getServer().getTicks() - at <= 10;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("kata")
            .requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("dummy").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                Dummy dummy = new Dummy(KataEntities.DUMMY, p.getWorld());
                net.minecraft.util.math.Vec3d at = p.getPos().add(net.minecraft.util.math.Vec3d.fromPolar(0, p.getYaw()).multiply(2.5));
                dummy.refreshPositionAndAngles(at.x, p.getY(), at.z, p.getYaw() + 180, 0);
                dummy.setCustomName(Text.literal("Training dummy"));
                p.getWorld().spawnEntity(dummy);
                c.getSource().sendFeedback(() -> Text.literal("Training dummy placed. Right click to guard, R to lock on, left click to punch."), false);
                return 1;
            }))
            .then(CommandManager.literal("testzone").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                int n = TestZone.build(p);
                c.getSource().sendFeedback(() -> Text.literal("Test zone built (" + n + " blocks). Press O for the pose editor."), false);
                return 1;
            }))));
        KataEntities.init();
        KataNet.register();
        Fighting.register();
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((h, s, server) -> body(h.getPlayer()));
        net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldP, newP, alive) -> body(newP));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!noHunger) return;
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                var food = p.getHungerManager();
                if (food.getFoodLevel() < 20) food.setFoodLevel(20);
            }
        });
        LOG.info("Kata Core ready");
    }
}
