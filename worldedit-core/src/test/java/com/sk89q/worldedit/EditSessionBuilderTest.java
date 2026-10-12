package com.sk89q.worldedit;

import com.fastasyncworldedit.core.extent.HistoryExtent;
import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet;
import com.fastasyncworldedit.core.util.ExtentTraverser;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.buffer.ForgetfulExtentBuffer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EditSessionBuilderTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void worldsWithoutChunkPlacementUseNativeAccessForUnlimitedAndFastEdits(boolean fastMode) {
        World world = mock(World.class);
        when(world.supportsChunkPlacement()).thenReturn(false);
        AbstractChangeSet history = mock(AbstractChangeSet.class);

        // No actor selects the unlimited limit, as do operators with fawe.limit.unlimited.
        EditSessionBuilder builder = new EditSessionBuilder(new EventBus())
                .world(WorldWrapper.wrap(world))
                .fastMode(fastMode)
                .checkMemory(false)
                .allowedRegions(new Region[]{mock(Region.class)})
                .changeSet(history)
                .combineStages(true)
                .compile();

        assertTrue(builder.isWNAMode());
        HistoryExtent extent = new ExtentTraverser<>(builder.getExtent()).findAndGet(HistoryExtent.class);
        assertNotNull(extent);
        assertSame(history, extent.getChangeSet());
        verify(world, never()).get(anyInt(), anyInt());
    }

    @Test
    void worldWrapperPreservesChunkPlacementCapability() {
        World world = mock(World.class);
        WorldWrapper wrapper = WorldWrapper.wrap(world);
        when(world.supportsChunkPlacement()).thenReturn(false);
        assertFalse(wrapper.supportsChunkPlacement());
        when(world.supportsChunkPlacement()).thenReturn(true);
        assertTrue(wrapper.supportsChunkPlacement());
    }

    @Test
    void worldWrapperPreservesDimensionIdentity() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world__minecraft%3athe_nether");
        when(world.id()).thenReturn("world_minecraft:the_nether");
        assertEquals("world_minecraft:the_nether", WorldWrapper.wrap(world).id());
    }

    @Test
    void nativeStandardModePreservesHistoryAndWritesVectorBlocksAndBiomes() throws WorldEditException {
        World world = mock(World.class);
        when(world.supportsChunkPlacement()).thenReturn(false);
        when(world.getMinY()).thenReturn(-64);
        when(world.getMaxY()).thenReturn(319);
        AbstractChangeSet history = mock(AbstractChangeSet.class);
        Region allowedRegion = mock(Region.class);
        when(allowedRegion.contains(anyInt(), anyInt(), anyInt())).thenReturn(true);
        EditSession edit = new EditSessionBuilder(new EventBus())
                .world(world)
                .fastMode(false)
                .checkMemory(false)
                .allowedRegions(new Region[]{allowedRegion})
                .changeSet(history)
                .build();
        Extent original = edit.getExtent();
        HistoryExtent originalHistory = new ExtentTraverser<>(original).findAndGet(HistoryExtent.class);
        assertNotNull(originalHistory);

        edit.enableStandardMode();
        edit.enableQueue();
        assertFalse(edit.isQueueEnabled());
        assertSame(original, edit.getExtent());
        assertNull(new ExtentTraverser<>(edit).findAndGet(ForgetfulExtentBuffer.class));

        BlockVector3 position = BlockVector3.at(1, 64, 1);
        BlockState block = mock(BlockState.class);
        BaseBlock previous = mock(BaseBlock.class);
        when(world.getFullBlock(1, 64, 1)).thenReturn(previous);
        when(world.setBlock(1, 64, 1, block)).thenReturn(true);
        assertTrue(edit.setBlock(position, block));
        verify(world).setBlock(1, 64, 1, block);

        edit.disableQueue();
        edit.disableBuffering();
        assertSame(original, edit.getExtent());
        assertSame(originalHistory, new ExtentTraverser<>(edit).findAndGet(HistoryExtent.class));

        BiomeType plains = new BiomeType("minecraft:plains");
        BiomeType desert = new BiomeType("minecraft:desert");
        when(world.getBiome(position)).thenReturn(plains);
        when(world.setBiome(position, desert)).thenReturn(true);
        assertTrue(edit.setBiome(position, desert));
        verify(world).setBiome(position, desert);
        verify(history).addBiomeChange(1, 64, 1, plains, desert);
    }
}
