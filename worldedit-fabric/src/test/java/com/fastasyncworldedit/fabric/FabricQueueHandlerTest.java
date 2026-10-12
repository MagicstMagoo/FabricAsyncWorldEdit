package com.fastasyncworldedit.fabric;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FabricQueueHandlerTest {

    private final AtomicReference<MinecraftServer> currentServer = new AtomicReference<>();
    private MinecraftServer server;
    private FabricQueueHandler queue;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        currentServer.set(server);
        // QueueHandler installs its tick callback through TaskManager during construction.
        new FabricTaskManager(currentServer::get, Runnable::run);
        queue = new FabricQueueHandler(currentServer::get);
    }

    @Test
    void workersReceiveResultsOnTheServerTickWithPriorityOverIdleWork() throws Exception {
        List<String> calls = new ArrayList<>();
        Future<String> idle = queue.syncWhenFree(() -> calls.add("idle"), "idle result");
        Future<String> urgent = queue.sync((Supplier<String>) () -> {
            calls.add("urgent");
            return "urgent result";
        });
        assertFalse(urgent.isDone());
        assertTrue(calls.isEmpty());

        when(server.isSameThread()).thenReturn(true);
        queue.run();
        queue.run();
        assertEquals("urgent result", urgent.get(1, TimeUnit.SECONDS));
        assertEquals("idle result", idle.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("urgent", "idle"), calls);
    }

    @Test
    void stopCancelsWaitingWorkersAndOldWorkCannotRunInTheNextWorld() throws Exception {
        AtomicInteger oldWorldCalls = new AtomicInteger();
        Future<?> waiting = queue.sync((Runnable) oldWorldCalls::incrementAndGet);
        Future<?> idle = queue.syncWhenFree((Runnable) oldWorldCalls::incrementAndGet);
        queue.serverStopping(server);
        assertTrue(waiting.isCancelled());
        assertTrue(idle.isCancelled());
        assertThrows(CancellationException.class, () -> waiting.get(1, TimeUnit.SECONDS));
        assertTrue(queue.sync((Runnable) oldWorldCalls::incrementAndGet).isCancelled());

        MinecraftServer secondServer = mock(MinecraftServer.class);
        when(secondServer.isSameThread()).thenReturn(true);
        currentServer.set(secondServer);
        queue.serverStarted(secondServer);
        queue.run();
        assertEquals(0, oldWorldCalls.get());
        assertEquals("new world", queue.sync((Callable<String>) () -> "new world").get());
    }

    @Test
    void immediateServerCallsReturnCompletedFuturesAndPropagateFailures() throws Exception {
        when(server.isSameThread()).thenReturn(true);
        Future<Integer> success = queue.sync((Supplier<Integer>) () -> 42);
        assertTrue(success.isDone());
        assertEquals(42, success.get());
        Future<?> failure = queue.sync((Callable<Void>) () -> {
            throw new IllegalStateException("native access failed");
        });
        ExecutionException thrown = assertThrows(ExecutionException.class, failure::get);
        assertEquals("native access failed", thrown.getCause().getMessage());
    }
}
