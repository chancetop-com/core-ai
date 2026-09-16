package ai.core.context;

/**
 * Compression tuning resolved from configuration instead of the built-in defaults.
 * Every field may be null, meaning "keep the default".
 *
 * @author xander
 */
public record CompressionConfig(Boolean enabled,
                                Double triggerThreshold,
                                Integer keepRecentTurns,
                                Integer keepMinTokens,
                                Integer contextWindowTokens,
                                String summaryModel) {
}
