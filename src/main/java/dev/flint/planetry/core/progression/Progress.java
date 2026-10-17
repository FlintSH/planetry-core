package dev.flint.planetry.core.progression;

/** Persistent plugin experience, independent of Minecraft's experience bar. */
public record Progress(int level, int experience, int rewardHighWatermark) {
    public static final int XP_PER_LEVEL = 100;
    public static final int MAX_LEVEL = 1_000_000;
    public static final int MAX_GIVE = 10_000;
    public static final long MAX_TOTAL_EXPERIENCE = (long) MAX_LEVEL * XP_PER_LEVEL;

    public Progress {
        if (level < 0 || level > MAX_LEVEL || experience < 0 || experience >= XP_PER_LEVEL
                || (level == MAX_LEVEL && experience != 0)
                || rewardHighWatermark < level || rewardHighWatermark > MAX_LEVEL) {
            throw new IllegalArgumentException("Invalid progression state");
        }
    }

    public static Progress initial() {
        return new Progress(0, 0, 0);
    }

    public long totalExperience() {
        return (long) level * XP_PER_LEVEL + experience;
    }
}
