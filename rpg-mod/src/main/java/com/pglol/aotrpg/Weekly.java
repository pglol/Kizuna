package com.pglol.aotrpg;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The weekly operation: every week (Monday, UTC) the command puts out one order for everyone, a
 * bonus that changes what's worth doing that week. Shown on join, in the stats panels and on Tab.
 */
public final class Weekly {
    private Weekly() {}

    public enum Op {
        RECRUITMENT("Recruitment Drive", "+50% XP from everything", 0x8CC08A),
        BOUNTY("Bounty Week", "Contracts pay +50%", 0xE0B96A),
        SALVAGE("Salvage Week", "+50% Salvage from extraction runs", 0x9AD0FF),
        WAR_CHEST("War Chest", "+50% Marks earned", 0xF2C14E),
        TITAN_HUNT("Titan Hunt", "+100% XP for titan kills, ogres and apexes roam more", 0xE04A3A);

        public final String title, desc;
        public final int color;

        Op(String title, String desc, int color) {
            this.title = title;
            this.desc = desc;
            this.color = color;
        }
    }

    private static long week() {
        // Weeks counted from a Monday.
        return Math.floorDiv(LocalDate.now(ZoneOffset.UTC).toEpochDay() - 4, 7);
    }

    public static Op now() {
        Op[] all = Op.values();
        return all[(int) Math.floorMod(week(), all.length)];
    }

    /** Days (rounded up) until next week's order. */
    public static int daysLeft() {
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay() - 4;
        return (int) (7 - Math.floorMod(day, 7));
    }

    public static double xp(boolean titan) {
        Op o = now();
        if (o == Op.RECRUITMENT) return 1.5;
        if (o == Op.TITAN_HUNT && titan) return 2.0;
        return 1;
    }

    public static double contracts() {
        return now() == Op.BOUNTY ? 1.5 : 1;
    }

    public static double salvage() {
        return now() == Op.SALVAGE ? 1.5 : 1;
    }

    public static double marks() {
        return now() == Op.WAR_CHEST ? 1.5 : 1;
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> AotRpg.SCHEDULER.later(160, () -> {
            if (h.player.isDisconnected()) return;
            Op o = now();
            Notify.toast(h.player, Text.literal("This week: " + o.title).formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(o.desc + " · " + daysLeft() + " days left"), o.color, "minecraft:writable_book", "weekly");
        }));
    }
}
