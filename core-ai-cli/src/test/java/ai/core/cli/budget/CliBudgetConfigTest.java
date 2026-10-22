package ai.core.cli.budget;

import ai.core.cli.CliAppOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class CliBudgetConfigTest {
    private static CliAppOptions options(Integer timeLimitSeconds, Integer timeBudgetSeconds) {
        return new CliAppOptions(null, null, null, false, false, false, Path.of("."),
                timeLimitSeconds, timeBudgetSeconds, null, null, null, null, null,
                null, null, null, null, Instant.EPOCH, 0);
    }

    @Test
    void timeBudgetIsClampedToTheLegacyTimeLimit() {
        var budget = Objects.requireNonNull(CliBudgetConfig.create(options(710, 885), null, "deepseek-flash"));
        var time = Objects.requireNonNull(budget.time());

        assertEquals(710_000, time.totalMillis());
    }

    @Test
    void legacyTimeLimitAloneDoesNotCreateABudget() {
        assertNull(CliBudgetConfig.create(options(710, null), null, "deepseek-flash"));
    }

    @Test
    void smallerTimeBudgetWins() {
        var budget = Objects.requireNonNull(CliBudgetConfig.create(options(710, 600), null, "deepseek-flash"));
        var time = Objects.requireNonNull(budget.time());

        assertEquals(600_000, time.totalMillis());
    }
}
