package ai.core.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2AStreamPublisherTest {
    @Test
    void streamStartsAfterSubscriptionAndClosesOnTerminalStatus() throws InterruptedException {
        var started = new AtomicBoolean(false);
        var task = new Task();
        task.id = "task-1";
        task.status = TaskStatus.of(TaskState.WORKING);
        var terminal = new TaskStatusUpdateEvent();
        terminal.taskId = "task-1";
        terminal.contextId = "ctx-1";
        terminal.status = TaskStatus.of(TaskState.COMPLETED);

        var publisher = new A2AStreamPublisher(sink -> {
            started.set(true);
            sink.accept(StreamResponse.ofTask(task));
            sink.accept(StreamResponse.ofStatusUpdate(terminal));
        });
        var subscriber = new RecordingSubscriber();

        assertFalse(started.get());
        publisher.subscribe(subscriber);

        assertTrue(subscriber.awaitComplete());
        assertTrue(started.get());
        assertNull(subscriber.error);
        assertEquals(2, subscriber.events.size());
        assertEquals("task-1", subscriber.events.getFirst().task.id);
        assertEquals(TaskState.COMPLETED, subscriber.events.get(1).statusUpdate.status.state);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<A2AStreamEvent> {
        final List<A2AStreamEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch complete = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(A2AStreamEvent item) {
            events.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            complete.countDown();
        }

        @Override
        public void onComplete() {
            complete.countDown();
        }

        boolean awaitComplete() throws InterruptedException {
            return complete.await(5, TimeUnit.SECONDS);
        }
    }
}
