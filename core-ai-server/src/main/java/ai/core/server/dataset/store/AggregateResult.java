package ai.core.server.dataset.store;

import java.util.List;

/**
 * Column names followed by rows; a value is null when the group has no value for that metric.
 *
 * @author stephen
 */
public record AggregateResult(List<String> columns, List<List<Object>> rows) {
}
