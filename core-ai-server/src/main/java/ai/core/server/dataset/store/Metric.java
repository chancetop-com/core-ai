package ai.core.server.dataset.store;

/**
 * One aggregation output column: {@code fn} is SUM, AVG, MIN, MAX, COUNT, or LAST.
 *
 * @author stephen
 */
public record Metric(String field, String fn) {
}
