package dev.flint.planetry.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProgressionTest {
    @Test
    void aLargeExperienceGrantCarriesAndRewardsEveryCrossedLevel() {
        Progress before = Progress.initial();

        MutationResult result = Progression.apply(before, Operation.GIVE, 345);

        assertEquals(before, result.previous());
        assertEquals(new Progress(3, 45, 3), result.current());
        assertEquals(List.of(1, 2, 3), result.newlyRewardedLevels());
    }

    @Test
    void takingExperienceBorrowsAcrossLevelsAndFloorsAtZero() {
        MutationResult result = Progression.apply(new Progress(4, 20, 4), Operation.TAKE, 145);

        assertEquals(new Progress(2, 75, 4), result.current());
        assertTrue(result.newlyRewardedLevels().isEmpty());
        assertEquals(new Progress(0, 0, 4),
                Progression.apply(result.current(), Operation.TAKE, Long.MAX_VALUE).current());
    }

    @Test
    void settingLevelResetsPartialExperienceAndKeepsTheRewardHighWatermark() {
        MutationResult lowered = Progression.apply(new Progress(5, 80, 7), Operation.SET_LEVEL, 3);

        assertEquals(new Progress(3, 0, 7), lowered.current());
        assertTrue(lowered.newlyRewardedLevels().isEmpty());

        MutationResult raised = Progression.apply(lowered.current(), Operation.SET_LEVEL, 10);
        assertEquals(new Progress(10, 0, 10), raised.current());
        assertTrue(raised.newlyRewardedLevels().isEmpty());
        assertEquals(List.of(11),
                Progression.apply(raised.current(), Operation.GIVE, 100).newlyRewardedLevels());
    }

    @Test
    void settingTheCurrentLevelAlsoClearsExperience() {
        MutationResult result = Progression.apply(new Progress(3, 99, 3), Operation.SET_LEVEL, 3);

        assertEquals(new Progress(3, 0, 3), result.current());
        assertTrue(result.newlyRewardedLevels().isEmpty());
    }

    @Test
    void regainingLostLevelsDoesNotRewardThemAgain() {
        Progress regressed = Progression.apply(new Progress(6, 50, 6), Operation.TAKE, 600).current();
        assertEquals(new Progress(0, 50, 6), regressed);

        MutationResult regained = Progression.apply(regressed, Operation.GIVE, 600);
        assertEquals(new Progress(6, 50, 6), regained.current());
        assertTrue(regained.newlyRewardedLevels().isEmpty());

        MutationResult newLevel = Progression.apply(regained.current(), Operation.GIVE, 50);
        assertEquals(new Progress(7, 0, 7), newLevel.current());
        assertEquals(List.of(7), newLevel.newlyRewardedLevels());
    }

    @Test
    void zeroExperienceChangesDoNotEraseProgressOrReissueRewards() {
        Progress before = new Progress(2, 19, 4);
        for (Operation operation : List.of(Operation.GIVE, Operation.TAKE)) {
            MutationResult result = Progression.apply(before, operation, 0);
            assertEquals(before, result.current());
            assertTrue(result.newlyRewardedLevels().isEmpty());
        }
    }

    @Test
    void grantLimitIsInclusiveAndRejectsOversizedOrNegativeRequests() {
        assertEquals(new Progress(100, 0, 100),
                Progression.apply(Progress.initial(), Operation.GIVE, Progress.MAX_GIVE).current());
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(Progress.initial(), Operation.GIVE, (long) Progress.MAX_GIVE + 1));
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(Progress.initial(), Operation.GIVE, Long.MAX_VALUE));
        for (Operation operation : Operation.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> Progression.apply(Progress.initial(), operation, -1));
        }
    }

    @Test
    void theMaximumLevelCanBeReachedExactlyButCannotOverflow() {
        Progress before = new Progress(Progress.MAX_LEVEL - 1, 50, Progress.MAX_LEVEL - 1);
        MutationResult result = Progression.apply(before, Operation.GIVE, 50);

        assertEquals(new Progress(Progress.MAX_LEVEL, 0, Progress.MAX_LEVEL), result.current());
        assertEquals(List.of(Progress.MAX_LEVEL), result.newlyRewardedLevels());
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(before, Operation.GIVE, 51));
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(result.current(), Operation.GIVE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(before, Operation.SET_LEVEL, (long) Progress.MAX_LEVEL + 1));
        assertThrows(IllegalArgumentException.class,
                () -> Progression.apply(before, Operation.SET_LEVEL, Long.MAX_VALUE));
    }

    @Test
    void invalidPersistedProgressCannotEnterTheDomain() {
        assertThrows(IllegalArgumentException.class, () -> new Progress(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(Progress.MAX_LEVEL + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, Progress.XP_PER_LEVEL, 0));
        assertThrows(IllegalArgumentException.class, () -> new Progress(Progress.MAX_LEVEL, 1, Progress.MAX_LEVEL));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Progress(2, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Progress(0, 0, Progress.MAX_LEVEL + 1));
    }
}
