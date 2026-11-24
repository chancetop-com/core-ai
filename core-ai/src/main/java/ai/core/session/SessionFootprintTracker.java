package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AgentFootprint;

/**
 * Keeps the last footprint of a session and its high-water marks. The turn end refreshes it, and the
 * runtime stats read refreshes it again when the snapshot is older than the long-turn interval, so a
 * 28-minute turn no longer stays invisible until it ends.
 *
 * @author stephen
 */
public class SessionFootprintTracker {
    public static final long DEFAULT_REFRESH_INTERVAL_MS = 30_000;

    private final Agent agent;
    private volatile AgentFootprint footprint = AgentFootprint.EMPTY;
    private volatile long refreshedAtMillis;
    private volatile long peakEstimatedBytes;
    private volatile long peakInlineImageBytes;
    private volatile long peakContextTokens;
    private volatile long peakAtMillis;

    public SessionFootprintTracker(Agent agent) {
        this.agent = agent;
    }

    public AgentFootprint footprint() {
        return footprint;
    }

    public AgentFootprint refresh() {
        synchronized (this) {
            try {
                var current = agent.footprint();
                footprint = current;
                refreshedAtMillis = System.currentTimeMillis();
                updatePeaks(current, refreshedAtMillis);
            } catch (RuntimeException e) {
                // a broken calculation must not fail the turn or the stats job
                return footprint;
            }
            return footprint;
        }
    }

    public AgentFootprint refreshIfDue() {
        return refreshIfDue(DEFAULT_REFRESH_INTERVAL_MS);
    }

    public AgentFootprint refreshIfDue(long intervalMillis) {
        if (System.currentTimeMillis() - refreshedAtMillis < intervalMillis) return footprint;
        return refresh();
    }

    public long peakEstimatedBytes() {
        return peakEstimatedBytes;
    }

    public long peakInlineImageBytes() {
        return peakInlineImageBytes;
    }

    public long peakContextTokens() {
        return peakContextTokens;
    }

    public long peakAtMillis() {
        return peakAtMillis;
    }

    private void updatePeaks(AgentFootprint current, long now) {
        var estimated = current.estimatedBytes();
        var inline = current.inlineImageBytes();
        var tokens = current.contextTokens();
        var changed = false;
        if (estimated > peakEstimatedBytes) {
            peakEstimatedBytes = estimated;
            changed = true;
        }
        if (inline > peakInlineImageBytes) {
            peakInlineImageBytes = inline;
            changed = true;
        }
        if (tokens > peakContextTokens) {
            peakContextTokens = tokens;
            changed = true;
        }
        if (changed) peakAtMillis = now;
    }
}
