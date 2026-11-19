package com.pglol.aotrpg.client;

/**
 * Living alongside shader packs (Iris). Shaders draw every entity a second time from the sun to
 * make shadows; our hand-built cosmetic shapes (wings, cloaks, halos, eyes, the ribcage...) must
 * sit that pass out, or a ghost of them is left hanging in the sky.
 */
public final class ShaderCompat {
    private ShaderCompat() {}

    private static Object api;
    private static java.lang.reflect.Method shadowPass;
    private static boolean looked;

    private static java.lang.reflect.Method packInUse;

    /** A shader pack is on (false without Iris). */
    public static boolean packInUse() {
        shadowPass();
        if (api == null) return false;
        try {
            if (packInUse == null) packInUse = api.getClass().getMethod("isShaderPackInUse");
            return (Boolean) packInUse.invoke(api);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Iris is drawing its shadow map right now (false without Iris). */
    public static boolean shadowPass() {
        if (!looked) {
            looked = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = c.getMethod("getInstance").invoke(null);
                shadowPass = c.getMethod("isRenderingShadowPass");
            } catch (Throwable ignored) {
                api = null;
            }
        }
        if (api == null) return false;
        try {
            return (Boolean) shadowPass.invoke(api);
        } catch (Throwable e) {
            return false;
        }
    }
}
