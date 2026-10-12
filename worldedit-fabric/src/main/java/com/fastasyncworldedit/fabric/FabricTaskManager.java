package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Schedules work against server ticks, including when an integrated server is paused.
 * The manager outlives individual servers because FAWE's core installs its repeating tasks once.
 */
public class FabricTaskManager extends TaskManager {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    private final Supplier<MinecraftServer> serverSupplier;
    private final Executor asyncExecutor;
    private final Object taskLock = new Object();
    private final Map<Integer, TickTask> tasks = new LinkedHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile ServerRun activeRun;
    private long ticks;

    public FabricTaskManager(Supplier<MinecraftServer> serverSupplier) {
        this(serverSupplier, Executors.newFixedThreadPool(2, new DaemonThreadFactory()));
        ServerTickEvents.END_SERVER_TICK.register(this::onEndTick);
    }

    // An executor can be supplied to test tick and lifecycle behavior without launching Minecraft.
    FabricTaskManager(Supplier<MinecraftServer> serverSupplier, Executor asyncExecutor) {
        this.serverSupplier = Objects.requireNonNull(serverSupplier);
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor);
        MinecraftServer server = serverSupplier.get();
        if (server != null) {
            serverStarted(server);
        }
    }

    public void serverStarted(MinecraftServer server) {
        synchronized (taskLock) {
            if (activeRun != null && activeRun.server == server) {
                return;
            }
            discardOneShotTasks();
            activeRun = new ServerRun(server);
            ticks = 0;
            for (TickTask task : tasks.values()) {
                task.nextTick = task.period;
            }
        }
    }

    public void serverStopping(MinecraftServer server) {
        synchronized (taskLock) {
            if (activeRun == null || activeRun.server != server) {
                return;
            }
            activeRun = null;
            discardOneShotTasks();
        }
    }

    private void discardOneShotTasks() {
        tasks.values().removeIf(task -> {
            if (task.period == 0) {
                task.cancelled = true;
                return true;
            }
            return false;
        });
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int interval) {
        int period = Math.max(1, interval);
        return schedule(runnable, period, period, false);
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        int period = Math.max(1, interval);
        return schedule(runnable, period, period, true);
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        // Immediate asynchronous work includes closing history streams and deleting old files.
        // It must finish even after a world stops; native access checks its owning server itself.
        asyncExecutor.execute(Objects.requireNonNull(runnable));
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        later(runnable, 1);
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        schedule(runnable, delay, 0, false);
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        schedule(runnable, delay, 0, true);
    }

    private int schedule(Runnable runnable, int delay, int period, boolean async) {
        Objects.requireNonNull(runnable);
        synchronized (taskLock) {
            // Repeating core services may be installed before the first server starts.
            // A one-shot belongs to one server and must never be carried to the next world.
            if (period == 0) {
                requireActiveRun();
            }
            int id = nextId.getAndIncrement();
            tasks.put(id, new TickTask(runnable, ticks + Math.max(1, delay), period, async));
            return id;
        }
    }

    @Override
    public void cancel(int task) {
        synchronized (taskLock) {
            TickTask removed = tasks.remove(task);
            if (removed != null) {
                removed.cancelled = true;
            }
        }
    }

    void onEndTick(MinecraftServer server) {
        ServerRun run = activeRun;
        if (run == null || run.server != server || !isActive(run)) {
            return;
        }
        List<TickTask> due = new ArrayList<>();
        synchronized (taskLock) {
            if (!isActive(run)) {
                return;
            }
            ticks++;
            var iterator = tasks.values().iterator();
            while (iterator.hasNext()) {
                TickTask task = iterator.next();
                if (task.nextTick <= ticks) {
                    due.add(task);
                    if (task.period == 0) {
                        iterator.remove();
                    } else {
                        task.nextTick = ticks + task.period;
                    }
                }
            }
        }
        for (TickTask task : due) {
            if (task.async) {
                // A slow asynchronous repeating task must not overlap itself or build a backlog.
                if (task.running.compareAndSet(false, true)) {
                    asyncExecutor.execute(() -> {
                        try {
                            runTask(task, run);
                        } finally {
                            task.running.set(false);
                        }
                    });
                }
            } else {
                runTask(task, run);
            }
        }
    }

    private void runTask(TickTask task, ServerRun run) {
        if (task.cancelled || !isActive(run)) {
            return;
        }
        try {
            task.runnable.run();
        } catch (Exception e) {
            LOGGER.error("Failed to run a scheduled Fabric task", e);
        }
    }

    private ServerRun requireActiveRun() {
        ServerRun run = activeRun;
        if (run == null || !isActive(run)) {
            throw new RejectedExecutionException("No running Fabric server");
        }
        return run;
    }

    private boolean isActive(ServerRun run) {
        return activeRun == run && serverSupplier.get() == run.server;
    }

    private record ServerRun(MinecraftServer server) {
    }

    private static final class TickTask {
        private final Runnable runnable;
        private final int period;
        private final boolean async;
        private final AtomicBoolean running = new AtomicBoolean();
        private long nextTick;
        private volatile boolean cancelled;

        private TickTask(Runnable runnable, long nextTick, int period, boolean async) {
            this.runnable = runnable;
            this.nextTick = nextTick;
            this.period = period;
            this.async = async;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger threadId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "fawe-fabric-async-" + threadId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
