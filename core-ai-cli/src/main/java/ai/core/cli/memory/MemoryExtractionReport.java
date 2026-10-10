package ai.core.cli.memory;

import java.util.List;

/**
 * Snapshot of one memory extraction run, reported to session clients so they can show what the
 * extraction agent kept: the knowledge files it created ({@code added}) and rewrote
 * ({@code updated}). Reads are deliberately not reported — the client shows what was extracted,
 * not what was re-read while merging.
 *
 * @author stephen
 */
public record MemoryExtractionReport(String runId, Phase phase, Trigger trigger, long durationMs,
                                     int cursor, List<String> added, List<String> updated, String note) {

    public enum Phase {
        STARTED, COMPLETED
    }

    /**
     * What scheduled the run: an explicit remember request, the turn counter, the idle timer,
     * a triggering compression, or a headless prompt session finishing.
     */
    public enum Trigger {
        EXPLICIT, TURN, IDLE, COMPRESSION, PROMPT
    }
}
