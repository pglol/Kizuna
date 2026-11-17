package com.pglol.aotrpg;

/**
 * What kind of fighter a character is, from how they've spent their points: Swift (Agility
 * leads), Heavy (Strength and Resolve lead, or a Tank) or Balanced. It changes how they move:
 * a Swift fighter can vanish out of a standing start, a Heavy one cracks the ground taking off.
 */
public enum Builds {
    SWIFT, BALANCED, HEAVY;

    /** From the stat totals (Stat order) and the class ordinal. */
    public static Builds of(int[] total, int cls) {
        if (total == null || total.length < 4) return BALANCED;
        int agi = total[Stat.AGILITY.ordinal()], heavy = total[Stat.STRENGTH.ordinal()] + total[Stat.RESOLVE.ordinal()];
        if (cls == PlayerClass.TANK.ordinal() || heavy >= 6 && heavy >= agi * 2) return HEAVY;
        if (agi >= 4 && agi * 4 >= heavy * 3 || cls == PlayerClass.RECON.ordinal() && agi >= 2) return SWIFT;
        return BALANCED;
    }

    public static Builds of(Profile p) {
        int[] t = new int[Stat.values().length];
        for (Stat s : Stat.values()) t[s.ordinal()] = p.total(s);
        return of(t, p.cls() == null ? -1 : p.cls().ordinal());
    }

    public static int agility(Profile p) {
        return p.total(Stat.AGILITY);
    }
}
