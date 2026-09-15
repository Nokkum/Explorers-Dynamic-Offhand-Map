package com.explorermap.mod.data;

import com.explorermap.mod.ExplorerMapMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

public class ExplorerMapSavedData extends PersistentState {

    private static final String SAVE_ID = "explorermap_data";

    private final Map<Integer, MapEntryData> entries = new HashMap<>();

    public ExplorerMapSavedData() {}

    private ExplorerMapSavedData(Map<Integer, MapEntryData> entries) {
        this.entries.putAll(entries);
    }

    public MapEntryData getOrCreate(int mapId) {
        return entries.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public MapEntryData getOrCreate(World ignoredDimension, int mapId) {
        return getOrCreate(mapId);
    }

    public MapEntryData get(int mapId) {
        return entries.get(mapId);
    }

    public MapEntryData get(World ignoredDimension, int mapId) {
        return get(mapId);
    }

    public boolean mergeBitmask(World dimension, int mapId, byte[] bitmask) {
        boolean changed = getOrCreate(mapId).mergeBitmask(bitmask);
        if (changed) markDirty();
        return changed;
    }

    public void addExpansion(World dimension, int mapId, com.explorermap.mod.expansion.ExpansionRecord record) {
        getOrCreate(mapId).addExpansion(record);
        markDirty();
    }

    public void addWaypoint(World dimension, int mapId, com.explorermap.mod.waypoint.Waypoint wp) {
        getOrCreate(mapId).addWaypoint(wp);
        markDirty();
    }

    public void removeWaypoint(World dimension, int mapId, String name) {
        getOrCreate(mapId).removeWaypoint(name);
        markDirty();
    }

    public static ExplorerMapSavedData get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("[ExplorerMap] Overworld is not loaded - cannot access saved data");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, SAVE_ID);
    }

    private record Entry(int key, MapEntryData value) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("key").forGetter(Entry::key),
                        MapEntryData.CODEC.fieldOf("value").forGetter(Entry::value)
                ).apply(instance, Entry::new)
        );
    }

    private record MapEntryList(java.util.List<Entry> entries) {}

    private static final Codec<MapEntryList> LIST_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Entry.CODEC.listOf().fieldOf("entries").forGetter(MapEntryList::entries)
            ).apply(instance, MapEntryList::new)
    );

    public static final Codec<ExplorerMapSavedData> CODEC = LIST_CODEC.xmap(
            list -> {
                Map<Integer, MapEntryData> map = new HashMap<>();
                for (Entry e : list.entries()) map.put(e.key(), e.value());
                return new ExplorerMapSavedData(map);
            },
            data -> {
                var list = new java.util.ArrayList<Entry>(data.entries.size());
                data.entries.forEach((k, v) -> list.add(new Entry(k, v)));
                return new MapEntryList(list);
            }
    );

    public static final PersistentState.Type<ExplorerMapSavedData> TYPE = new PersistentState.Type<>(
            ExplorerMapSavedData::new,
            (nbt, registries) -> fromNbt(nbt, registries),
            null
    );

    private static ExplorerMapSavedData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        var ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        return CODEC.parse(ops, nbt)
                .resultOrPartial(err -> ExplorerMapMod.LOGGER
                        .error("[ExplorerMap] Failed to parse saved data: {}", err))
                .orElseGet(ExplorerMapSavedData::new);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        var ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        var encoded = CODEC.encodeStart(ops, this);
        encoded.resultOrPartial(err -> ExplorerMapMod.LOGGER
                .error("[ExplorerMap] Failed to encode saved data: {}", err))
                .ifPresent(tag -> {
                    if (tag instanceof NbtCompound compound) {
                        nbt.copyFrom(compound);
                    }
                });
        return nbt;
    }
}
