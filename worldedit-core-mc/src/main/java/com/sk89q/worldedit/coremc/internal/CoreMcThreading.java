package com.sk89q.worldedit.coremc.internal;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs native Minecraft access on its owning server while FAWE computes edits on workers.
 */
final class CoreMcThreading {

    private static final long MAX_WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private CoreMcThreading() {
    }

    @FunctionalInterface
    interface Operation<T, E extends Exception> {
        T run() throws E;
    }

    static <T, E extends Exception> T call(MinecraftServer server, Operation<T, E> operation) throws E {
        // Native operations call one another; executing inline also prevents deadlocks in callbacks.
        if (server.isSameThread()) {
            return operation.run();
        }
        requireRunning(server);
        FutureTask<T> task = new FutureTask<>(() -> {
            requireRunning(server);
            return operation.run();
        });
        server.execute(task);
        long deadline = System.nanoTime() + MAX_WAIT_NANOS;
        try {
            while (true) {
                try {
                    return task.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    requireRunning(server);
                    if (System.nanoTime() - deadline >= 0) {
                        throw new IllegalStateException("Timed out waiting for Minecraft's server thread", e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Minecraft's server thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            // The only checked exceptions the FutureTask can throw originate in this operation.
            @SuppressWarnings("unchecked")
            E checkedException = (E) cause;
            throw checkedException;
        } finally {
            // Prevent pending native writes from running after cancellation, timeout, or server shutdown.
            task.cancel(false);
        }
    }

    private static void requireRunning(MinecraftServer server) {
        if (!server.isRunning() || server.isStopped()) {
            throw new IllegalStateException("Minecraft's server is stopping or has stopped");
        }
    }
}
