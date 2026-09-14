package com.explorermap.mod.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * Mod-owned persistent saved data holding all Explorer Map per-map entries,
 * keyed by the map's raw integer ID.
 *
 * Why the key is a plain int, not (dimension, id)
 * ─────────────────────────────────────────────────
 * An earlier draft of this class qualified the key by dimension as a
 * defensive measure. That turned out to introduce a real risk rather than
 * remove one: MapIdComponent IDs are drawn from a single counter shared by
 * the entire server (World.increaseAndGetMapId()), never reused across
 * dimensions, so the raw int is already a globally unique key. Qualifying
 * it by dimension only matters if every caller is guaranteed to pass the
 * map's own true dimension (mapState.dimension) — but callers often only
 * have "whichever World the player currently happens to be standing in"
 * on hand, and using that instead would split a single physical map across
 * two different, disconnected entries. A plain int key cannot suffer that
 * inconsistency, so that is what is used here.
 *
 * Architecture note
 * ─────────────────
 * An earlier revision tried to attach this data directly to MapState via
 * Fabric's Data Attachment API — an API that only targets Entity,
 * BlockEntity, ServerWorld, and Chunk, never PersistentState. That could not
 * compile. This class is the correct replacement: a real PersistentState
 * subclass, retrieved through ServerWorld.getPersistentStateManager().
 *
 * Dirty tracking
 * ──────────────
 * Every mutation method below calls markDirty() itself, so callers never
 * need to remember to do so manually.
 */
public class ExplorerMapSavedData extends PersistentState {

    private static final String SAVE_ID = "explorermap_data";

    private final Map<Integer, MapEntryData> entries = new HashMap<>();

    public ExplorerMapSavedData() {}

    private ExplorerMapSavedData(Map<Integer, MapEntryData> entries) {
        this.entries.putAll(entries);
    }

    // ── Access ───────────────────────────────────────────────────────────

    public MapEntryData getOrCreate(int mapId) {
        return entries.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    /** Convenience overload; the World parameter is accepted for call-site clarity but unused for keying. */
    public MapEntryData getOrCreate(World ignoredDimension, int mapId) {
        return getOrCreate(mapId);
    }

    public MapEntryData get(int mapId) {
        return entries.get(mapId);
    }

    public MapEntryData get(World ignoredDimension, int mapId) {
        return get(mapId);
    }

    // ── Dirty-tracking mutation wrappers ─────────────────────────────────

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

    // ── Server-side accessor ─────────────────────────────────────────────

    /**
     * Retrieves (creating if necessary) the single server-wide instance of
     * this saved data, stored under the Overworld's PersistentStateManager
     * as the canonical location — map IDs are drawn from one counter shared
     * by the whole server regardless of dimension, so there is exactly one
     * instance of this data for the entire server.
     */
    public static ExplorerMapSavedData get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("[ExplorerMap] Overworld is not loaded - cannot access saved data");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, SAVE_ID);
    }

    // ── Persistence ──────────────────────────────────────────────────────

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

    /**
     * Registration type for 1.21.1's PersistentStateManager API, which at
     * this version still uses the nested PersistentState.Type<T> record
     * with a BiFunction deserializer, rather than the later top-level
     * PersistentStateType introduced in subsequent 1.21.x releases.
     */
    public static final PersistentState.Type<ExplorerMapSavedData> TYPE = new PersistentState.Type<>(
            ExplorerMapSavedData::new,
            (nbt, registries) -> fromNbt(nbt, registries),
            null
    );

    private static ExplorerMapSavedData fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        var ops = net.minecraft.registry.RegistryOps.of(net.minecraft.nbt.NbtOps.INSTANCE, registries);
        return CODEC.parse(ops, nbt)
                .resultOrPartial(err -> com.explorermap.mod.ExplorerMapMod.LOGGER
                        .error("[ExplorerMap] Failed to parse saved data: {}", err))
                .orElseGet(ExplorerMapSavedData::new);
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        var ops = net.minecraft.registry.RegistryOps.of(net.minecraft.nbt.NbtOps.INSTANCE, registries);
        var encoded = CODEC.encodeStart(ops, this);
        encoded.resultOrPartial(err -> com.explorermap.mod.ExplorerMapMod.LOGGER
                .error("[ExplorerMap] Failed to encode saved data: {}", err))
                .ifPresent(tag -> {
                    if (tag instanceof NbtCompound compound) {
                        nbt.copyFrom(compound);
                    }
                });
        return nbt;
    }
}
