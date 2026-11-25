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

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((d, reg, env) -> d.register(CommandManager.literal("kata")
            .requires(s -> s.hasPermissionLevel(2))
            .then(CommandManager.literal("testzone").executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                int n = TestZone.build(p);
                c.getSource().sendFeedback(() -> Text.literal("Test zone built (" + n + " blocks). Press O for the pose editor."), false);
                return 1;
            }))));
        KataNet.register();
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
