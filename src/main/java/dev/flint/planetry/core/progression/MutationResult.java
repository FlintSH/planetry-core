package dev.flint.planetry.core.progression;

import java.util.List;

public record MutationResult(Progress previous, Progress current, List<Integer> newlyRewardedLevels) {
    public MutationResult {
        newlyRewardedLevels = List.copyOf(newlyRewardedLevels);
    }
}
