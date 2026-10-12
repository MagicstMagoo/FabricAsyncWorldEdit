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

import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import com.sk89q.worldedit.world.registry.BlockRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * Block registry implementation for platforms sharing native code.
 */
public final class CoreMcBlockRegistry implements BlockRegistry {

    private final CoreMcPlatform platform;
    private final Map<net.minecraft.world.level.block.state.BlockState, CoreMcBlockMaterial> materialMap = new HashMap<>();

    public CoreMcBlockRegistry(CoreMcPlatform platform) {
        this.platform = platform;
    }

    @Override
    public Component getRichName(BlockType blockType) {
        return TranslatableComponent.of(platform.getAdapter().toNativeBlock(blockType).getDescriptionId());
    }

    @Override
    public BlockMaterial getMaterial(BlockType blockType) {
        Block block = platform.getAdapter().toNativeBlock(blockType);
        return materialMap.computeIfAbsent(
            block.defaultBlockState(),
            CoreMcBlockMaterial::new
        );
    }

    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        Block block = platform.getAdapter().toNativeBlock(blockType);
        Map<String, Property<?>> map = new TreeMap<>();
        Collection<net.minecraft.world.level.block.state.properties.Property<?>> propertyKeys = block
            .defaultBlockState()
            .getProperties();
        for (net.minecraft.world.level.block.state.properties.Property<?> key : propertyKeys) {
            map.put(key.getName(), platform.getTransmogrifier().transmogToWorldEditProperty(key));
        }
        return map;
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockState state) {
        net.minecraft.world.level.block.state.BlockState equivalent = platform.getAdapter().toNativeBlockState(state);
        return OptionalInt.of(Block.getId(equivalent));
    }

    // FAWE start
    @Override
    public Collection<String> values() {
        List<String> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
            // Minecraft 26.3 returns a Stream<Value<?>> from getValues(); iterate the declared
            // properties instead, which is valid for both the old and the new shape.
            List<net.minecraft.world.level.block.state.properties.Property<?>> properties = state.getProperties()
                .stream()
                .sorted(Comparator.comparing(net.minecraft.world.level.block.state.properties.Property::getName))
                .toList();
            if (properties.isEmpty()) {
                blocks.add(id.toString());
                continue;
            }
            StringBuilder builder = new StringBuilder(id.toString()).append('[');
            boolean first = true;
            for (net.minecraft.world.level.block.state.properties.Property<?> property : properties) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(property.getName()).append('=');
                Comparable<?> value = (Comparable<?>) state.getValue(property);
                if (value instanceof StringRepresentable stringRepresentable) {
                    builder.append(stringRepresentable.getSerializedName());
                } else {
                    builder.append(value);
                }
            }
            builder.append(']');
            blocks.add(builder.toString());
        }
        return blocks;
    }

    @Override
    public Map<String, ? extends List<Property<?>>> getAllProperties() {
        Map<String, List<Property<?>>> all = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (net.minecraft.world.level.block.state.properties.Property<?> property : block.defaultBlockState().getProperties()) {
                Property<?> weProperty = platform.getTransmogrifier().transmogToWorldEditProperty(property);
                String key = weProperty.getName().toLowerCase(Locale.ROOT);
                all.computeIfAbsent(key, ignored -> new ArrayList<>()).add(weProperty);
            }
        }
        return all;
    }
    // FAWE end
}
