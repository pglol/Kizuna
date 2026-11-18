package com.pglol.aotrpg;

import java.util.Map;

/**
 * How rare each cosmetic is (0 standard, 1 rare, 2 epic, 3 legendary, 4 mythic), shared by the
 * server (prices) and the client (frames). Anything not listed: standard if free, else rare.
 */
public final class CosmeticTiers {
    private CosmeticTiers() { }

    private static final Map<String, Integer> TIERS = Map.ofEntries(
        Map.entry("trail_gold", 2),
        Map.entry("slash_steam", 2),
        Map.entry("slash_petal", 2),
        Map.entry("slash_gold", 2),
        Map.entry("slash_lightning", 3),
        Map.entry("slash_rainbow", 3),
        Map.entry("slash_moon", 4),
        Map.entry("body_gold", 2),
        Map.entry("body_steam", 2),
        Map.entry("body_spark", 3),
        Map.entry("body_coordinate", 4),
        Map.entry("odm_petal", 2),
        Map.entry("odm_gold", 3),
        Map.entry("horse_gold", 2),
        Map.entry("horse_void", 2),
        Map.entry("head_laurel", 2),
        Map.entry("head_crows", 3),
        Map.entry("head_sun", 3),
        Map.entry("head_founder", 4),
        Map.entry("outfit_scout_jacket", 1),
        Map.entry("outfit_garrison_coat", 2),
        Map.entry("outfit_mp_coat", 2),
        Map.entry("outfit_marley_tunic", 2),
        Map.entry("outfit_officer_sash", 1),
        Map.entry("outfit_hooded_cape", 3),
        Map.entry("outfit_scarf", 3),
        Map.entry("outfit_civilian_vest", 1),
        Map.entry("outfit_bandana", 1),
        Map.entry("outfit_armband", 1),
        Map.entry("back_banner", 2),
        Map.entry("back_steam", 2),
        Map.entry("back_wings_frost", 2),
        Map.entry("back_wings_freedom", 3),
        Map.entry("back_wings_ember", 3),
        Map.entry("back_wings_void", 3),
        Map.entry("back_wings_crimson", 4),
        Map.entry("kill_ember", 1),
        Map.entry("kill_petal", 2),
        Map.entry("kill_void", 2),
        Map.entry("kill_firework", 2),
        Map.entry("kill_lightning", 3),
        Map.entry("kill_holy", 3),
        Map.entry("kill_wings", 3),
        Map.entry("kill_rumbling", 4),
        Map.entry("block_petal", 2),
        Map.entry("clash_gold", 2));

    public static int of(String id) {
        if (id.startsWith("fin_")) return FinisherStyles.of(id).tier();
        if (Cosmetics.exclusiveId(id)) return 5;
        Integer t = TIERS.get(id);
        if (t != null) return t;
        if (Store.MYTHIC_COSMETICS.contains(id)) return 4;
        return id.endsWith("_none") || id.equals("trail_tracer") || id.equals("slash_steel") || id.equals("odm_wind") || id.equals("horse_dust")
            || id.equals("block_steel") || id.equals("clash_steel") || id.equals("kill_steam") ? 0 : 1;
    }

    /** Gold, by rarity: rare 180, epic 350, legendary 650 (mythic isn't sold). */
    public static long price(String id) {
        return switch (of(id)) {
            case 0 -> 0;
            case 1 -> 180;
            case 2 -> 350;
            case 3 -> 650;
            default -> 0;
        };
    }
}
