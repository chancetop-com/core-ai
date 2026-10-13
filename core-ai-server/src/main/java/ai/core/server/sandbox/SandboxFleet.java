package ai.core.server.sandbox;

/**
 * Cluster-level sandbox facts a single pod cannot see on its own: how many sandboxes the operator is
 * actually holding (claims from the warm pool vs directly created CRs), how old the oldest one is, and
 * how creations are going. Counts and ages are deliberately raw — the point is to make an unbounded
 * pile or a stuck deletion visible, which is what the orphan-CR incident lacked.
 *
 * @author stephen
 */
public class SandboxFleet {
    public int claims;
    public int directCrs;
    public Long oldestClaimAgeSeconds;
    public Long oldestCrAgeSeconds;
    public long createsTotal;
    public long createsFailedTotal;
    public Long lastCreateMs;

    public int live() {
        return claims + directCrs;
    }
}
