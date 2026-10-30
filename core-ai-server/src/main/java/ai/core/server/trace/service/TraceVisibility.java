package ai.core.server.trace.service;

import java.util.List;
import java.util.Set;

/**
 * Resolved trace read visibility of one caller: either unrestricted (admin / scope=all) or a
 * concrete set of user ids whose traces may be read. The caller is always part of the set.
 * <p>
 * {@link #applyTo(TraceListFilter, String)} is the single intersection point shared by the
 * trace web API and the self-harness tools: a requested user filter narrows the visible set and
 * never widens it (a user outside the set yields an empty result, not a 403).
 *
 * @author stephen
 */
public final class TraceVisibility {
    private static final TraceVisibility ALL = new TraceVisibility(true, Set.of());

    public static TraceVisibility all() {
        return ALL;
    }

    public static TraceVisibility of(Set<String> userIds) {
        return new TraceVisibility(false, Set.copyOf(userIds));
    }

    private final boolean unrestricted;
    private final Set<String> visibleUserIds;

    private TraceVisibility(boolean unrestricted, Set<String> visibleUserIds) {
        this.unrestricted = unrestricted;
        this.visibleUserIds = visibleUserIds;
    }

    public boolean unrestricted() {
        return unrestricted;
    }

    public boolean canSee(String userId) {
        return unrestricted || userId != null && visibleUserIds.contains(userId);
    }

    /**
     * @return {@code null} when unrestricted (no user filter), otherwise the exact visible set;
     * an empty list means "match nothing".
     */
    public List<String> scopedUserIdsOrNull() {
        return unrestricted ? null : List.copyOf(visibleUserIds);
    }

    public void applyTo(TraceListFilter filter, String requestedUserId) {
        if (unrestricted) return;
        if (requestedUserId != null && !requestedUserId.isEmpty()) {
            filter.userIds = canSee(requestedUserId) ? List.of(requestedUserId) : List.of();
        } else {
            filter.userIds = List.copyOf(visibleUserIds);
        }
        // restrict through the set only; leaving userId set would let an out-of-scope request bypass the set
        filter.userId = null;
    }
}
