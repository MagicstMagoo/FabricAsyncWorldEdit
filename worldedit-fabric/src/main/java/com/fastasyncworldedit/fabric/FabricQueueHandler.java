package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.configuration.Settings;
import com.sk89q.worldedit.fabric.internal.FabricWorldEdit;
import net.minecraft.server.MinecraftServer;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class FabricQueueHandler extends QueueHandler {

    private final Supplier<MinecraftServer> serverSupplier;
    private final Object lifecycleLock = new Object();
    private final Queue<SyncTask<?>> syncTasks = new ConcurrentLinkedQueue<>();
    private final Queue<SyncTask<?>> syncWhenFree = new ConcurrentLinkedQueue<>();
    private volatile ServerRun activeRun;

    public FabricQueueHandler() {
        this(FabricWorldEdit::currentServer);
    }

    FabricQueueHandler(Supplier<MinecraftServer> serverSupplier) {
        this.serverSupplier = serverSupplier;
        MinecraftServer server = serverSupplier.get();
        if (server != null) {
            serverStarted(server);
        }
    }

    public void serverStarted(MinecraftServer server) {
        synchronized (lifecycleLock) {
            if (activeRun != null && activeRun.server == server) {
                return;
            }
            cancelPendingTasks();
            activeRun = new ServerRun(server);
        }
    }

    public void serverStopping(MinecraftServer server) {
        synchronized (lifecycleLock) {
            if (activeRun != null && activeRun.server == server) {
                activeRun = null;
                // Cancellation releases workers waiting in TaskManager.sync(). Calling get()
                // or waiting for those workers here would deadlock the stopping server thread.
                cancelPendingTasks();
            }
        }
    }

    private void cancelPendingTasks() {
        cancelQueue(syncTasks);
        cancelQueue(syncWhenFree);
    }

    private static void cancelQueue(Queue<SyncTask<?>> queue) {
        SyncTask<?> task;
        while ((task = queue.poll()) != null) {
            task.future.cancel(false);
        }
    }

    @Override
    public void run() {
        ServerRun run = activeRun;
        if (run == null || !isActive(run)) {
            return;
        }
        if (!run.server.isSameThread()) {
            throw new IllegalStateException("Not the Fabric server thread");
        }
        long budgetMillis = Math.clamp(5L + Settings.settings().QUEUE.EXTRA_TIME_MS, 1L, 50L);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        do {
            SyncTask<?> task = syncTasks.poll();
            if (task == null) {
                task = syncWhenFree.poll();
            }
            if (task == null) {
                return;
            }
            if (task.run == run && isActive(run)) {
                task.future.run();
            } else {
                task.future.cancel(false);
            }
        } while (System.nanoTime() < deadline);
    }

    @Override
    public <T> Future<T> sync(Runnable runnable) {
        return enqueue(() -> {
            runnable.run();
            return null;
        }, false);
    }

    @Override
    public <T> Future<T> sync(Callable<T> callable) {
        return enqueue(callable, false);
    }

    @Override
    public <T> Future<T> sync(Supplier<T> supplier) {
        return enqueue(supplier::get, false);
    }

    @Override
    public <T> Future<T> syncWhenFree(Runnable runnable, T value) {
        return enqueue(() -> {
            runnable.run();
            return value;
        }, true);
    }

    @Override
    public <T> Future<T> syncWhenFree(Runnable runnable) {
        return syncWhenFree(runnable, null);
    }

    @Override
    public <T> Future<T> syncWhenFree(Callable<T> callable) {
        return enqueue(callable, true);
    }

    @Override
    public <T> Future<T> syncWhenFree(Supplier<T> supplier) {
        return enqueue(supplier::get, true);
    }

    private <T> Future<T> enqueue(Callable<T> callable, boolean whenFree) {
        FutureTask<T> future = new FutureTask<>(callable);
        boolean immediate;
        synchronized (lifecycleLock) {
            ServerRun run = activeRun;
            if (run == null || !isActive(run)) {
                future.cancel(false);
                return future;
            }
            immediate = run.server.isSameThread();
            if (!immediate) {
                (whenFree ? syncWhenFree : syncTasks).add(new SyncTask<>(run, future));
            }
        }
        if (immediate) {
            future.run();
        }
        return future;
    }

    private boolean isActive(ServerRun run) {
        return activeRun == run && serverSupplier.get() == run.server;
    }

    private record ServerRun(MinecraftServer server) {
    }

    private record SyncTask<T>(ServerRun run, FutureTask<T> future) {
    }

    @Override
    public void startUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }

    @Override
    public void endUnsafe(boolean parallel) {
        // No-op on Fabric for now.
    }
}
