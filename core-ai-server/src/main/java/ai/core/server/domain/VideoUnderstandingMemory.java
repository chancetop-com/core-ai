package ai.core.server.domain;

import java.util.Optional;

/**
 * Remembers what a video-understanding model said about a file, keyed by the file behind a session attachment
 * reference. A rendered clip is immutable, so the same question about the same file has the same answer: recording
 * it lets a later turn (or a later session) reuse the observation instead of paying for the model again, and lets a
 * domain (the drama ledger) show the recorded observations next to the file.
 *
 * @author stephen
 */
public interface VideoUnderstandingMemory {
    /** A recorded answer to exactly this question about the file behind the reference, when there is one. */
    Optional<Recalled> recall(SessionAttachmentRef reference, String model, String question);

    /** Records the model's answer; a no-op when the reference is not backed by anything this memory tracks. */
    void record(SessionAttachmentRef reference, String model, String question, String answer);

    record Recalled(String answer, String model) { }
}
