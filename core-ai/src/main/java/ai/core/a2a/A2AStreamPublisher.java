package ai.core.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.TaskState;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Single-subscriber publisher that starts a remote agent stream after subscription.
 *
 * @author xander
 */
final class A2AStreamPublisher implements Flow.Publisher<A2AStreamEvent> {
    private static boolean shouldClose(StreamResponse response) {
        if (response == null || response.statusUpdate == null || response.statusUpdate.status == null) return false;
        var state = response.statusUpdate.status.state;
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED
                || state == TaskState.INPUT_REQUIRED
                || state == TaskState.AUTH_REQUIRED;
    }

    private final Consumer<Consumer<StreamResponse>> streamStarter;
    private final AtomicBoolean started = new AtomicBoolean(false);

    A2AStreamPublisher(Consumer<Consumer<StreamResponse>> streamStarter) {
        this.streamStarter = streamStarter;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super A2AStreamEvent> subscriber) {
        var publisher = new SubmissionPublisher<A2AStreamEvent>();
        publisher.subscribe(subscriber);
        if (!started.compareAndSet(false, true)) {
            publisher.closeExceptionally(new IllegalStateException("A2A stream supports one subscriber"));
            return;
        }
        try {
            streamStarter.accept(response -> {
                publisher.submit(A2AStreamEvent.ofResponse(response));
                if (shouldClose(response)) {
                    publisher.close();
                }
            });
        } catch (Exception e) {
            publisher.closeExceptionally(e);
        }
    }
}
