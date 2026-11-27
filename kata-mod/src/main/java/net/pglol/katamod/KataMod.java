package net.pglol.katamod;

import net.fabricmc.api.ModInitializer;
import net.pglol.kata.KataCore;

/**
 * Kata: the Kata server's own game, on top of Kata Core. Kata Core is the body, movement and combat
 * any Kizuna game can use; this is the one server's world and look: Kizuna town (built, watched and
 * managed here, with its commands), and the Kata interface.
 */
public final class KataMod implements ModInitializer {
    public static final String ID = "kata";

    @Override
    public void onInitialize() {
        Town.register();
        KataCore.LOG.info("Kata ready");
    }
}
