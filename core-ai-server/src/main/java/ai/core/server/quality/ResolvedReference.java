package ai.core.server.quality;

/**
 * One resolved configured reference. {@code name} is only populated when the caller may read the target;
 * otherwise the external report says "missing or unavailable" without exposing another owner's resource.
 *
 * @author core-ai
 */
public record ResolvedReference(String type,
                                String id,
                                String source,
                                String name,
                                QualityReferenceStatus status,
                                String reason,
                                String fieldPath,
                                int index) {
    public boolean available() {
        return status == QualityReferenceStatus.AVAILABLE;
    }
}
