/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric.internal;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.fabric.FabricQueueHandler;
import com.fastasyncworldedit.fabric.FabricTaskManager;
import com.fastasyncworldedit.fabric.FaweFabric;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.coremc.CoreMcPermissionsProvider;
import com.sk89q.worldedit.coremc.internal.CoreMcMod;
import com.sk89q.worldedit.coremc.internal.CoreMcPlatform;
import com.sk89q.worldedit.coremc.internal.ThreadSafeCache;
import com.sk89q.worldedit.event.platform.PlatformUnreadyEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.util.lifecycle.SimpleLifecycled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.InteractionResult;
import org.apache.logging.log4j.Logger;
import org.enginehub.worldeditcui.protocol.CUIPacketHandler;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Fabric implementation of WorldEdit.
 */
public class FabricWorldEdit extends CoreMcMod implements ModInitializer {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    public static FabricWorldEdit inst;

    /**
     * FAWE needs access to the running server for its task manager. The platform keeps its own
     * lifecycle instance, so track a second one that FAWE can observe.
     */
    private static final SimpleLifecycled<MinecraftServer> FAWE_SERVER = SimpleLifecycled.invalid();

    public static MinecraftServer currentServer() {
        return FAWE_SERVER.value().orElse(null);
    }

    private static volatile CoreMcPlatform PLATFORM;

    public static CoreMcPlatform getPlatform() {
        CoreMcPlatform platform = PLATFORM;
        if (platform == null) {
            throw new IllegalStateException("FabricWorldEdit is not initialized");
        }
        return platform;
    }

    private ModContainer container;

    public FabricWorldEdit() {
        inst = this;
    }

    @Override
    public void onInitialize() {
        this.container = FabricLoader.getInstance().getModContainer("worldedit").orElseThrow(
            () -> new IllegalStateException("WorldEdit mod missing in Fabric")
        );

        PLATFORM = new FabricPlatform(this);
        init(PLATFORM, FabricLoader.getInstance().getConfigDir());

        ServerLifecycleEvents.SERVER_STARTING.register(FAWE_SERVER::newValue);

        CUIPacketHandler.instance().registerServerboundHandler(this::onCuiPacket);

        ServerTickEvents.END_SERVER_TICK.register(server -> ThreadSafeCache.getInstance().onEndTick(server));
        CommandRegistrationCallback.EVENT.register((dispatcher, _, _) -> registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(_ -> serverAboutToStart());
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> serverStopping());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> {
            try {
                onPlayerDisconnect(handler.player);
            } finally {
                ThreadSafeCache.getInstance().getOnlineIds().remove(handler.player.getUUID());
            }
        });
        AttackBlockCallback.EVENT.register(
            (playerEntity, _, hand, blockPos, direction) ->
                onLeftClickBlock(playerEntity, hand, blockPos, direction)
                    ? InteractionResult.SUCCESS : InteractionResult.PASS
        );
        UseBlockCallback.EVENT.register(
            (playerEntity, _, hand, blockHitResult) ->
                onRightClickBlock(playerEntity, hand, blockHitResult.getBlockPos(), blockHitResult.getDirection())
                    ? InteractionResult.SUCCESS : InteractionResult.PASS
        );
        UseItemCallback.EVENT.register((playerEntity, _, hand) -> {
            Optional<Boolean> result = onRightClickItem(playerEntity, hand);
            if (result.isPresent()) {
                return result.get() ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            return InteractionResult.PASS;
        });
        LOGGER.info("WorldEdit for Fabric (version " + getInternalVersion() + ") is loaded");
    }

    @Override
    protected String getInternalVersion() {
        return container.getMetadata().getVersion().getFriendlyString();
    }

    @Override
    protected CoreMcPermissionsProvider createPermissionsProvider(CoreMcPlatform platform) {
        CoreMcPermissionsProvider provider = super.createPermissionsProvider(platform);

        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions", false, getClass().getClassLoader());
            Optional<Version> version = FabricLoader.getInstance().getModContainer("fabric-permissions-api-v0")
                    .map(ModContainer::getMetadata)
                    .map(ModMetadata::getVersion);

            if (version.isPresent() && !VersionPredicate.parse(">=0.7.0").test(version.get())) {
                throw new RuntimeException("Fabric permissions version " + version.get() + " is not supported. Please update Fabric Permissions API");
            }

            provider = new LuckoFabricPermissionsProvider(provider);
        } catch (ClassNotFoundException _) {
            // fallback to vanilla
        } catch (Exception e) {
            // catch any exception to prevent crashing the server, but still print a warning
            LOGGER.warn("Failed to load Fabric permissions provider. Falling back to Minecraft", e);
        }

        return new FabricPermissionsProvider(provider);
    }

    @Override
    protected void serverStarted(MinecraftServer server) {
        initFawe();
        if (Fawe.instance() != null) {
            // A new integrated server uses a different thread even though the mod and FAWE persist.
            Fawe.instance().setMainThread();
            if (Fawe.platform() instanceof FaweFabric fabric) {
                ((FabricTaskManager) fabric.getTaskManager()).serverStarted(server);
                ((FabricQueueHandler) fabric.getQueueHandler()).serverStarted(server);
            }
        }
        super.serverStarted(server);
    }

    @Override
    protected void serverStopping() {
        MinecraftServer server = currentServer();
        WorldEdit worldEdit = WorldEdit.getInstance();
        try {
            // Mark the platform unready before releasing workers or persisting their final history.
            worldEdit.getEventBus().post(new PlatformUnreadyEvent(PLATFORM));
            if (server != null && Fawe.platform() instanceof FaweFabric fabric) {
                Map<UUID, Actor> actors = new LinkedHashMap<>();
                Map<UUID, Set<ChangeSet>> pendingHistory = new LinkedHashMap<>();
                for (Actor actor : PLATFORM.getConnectedUsers()) {
                    actors.put(actor.getUniqueId(), actor);
                }
                for (Request request : Request.getAll()) {
                    if (request.getEditSession() != null) {
                        request.getEditSession().cancel();
                        Actor actor = request.getEditSession().getActor();
                        if (actor != null) {
                            actors.putIfAbsent(actor.getUniqueId(), actor);
                            ChangeSet history = request.getEditSession().getChangeSet();
                            if (history != null) {
                                pendingHistory.computeIfAbsent(actor.getUniqueId(), _ ->
                                    Collections.newSetFromMap(new IdentityHashMap<>())).add(history);
                            }
                        }
                    }
                    if (request.getActor() != null) {
                        Actor actor = request.getActor();
                        actors.putIfAbsent(actor.getUniqueId(), actor);
                    }
                }
                // Queued commands consult SessionKey.isActive(), so invalidate old players now.
                // Direct actor.runAction barriers below do not perform that active-player check.
                ThreadSafeCache.getInstance().getOnlineIds().clear();
                ((FabricQueueHandler) fabric.getQueueHandler()).serverStopping(server);
                flushSessions(server, actors, pendingHistory);
                Fawe.instance().onDisable();
            }
            if (server instanceof DedicatedServer) {
                worldEdit.getSessionManager().unload();
            } else {
                // Unlike unload(), clear() keeps the session tracker alive for the next integrated server.
                worldEdit.getSessionManager().clear();
            }
        } finally {
            if (server != null && Fawe.platform() instanceof FaweFabric fabric) {
                ((FabricTaskManager) fabric.getTaskManager()).serverStopping(server);
                ((FabricQueueHandler) fabric.getQueueHandler()).serverStopping(server);
            }
            ThreadSafeCache.getInstance().getOnlineIds().clear();
            FAWE_SERVER.invalidate();
        }
    }

    private void flushSessions(MinecraftServer server, Map<UUID, Actor> actors,
                               Map<UUID, Set<ChangeSet>> pendingHistory) {
        CountDownLatch pending = new CountDownLatch(actors.size());
        for (Actor actor : actors.values()) {
            // The actor's serial queue ensures an edit finishes recording before its history is closed.
            actor.cancel(true);
            actor.runAction(() -> {
                try {
                    LocalSession session = WorldEdit.getInstance().getSessionManager().getIfPresent(actor);
                    Set<ChangeSet> histories = Collections.newSetFromMap(new IdentityHashMap<>());
                    histories.addAll(pendingHistory.getOrDefault(actor.getUniqueId(), Set.of()));
                    if (session != null) {
                        histories.addAll(session.getHistory());
                    }
                    // A canceled native flush can prevent a command from remembering its edit.
                    // Include its captured changeset so partial history still releases its streams.
                    for (ChangeSet history : histories) {
                        if (history != null) {
                            try {
                                history.close();
                            } catch (IOException e) {
                                LOGGER.error("Failed to close WorldEdit history for {}", actor.getUniqueId(), e);
                            }
                        }
                    }
                    if (session != null) {
                        session.flushClipboard();
                        session.save();
                    }
                } catch (RuntimeException e) {
                    LOGGER.error("Failed to flush WorldEdit session for {}", actor.getUniqueId(), e);
                } finally {
                    pending.countDown();
                }
            }, false, true);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // Worker finalizers can still need a server callback. Pumping that executor prevents
        // the main-thread/worker deadlock caused by simply waiting on a Future here.
        server.managedBlock(() -> pending.getCount() == 0 || System.nanoTime() >= deadline);
        if (pending.getCount() != 0) {
            LOGGER.warn("{} WorldEdit sessions did not finish flushing before server shutdown", pending.getCount());
        }
    }

    private void initFawe() {
        if (Fawe.instance() != null) {
            return;
        }
        try {
            File workingDir = FabricLoader.getInstance().getConfigDir().resolve("worldedit").toFile();
            Fawe.set(new FaweFabric(this, workingDir, FabricWorldEdit::currentServer));
            Fawe.setupInjector();
            // Fabric does not yet provide a chunk getter for FAWE's queue.
            // Disable fast placement and database-backed history to keep commands functional.
            Settings settings = Settings.settings();
            Settings.LIMITS defaultLimit = settings.LIMITS.get("default");
            if (defaultLimit != null) {
                defaultLimit.FAST_PLACEMENT = false;
            }
            settings.HISTORY.USE_DATABASE = false;
            settings.REGION_RESTRICTIONS = false;
        } catch (Exception e) {
            LOGGER.error("Failed to initialize FAWE for Fabric", e);
        }
    }
}
