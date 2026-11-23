package com.pglol.aotrpg.client;

import com.pglol.aotrpg.Cooking;
import com.pglol.aotrpg.Recipes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every item says what it's for: the dishes and things it goes into (and at which bench), what a
 * campfire makes of it, and failing all that, that traders will still take it off your hands.
 */
public final class ItemUses {
    private ItemUses() {}

    private static final Map<Item, List<Text>> cache = new HashMap<>();

    public static void add(ItemStack stack, List<Text> lines) {
        if (stack.isEmpty() || com.pglol.aotrpg.Gear.isGear(stack) || com.pglol.aotrpg.Recipes.schematicOf(stack) != null) return;
        // Plain materials only: named dishes and special items explain themselves.
        if (stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_DATA)) return;
        List<Text> extra = cache.computeIfAbsent(stack.getItem(), k -> compute(stack.copyWithCount(1)));
        lines.addAll(extra);
    }

    private static List<Text> compute(ItemStack s) {
        List<Text> out = new ArrayList<>();
        Map<String, List<String>> byBench = new LinkedHashMap<>();
        try {
            for (Recipes.Recipe r : Recipes.all()) {
                for (Recipes.Ing in : r.inputs()) {
                    if (in.test().test(s)) {
                        byBench.computeIfAbsent(r.bench().title, k -> new ArrayList<>()).add(r.name());
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        List<String> fire = new ArrayList<>();
        for (Cooking.Recipe r : Cooking.Recipe.values()) {
            for (Cooking.Ingredient in : r.ingredients) if (in.group().items.contains(s.getItem())) {
                fire.add(r.title);
                break;
            }
        }
        if (byBench.isEmpty() && fire.isEmpty()) {
            if (!s.contains(net.minecraft.component.DataComponentTypes.FOOD))
                out.add(Text.literal("Odds and ends: any Craftsman buys it").formatted(Formatting.DARK_GRAY));
            return out;
        }
        out.add(Text.literal("Used in").formatted(Formatting.GRAY));
        for (var e : byBench.entrySet()) out.add(line(e.getKey(), e.getValue()));
        if (!fire.isEmpty()) out.add(line("Campfire", fire));
        return out;
    }

    private static Text line(String where, List<String> names) {
        String list = String.join(", ", names.subList(0, Math.min(3, names.size())));
        if (names.size() > 3) list += " +" + (names.size() - 3) + " more";
        return Text.literal(" " + where + ": ").formatted(Formatting.DARK_GRAY).append(Text.literal(list).formatted(Formatting.GRAY));
    }
}
