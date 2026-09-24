package com.explorermap.data;

import com.explorermap.ExplorerMapMod;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.waypoint.Waypoint;
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

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ExplorerMapSavedData extends PersistentState {

    private static final String SAVE_ID = "explorermap_data";

    private final Map<Integer, MapEntryData> entries = new HashMap<>();
    private final Map<TileKey, Integer> tileIndex = new HashMap<>();
    private final Map<Integer, Map<String, Set<String>>> waypointShares = new HashMap<>();

    public ExplorerMapSavedData() {}

    private ExplorerMapSavedData(Map<Integer, MapEntryData> entries,
                                 Map<TileKey, Integer> tileIndex,
                                 Map<Integer, Map<String, Set<String>>> waypointShares) {
        this.entries.putAll(entries);
        this.tileIndex.putAll(tileIndex);
        waypointShares.forEach((mapId, recipients) -> this.waypointShares.put(
                mapId,
                new HashMap<>(recipients)
        ));
    }

    public MapEntryData getOrCreate(int mapId) {
        return entries.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public MapEntryData get(int mapId) {
        return entries.get(mapId);
    }

    public boolean mergeBitmask(int mapId, byte[] bitmask) {
        boolean changed = getOrCreate(mapId).mergeBitmask(bitmask);
        if (changed) markDirty();
        return changed;
    }

    public void addExpansion(int mapId, ExpansionRecord record) {
        getOrCreate(mapId).addExpansion(record);
        markDirty();
    }

    public void addWaypoint(int mapId, Waypoint wp) {
        getOrCreate(mapId).addWaypoint(wp);
        markDirty();
    }

    public void removeWaypoint(int mapId, String name) {
        getOrCreate(mapId).removeWaypoint(name);
        removeWaypointShares(mapId, name);
        markDirty();
    }

    public boolean canManageWaypoint(int mapId, String name, UUID playerId) {
        var entry = get(mapId);
        if (entry == null || playerId == null) return false;

        return entry.getWaypoints().stream()
                .filter(wp -> wp.name().equals(name))
                .findFirst()
                .map(wp -> wp.isOwnedBy(playerId))
                .orElse(false);
    }

    public boolean grantWaypointShare(int mapId, String name, UUID recipientId) {
        if (recipientId == null) return false;
        var entry = get(mapId);
        if (entry == null) return false;

        var waypoint = entry.getWaypoints().stream()
                .filter(wp -> wp.name().equals(name))
                .findFirst()
                .orElse(null);
        if (waypoint == null || waypoint.isSystemOrLegacy()) return false;

        waypointShares
                .computeIfAbsent(mapId, ignored -> new HashMap<>())
                .computeIfAbsent(recipientId.toString(), ignored -> new HashSet<>())
                .add(name);
        markDirty();
        return true;
    }

    public void removeWaypointShares(int mapId, String name) {
        var recipients = waypointShares.get(mapId);
        if (recipients == null) return;

        recipients.values().forEach(names -> names.remove(name));
        recipients.values().removeIf(Set::isEmpty);
        if (recipients.isEmpty()) waypointShares.remove(mapId);
    }

    public List<Waypoint> visibleWaypoints(int mapId, UUID playerId) {
        var entry = get(mapId);
        if (entry == null) return List.of();

        String playerUuid = playerId == null ? "" : playerId.toString();
        var recipients = waypointShares.getOrDefault(mapId, Map.of());
        var sharedNames = recipients.getOrDefault(playerUuid, Set.of());

        return entry.getWaypoints().stream()
                .filter(wp -> wp.isSystemOrLegacy()
                        || playerUuid.equals(wp.ownerUuid())
                        || sharedNames.contains(wp.name()))
                .toList();
    }

    public boolean isAccessibleFrom(int heldRootId, int targetMapId) {
        if (heldRootId == targetMapId) return true;

        Set<Integer> visited = new HashSet<>();
        Deque<Integer> pending = new ArrayDeque<>();
        pending.add(heldRootId);

        while (!pending.isEmpty()) {
            int currentMapId = pending.removeFirst();
            if (!visited.add(currentMapId)) continue;

            var currentEntry = get(currentMapId);
            if (currentEntry == null) continue;

            for (ExpansionRecord record : currentEntry.getExpansions()) {
                if (record.mapId() == targetMapId) return true;
                if (!visited.contains(record.mapId())) {
                    pending.addLast(record.mapId());
                }
            }
        }

        return false;
    }

    public record TileKey(String dimension, int scale, int centerX, int centerZ) {
        static final Codec<TileKey> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.STRING.fieldOf("dimension").forGetter(TileKey::dimension),
                        Codec.INT.fieldOf("scale").forGetter(TileKey::scale),
                        Codec.INT.fieldOf("center_x").forGetter(TileKey::centerX),
                        Codec.INT.fieldOf("center_z").forGetter(TileKey::centerZ)
                ).apply(instance, TileKey::new)
        );
    }

    public Integer findTileId(World dimension, int scale, int centerX, int centerZ) {
        return tileIndex.get(new TileKey(dimension.getRegistryKey().getValue().toString(), scale, centerX, centerZ));
    }

    public void recordTile(World dimension, int scale, int centerX, int centerZ, int mapId) {
        tileIndex.put(new TileKey(dimension.getRegistryKey().getValue().toString(), scale, centerX, centerZ), mapId);
        markDirty();
    }

    public static ExplorerMapSavedData get(MinecraftServer server) {
        ServerWorld overworld = server.getWorld(World.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("[ExplorerMap] Overworld is not loaded - cannot access saved data");
        }
        return overworld.getPersistentStateManager().getOrCreate(TYPE, SAVE_ID);
    }

    private record EntryRow(int key, MapEntryData value) {
        static final Codec<EntryRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("key").forGetter(EntryRow::key),
                        MapEntryData.CODEC.fieldOf("value").forGetter(EntryRow::value)
                ).apply(instance, EntryRow::new)
        );
    }

    private record TileRow(TileKey key, int mapId) {
        static final Codec<TileRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        TileKey.CODEC.fieldOf("key").forGetter(TileRow::key),
                        Codec.INT.fieldOf("map_id").forGetter(TileRow::mapId)
                ).apply(instance, TileRow::new)
        );
    }

    private record ShareRow(int mapId, String recipientUuid, String waypointName) {
        static final Codec<ShareRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.INT.fieldOf("map_id").forGetter(ShareRow::mapId),
                        Codec.STRING.fieldOf("recipient_uuid").forGetter(ShareRow::recipientUuid),
                        Codec.STRING.fieldOf("waypoint_name").forGetter(ShareRow::waypointName)
                ).apply(instance, ShareRow::new)
        );
    }

    private record SaveShape(List<EntryRow> entries,
                             List<TileRow> tiles,
                             List<ShareRow> shares) {}

    private static final Codec<SaveShape> SHAPE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    EntryRow.CODEC.listOf().fieldOf("entries").forGetter(SaveShape::entries),
                    TileRow.CODEC.listOf().fieldOf("tiles").forGetter(SaveShape::tiles),
                    ShareRow.CODEC.listOf()
                            .optionalFieldOf("waypoint_shares", List.of())
                            .forGetter(SaveShape::shares)
            ).apply(instance, SaveShape::new)
    );

    public static final Codec<ExplorerMapSavedData> CODEC = SHAPE_CODEC.xmap(
            shape -> {
                Map<Integer, MapEntryData> entryMap = new HashMap<>();
                for (EntryRow row : shape.entries()) entryMap.put(row.key(), row.value());
                Map<TileKey, Integer> tileMap = new HashMap<>();
                for (TileRow row : shape.tiles()) tileMap.put(row.key(), row.mapId());
                Map<Integer, Map<String, Set<String>>> shareMap = new HashMap<>();
                for (ShareRow row : shape.shares()) {
                    shareMap
                            .computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .computeIfAbsent(row.recipientUuid(), ignored -> new HashSet<>())
                            .add(row.waypointName());
                }
                return new ExplorerMapSavedData(entryMap, tileMap, shareMap);
            },
            data -> {
                List<EntryRow> entryRows = new ArrayList<>(data.entries.size());
                data.entries.forEach((k, v) -> entryRows.add(new EntryRow(k, v)));
                List<TileRow> tileRows = new ArrayList<>(data.tileIndex.size());
                data.tileIndex.forEach((k, v) -> tileRows.add(new TileRow(k, v)));
                List<ShareRow> shareRows = new ArrayList<>();
                data.waypointShares.forEach((mapId, recipients) ->
                        recipients.forEach((recipient, names) ->
                                names.forEach(name ->
                                        shareRows.add(new ShareRow(mapId, recipient, name)))));
                return new SaveShape(entryRows, tileRows, shareRows);
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
