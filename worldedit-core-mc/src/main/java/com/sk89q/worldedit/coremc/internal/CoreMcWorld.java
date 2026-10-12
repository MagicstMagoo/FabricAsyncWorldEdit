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

package com.sk89q.worldedit.coremc.internal;

import com.fastasyncworldedit.core.nbt.FaweCompoundTag;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.packet.ChunkPacket;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.coremc.mixin.AccessorChunkMap;
import com.sk89q.worldedit.coremc.mixin.AccessorMinecraftServer;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.AbstractExtentMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.Constants;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.util.TreeGenerator;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.AbstractWorld;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.generation.ConfiguredFeatureType;
import com.sk89q.worldedit.world.generation.StructureType;
import com.sk89q.worldedit.world.generation.TreeType;
import com.sk89q.worldedit.world.generation.WorldEditTreeGeneration;
import com.sk89q.worldedit.world.item.ItemTypes;
import com.sk89q.worldedit.world.weather.WeatherType;
import com.sk89q.worldedit.world.weather.WeatherTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.EndPlacements;
import net.minecraft.data.worldgen.placement.TreePlacements;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Shared implementation of a Minecraft world for platforms sharing native code.
 */
public final class CoreMcWorld extends AbstractWorld {

    private static final RandomSource random = RandomSource.create();

    static String nameForDimension(String levelName, String dimensionId) {
        if (dimensionId.equals("minecraft:overworld")) {
            return levelName;
        }
        return encodeWorldName(levelName) + "__" + encodeWorldName(dimensionId);
    }

    private static String encodeWorldName(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte rawByte : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(rawByte);
            if (unsigned >= 'a' && unsigned <= 'z' || unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= '0' && unsigned <= '9' || unsigned == '_' || unsigned == '-') {
                encoded.append((char) unsigned);
            } else {
                // Escape '%' too, so a literal escape-looking name cannot collide with an encoded name.
                encoded.append('%').append(HexFormat.of().toHexDigits(rawByte));
            }
        }
        return encoded.toString();
    }

    private final CoreMcPlatform platform;
    private final WeakReference<ServerLevel> worldRef;
    private final CoreMcWorldNativeAccess worldNativeAccess;
    // FAWE: cached so the name can be read without touching the level (see getNameUnsafe).
    private final String worldNameRef;
    private final String worldId;
    private final int minY;
    private final int maxY;
    // Native access and flush both run on the server thread.
    private final Set<BlockVector2> changedBiomeChunks = new HashSet<>();

    private record WorldInfo(String name, String id, int minY, int maxY) {
    }

    /**
     * Construct a new world.
     *
     * @param platform the owning platform
     * @param world the world
     */
    public CoreMcWorld(CoreMcPlatform platform, ServerLevel world) {
        checkNotNull(world);
        this.platform = platform;
        this.worldRef = new WeakReference<>(world);
        WorldInfo info = CoreMcThreading.call(world.getServer(), () -> {
            String levelName = world.getLevelData() instanceof ServerLevelData serverLevelData
                ? serverLevelData.getLevelName() : "world";
            String dimensionId = world.dimension().identifier().toString();
            return new WorldInfo(nameForDimension(levelName, dimensionId), levelName + "_" + dimensionId,
                    world.getMinY(), world.getMaxY());
        });
        this.worldNameRef = info.name();
        this.worldId = info.id();
        this.minY = info.minY();
        this.maxY = info.maxY();
        this.worldNativeAccess = new CoreMcWorldNativeAccess(platform, worldRef);
    }

    /**
     * Get the underlying handle to the world.
     *
     * @return the world
     * @throws RuntimeException thrown if a reference to the world was lost (i.e. world was unloaded)
     */
    public ServerLevel getWorld() {
        ServerLevel world = worldRef.get();
        if (world != null) {
            return world;
        } else {
            throw new RuntimeException("The reference to the world was lost (i.e. the world may have been unloaded)");
        }
    }

    @Override
    public String getName() {
        return worldNameRef;
    }

    // FAWE start
    @Override
    public String getNameUnsafe() {
        return worldNameRef;
    }
    // FAWE end

    @Override
    public String id() {
        return worldId;
    }

    @Override
    public Path getStoragePath() {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getStoragePath());
        }
        ServerLevel world = getWorld();
        return ((AccessorMinecraftServer) world.getServer()).getStorageSource().getDimensionPath(world.dimension());
    }

    @Override
    public <B extends BlockStateHolder<B>> boolean setBlock(BlockVector3 position, B block, SideEffectSet sideEffects) throws WorldEditException {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> setBlock(snapshot, block, sideEffects));
        }
        clearContainerBlockContents(position);
        return worldNativeAccess.setBlock(position, block, sideEffects);
    }

    @Override
    public Set<SideEffect> applySideEffects(BlockVector3 position, BlockState previousType, SideEffectSet sideEffectSet) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> applySideEffects(snapshot, previousType, sideEffectSet));
        }
        worldNativeAccess.applySideEffects(position, previousType, sideEffectSet);
        return Sets.intersection(platform.getSupportedSideEffects(), sideEffectSet.getSideEffectsToApply());
    }

    @Override
    public int getBlockLightLevel(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> getBlockLightLevel(snapshot));
        }
        checkNotNull(position);
        return getWorld().getMaxLocalRawBrightness(platform.getAdapter().toBlockPos(position));
    }

    @Override
    public boolean clearContainerBlockContents(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> clearContainerBlockContents(snapshot));
        }
        checkNotNull(position);
        BlockEntity tile = getWorld().getBlockEntity(platform.getAdapter().toBlockPos(position));
        if (tile instanceof Clearable clearable) {
            clearable.clearContent();
            return true;
        }
        return false;
    }

    @Override
    public BiomeType getBiome(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> getBiome(snapshot));
        }
        checkNotNull(position);
        ChunkAccess chunk = getWorld().getChunk(position.x() >> 4, position.z() >> 4);
        return getBiomeInChunk(position, chunk);
    }

    private BiomeType getBiomeInChunk(BlockVector3 position, ChunkAccess chunk) {
        return platform.getAdapter().fromNativeBiome(
            chunk.getNoiseBiome(position.x() >> 2, position.y() >> 2, position.z() >> 2).value()
        );
    }

    @Override
    public boolean setBiome(BlockVector3 position, BiomeType biome) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> setBiome(snapshot, biome));
        }
        checkNotNull(position);
        checkNotNull(biome);

        ChunkAccess chunk = getWorld().getChunk(position.x() >> 4, position.z() >> 4);
        // Screw it, we know it's really mutable...
        var biomeArray = (PalettedContainer<Holder<Biome>>) chunk.getSection(chunk.getSectionIndex(position.y())).getBiomes();
        biomeArray.getAndSetUnchecked(
            (position.x() >> 2) & 3, (position.y() >> 2) & 3, (position.z() >> 2) & 3,
            getWorld().registryAccess().lookupOrThrow(Registries.BIOME)
                .getOrThrow(ResourceKey.create(Registries.BIOME, Identifier.parse(biome.id())))
        );
        chunk.markUnsaved();
        changedBiomeChunks.add(BlockVector2.at(position.x() >> 4, position.z() >> 4));
        return true;
    }

    @Override
    public boolean setBiome(int x, int y, int z, BiomeType biome) {
        return setBiome(BlockVector3.at(x, y, z), biome);
    }

    private static final LoadingCache<ServerLevel, CoreMcFakePlayer> fakePlayers
        = CacheBuilder.newBuilder().weakKeys().softValues().build(CacheLoader.from(CoreMcFakePlayer::new));

    @Override
    public boolean useItem(BlockVector3 position, BaseItem item, Direction face) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> useItem(snapshot, item, face));
        }
        ItemStack stack = platform.getAdapter().toNativeItemStack(new BaseItemStack(item.getType(), item.getNbtReference(), 1));
        ServerLevel world = getWorld();
        final CoreMcFakePlayer fakePlayer;
        try {
            fakePlayer = fakePlayers.get(world);
        } catch (ExecutionException _) {
            return false;
        }
        fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, stack);
        fakePlayer.absSnapTo(position.x(), position.y(), position.z(),
            (float) face.toVector().toYaw(), (float) face.toVector().toPitch());
        final BlockPos blockPos = platform.getAdapter().toBlockPos(position);
        final BlockHitResult rayTraceResult = new BlockHitResult(platform.getAdapter().toVec3(position),
            platform.getAdapter().adapt(face), blockPos, false);
        UseOnContext itemUseContext = new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
        InteractionResult used = stack.useOn(itemUseContext);
        if (!used.consumesAction()) {
            used = world.getBlockState(blockPos).useItemOn(stack, world, fakePlayer, InteractionHand.MAIN_HAND, rayTraceResult);
            if (!used.consumesAction()) {
                used = stack.getItem().use(world, fakePlayer, InteractionHand.MAIN_HAND);
            }
        }
        return used.consumesAction();
    }

    @Override
    public void dropItem(Vector3 position, BaseItemStack item) {
        if (!getWorld().getServer().isSameThread()) {
            Vector3 snapshot = Vector3.at(position.x(), position.y(), position.z());
            CoreMcThreading.call(getWorld().getServer(), () -> {
                dropItem(snapshot, item);
                return null;
            });
            return;
        }
        checkNotNull(position);
        checkNotNull(item);

        if (item.getType() == ItemTypes.AIR) {
            return;
        }

        ItemEntity entity = new ItemEntity(getWorld(), position.x(), position.y(), position.z(), platform.getAdapter().toNativeItemStack(item));
        entity.setPickUpDelay(10);
        getWorld().addFreshEntity(entity);
    }

    @Override
    public void simulateBlockMine(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            CoreMcThreading.call(getWorld().getServer(), () -> {
                simulateBlockMine(snapshot);
                return null;
            });
            return;
        }
        BlockPos pos = platform.getAdapter().toBlockPos(position);
        getWorld().destroyBlock(pos, true);
    }

    @Override
    public boolean canPlaceAt(BlockVector3 position, BlockState blockState) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> canPlaceAt(snapshot, blockState));
        }
        return platform.getAdapter().toNativeBlockState(blockState).canSurvive(getWorld(), platform.getAdapter().toBlockPos(position));
    }

    @Override
    public boolean regenerate(Region region, Extent extent, RegenOptions options) {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> regenerate(region, extent, options));
        }
        if (options.getSeed().isPresent()) {
            throw new UnsupportedOperationException("26.1+ worldgen does not support overriding the seed for regen");
        }

        try {
            doRegen(region, extent, options);
        } catch (Exception e) {
            throw new IllegalStateException("Regen failed", e);
        }

        return true;
    }

    private void doRegen(Region region, Extent extent, RegenOptions options) throws Exception {
        Path tempDir = Files.createTempDirectory("WorldEditWorldGen");
        LevelStorageSource levelStorage = LevelStorageSource.createDefault(tempDir);
        try (LevelStorageSource.LevelStorageAccess session = levelStorage.createAccess("WorldEditTempGen")) {
            ServerLevel originalWorld = getWorld();

            ResourceKey<Level> worldRegKey = originalWorld.dimension();
            try (ServerLevel serverWorld = new ServerLevel(
                originalWorld.getServer(), Util.backgroundExecutor(), session,
                ((ServerLevelData) originalWorld.getLevelData()),
                worldRegKey,
                new LevelStem(
                    originalWorld.dimensionTypeRegistration(),
                    originalWorld.getChunkSource().getGenerator()
                ),
                originalWorld.isDebug(),
                originalWorld.getSeed(),
                // No spawners are needed for this world.
                ImmutableList.of(),
                // This controls ticking, we don't need it so set it to false.
                false
            )) {
                regenForWorld(region, extent, serverWorld, options);

                // drive the server executor until all tasks are popped off
                originalWorld.getServer().managedBlock(() -> originalWorld.getServer().getPendingTasksCount() == 0);
            }
        } finally {
            SafeFiles.tryHardToDeleteDir(tempDir);
        }
    }

    private void regenForWorld(Region region, Extent extent, ServerLevel serverWorld,
                               RegenOptions options) throws WorldEditException {
        List<CompletableFuture<ChunkAccess>> chunkLoadings = submitChunkLoadTasks(region, serverWorld);

        // drive executor until loading finishes
        ((AccessorChunkMap) serverWorld.getChunkSource().chunkMap).getMainThreadExecutor()
            .managedBlock(() -> chunkLoadingFinished(chunkLoadings));

        Map<ChunkPos, ChunkAccess> chunks = new HashMap<>();
        for (CompletableFuture<ChunkAccess> future : chunkLoadings) {
            @Nullable
            ChunkAccess chunk = future.getNow(null);
            checkState(chunk != null, "Failed to generate a chunk, regen failed.");
            chunks.put(chunk.getPos(), chunk);
        }

        for (BlockVector3 vec : region) {
            BlockPos pos = platform.getAdapter().toBlockPos(vec);
            ChunkAccess chunk = chunks.get(ChunkPos.containing(pos));
            BlockStateHolder<?> state = platform.getAdapter().fromNativeBlockState(chunk.getBlockState(pos));
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) {
                CompoundTag tag = CoreMcLoggingProblemReporter.with(
                    () -> "serializing block entity at " + pos,
                    reporter -> {
                        var tagValueOutput = TagValueOutput.createWithContext(reporter, getWorld().registryAccess());
                        blockEntity.saveWithId(tagValueOutput);
                        return tagValueOutput.buildResult();
                    }
                );
                state = state.toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(tag)));
            }
            extent.setBlock(vec, state.toBaseBlock());

            if (options.shouldRegenBiomes()) {
                BiomeType biome = getBiomeInChunk(vec, chunk);
                extent.setBiome(vec, biome);
            }
        }
    }

    static boolean chunkLoadingFinished(List<? extends CompletableFuture<?>> futures) {
        // managedBlock waits while its predicate is false. Failed/null results must end the wait,
        // so the validation below can report failure instead of leaving the server in an infinite loop.
        return futures.stream().anyMatch(future -> future.isCompletedExceptionally()
                || future.isDone() && future.getNow(null) == null)
                || futures.stream().allMatch(CompletableFuture::isDone);
    }

    private List<CompletableFuture<ChunkAccess>> submitChunkLoadTasks(Region region, ServerLevel world) {
        List<CompletableFuture<ChunkAccess>> chunkLoadings = new ArrayList<>();
        // Pre-gen all the chunks
        for (BlockVector2 chunk : region.getChunks()) {
            chunkLoadings.add(
                world.getChunkSource().getChunkFuture(chunk.x(), chunk.z(), ChunkStatus.FEATURES, true)
                    .thenApply(either -> either.orElse(null))
            );
        }
        return chunkLoadings;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static ResourceKey<PlacedFeature> createTreeFeatureGenerator(TreeGenerator.TreeType type) {
        return switch (type) {
            // Based off of the SaplingGenerator class, as well as uses of DefaultBiomeFeatures fields
            case TREE -> TreePlacements.OAK_CHECKED;
            case BIG_TREE -> TreePlacements.FANCY_OAK_CHECKED;
            case REDWOOD -> TreePlacements.SPRUCE_CHECKED;
            case TALL_REDWOOD -> TreePlacements.MEGA_SPRUCE_CHECKED;
            case MEGA_REDWOOD -> TreePlacements.MEGA_PINE_CHECKED;
            case BIRCH -> TreePlacements.BIRCH_CHECKED;
            case JUNGLE -> TreePlacements.MEGA_JUNGLE_TREE_CHECKED;
            case SMALL_JUNGLE -> TreePlacements.JUNGLE_TREE_CHECKED;
            case SHORT_JUNGLE -> TreePlacements.JUNGLE_TREE_CHECKED;
            case JUNGLE_BUSH -> TreePlacements.JUNGLE_BUSH;
            case SWAMP -> TreePlacements.OAK_CHECKED;
            case ACACIA -> TreePlacements.ACACIA_CHECKED;
            case DARK_OAK -> TreePlacements.DARK_OAK_CHECKED;
            case TALL_BIRCH -> TreePlacements.SUPER_BIRCH_BEES_0002;
            case WARPED_FUNGUS -> TreePlacements.WARPED_FUNGI;
            case CRIMSON_FUNGUS -> TreePlacements.CRIMSON_FUNGI;
            case CHORUS_PLANT -> EndPlacements.CHORUS_PLANT;
            case MANGROVE -> TreePlacements.MANGROVE_CHECKED;
            case TALL_MANGROVE -> TreePlacements.TALL_MANGROVE_CHECKED;
            case CHERRY -> TreePlacements.CHERRY_CHECKED;
            case PALE_OAK -> TreePlacements.PALE_OAK_CHECKED;
            case PALE_OAK_CREAKING -> TreePlacements.PALE_OAK_CREAKING_CHECKED;
            case RANDOM -> {
                // We're intentionally using index here to get a random tree type
                @SuppressWarnings("EnumOrdinal")
                TreeGenerator.TreeType randomTreeType = TreeGenerator.TreeType.values()[ThreadLocalRandom.current().nextInt(TreeGenerator.TreeType.values().length)];
                yield createTreeFeatureGenerator(randomTreeType);
            }
            default -> null;
        };
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean generateTree(TreeGenerator.TreeType type, EditSession editSession, BlockVector3 position) throws MaxChangedBlocksException {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> generateTree(type, editSession, snapshot));
        }
        ServerLevel world = getWorld();
        PlacedFeature generator = Optional.ofNullable(createTreeFeatureGenerator(type))
            .map(k -> world.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getValue(k))
            .orElse(null);
        ServerChunkCache chunkManager = world.getChunkSource();
        if (type == TreeGenerator.TreeType.CHORUS_PLANT) {
            position = position.add(0, 1, 0);
        }
        try (CoreMcServerLevelDelegateProxy.LevelAndProxy proxyLevel = CoreMcServerLevelDelegateProxy.newInstance(platform, editSession, world)) {
            return generator != null && generator.place(
                proxyLevel.level(), chunkManager.getGenerator(), random,
                platform.getAdapter().toBlockPos(position)
            );
        }
    }

    @Override
    public boolean generateTree(TreeType type, EditSession editSession, BlockVector3 position) throws MaxChangedBlocksException {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> generateTree(type, editSession, snapshot));
        }
        Boolean customResult = WorldEditTreeGeneration.handleWorldEditTrees(this, type, editSession, position);
        if (customResult != null) {
            return customResult;
        }
        ServerLevel world = getWorld();
        PlacedFeature generator = world.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getValue(Identifier.tryParse(type.id()));
        ServerChunkCache chunkManager = world.getChunkSource();
        try (CoreMcServerLevelDelegateProxy.LevelAndProxy proxyLevel = CoreMcServerLevelDelegateProxy.newInstance(platform, editSession, world)) {
            return generator != null && generator.place(
                proxyLevel.level(), chunkManager.getGenerator(), random,
                platform.getAdapter().toBlockPos(position)
            );
        }
    }

    @Override
    public boolean generateFeature(ConfiguredFeatureType type, EditSession editSession, BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> generateFeature(type, editSession, snapshot));
        }
        ServerLevel world = getWorld();
        Feature feature = world.registryAccess().lookupOrThrow(Registries.FEATURE).getValue(Identifier.tryParse(type.id()));
        ServerChunkCache chunkManager = world.getChunkSource();
        try (CoreMcServerLevelDelegateProxy.LevelAndProxy proxyLevel = CoreMcServerLevelDelegateProxy.newInstance(platform, editSession, world)) {
            return feature != null && feature.place(
                proxyLevel.level(), chunkManager.getGenerator(), random,
                platform.getAdapter().toBlockPos(position)
            );
        } catch (MaxChangedBlocksException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean generateStructure(StructureType type, EditSession editSession, BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> generateStructure(type, editSession, snapshot));
        }
        ServerLevel world = getWorld();
        Registry<Structure> structureRegistry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Structure structure = structureRegistry.getValue(Identifier.tryParse(type.id()));
        if (structure == null) {
            return false;
        }

        ServerChunkCache chunkManager = world.getChunkSource();
        Climate.Sampler climateSampler = chunkManager.randomState().createClimateSampler(
            SamplerContext.builder().enableCaches().build()
        );
        try (CoreMcServerLevelDelegateProxy.LevelAndProxy proxyLevel = CoreMcServerLevelDelegateProxy.newInstance(platform, editSession, world)) {
            ChunkPos chunkPos = ChunkPos.containing(new BlockPos(position.x(), position.y(), position.z()));
            StructureStart structureStart = structure.generate(
                structureRegistry.wrapAsHolder(structure), world.dimension(), world.registryAccess(),
                chunkManager.getGenerator(), chunkManager.getGenerator().getBiomeSource(), climateSampler, chunkManager.randomState(),
                world.getStructureTemplateManager(), world.getSeed(), chunkPos, 0, proxyLevel.level(),
                _ -> true
            );

            if (!structureStart.isValid()) {
                return false;
            } else {
                BoundingBox boundingBox = structureStart.getBoundingBox();
                ChunkPos min = new ChunkPos(SectionPos.blockToSectionCoord(boundingBox.minX()), SectionPos.blockToSectionCoord(boundingBox.minZ()));
                ChunkPos max = new ChunkPos(SectionPos.blockToSectionCoord(boundingBox.maxX()), SectionPos.blockToSectionCoord(boundingBox.maxZ()));
                ChunkPos.rangeClosed(min, max).forEach((chunkPosx) ->
                    structureStart.placeInChunk(
                        proxyLevel.level(), world.structureManager(), chunkManager.getGenerator(), world.getRandom(),
                        new BoundingBox(chunkPosx.getMinBlockX(), world.getMinY(), chunkPosx.getMinBlockZ(),
                            chunkPosx.getMaxBlockX(), world.getMaxY(), chunkPosx.getMaxBlockZ()),
                        chunkPosx
                    )
                );
                return true;
            }
        } catch (MaxChangedBlocksException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void checkLoadedChunk(BlockVector3 pt) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(pt.x(), pt.y(), pt.z());
            CoreMcThreading.call(getWorld().getServer(), () -> {
                checkLoadedChunk(snapshot);
                return null;
            });
            return;
        }
        getWorld().getChunk(platform.getAdapter().toBlockPos(pt));
    }

    @Override
    public void fixAfterFastMode(Iterable<BlockVector2> chunks) {
        fixLighting(chunks);
    }

    // FAWE: sendBiomeUpdates/isValid are not part of this fork's World interface.
    public void sendBiomeUpdates(Iterable<BlockVector2> chunks) {
        if (!getWorld().getServer().isSameThread()) {
            CoreMcThreading.call(getWorld().getServer(), () -> {
                sendBiomeUpdates(chunks);
                return null;
            });
            return;
        }
        List<ChunkAccess> nativeChunks = chunks instanceof Collection<BlockVector2> chunkCollection ? Lists.newArrayListWithCapacity(chunkCollection.size()) : Lists.newArrayList();
        for (BlockVector2 chunk : chunks) {
            nativeChunks.add(getWorld().getChunk(chunk.x(), chunk.z(), ChunkStatus.BIOMES, false));
        }
        getWorld().getChunkSource().chunkMap.resendBiomesForChunks(nativeChunks);
    }

    @Override
    public void fixLighting(Iterable<BlockVector2> chunks) {
        if (!getWorld().getServer().isSameThread()) {
            CoreMcThreading.call(getWorld().getServer(), () -> {
                fixLighting(chunks);
                return null;
            });
            return;
        }
        ServerLevel world = getWorld();
        for (BlockVector2 chunk : chunks) {
            // Fetch the chunk after light initialization at least
            // We'll be doing a full relight anyways, so we don't need to be LIGHT yet
            world.getChunkSource().getLightEngine().lightChunk(world.getChunk(
                chunk.x(), chunk.z(), ChunkStatus.INITIALIZE_LIGHT
            ), false).exceptionally(t -> {
                WorldEdit.logger.warn("Failed to relight chunk at " + chunk, t);
                return null;
            });
        }
    }

    @Override
    public WeatherType getWeather() {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getWeather());
        }
        WeatherData weatherData = getWorld().getServer().getWeatherData();
        if (weatherData.isThundering()) {
            return WeatherTypes.THUNDER_STORM;
        }
        if (weatherData.isRaining()) {
            return WeatherTypes.RAIN;
        }
        return WeatherTypes.CLEAR;
    }

    @Override
    public long getRemainingWeatherDuration() {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getRemainingWeatherDuration());
        }
        WeatherData weatherData = getWorld().getServer().getWeatherData();
        if (weatherData.isThundering()) {
            return weatherData.getThunderTime();
        }
        if (weatherData.isRaining()) {
            return weatherData.getRainTime();
        }
        return weatherData.getClearWeatherTime();
    }

    @Override
    public void setWeather(WeatherType weatherType) {
        setWeather(weatherType, 0);
    }

    @Override
    public void setWeather(WeatherType weatherType, long duration) {
        if (!getWorld().getServer().isSameThread()) {
            CoreMcThreading.call(getWorld().getServer(), () -> {
                setWeather(weatherType, duration);
                return null;
            });
            return;
        }
        WeatherData weatherData = getWorld().getServer().getWeatherData();
        if (weatherType == WeatherTypes.THUNDER_STORM) {
            weatherData.setClearWeatherTime(0);
            weatherData.setThundering(true);
            weatherData.setThunderTime((int) duration);
        } else if (weatherType == WeatherTypes.RAIN) {
            weatherData.setClearWeatherTime(0);
            weatherData.setRaining(true);
            weatherData.setRainTime((int) duration);
        } else if (weatherType == WeatherTypes.CLEAR) {
            weatherData.setRaining(false);
            weatherData.setThundering(false);
            weatherData.setClearWeatherTime((int) duration);
        }
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public BlockVector3 getSpawnPosition() {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getSpawnPosition());
        }
        return platform.getAdapter().adapt(getWorld().getLevelData().getRespawnData().pos());
    }

    @Override
    public BlockState getBlock(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> getBlock(snapshot));
        }
        net.minecraft.world.level.block.state.BlockState mcState = getWorld()
            .getChunk(position.x() >> 4, position.z() >> 4)
            .getBlockState(platform.getAdapter().toBlockPos(position));
        return platform.getAdapter().fromNativeBlockState(mcState);
    }

    @Override
    public BaseBlock getFullBlock(BlockVector3 position) {
        if (!getWorld().getServer().isSameThread()) {
            BlockVector3 snapshot = BlockVector3.at(position.x(), position.y(), position.z());
            return CoreMcThreading.call(getWorld().getServer(), () -> getFullBlock(snapshot));
        }
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        // Avoid creation by using the CHECK mode -- if it's needed, it'll be re-created anyways
        BlockEntity tile = ((LevelChunk) getWorld().getChunk(pos)).getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);

        if (tile != null) {
            CompoundTag tag = CoreMcLoggingProblemReporter.with(
                () -> "serializing block entity at " + pos,
                reporter -> {
                    var tagValueOutput = TagValueOutput.createWithContext(reporter, getWorld().registryAccess());
                    tile.saveWithId(tagValueOutput);
                    return tagValueOutput.buildResult();
                }
            );
            return getBlock(position).toBaseBlock(LazyReference.from(() -> NBTConverter.fromNative(tag)));
        } else {
            return getBlock(position).toBaseBlock();
        }
    }

    @Override
    public int hashCode() {
        return worldId.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return switch (o) {
            case CoreMcWorld other -> {
                ServerLevel otherWorld = other.worldRef.get();
                ServerLevel thisWorld = worldRef.get();
                yield otherWorld != null && otherWorld.equals(thisWorld);
            }
            case World world -> world.id().equals(id());
            case null, default -> false;
        };
    }

    @Override
    public List<? extends Entity> getEntities(Region region) {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getEntities(region));
        }
        final ServerLevel world = getWorld();
        AABB box = new AABB(
            platform.getAdapter().toVec3(region.getMinimumPoint()),
            platform.getAdapter().toVec3(region.getMaximumPoint().add(BlockVector3.ONE))
        );
        List<net.minecraft.world.entity.Entity> nmsEntities = world.getEntities(
            (net.minecraft.world.entity.Entity) null,
            box,
            e -> region.contains(platform.getAdapter().adapt(e.blockPosition()))
        );
        return nmsEntities.stream()
            .map(e -> new CoreMcEntity(platform, e))
            .collect(ImmutableList.toImmutableList());
    }

    @Override
    public List<? extends Entity> getEntities() {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> getEntities());
        }
        final ServerLevel world = getWorld();
        return Streams.stream(world.getAllEntities())
            .map(entity -> new CoreMcEntity(platform, entity))
            .collect(ImmutableList.toImmutableList());
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity entity) {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> createEntity(location, entity));
        }
        ServerLevel world = getWorld();
        String entityId = entity.getType().id();
        final Optional<EntityType<?>> entityType = world.registryAccess().lookupOrThrow(Registries.ENTITY_TYPE).getOptional(Identifier.parse(entityId));
        if (entityType.isEmpty()) {
            return null;
        }
        LinCompoundTag linTag = entity.getNbt();
        CompoundTag tag;
        if (linTag != null) {
            tag = NBTConverter.toNative(linTag);
            removeUnwantedEntityTagsRecursively(tag);
        } else {
            tag = new CompoundTag();
        }
        tag.putString("id", entityId);

        net.minecraft.world.entity.Entity createdEntity = EntityType.loadEntityRecursive(entityType.get(), tag, world, EntitySpawnReason.COMMAND, loadedEntity -> {
            loadedEntity.absSnapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            return loadedEntity;
        });
        if (createdEntity != null) {
            world.addFreshEntityWithPassengers(createdEntity);
            return new CoreMcEntity(platform, createdEntity);
        }
        return null;
    }

    private void removeUnwantedEntityTagsRecursively(CompoundTag tag) {
        for (String name : Constants.NO_COPY_ENTITY_NBT_FIELDS) {
            tag.remove(name);
        }

        // Adapted from net.minecraft.world.entity.EntityType#loadEntityRecursive
        tag.getList("Passengers").ifPresent(nbttaglist -> {
            for (int i = 0; i < nbttaglist.size(); ++i) {
                removeUnwantedEntityTagsRecursively(nbttaglist.getCompoundOrEmpty(i));
            }
        });
    }

    // FAWE start
    private static final class LiquidMask extends AbstractExtentMask {

        private final CoreMcPlatform platform;

        private LiquidMask(CoreMcPlatform platform, Extent extent) {
            super(extent);
            this.platform = platform;
        }

        @Override
        public boolean test(Extent extent, BlockVector3 position) {
            return platform.getAdapter().toNativeBlockState(extent.getBlock(position)).getBlock() instanceof LiquidBlock;
        }

        @Override
        public boolean test(BlockVector3 vector) {
            return test(getExtent(), vector);
        }

        @Override
        public Mask copy() {
            return new LiquidMask(platform, getExtent());
        }
    }

    @Override
    public Mask createLiquidMask() {
        return new LiquidMask(platform, this);
    }

    @Override
    public void flush() {
        if (!getWorld().getServer().isSameThread()) {
            CoreMcThreading.call(getWorld().getServer(), () -> {
                flush();
                return null;
            });
            return;
        }
        if (!changedBiomeChunks.isEmpty()) {
            sendBiomeUpdates(List.copyOf(changedBiomeChunks));
            changedBiomeChunks.clear();
        }
    }

    @Override
    public void sendFakeChunk(@Nullable Player player, ChunkPacket packet) {
        // Not supported on Fabric without additional networking hooks.
    }

    @Override
    public IChunkGet get(int x, int z) {
        throw new UnsupportedOperationException("Fabric uses individual block placement; native FAWE chunks are unavailable");
    }

    @Override
    public boolean supportsChunkPlacement() {
        return false;
    }

    @Override
    public void refreshChunk(int chunkX, int chunkZ) {
        if (!getWorld().getServer().isSameThread()) {
            CoreMcThreading.call(getWorld().getServer(), () -> {
                refreshChunk(chunkX, chunkZ);
                return null;
            });
            return;
        }
        getWorld().getChunk(chunkX, chunkZ);
    }

    @Override
    public boolean tile(int x, int y, int z, FaweCompoundTag tile) throws WorldEditException {
        if (!getWorld().getServer().isSameThread()) {
            return CoreMcThreading.call(getWorld().getServer(), () -> tile(x, y, z, tile));
        }
        BlockPos position = new BlockPos(x, y, z);
        LinCompoundTag tag = tile.linTag().toBuilder().putInt("x", x).putInt("y", y).putInt("z", z).build();
        if (!worldNativeAccess.updateTileEntity(position, tag)) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState state = getWorld().getBlockState(position);
        getWorld().sendBlockUpdated(position, state, state, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        return true;
    }
    // FAWE end

    public boolean isValid() {
        return worldRef.get() != null;
    }
}
