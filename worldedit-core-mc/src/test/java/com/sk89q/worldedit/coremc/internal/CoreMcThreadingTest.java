package com.sk89q.worldedit.coremc.internal;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests the thread-ownership and cancellation guarantees needed by native world writes. */
@Timeout(10)
class CoreMcThreadingTest {

    @Test
    void workerRunsNativeOperationOnTheServerOwner() throws Exception {
        QueuedServer fixture = new QueuedServer();
        Thread owner = Thread.currentThread();
        CompletableFuture<Thread> result = CompletableFuture.supplyAsync(
                () -> CoreMcThreading.call(fixture.server, Thread::currentThread)
        );

        fixture.runQueuedOperation();

        assertSame(owner, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void checkedNativeFailureReachesWorkerWithoutLosingItsTypeOrIdentity() throws Exception {
        QueuedServer fixture = new QueuedServer();
        IOException expected = new IOException("native I/O failure");
        CompletableFuture<IOException> result = CompletableFuture.supplyAsync(() -> {
            try {
                CoreMcThreading.call(fixture.server, () -> {
                    throw expected;
                });
                throw new AssertionError("operation must fail");
            } catch (IOException failure) {
                return failure;
            }
        });

        fixture.runQueuedOperation();

        assertSame(expected, result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void shutdownCancelsAQueuedWriteBeforeTheOwnerDrainsItsQueue() throws Exception {
        QueuedServer fixture = new QueuedServer();
        AtomicBoolean mutatedWorld = new AtomicBoolean();
        CompletableFuture<Void> result = CompletableFuture.runAsync(() -> CoreMcThreading.call(
                fixture.server,
                () -> {
                    mutatedWorld.set(true);
                    return null;
                }
        ));

        fixture.awaitQueuedOperation();
        fixture.running.set(false);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> result.get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, failure.getCause());

        // Simulate the owner's late queue drain after the edit worker has already failed.
        fixture.queued.get().run();
        assertFalse(mutatedWorld.get(), "cancelled native writes must not reach an old world");
    }

    @Test
    void interruptCancelsQueuedWriteAndPreservesWorkerInterruptStatus() throws Exception {
        QueuedServer fixture = new QueuedServer();
        AtomicBoolean mutatedWorld = new AtomicBoolean();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CompletableFuture<Throwable> result = new CompletableFuture<>();
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            try {
                CoreMcThreading.call(fixture.server, () -> {
                    mutatedWorld.set(true);
                    return null;
                });
                result.complete(new AssertionError("interrupted operation must fail"));
            } catch (Throwable failure) {
                interruptPreserved.set(Thread.currentThread().isInterrupted());
                result.complete(failure);
            }
        });

        fixture.awaitQueuedOperation();
        worker.interrupt();

        assertInstanceOf(IllegalStateException.class, result.get(5, TimeUnit.SECONDS));
        assertTrue(interruptPreserved.get());
        fixture.queued.get().run();
        assertFalse(mutatedWorld.get(), "interrupted edits must not leave delayed writes");
    }

    @Test
    void ownerCallbackRunsInlineWithoutEnqueueingOrWaiting() throws Exception {
        QueuedServer fixture = new QueuedServer();
        fixture.ownerThread.set(Thread.currentThread());

        int result = CoreMcThreading.call(fixture.server, () -> 42);
        assertEquals(42, result);
        assertEquals(1, fixture.accepted.getCount(), "owner callbacks must not enqueue another task");
    }

    private static final class QueuedServer {

        private final MinecraftServer server = mock(MinecraftServer.class);
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicReference<Thread> ownerThread = new AtomicReference<>();
        private final AtomicReference<Runnable> queued = new AtomicReference<>();
        private final CountDownLatch accepted = new CountDownLatch(1);

        private QueuedServer() {
            when(server.isSameThread()).thenAnswer(
                    ignored -> Thread.currentThread() == ownerThread.get()
            );
            when(server.isRunning()).thenAnswer(ignored -> running.get());
            when(server.isStopped()).thenAnswer(ignored -> !running.get());
            doAnswer(invocation -> {
                queued.set(invocation.getArgument(0));
                accepted.countDown();
                return null;
            }).when(server).execute(any(Runnable.class));
        }

        private void awaitQueuedOperation() throws InterruptedException {
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "worker must submit its native operation");
        }

        private void runQueuedOperation() throws InterruptedException {
            awaitQueuedOperation();
            ownerThread.set(Thread.currentThread());
            queued.get().run();
        }
    }
}
