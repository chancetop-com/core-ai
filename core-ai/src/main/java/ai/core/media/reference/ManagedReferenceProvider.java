package ai.core.media.reference;

/**
 * Marker for a {@code MediaProvider} that resolves symbolic references itself, after routing, when the
 * destination provider is known. Tools hand such a provider unresolved references and do no I/O.
 * <p>
 * A provider without this marker is talked to directly (no gateway, no media job index), so the tool
 * still has to materialise external URLs locally and cannot honour a {@code media_id} at all.
 *
 * @author stephen
 */
public interface ManagedReferenceProvider {
}
