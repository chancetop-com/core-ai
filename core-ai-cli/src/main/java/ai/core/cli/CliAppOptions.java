package ai.core.cli;

import java.nio.file.Path;
import java.time.Instant;

/**
 * @author stephen
 */
public record CliAppOptions(Path configFile, String modelOverride, String prompt,
                            boolean autoApproveAll, boolean continueSession,
                            boolean resume, Path workspace,
                            Integer timeLimitSeconds,
                            Integer timeBudgetSeconds,
                            Long timeBudgetReserveSeconds,
                            Instant deadline,
                            Integer tokenBudget,
                            Integer outputTokenBudget,
                            Double costBudgetUsd,
                            Long reserveTokens,
                            Double reserveCostUsd,
                            Double reservePercent,
                            Double estimateSafetyFactor,
                            Instant startedAt,
                            long startedAtNanos) {
}
