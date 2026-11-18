package ai.core.sse.internal;

import core.framework.internal.web.request.RequestImpl;
import io.undertow.server.HttpServerExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.xnio.XnioIoThread;
import org.xnio.channels.StreamSinkChannel;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatchedChannelImplTest {
    private HttpServerExchange exchange;
    private StreamSinkChannel sink;
    private PatchedChannelImpl<Object> channel;

    @BeforeEach
    void setUp() {
        exchange = Mockito.mock(HttpServerExchange.class);
        sink = Mockito.mock(StreamSinkChannel.class);
        var ioThread = Mockito.mock(XnioIoThread.class);
        var request = Mockito.mock(RequestImpl.class);
        Mockito.when(request.path()).thenReturn("/api/sessions/events");
        Mockito.when(request.clientIP()).thenReturn("127.0.0.1");
        Mockito.when(sink.getIoThread()).thenReturn(ioThread);
        // run io thread tasks inline so close() is observable from the test thread
        Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(ioThread).execute(ArgumentMatchers.any(Runnable.class));

        channel = new PatchedChannelImpl<>(exchange, sink, new PatchedServerSentEventContextImpl<>(), null, "ref", request);
        channel.queue.add("data: 1\n\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void frameIncludesIdLineWhenPresent() {
        assertEquals("id: 7\nevent: output\ndata: abc\n\n", PatchedChannelImpl.frame("7", "output", "abc"));
    }

    @Test
    void frameOmitsIdLineWhenIdIsNull() {
        assertEquals("event: output\ndata: abc\n\n", PatchedChannelImpl.frame(null, "output", "abc"));
    }

    @Test
    void frameOmitsIdLineWhenIdIsBlank() {
        assertEquals("event: output\ndata: abc\n\n", PatchedChannelImpl.frame("   ", "output", "abc"));
    }

    @Test
    void writeListenerStopsWhenChannelMakesNoProgress() throws Exception {
        var writeCalls = new AtomicInteger();
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenAnswer(invocation -> {
            // socket send buffer is full, while xnio flush() still reports the buffer as flushed
            if (writeCalls.incrementAndGet() > 5) throw new IllegalStateException("write loop kept writing after the channel made no progress");
            return 0;
        });
        Mockito.when(sink.flush()).thenReturn(Boolean.TRUE);

        channel.writeListener.handleEvent(sink);

        assertEquals(1, writeCalls.get(), "write loop must stop as soon as the channel made no progress");
        Mockito.verify(sink).resumeWrites();
        Mockito.verify(sink, Mockito.never()).suspendWrites();
    }

    @Test
    void writeListenerSuspendsWritesWhenAllBytesAreWritten() throws Exception {
        stubAcceptedWrites();

        channel.writeListener.handleEvent(sink);

        Mockito.verify(sink).suspendWrites();
        Mockito.verify(sink, Mockito.never()).resumeWrites();
        assertTrue(channel.queue.isEmpty());
    }

    @Test
    void writeListenerResumesWritesWhenChannelIsNotFlushed() throws Exception {
        stubAcceptedWrites();
        Mockito.when(sink.flush()).thenReturn(Boolean.FALSE);

        channel.writeListener.handleEvent(sink);

        Mockito.verify(sink).resumeWrites();
        Mockito.verify(sink, Mockito.never()).suspendWrites();
    }

    @Test
    void shutdownGivesUpOnLockHeldByStuckWriter() throws Exception {
        var writerStarted = new CountDownLatch(1);
        var writeCalls = new AtomicInteger();
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenAnswer(invocation -> {
            // the guard keeps a regression to this loop from hanging the build, as the io thread would spin forever
            if (writeCalls.incrementAndGet() > 3) throw new IllegalStateException("write loop kept writing after the channel made no progress");
            writerStarted.countDown();
            TimeUnit.SECONDS.sleep(1);  // hold the lock longer than the shutdown lock timeout
            return 0;
        });
        Mockito.when(sink.flush()).thenReturn(Boolean.TRUE);

        var writer = new Thread(() -> channel.writeListener.handleEvent(sink));
        writer.start();
        assertTrue(writerStarted.await(10, TimeUnit.SECONDS), "writer should hold the channel lock");

        long start = System.nanoTime();
        channel.shutdown();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(elapsed < 1_500, "shutdown must give up on a stuck writer instead of blocking, elapsed=" + elapsed);
        Mockito.verify(exchange).endExchange();
        writer.join(30_000);
    }

    @Test
    void shutdownEndsExchangeAndClearsQueueWhenLockIsFree() {
        channel.shutdown();

        Mockito.verify(exchange).endExchange();
        assertTrue(channel.queue.isEmpty());
    }

    @Test
    void closeDrainsQueuedEventsBeforeEndingExchange() throws Exception {
        channel.queue.add("data: 2\n\n".getBytes(StandardCharsets.UTF_8));
        stubAcceptedWrites();

        channel.close();

        InOrder inOrder = Mockito.inOrder(sink, exchange);
        inOrder.verify(sink, Mockito.atLeastOnce()).write(ArgumentMatchers.any(ByteBuffer.class));
        inOrder.verify(exchange).endExchange();
        assertTrue(channel.queue.isEmpty());
        Mockito.verify(sink, Mockito.never()).suspendWrites();
    }

    @Test
    void closeWaitsForTheWritableEventWhenTheSocketBufferIsFull() throws Exception {
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenReturn(0);
        Mockito.when(sink.flush()).thenReturn(Boolean.TRUE);

        channel.close();

        Mockito.verify(sink).resumeWrites();
        Mockito.verify(exchange, Mockito.never()).endExchange();

        stubAcceptedWrites();
        channel.writeListener.handleEvent(sink);

        Mockito.verify(exchange).endExchange();
    }

    @Test
    void closeEndsExchangeImmediatelyWhenNothingIsQueued() throws Exception {
        channel.queue.clear();

        channel.close();

        Mockito.verify(exchange).endExchange();
        Mockito.verify(sink, Mockito.never()).write(ArgumentMatchers.any(ByteBuffer.class));
    }

    @Test
    void rejectsNewEventsAfterClose() throws Exception {
        channel.queue.clear();

        channel.close();

        assertFalse(channel.sendBytes("data: late\n\n".getBytes(StandardCharsets.UTF_8)));
        Mockito.verify(sink, Mockito.never()).write(ArgumentMatchers.any(ByteBuffer.class));
    }

    private void stubAcceptedWrites() throws Exception {
        Mockito.when(sink.write(ArgumentMatchers.any(ByteBuffer.class))).thenAnswer(invocation -> {
            var buffer = invocation.getArgument(0, ByteBuffer.class);
            int remaining = buffer.remaining();
            buffer.position(buffer.limit());
            return remaining;
        });
        Mockito.when(sink.flush()).thenReturn(Boolean.TRUE);
    }
}
