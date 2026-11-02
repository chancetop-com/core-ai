package ai.core.session.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transcript channel is written on the turn thread, and cancelling a turn interrupts that thread.
 * An interrupted write closes the JDK channel, so the store must reopen instead of staying broken for
 * the rest of the session.
 *
 * @author stephen
 */
class TranscriptStoreTest {

    private static TranscriptRecord record(long seq) {
        var record = new TranscriptRecord();
        record.seq = seq;
        record.ts = seq;
        record.kind = TranscriptKinds.TURN_START;
        return record;
    }

    @TempDir
    Path dir;

    @Test
    void appendOnInterruptedThreadStillWritesAndKeepsTheInterrupt() throws InterruptedException {
        var store = TranscriptStore.open(dir, "interrupted-append");
        var failure = new AtomicReference<Throwable>();
        var interruptAfter = new AtomicBoolean();

        var writer = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                store.append(record(1));
                interruptAfter.set(Thread.currentThread().isInterrupted());
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "interrupted-writer");
        writer.start();
        writer.join(10_000);

        assertNull(failure.get(), () -> "append must survive a pending interrupt: " + failure.get());
        assertTrue(interruptAfter.get(), "the interrupt must be left pending for the cancelling side");
        assertEquals(1, store.records().size());

        store.append(record(2));
        assertEquals(2, store.records().size(), "the store must still be writable after the reopen");

        store.close();
    }

    @Test
    void appendReopensAChannelClosedByTheInterrupt() throws InterruptedException {
        var store = TranscriptStore.open(dir, "closed-append");
        store.append(record(1));

        var failure = new AtomicReference<Throwable>();
        var writer = new Thread(() -> {
            try {
                // the interrupt closes the channel even when nothing is blocking on it
                Thread.currentThread().interrupt();
                store.append(record(2));
                // a second write on the same thread hits the restored interrupt again
                store.append(record(3));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "interrupted-reopen");
        writer.start();
        writer.join(10_000);

        assertNull(failure.get(), () -> "every append must be retried on a fresh channel: " + failure.get());
        assertEquals(3, store.records().size());

        store.close();
    }
}
