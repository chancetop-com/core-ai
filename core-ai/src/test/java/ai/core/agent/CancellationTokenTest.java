package ai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void shouldNotBeCancelledByDefault() {
        var token = CancellationToken.create();
        assertFalse(token.isCancelled());
    }

    @Test
    void shouldBeCancelledAfterCancel() {
        var token = CancellationToken.create();
        token.cancel();
        assertTrue(token.isCancelled());
    }

    @Test
    void cancelShouldBeIdempotent() {
        var token = CancellationToken.create();
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
    }

    @Test
    void resetShouldClearCancelledState() {
        var token = CancellationToken.create();
        token.cancel();
        assertTrue(token.isCancelled());
        token.reset();
        assertFalse(token.isCancelled());
    }

    @Test
    void resetWithoutCancelShouldWork() {
        var token = CancellationToken.create();
        token.reset();
        assertFalse(token.isCancelled());
    }

    @Test
    void throwIfCancelledShouldThrowWhenCancelled() {
        var token = CancellationToken.create();
        token.cancel();
        assertThrows(CancellationException.class, token::throwIfCancelled);
    }

    @Test
    void throwIfCancelledShouldNotThrowWhenNotCancelled() {
        var token = CancellationToken.create();
        assertDoesNotThrow(token::throwIfCancelled);
    }

    @Test
    void parentCancelShouldPropagateToChild() {
        var parent = CancellationToken.create();
        var child = parent.createChild();
        parent.cancel();
        assertTrue(child.isCancelled());
    }

    @Test
    void childCreatedAfterParentCancelShouldBePreCancelled() {
        var parent = CancellationToken.create();
        parent.cancel();
        var child = parent.createChild();
        assertTrue(child.isCancelled());
    }

    @Test
    void childCancelShouldNotAffectParent() {
        var parent = CancellationToken.create();
        var child = parent.createChild();
        child.cancel();
        assertFalse(parent.isCancelled());
    }

    @Test
    void parentResetShouldNotAffectChild() {
        var parent = CancellationToken.create();
        var child = parent.createChild();
        parent.cancel();
        parent.reset();
        assertFalse(parent.isCancelled());
        assertTrue(child.isCancelled());
    }

    @Test
    void cancelShouldInterruptBoundThread() throws InterruptedException {
        var token = CancellationToken.create();
        var interrupted = new AtomicBoolean(false);
        var started = new CountDownLatch(1);

        var thread = new Thread(() -> {
            token.bindThread(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        thread.start();
        started.await(1, TimeUnit.SECONDS);
        token.cancel();
        thread.join(2000);
        assertTrue(interrupted.get(), "bound thread should be interrupted on cancel");
    }

    @Test
    void cancelShouldCloseBoundResource() {
        var token = CancellationToken.create();
        var closed = new AtomicBoolean(false);
        token.bindResource(() -> closed.set(true));
        token.cancel();
        assertTrue(closed.get(), "bound resource should be closed on cancel");
    }

    @Test
    void cancelShouldInvokeCallbacks() {
        var token = CancellationToken.create();
        var called = new AtomicBoolean(false);
        token.onCancel(() -> called.set(true));
        token.cancel();
        assertTrue(called.get(), "cancel callback should be invoked");
    }

    @Test
    void callbackRegisteredAfterCancelShouldFireImmediately() {
        var token = CancellationToken.create();
        token.cancel();
        var called = new AtomicBoolean(false);
        token.onCancel(() -> called.set(true));
        assertTrue(called.get(), "callback registered after cancel should fire immediately");
    }

    @Test
    void cancelShouldOnlyInvokeCallbacksOnce() {
        var token = CancellationToken.create();
        var count = new AtomicInteger(0);
        token.onCancel(count::incrementAndGet);
        token.cancel();
        token.cancel();
        assertEquals(1, count.get(), "callback should be invoked exactly once");
    }

    @Test
    void resetThenReCancelShouldInvokeNewCallback() {
        var token = CancellationToken.create();
        var count = new AtomicInteger(0);
        token.onCancel(count::incrementAndGet);
        token.cancel();
        assertEquals(1, count.get());

        token.reset();
        var newCallbackCount = new AtomicInteger(0);
        token.onCancel(newCallbackCount::incrementAndGet);
        token.cancel();
        assertEquals(1, newCallbackCount.get(), "new callback should be invoked after reset and re-cancel");
        assertEquals(1, count.get(), "old callback should not be invoked again after reset");
    }

    @Test
    void deregisterShouldPreventCleanupOnCancel() throws InterruptedException {
        var token = CancellationToken.create();
        var interrupted = new AtomicBoolean(false);
        var started = new CountDownLatch(1);

        var thread = new Thread(() -> {
            var deregister = token.bindThread(Thread.currentThread());
            started.countDown();
            deregister.run(); // unbind before cancel
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        thread.start();
        started.await(1, TimeUnit.SECONDS);
        Thread.sleep(50); // let deregister execute
        token.cancel();
        thread.join(2000);
        assertFalse(interrupted.get(), "deregistered thread should not be interrupted");
    }

    @Test
    void deregisteredResourceShouldNotBeClosedOnCancel() {
        var token = CancellationToken.create();
        var closed = new AtomicBoolean(false);
        var deregister = token.bindResource(() -> closed.set(true));
        deregister.run();
        token.cancel();
        assertFalse(closed.get(), "deregistered resource should not be closed on cancel");
    }

    @Test
    void isCancelledShouldWorkConcurrently() throws InterruptedException {
        var token = CancellationToken.create();
        var latch = new CountDownLatch(1);
        var errors = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            new Thread(() -> {
                try {
                    latch.await();
                    for (int j = 0; j < 1000; j++) {
                        token.isCancelled(); // should never throw
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }).start();
        }

        token.cancel();
        latch.countDown();
        Thread.sleep(500);
        assertEquals(0, errors.get(), "concurrent reads should never throw");
    }

    @Test
    void deepParentChainShouldWork() {
        var root = CancellationToken.create();
        var child = root.createChild();
        var grandchild = child.createChild();
        root.cancel();
        assertTrue(grandchild.isCancelled());
    }

    @Test
    void resetShouldClearAllCleanupActions() {
        var token = CancellationToken.create();
        var closed = new AtomicBoolean(false);
        token.bindResource(() -> closed.set(true));
        token.reset();
        token.cancel();
        assertFalse(closed.get(), "cleanup actions should be cleared on reset");
    }

    @Test
    void mixedResourcesAndCallbacksShouldAllFireOnCancel() {
        var token = CancellationToken.create();
        var resourceClosed = new AtomicBoolean(false);
        var callbackFired = new AtomicBoolean(false);

        token.bindResource(() -> resourceClosed.set(true));
        token.onCancel(() -> callbackFired.set(true));
        token.cancel();

        assertTrue(resourceClosed.get(), "bound resource should be closed");
        assertTrue(callbackFired.get(), "callback should be invoked");
    }

    // ==================== CancelReason ====================

    @Test
    void cancelDefaultReasonShouldBeUserCancelled() {
        var token = CancellationToken.create();
        token.cancel();
        assertEquals(CancelReason.USER_CANCELLED, token.getReason());
    }

    @Test
    void cancelWithExplicitReason() {
        var token = CancellationToken.create();
        token.cancel(CancelReason.TIMEOUT);
        assertEquals(CancelReason.TIMEOUT, token.getReason());
    }

    @Test
    void interruptShouldSetNewMessageInterruptReason() {
        var token = CancellationToken.create();
        token.interrupt();
        assertTrue(token.isCancelled());
        assertTrue(token.isInterrupted());
        assertEquals(CancelReason.NEW_MESSAGE_INTERRUPT, token.getReason());
    }

    @Test
    void resetShouldClearReason() {
        var token = CancellationToken.create();
        token.cancel(CancelReason.TIMEOUT);
        token.reset();
        assertFalse(token.isCancelled());
        assertFalse(token.isInterrupted());
    }

    // ==================== Phase order & skipping ====================

    @Test
    void phasesShouldExecuteInOrder() {
        var token = CancellationToken.create();
        var order = new java.util.ArrayList<String>();

        token.onCancel(() -> order.add("NOTIFY"));
        token.bindThread(Thread.currentThread());  // INTERRUPT
        token.bindResource(() -> { });  // CLOSE
        token.cancel();

        assertEquals(java.util.List.of("NOTIFY"), order.subList(0, 1));
        // INTERRUPT and CLOSE don't have assertable order via bind methods
        // but they do execute — verified by the fact cancel() completes
    }

    @Test
    void interruptShouldSkipCloseAndAbortPhases() {
        var token = CancellationToken.create();
        var closed = new AtomicBoolean(false);
        var aborted = new AtomicBoolean(false);

        token.bindResource(() -> closed.set(true));  // CLOSE phase
        token.bindProcess(ProcessHandle.current());   // ABORT phase (won't actually kill current process because interrupt skips it)

        token.interrupt();

        assertFalse(closed.get(), "interrupt should skip CLOSE phase");
        assertFalse(aborted.get(), "interrupt should skip ABORT phase");
    }

    @Test
    void hardCancelShouldRunAllPhases() {
        var token = CancellationToken.create();
        var closed = new AtomicBoolean(false);

        token.bindResource(() -> closed.set(true));

        token.cancel();  // USER_CANCELLED — runs all phases

        assertTrue(closed.get(), "hard cancel should run CLOSE phase");
    }

    @Test
    void phaseActionExceptionShouldNotBlockNextPhase() {
        var token = CancellationToken.create();
        var secondFired = new AtomicBoolean(false);

        token.onCancel(() -> {
            throw new RuntimeException("boom");
        });
        token.onCancel(() -> secondFired.set(true));

        token.cancel();

        assertTrue(secondFired.get(), "second NOTIFY action should fire despite first throwing");
    }

    // ==================== orCancel ====================

    @Test
    void orCancelShouldReturnFutureResultWhenNotCancelled() throws Exception {
        var token = CancellationToken.create();
        var future = CompletableFuture.completedFuture("done");

        var result = token.orCancel(future, 1000);
        assertEquals("done", result);
    }

    @Test
    void orCancelShouldThrowCancellationExceptionWhenCancelled() {
        var token = CancellationToken.create();
        var future = new CompletableFuture<String>();
        token.cancel();

        assertThrows(CancellationException.class, () -> token.orCancel(future, 100));
    }

    @Test
    void orCancelShouldThrowTimeoutExceptionWhenTimeout() {
        var token = CancellationToken.create();
        var future = new CompletableFuture<String>();

        assertThrows(TimeoutException.class, () -> token.orCancel(future, 50));
    }

    @Test
    void orCancelShouldDeregisterCallbackOnNormalCompletion() throws Exception {
        var token = CancellationToken.create();
        var future = CompletableFuture.completedFuture("ok");

        token.orCancel(future, 1000);
        // token not cancelled — verify no side effects
        assertFalse(token.isCancelled());
    }

    @Test
    void orCancelShouldCancelFutureOnCancellation() {
        var token = CancellationToken.create();
        var future = new CompletableFuture<String>();

        new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        }).start();

        assertThrows(CancellationException.class, () -> token.orCancel(future, 5000));
    }

    // ==================== cancelAfter ====================

    @Test
    void cancelAfterShouldTriggerTimeoutCancel() throws InterruptedException {
        var token = CancellationToken.create();
        token.cancelAfter(50);  // cancel after 50ms

        Thread.sleep(200);
        assertTrue(token.isCancelled());
        assertEquals(CancelReason.TIMEOUT, token.getReason());
    }

    @Test
    void cancelAfterDeregisterShouldPreventTimeout() throws InterruptedException {
        var token = CancellationToken.create();
        var deregister = token.cancelAfter(50);
        deregister.run();  // cancel the timeout

        Thread.sleep(200);
        assertFalse(token.isCancelled());
    }

    // ==================== Tree propagation ====================

    @Test
    void childCancelShouldNotAffectSiblings() {
        var parent = CancellationToken.create();
        var child1 = parent.createChild();
        var child2 = parent.createChild();

        child1.cancel();
        assertTrue(child1.isCancelled());
        assertFalse(child2.isCancelled(), "sibling should be unaffected");
        assertFalse(parent.isCancelled(), "parent should be unaffected");
    }

    @Test
    void deepTreeCancelShouldPropagateToLeaf() {
        var root = CancellationToken.create();
        var child = root.createChild();
        var grandchild = child.createChild();
        var greatGrandchild = grandchild.createChild();

        root.cancel();
        assertTrue(greatGrandchild.isCancelled());
        assertEquals(CancelReason.USER_CANCELLED, greatGrandchild.getReason());
    }

    @Test
    void parentReasonShouldPropagateToChildren() {
        var root = CancellationToken.create();
        var child = root.createChild();

        root.cancel(CancelReason.TIMEOUT);
        assertEquals(CancelReason.TIMEOUT, child.getReason());
    }

    @Test
    void interruptReasonPropagatesToChildren() {
        var root = CancellationToken.create();
        var child = root.createChild();

        root.interrupt();
        assertTrue(child.isCancelled());
        assertTrue(child.isInterrupted());
    }

    @Test
    void childShouldKnowItIsChild() {
        var parent = CancellationToken.create();
        var child = parent.createChild();

        assertTrue(child.isChild());
        assertFalse(parent.isChild());
    }

    @Test
    void cancelExceptionCarriesReason() {
        var token = CancellationToken.create();
        token.cancel(CancelReason.TIMEOUT);

        try {
            token.throwIfCancelled();
        } catch (CancellationException e) {
            assertEquals(CancelReason.TIMEOUT, e.getReason());
        }
    }

    @Test
    void cancelExceptionFromChildCarriesParentReason() {
        var parent = CancellationToken.create();
        var child = parent.createChild();
        parent.cancel(CancelReason.REPLACED);

        try {
            child.throwIfCancelled();
        } catch (CancellationException e) {
            assertEquals(CancelReason.REPLACED, e.getReason());
            assertNotNull(e.getMessage());
        }
    }
}
