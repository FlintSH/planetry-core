package dev.flint.planetry.core.progression;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Progression {
    private Progression() {}

    public static MutationResult apply(Progress previous, Operation operation, long amount) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(operation, "operation");
        if (amount < 0) {
            throw new IllegalArgumentException("Amount must be a nonnegative whole number");
        }
        long total = switch (operation) {
            case GIVE -> {
                if (amount > Progress.MAX_GIVE) {
                    throw new IllegalArgumentException("At most " + Progress.MAX_GIVE + " XP may be given at once");
                }
                long result = previous.totalExperience() + amount;
                if (result > Progress.MAX_TOTAL_EXPERIENCE) {
                    throw new IllegalArgumentException("This amount exceeds the maximum level");
                }
                yield result;
            }
            case TAKE -> previous.totalExperience() - Math.min(previous.totalExperience(), amount);
            case SET_LEVEL -> {
                if (amount > Progress.MAX_LEVEL) {
                    throw new IllegalArgumentException("Maximum level is " + Progress.MAX_LEVEL);
                }
                yield amount * Progress.XP_PER_LEVEL;
            }
        };
        int level = (int) (total / Progress.XP_PER_LEVEL);
        Progress current = new Progress(level, (int) (total % Progress.XP_PER_LEVEL),
                Math.max(previous.rewardHighWatermark(), level));
        List<Integer> earned = new ArrayList<>();
        if (operation == Operation.GIVE) {
            for (int earnedLevel = previous.rewardHighWatermark() + 1; earnedLevel <= level; earnedLevel++) {
                earned.add(earnedLevel);
            }
        }
        return new MutationResult(previous, current, earned);
    }
}
