package ai.core.session;

/**
 * Status of a background agent run once it settles.
 *
 * @author stephen
 */
record TaskRunResult(String status, String result, String error) {
}
