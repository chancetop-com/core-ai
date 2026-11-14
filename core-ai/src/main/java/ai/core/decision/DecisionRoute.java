package ai.core.decision;

/**
 * Resolved decision route of one call. A publisher quality gate records this so a receipt proves which
 * provider and upstream model produced the verdict; an empty route means the provider could not report it.
 *
 * @author core-ai
 */
public record DecisionRoute(String provider, String upstreamModel, String routeFingerprint) {
    public static DecisionRoute empty() {
        return new DecisionRoute(null, null, null);
    }

    public boolean isComplete() {
        return provider != null && !provider.isBlank()
            && upstreamModel != null && !upstreamModel.isBlank()
            && routeFingerprint != null && !routeFingerprint.isBlank();
    }
}
