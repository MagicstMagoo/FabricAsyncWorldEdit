package com.sk89q.worldedit.coremc.internal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreMcWorldTest {

    @Test
    void dimensionNamesKeepTheOverworldNameAndSeparateOtherDimensions() {
        assertEquals("world", CoreMcWorld.nameForDimension("world", "minecraft:overworld"));
        assertEquals("world__minecraft%3athe_nether",
                CoreMcWorld.nameForDimension("world", "minecraft:the_nether"));
        assertEquals("world__minecraft%3athe_end",
                CoreMcWorld.nameForDimension("world", "minecraft:the_end"));
        assertNotEquals(CoreMcWorld.nameForDimension("world", "example:moon"),
                CoreMcWorld.nameForDimension("world", "example:mars"));
    }

    @Test
    void historyDirectoryNamesEscapeUnsafeCharactersWithoutCollisions() {
        String name = CoreMcWorld.nameForDimension("My: World", "example:islands/moon");
        assertEquals("My%3a%20World__example%3aislands%2fmoon", name);
        assertTrue(name.matches("[A-Za-z0-9_%\\-]+"));
        assertNotEquals(CoreMcWorld.nameForDimension("My: World", "example:moon"),
                CoreMcWorld.nameForDimension("My%3a World", "example:moon"));
        assertNotEquals(CoreMcWorld.nameForDimension("world", "example:islands/moon"),
                CoreMcWorld.nameForDimension("world", "example:islands%2fmoon"));
    }

    @Test
    void regenerationStopsWaitingWhenAChunkFailsEvenWhileOtherChunksArePending() {
        CompletableFuture<Object> failed = new CompletableFuture<>();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        assertFalse(CoreMcWorld.chunkLoadingFinished(List.of(failed, pending)));

        failed.complete(null);
        assertTrue(CoreMcWorld.chunkLoadingFinished(List.of(failed, pending)));
    }

    @Test
    void regenerationStopsWaitingOnExceptionalResultsAndSuccessfulCompletion() {
        CompletableFuture<Object> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("chunk generation failed"));
        assertTrue(CoreMcWorld.chunkLoadingFinished(List.of(failed, new CompletableFuture<>())));
        assertTrue(CoreMcWorld.chunkLoadingFinished(List.of(CompletableFuture.completedFuture(new Object()))));
        assertTrue(CoreMcWorld.chunkLoadingFinished(List.of()));
    }
}
