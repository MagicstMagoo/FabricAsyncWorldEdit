package com.fastasyncworldedit.fabric;

import com.fastasyncworldedit.core.FAWEPlatformAdapterImpl;
import com.fastasyncworldedit.core.queue.IChunkGet;

public class FabricPlatformAdapter implements FAWEPlatformAdapterImpl {

    @Override
    public void sendChunk(IChunkGet chunk, int mask, boolean lighting) {
        // TODO: Fabric chunk packet support not implemented yet.
    }
}
