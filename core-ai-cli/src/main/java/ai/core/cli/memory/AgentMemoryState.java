package ai.core.cli.memory;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Extraction state owned by one live agent. The app-server engine serves several sessions in the
 * same process, so the cursor, turn counter and idle clock must never be shared between them —
 * otherwise a remember request in one session extracts another session's conversation and its
 * activity is reported there.
 */
final class AgentMemoryState {
    final AtomicInteger turnCount = new AtomicInteger(0);
    final AtomicReference<Instant> lastActivity = new AtomicReference<>(Instant.now());
    final AtomicBoolean agentBusy = new AtomicBoolean(false);
    final AtomicBoolean compressionListenerAttached = new AtomicBoolean(false);
    final AtomicInteger extractionCursor = new AtomicInteger(-1);
    final AtomicInteger extractionTargetCount = new AtomicInteger(-1);
}
