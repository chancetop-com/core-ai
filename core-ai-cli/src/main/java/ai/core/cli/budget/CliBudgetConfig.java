package ai.core.cli.budget;

import ai.core.agent.budget.CostBudget;
import ai.core.agent.budget.RunBudget;
import ai.core.agent.budget.TimeBudget;
import ai.core.agent.budget.TokenBudget;
import ai.core.agent.budget.TokenEstimator;
import ai.core.bootstrap.PropertiesFileSource;
import ai.core.cli.CliAppOptions;
import ai.core.llm.LLMModelContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * Builds the optional run budget from CLI flags and agent.properties. Flags win
 * over properties; a value of zero disables that dimension.
 *
 * @author stephen
 */
public final class CliBudgetConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(CliBudgetConfig.class);

    public static RunBudget create(CliAppOptions options, PropertiesFileSource props, String modelName) {
        if (options == null) return null;
        var startedAt = options.startedAt() != null ? options.startedAt() : Instant.now();
        var startedAtNanos = options.startedAtNanos() != 0 ? options.startedAtNanos() : System.nanoTime();

        var time = createTime(options, props, startedAt, startedAtNanos);
        var tokens = createTokens(options, props);
        var cost = createCost(options, props, modelName);
        if (time == null && tokens == null && cost == null) return null;

        var safetyFactor = doubleValue(options.estimateSafetyFactor(),
                propertyDouble(props, "agent.budget.estimate.safety.factor"), 1.10);
        var toolResultMaxTokens = longValue(null,
                propertyLong(props, "agent.budget.tool.result.max.tokens"), 8_000);
        return new RunBudget(time, tokens, cost, new TokenEstimator(safetyFactor), toolResultMaxTokens);
    }

    private static TimeBudget createTime(CliAppOptions options, PropertiesFileSource props,
                                         Instant startedAt, long startedAtNanos) {
        var seconds = positive(intValue(options.timeBudgetSeconds(),
                propertyInteger(props, "agent.time.budget.seconds")));
        var reserveSeconds = longValue(options.timeBudgetReserveSeconds(),
                propertyLong(props, "agent.time.budget.reserve.seconds"), -1);
        var deadline = options.deadline();
        if (deadline != null) {
            long totalMillis = Math.max(0, Duration.between(startedAt, deadline).toMillis());
            if (seconds != null) totalMillis = Math.min(totalMillis, seconds * 1000L);
            return TimeBudget.ofMillis(totalMillis, reserveSeconds < 0 ? -1 : reserveSeconds * 1000,
                    startedAtNanos, startedAt, System::nanoTime);
        }
        if (seconds == null) return null;
        return TimeBudget.ofSeconds(seconds, reserveSeconds < 0 ? null : reserveSeconds,
                startedAtNanos, startedAt, System::nanoTime);
    }

    private static TokenBudget createTokens(CliAppOptions options, PropertiesFileSource props) {
        var limit = positive(intValue(options.tokenBudget(),
                propertyInteger(props, "agent.budget.tokens")));
        if (limit == null) return null;
        var outputLimit = positive(intValue(options.outputTokenBudget(),
                propertyInteger(props, "agent.budget.output.tokens")));
        var reserve = longValue(options.reserveTokens(),
                propertyLong(props, "agent.budget.reserve.tokens"), -1);
        if (reserve < 0) {
            var percent = doubleValue(options.reservePercent(),
                    propertyDouble(props, "agent.budget.reserve.percent"), -1);
            if (percent >= 0) reserve = Math.round(limit * percent / 100.0);
        }
        var outputReserve = longValue(null,
                propertyLong(props, "agent.budget.reserve.output.tokens"), -1);
        var countCached = !booleanValue(props, "agent.budget.tokens.exclude.cache", false);
        return TokenBudget.of(limit, outputLimit == null ? 0 : outputLimit,
                reserve < 0 ? null : reserve,
                outputReserve < 0 ? null : outputReserve, countCached);
    }

    private static CostBudget createCost(CliAppOptions options, PropertiesFileSource props, String modelName) {
        var limit = positive(doubleValue(options.costBudgetUsd(),
                propertyDouble(props, "agent.budget.cost.usd"), 0));
        if (limit == null) return null;
        if (!LLMModelContextRegistry.getInstance().hasModel(modelName)) {
            var allowUnknown = booleanValue(props, "agent.budget.cost.allow.unknown.pricing", false);
            if (allowUnknown) {
                LOGGER.warn("cost budget disabled: no price for model {}, set agent.budget.model prices or use a priced model", modelName);
                return null;
            }
            throw new IllegalArgumentException("cost budget requires pricing for model '" + modelName
                    + "'; use a model in the price catalog or set agent.budget.cost.allow.unknown.pricing=true");
        }
        var reserve = doubleValue(options.reserveCostUsd(),
                propertyDouble(props, "agent.budget.reserve.cost.usd"), -1);
        if (reserve < 0) {
            var percent = doubleValue(options.reservePercent(),
                    propertyDouble(props, "agent.budget.reserve.percent"), -1);
            if (percent >= 0) reserve = limit * percent / 100.0;
        }
        return CostBudget.ofUsd(limit, reserve < 0 ? null : reserve);
    }

    private static Integer positive(Integer value) {
        if (value == null || value == 0) return null;
        if (value < 0) throw new IllegalArgumentException("budget values must be zero or positive");
        return value;
    }

    private static Double positive(Double value) {
        if (value == null || value == 0) return null;
        if (value < 0) throw new IllegalArgumentException("budget values must be zero or positive");
        return value;
    }

    private static Integer intValue(Integer option, Integer fallback) {
        return option != null ? option : fallback;
    }

    private static long longValue(Number option, Number fallback, long defaultValue) {
        var value = option != null ? option : fallback;
        return value == null ? defaultValue : value.longValue();
    }

    private static double doubleValue(Double option, Double fallback, double defaultValue) {
        var value = option != null ? option : fallback;
        return value == null ? defaultValue : value;
    }

    private static Integer propertyInteger(PropertiesFileSource props, String key) {
        return props == null ? null : props.property(key).map(CliBudgetConfig::parseInteger).orElse(null);
    }

    private static Long propertyLong(PropertiesFileSource props, String key) {
        return props == null ? null : props.property(key).map(CliBudgetConfig::parseLong).orElse(null);
    }

    private static Double propertyDouble(PropertiesFileSource props, String key) {
        return props == null ? null : props.property(key).map(CliBudgetConfig::parseDouble).orElse(null);
    }

    private static boolean booleanValue(PropertiesFileSource props, String key, boolean defaultValue) {
        return props == null ? defaultValue : props.property(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer budget property: " + value, e);
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid long budget property: " + value, e);
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid numeric budget property: " + value, e);
        }
    }

    private CliBudgetConfig() {
    }
}
