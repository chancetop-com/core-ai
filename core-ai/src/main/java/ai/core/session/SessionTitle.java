package ai.core.session;

/**
 * Title plus the load state of the snapshot it came from. {@code text} is null when the snapshot has
 * no user message, cannot be read, or does not exist.
 *
 * @author stephen
 */
public record SessionTitle(String text, SessionLoadState state) {
}
