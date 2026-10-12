package com.fastasyncworldedit.fabric;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FabricTaskManagerTest {

    private final AtomicReference<MinecraftServer> currentServer = new AtomicReference<>();
    private final Queue<Runnable> asyncTasks = new ArrayDeque<>();
    private MinecraftServer server;
    private FabricTaskManager manager;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        currentServer.set(server);
        manager = new FabricTaskManager(currentServer::get, asyncTasks::add);
    }

    @Test
    void delaysAndRepeatsAdvanceOnlyOnServerTicks() {
        List<String> calls = new ArrayList<>();
        manager.later(() -> calls.add("later"), 3);
        manager.repeat(() -> calls.add("repeat"), 2);
        manager.task(() -> calls.add("next tick"));
        assertTrue(calls.isEmpty());

        manager.onEndTick(server);
        assertEquals(List.of("next tick"), calls);
        manager.onEndTick(server);
        assertEquals(List.of("next tick", "repeat"), calls);
        manager.onEndTick(server);
        assertEquals(List.of("next tick", "repeat", "later"), calls);
        manager.onEndTick(server);
        assertEquals(List.of("next tick", "repeat", "later", "repeat"), calls);
    }

    @Test
    void zeroIntervalsAndDelaysMeanNextTick() {
        AtomicInteger calls = new AtomicInteger();
        int repeating = manager.repeat(calls::incrementAndGet, 0);
        manager.later(calls::incrementAndGet, 0);
        assertEquals(0, calls.get());
        manager.onEndTick(server);
        assertEquals(2, calls.get());
        manager.cancel(repeating);
        manager.onEndTick(server);
        assertEquals(2, calls.get());
    }

    @Test
    void cancellationAlsoSkipsTasksAlreadySelectedForTheCurrentTick() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger toCancel = new AtomicInteger();
        manager.repeat(() -> manager.cancel(toCancel.get()), 1);
        toCancel.set(manager.repeat(calls::incrementAndGet, 1));
        manager.onEndTick(server);
        assertEquals(0, calls.get());
    }

    @Test
    void asynchronousRepeatsStayOffTheTickThreadAndDoNotBuildABacklog() {
        AtomicInteger calls = new AtomicInteger();
        int repeating = manager.repeatAsync(calls::incrementAndGet, 1);
        manager.onEndTick(server);
        manager.onEndTick(server);
        manager.onEndTick(server);
        assertEquals(0, calls.get());
        assertEquals(1, asyncTasks.size());
        asyncTasks.remove().run();
        assertEquals(1, calls.get());

        manager.onEndTick(server);
        manager.cancel(repeating);
        asyncTasks.remove().run();
        assertEquals(1, calls.get());
        manager.onEndTick(server);
        assertTrue(asyncTasks.isEmpty());
    }

    @Test
    void restartPreservesCoreRepeatsButDiscardsOldWorldOneShots() {
        AtomicInteger repeatingCalls = new AtomicInteger();
        AtomicInteger oldWorldCalls = new AtomicInteger();
        manager.repeat(repeatingCalls::incrementAndGet, 2);
        manager.later(oldWorldCalls::incrementAndGet, 5);
        manager.laterAsync(oldWorldCalls::incrementAndGet, 1);
        manager.onEndTick(server);

        manager.serverStopping(server);
        currentServer.set(null);
        while (!asyncTasks.isEmpty()) {
            asyncTasks.remove().run();
        }
        manager.onEndTick(server);
        assertEquals(0, oldWorldCalls.get());

        MinecraftServer secondServer = mock(MinecraftServer.class);
        currentServer.set(secondServer);
        manager.serverStarted(secondServer);
        manager.onEndTick(server);
        manager.onEndTick(secondServer);
        assertEquals(0, repeatingCalls.get());
        manager.onEndTick(secondServer);
        assertEquals(1, repeatingCalls.get());
        assertEquals(0, oldWorldCalls.get());
    }

    @Test
    void noServerRejectsMainAndDelayedJobsInsteadOfRunningOnTheCallingThread() {
        AtomicInteger calls = new AtomicInteger();
        manager.serverStopping(server);
        currentServer.set(null);
        assertThrows(RejectedExecutionException.class, () -> manager.task(calls::incrementAndGet));
        assertThrows(RejectedExecutionException.class, () -> manager.later(calls::incrementAndGet, 1));
        assertThrows(RejectedExecutionException.class, () -> manager.laterAsync(calls::incrementAndGet, 1));
        manager.onEndTick(server);
        assertEquals(0, calls.get());
        assertTrue(asyncTasks.isEmpty());
    }

    @Test
    void immediateAsyncCleanupSurvivesShutdownAndCanBeSubmittedBetweenWorlds() {
        AtomicInteger calls = new AtomicInteger();
        manager.async(calls::incrementAndGet);
        manager.serverStopping(server);
        currentServer.set(null);
        manager.async(calls::incrementAndGet);
        assertEquals(0, calls.get());
        assertEquals(2, asyncTasks.size());
        asyncTasks.remove().run();
        asyncTasks.remove().run();
        assertEquals(2, calls.get());
    }

    @Test
    void tasksScheduledByATickCallbackWaitForTheFollowingTick() {
        AtomicInteger calls = new AtomicInteger();
        manager.task(() -> manager.later(calls::incrementAndGet, 0));
        manager.onEndTick(server);
        assertEquals(0, calls.get());
        manager.onEndTick(server);
        assertEquals(1, calls.get());
    }
}
