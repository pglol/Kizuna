package net.pglol.kata.rig;

/**
 * The skeleton's joints. Minecraft's player is six rigid blocks; Kata's has a bending spine, elbows
 * and knees. Upper limbs rotate at the shoulder and hip, lower limbs bend at the elbow and knee, and
 * the chest bends and twists at the waist (head and arms follow it, hips and legs don't).
 */
public enum Bone {
    HEAD("Head"),
    CHEST("Chest"),
    RIGHT_ARM("Right arm"),
    RIGHT_FOREARM("Right elbow"),
    LEFT_ARM("Left arm"),
    LEFT_FOREARM("Left elbow"),
    RIGHT_LEG("Right leg"),
    RIGHT_SHIN("Right knee"),
    LEFT_LEG("Left leg"),
    LEFT_SHIN("Left knee");

    public final String title;

    Bone(String title) {
        this.title = title;
    }
}
