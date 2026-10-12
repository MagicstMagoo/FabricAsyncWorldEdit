package com.fastasyncworldedit.testharness;

import com.fastasyncworldedit.core.internal.exception.FaweException;
import com.fastasyncworldedit.core.limit.FaweLimit;
import com.fastasyncworldedit.core.wrappers.WorldWrapper;
import com.google.gson.GsonBuilder;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.AbstractNonPlayerActor;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.fabric.internal.FabricWorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.session.SessionKey;
import com.sk89q.worldedit.util.auth.AuthorizationException;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Opt-in, unattended dedicated-server integration checks. Launch only in a disposable world
 * with {@code -Dfawe.smokeTest=true}; the harness writes JSON results and stops the server.
 * Commands use the real Brigadier dispatcher, and assertions inspect every block in each region.
 * All waits run on a worker so the server can continue applying edits on its tick thread.
 */
public final class ServerTestHarness implements ModInitializer {

    private static final BlockVector3 MIN = BlockVector3.at(-2, 96, -2);
    private static final BlockVector3 MAX = BlockVector3.at(2, 100, 2);
    private static final BlockVector3 PASTE_MIN = BlockVector3.at(30, 96, -2);
    private static final BlockVector3 PASTE_MAX = BlockVector3.at(34, 100, 2);
    private static final long TIMEOUT_NANOS = Duration.ofSeconds(
            Long.getLong("fawe.smokeTest.timeoutSeconds", 30L)
    ).toNanos();

    private final List<Map<String, Object>> results = new ArrayList<>();
    private MinecraftServer server;
    private ServerLevel level;
    private World world;
    private LocalSession consoleSession;
    private Map<BlockPos, BlockState> baseline;
    private boolean setCompleted;
    private boolean undoCompleted;

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("fawe.smokeTest")) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            if (!started.isDedicatedServer()) {
                return;
            }
            server = started;
            Thread.ofPlatform().name("FAWE-Server-Smoke-Test").daemon().start(this::run);
        });
    }

    private void run() {
        try {
            // SERVER_STARTED listeners and initial scheduled FAWE setup must finish first.
            Thread.sleep(1000);
            prepare();
            check("all native block states round-trip through the WorldEdit adapter", () -> {
                int checked = onServer(() -> {
                    var adapter = FabricWorldEdit.getPlatform().getAdapter();
                    int states = 0;
                    for (BlockState original : Block.BLOCK_STATE_REGISTRY) {
                        var converted = adapter.fromNativeBlockState(original);
                        BlockState restored = adapter.toNativeBlockState(converted);
                        require(original.equals(restored), "round-trip changed " + original + " to " + restored
                                + " through " + converted.getAsString());
                        states++;
                    }
                    return states;
                });
                require(checked > 0, "native block-state registry must be populated");
                System.out.println("[SERVERHARNESS] verified " + checked + " native block-state round trips");
            });
            check("console selection and //set across four chunks (125 blocks)", () -> {
                command("/world " + world.id());
                await(() -> consoleSession.getWorldOverride() != null, "console world override");
                select(MIN, MAX);
                command("/set minecraft:diamond_block");
                awaitRegion(MIN, MAX, Blocks.DIAMOND_BLOCK.defaultBlockState());
                setCompleted = true;
            });
            check("//undo restores all 125 original block states", () -> {
                require(setCompleted, "the initial //set must have completed");
                command("/undo");
                await(() -> baseline.entrySet().stream().allMatch(
                        entry -> level.getBlockState(entry.getKey()).equals(entry.getValue())
                ), "complete baseline restoration");
                undoCompleted = true;
            });
            check("//redo restores the entire edit", () -> {
                require(undoCompleted, "//undo must have completed");
                command("/redo");
                awaitRegion(MIN, MAX, Blocks.DIAMOND_BLOCK.defaultBlockState());
            });
            check("//fast still uses the supported Fabric placement path", () -> {
                command("/fast true");
                await(consoleSession::hasFastMode, "fast mode enabled");
                command("/set minecraft:gold_block");
                awaitRegion(MIN, MAX, Blocks.GOLD_BLOCK.defaultBlockState());
                command("/fast false");
                await(() -> !consoleSession.hasFastMode(), "fast mode disabled");
            });
            check("//copy and //paste transfer all 125 blocks", () -> {
                select(MIN, MAX);
                onServer(() -> {
                    consoleSession.setPlaceAtPos1(true);
                    return null;
                });
                command("/copy");
                await(() -> {
                    try {
                        return consoleSession.getClipboard().getClipboard().getRegion().getVolume() == 125;
                    } catch (Exception ignored) {
                        return false;
                    }
                }, "clipboard creation");
                select(PASTE_MIN, PASTE_MAX);
                command("/paste");
                awaitRegion(PASTE_MIN, PASTE_MAX, Blocks.GOLD_BLOCK.defaultBlockState());
            });
            check("//setbiome updates each selected biome cell", () -> {
                BlockVector3 biomeMin = BlockVector3.at(-4, 96, -4);
                BlockVector3 biomeMax = BlockVector3.at(3, 103, 3);
                select(biomeMin, biomeMax);
                command("/setbiome minecraft:plains");
                awaitBiome(Biomes.PLAINS);
                command("/setbiome minecraft:desert");
                awaitBiome(Biomes.DESERT);
            });
            check("actor-null API session preserves block-state properties", () -> {
                BlockState expected = Blocks.OAK_LOG.defaultBlockState()
                        .setValue(BlockStateProperties.AXIS, Direction.Axis.X);
                BlockVector3 min = BlockVector3.at(-2, 96, 30);
                BlockVector3 max = BlockVector3.at(2, 100, 34);
                onServer(() -> {
                    for (BlockVector3 position : positions(min, max)) {
                        level.setBlock(nativePos(position), Blocks.AIR.defaultBlockState(), 3);
                    }
                    return null;
                });
                var block = FabricWorldEdit.getPlatform().getAdapter().fromNativeBlockState(expected);
                // Deliberately omit actor, explicit limit, fastMode, and queue overrides.
                try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(world).build()) {
                    for (BlockVector3 position : positions(min, max)) {
                        edit.setBlock(position, block);
                    }
                }
                awaitRegion(min, max, expected);
            });
            check("non-OP API actor obeys an explicit three-block limit", this::checkLimitedActor);
            check("API block-entity copy preserves NBT and updates village POI", this::checkBlockEntity);
            check("dimension identities, edits, and undo histories stay isolated", this::checkDimensions);
            check("overlapping //move preserves source colors and undo restores them", this::checkOverlappingMove);
        } catch (Throwable failure) {
            record("harness setup", false, failure.toString(), 0);
            failure.printStackTrace();
        } finally {
            finish();
        }
    }

    private void prepare() throws Exception {
        onServer(() -> {
            level = server.overworld();
            world = FabricWorldEdit.getPlatform().getAdapter().fromNativeWorld(level);
            Actor console = FabricWorldEdit.getPlatform().getAdapter().adaptCommandSource(
                    server.createCommandSourceStack()
            );
            consoleSession = console.getSession();
            consoleSession.setFastMode(false);
            consoleSession.setClipboard(null);
            baseline = new LinkedHashMap<>();
            for (BlockVector3 position : positions(MIN, MAX)) {
                BlockPos nativePos = nativePos(position);
                level.getChunk(nativePos);
                BlockState state = (position.x() + position.z()) % 2 == 0
                        ? Blocks.STONE.defaultBlockState() : Blocks.OAK_PLANKS.defaultBlockState();
                level.setBlock(nativePos, state, 3);
                baseline.put(nativePos, state);
            }
            for (BlockVector3 position : positions(PASTE_MIN, PASTE_MAX)) {
                level.getChunk(nativePos(position));
                level.setBlock(nativePos(position), Blocks.AIR.defaultBlockState(), 3);
            }
            return null;
        });
    }

    private void checkLimitedActor() throws Exception {
        Actor actor = new LimitedActor();
        require(FaweLimit.MAX.MAX_CHANGES.get() == Long.MAX_VALUE,
                "the global unlimited change counter must be intact before the limit test");
        // copy() intentionally shares atomic counters between an actor's edit sessions.
        // A test fixture needs independent counters so it cannot restrict every later session.
        FaweLimit limit = new FaweLimit();
        limit.set(FaweLimit.MAX);
        limit.MAX_CHANGES.set(3);
        // Exercise the capability guard even with fast-placement explicitly requested.
        limit.FAST_PLACEMENT = true;
        List<BlockVector3> positions = positions(BlockVector3.at(40, 96, 0), BlockVector3.at(47, 96, 0));
        onServer(() -> {
            for (BlockVector3 position : positions) {
                level.setBlock(nativePos(position), Blocks.AIR.defaultBlockState(), 3);
            }
            return null;
        });
        boolean rejected = false;
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(world)
                .actor(actor).limit(limit).fastMode(false).build()) {
            for (BlockVector3 position : positions) {
                edit.setBlock(position, BlockTypes.EMERALD_BLOCK.getDefaultState());
            }
        } catch (FaweException failure) {
            if (failure.getType() != FaweException.Type.MAX_CHANGES) {
                throw failure;
            }
            rejected = true;
        } finally {
            require(FaweLimit.MAX.MAX_CHANGES.get() == Long.MAX_VALUE,
                    "the limit test must not mutate the global unlimited change counter");
        }
        long changed = onServer(() -> positions.stream().filter(
                position -> level.getBlockState(nativePos(position)).is(Blocks.EMERALD_BLOCK)
        ).count());
        require(rejected && changed == 3,
                "limit must reject the fourth edit and leave exactly three changes, rejected="
                        + rejected + ", changed=" + changed);
    }

    private void checkBlockEntity() throws Exception {
        BlockVector3 source = BlockVector3.at(48, 96, 0);
        BlockVector3 destination = BlockVector3.at(49, 96, 0);
        BaseBlock snapshot = onServer(() -> {
            level.setBlock(nativePos(source), Blocks.BARREL.defaultBlockState(), 3);
            BarrelBlockEntity barrel = (BarrelBlockEntity) level.getBlockEntity(nativePos(source));
            barrel.applyComponents(DataComponentMap.builder().set(
                    DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("FAWE smoke barrel")
            ).build(), DataComponentPatch.EMPTY);
            barrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
            barrel.setChanged();
            level.setBlock(nativePos(destination), Blocks.AIR.defaultBlockState(), 3);
            return world.getFullBlock(source);
        });
        require(snapshot.getNbtReference() != null, "source barrel must expose NBT");
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(world).build()) {
            edit.setBlock(destination, snapshot);
        }
        await(() -> level.getBlockEntity(nativePos(destination)) instanceof BarrelBlockEntity barrel
                && barrel.getCustomName() != null
                && barrel.getCustomName().getString().equals("FAWE smoke barrel")
                && barrel.getItem(0).is(Items.DIAMOND)
                && barrel.getItem(0).getCount() == 7, "barrel name and inventory");
        await(() -> level.getPoiManager().existsAtPosition(PoiTypes.FISHERMAN, nativePos(destination)),
                "copied barrel registers a fisherman POI",
                () -> "destination POI=" + level.getPoiManager().getType(nativePos(destination)));
        try (EditSession edit = WorldEdit.getInstance().newEditSessionBuilder().world(world).build()) {
            edit.setBlock(destination, BlockTypes.AIR.getDefaultState());
        }
        await(() -> level.getBlockState(nativePos(destination)).is(Blocks.AIR)
                        && level.getPoiManager().getType(nativePos(destination)).isEmpty(),
                "removing the barrel removes its village POI",
                () -> "destination block=" + level.getBlockState(nativePos(destination))
                        + ", POI=" + level.getPoiManager().getType(nativePos(destination)));
    }

    private void checkDimensions() throws Exception {
        List<? extends World> dimensions = onServer(() -> FabricWorldEdit.getPlatform().getWorlds());
        require(dimensions.size() >= 3, "test server must expose overworld, nether, and end");
        require(dimensions.stream().map(World::id).distinct().count() == dimensions.size(),
                "dimension IDs must be unique: " + dimensions.stream().map(World::id).toList());
        require(dimensions.stream().map(World::getName).distinct().count() == dimensions.size(),
                "dimension history names must be unique: " + dimensions.stream().map(World::getName).toList());
        require(dimensions.stream().map(World::getNameUnsafe).distinct().count() == dimensions.size(),
                "cached dimension history names must be unique");
        onServer(() -> {
            var adapter = FabricWorldEdit.getPlatform().getAdapter();
            require(dimensions.stream().map(adapter::toNativeWorld).distinct().count() == dimensions.size(),
                    "each world adapter must retain its own native dimension");
            for (World dimension : dimensions) {
                for (World other : dimensions) {
                    boolean sameDimension = dimension.id().equals(other.id());
                    World wrapper = WorldWrapper.wrap(other);
                    require(dimension.equals(wrapper) == sameDimension,
                            "native/foreign-wrapper equality mixed " + dimension.id() + " and " + other.id());
                    require(wrapper.equals(dimension) == sameDimension,
                            "foreign-wrapper/native equality mixed " + other.id() + " and " + dimension.id());
                }
            }
            return null;
        });
        World nether = onServer(() -> dimensions.stream().filter(dimension ->
                FabricWorldEdit.getPlatform().getAdapter().toNativeWorld(dimension).dimension().equals(Level.NETHER)
        ).findFirst().orElseThrow());
        ServerLevel nativeNether = onServer(() -> FabricWorldEdit.getPlatform().getAdapter().toNativeWorld(nether));
        BlockVector3 position = BlockVector3.at(60, 96, 60);
        onServer(() -> {
            level.setBlock(nativePos(position), Blocks.GLASS.defaultBlockState(), 3);
            nativeNether.setBlock(nativePos(position), Blocks.GLASS.defaultBlockState(), 3);
            return null;
        });
        try {
            switchWorld(world);
            select(world, position, position);
            command("/set minecraft:emerald_block");
            await(() -> level.getBlockState(nativePos(position)).is(Blocks.EMERALD_BLOCK),
                    "overworld emerald block");
            switchWorld(nether);
            select(nether, position, position);
            command("/set minecraft:redstone_block");
            await(() -> nativeNether.getBlockState(nativePos(position)).is(Blocks.REDSTONE_BLOCK),
                    "nether redstone block");
            require(onServer(() -> level.getBlockState(nativePos(position)).is(Blocks.EMERALD_BLOCK)),
                    "nether edit must not alter overworld at the same coordinates");
            command("/undo");
            await(() -> nativeNether.getBlockState(nativePos(position)).is(Blocks.GLASS),
                    "nether undo restores its own baseline");
            require(onServer(() -> level.getBlockState(nativePos(position)).is(Blocks.EMERALD_BLOCK)),
                    "nether undo must not consume overworld history");
            switchWorld(world);
            command("/undo");
            await(() -> level.getBlockState(nativePos(position)).is(Blocks.GLASS),
                    "overworld undo restores its own baseline after switching dimensions");
            require(onServer(() -> nativeNether.getBlockState(nativePos(position)).is(Blocks.GLASS)),
                    "overworld undo must not change the nether");
        } finally {
            switchWorld(world);
        }
    }

    private void switchWorld(World selectedWorld) throws Exception {
        command("/world " + selectedWorld.id());
        await(() -> consoleSession.getWorldOverride() != null
                        && consoleSession.getWorldOverride().id().equals(selectedWorld.id()),
                "world override " + selectedWorld.id());
    }

    private void checkOverlappingMove() throws Exception {
        BlockVector3 min = BlockVector3.at(80, 96, 80);
        BlockVector3 max = BlockVector3.at(82, 96, 80);
        List<BlockState> original = List.of(
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.EMERALD_BLOCK.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        );
        List<BlockState> moved = List.of(
                Blocks.AIR.defaultBlockState(),
                Blocks.DIAMOND_BLOCK.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(),
                Blocks.EMERALD_BLOCK.defaultBlockState()
        );
        onServer(() -> {
            for (int i = 0; i < original.size(); i++) {
                level.setBlock(nativePos(min.add(i, 0, 0)), original.get(i), 3);
            }
            consoleSession.setFastMode(false);
            return null;
        });
        switchWorld(world);
        select(min, max);
        command("/move 1 east");
        awaitRow(min, moved, "overlapping move");
        command("/undo");
        awaitRow(min, original, "overlapping move undo");
    }

    private void awaitRow(BlockVector3 min, List<BlockState> expected, String description) throws Exception {
        Supplier<String> mismatch = () -> {
            for (int i = 0; i < expected.size(); i++) {
                BlockVector3 position = min.add(i, 0, 0);
                BlockState actual = level.getBlockState(nativePos(position));
                if (!actual.equals(expected.get(i))) {
                    return "first mismatch at " + position + ": actual=" + actual + ", expected=" + expected.get(i);
                }
            }
            return null;
        };
        await(() -> mismatch.get() == null, description, mismatch);
    }

    private void select(BlockVector3 min, BlockVector3 max) throws Exception {
        select(world, min, max);
    }

    private void select(World selectedWorld, BlockVector3 min, BlockVector3 max) throws Exception {
        command("/pos1 " + coordinates(min));
        command("/pos2 " + coordinates(max));
        await(() -> {
            try {
                var selection = consoleSession.getSelection(selectedWorld);
                return selection.getMinimumPoint().equals(min) && selection.getMaximumPoint().equals(max);
            } catch (Exception ignored) {
                return false;
            }
        }, "selection " + min + " to " + max);
    }

    private void command(String command) throws Exception {
        System.out.println("[SERVERHARNESS] executing " + command);
        onServer(() -> {
            try {
                // The leading slash is part of WorldEdit's Brigadier literal (//set in chat).
                return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
            } catch (Exception failure) {
                throw new IllegalStateException("Brigadier rejected " + command, failure);
            }
        });
    }

    private void awaitRegion(BlockVector3 min, BlockVector3 max, BlockState expected) throws Exception {
        List<BlockVector3> positions = positions(min, max);
        await(() -> positions.stream().allMatch(
                position -> level.getBlockState(nativePos(position)).equals(expected)
        ), positions.size() + " blocks equal " + expected, () -> {
            for (BlockVector3 position : positions) {
                BlockState actual = level.getBlockState(nativePos(position));
                if (!actual.equals(expected)) {
                    return "first mismatch at " + position + ": actual=" + actual + ", expected=" + expected;
                }
            }
            return "region changed after the last poll";
        });
    }

    private void awaitBiome(net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> biome)
            throws Exception {
        await(() -> firstBiomeMismatch(biome) == null, "eight biome cells equal " + biome.identifier(),
                () -> firstBiomeMismatch(biome));
    }

    private String firstBiomeMismatch(net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome> biome) {
        for (int x = -4; x <= 3; x += 4) {
            for (int y = 96; y <= 103; y += 4) {
                for (int z = -4; z <= 3; z += 4) {
                    // getBiome(BlockPos) applies BiomeManager's fuzzy zoom, which can sample outside
                    // the selection near its edges. WorldEdit edits the exact stored quart cells.
                    var actual = level.getNoiseBiome(x >> 2, y >> 2, z >> 2);
                    if (!actual.is(biome)) {
                        return "first mismatch at quart " + (x >> 2) + "," + (y >> 2) + "," + (z >> 2)
                                + ": actual=" + actual.unwrapKey().map(key -> key.identifier().toString())
                                .orElse("<unregistered>") + ", expected=" + biome.identifier();
                    }
                }
            }
        }
        return null;
    }

    private void await(BooleanSupplier condition, String description) throws Exception {
        await(condition, description, () -> "");
    }

    private void await(BooleanSupplier condition, String description, Supplier<String> diagnostic) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (onServer(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + description + "; " + onServer(diagnostic));
    }

    private <T> T onServer(Supplier<T> action) throws Exception {
        return server.submit(action).get(TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
    }

    private void check(String name, CheckedRunnable test) {
        long started = System.nanoTime();
        try {
            test.run();
            record(name, true, "", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (Throwable failure) {
            record(name, false, failure.toString(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            failure.printStackTrace();
        }
    }

    private void record(String name, boolean passed, String detail, long elapsedMillis) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("passed", passed);
        result.put("detail", detail);
        result.put("elapsedMillis", elapsedMillis);
        results.add(result);
        System.out.println("[SERVERHARNESS] " + (passed ? "PASS " : "FAIL ") + name + " " + detail);
    }

    private void finish() {
        long failures = results.stream().filter(result -> !Boolean.TRUE.equals(result.get("passed"))).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("passed", failures == 0 && results.size() == 12);
        summary.put("checks", results.size());
        summary.put("failures", failures);
        summary.put("results", results);
        try {
            Path output = Path.of(System.getProperty("fawe.smokeTest.result", "server-test-results.json"));
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(summary) + "\n");
            System.out.println("[SERVERHARNESS] results: " + output.toAbsolutePath());
        } catch (Exception failure) {
            failure.printStackTrace();
        }
        System.out.println("[SERVERHARNESS] " + (results.size() - failures) + "/" + results.size() + " passed");
        server.halt(false);
    }

    private static List<BlockVector3> positions(BlockVector3 min, BlockVector3 max) {
        List<BlockVector3> result = new ArrayList<>();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    result.add(BlockVector3.at(x, y, z));
                }
            }
        }
        return result;
    }

    private static BlockPos nativePos(BlockVector3 position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static String coordinates(BlockVector3 position) {
        return position.x() + "," + position.y() + "," + position.z();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    /** Actor with no permission grants, so an explicit limit cannot be bypassed as an OP. */
    private static final class LimitedActor extends AbstractNonPlayerActor {

        private final UUID id = UUID.fromString("725f870c-253b-4d5f-8c75-0fa0e5173e15");

        @Override public String getName() { return "FAWE limited smoke actor"; }
        @Override public UUID getUniqueId() { return id; }
        @Override public String[] getGroups() { return new String[0]; }
        @Override public boolean hasPermission(String permission) { return false; }
        @Override public void setPermission(String permission, boolean value) { }
        @Override public void checkPermission(String permission) throws AuthorizationException {
            throw new AuthorizationException();
        }
        @Override public Locale getLocale() { return Locale.ROOT; }
        @Override public void printRaw(String message) { System.out.println("[SERVERHARNESS] " + message); }
        @Override public void printDebug(String message) { printRaw(message); }
        @Override public void print(String message) { printRaw(message); }
        @Override public void printError(String message) { printRaw(message); }
        @Override public void print(Component component) { printRaw(component.toString()); }
        @Override public SessionKey getSessionKey() {
            return new SessionKey() {
                @Override public UUID getUniqueId() { return id; }
                @Override public String getName() { return LimitedActor.this.getName(); }
                @Override public boolean isActive() { return true; }
                @Override public boolean isPersistent() { return false; }
            };
        }
    }
}
