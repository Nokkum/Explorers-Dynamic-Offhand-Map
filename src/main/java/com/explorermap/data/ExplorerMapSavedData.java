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

    private static final int MAX_ENTRY_ROWS = 20_000;
    private static final int MAX_TILE_ROWS = 20_000;
    private static final int MAX_SHARE_ROWS = 100_000;
    private static final int MAX_DISCOVERY_ROWS = 100_000;

    private final Map<MapIdentity, MapEntryData> entries = new HashMap<>();
    private final Map<TileKey, MapIdentity> tileIndex = new HashMap<>();
    private final Map<MapIdentity, Map<String, Set<UUID>>> waypointShares = new HashMap<>();
    private final Map<MapIdentity, Map<String, byte[]>> playerDiscoveries = new HashMap<>();

    public ExplorerMapSavedData() {}

    private ExplorerMapSavedData(Map<MapIdentity, MapEntryData> entries,
                                 Map<TileKey, MapIdentity> tileIndex,
                                 Map<MapIdentity, Map<String, Set<UUID>>> waypointShares,
                                 Map<MapIdentity, Map<String, byte[]>> playerDiscoveries) {
        this.entries.putAll(entries);
        this.tileIndex.putAll(tileIndex);
        waypointShares.forEach((mapId, recipients) -> this.waypointShares.put(
                mapId,
                new HashMap<>(recipients)
        ));
        playerDiscoveries.forEach((mapId, players) -> {
            Map<String, byte[]> copied = new HashMap<>();
            players.forEach((uuid, bits) -> copied.put(uuid, bits.clone()));
            this.playerDiscoveries.put(mapId, copied);
        });
    }

    public MapEntryData getOrCreate(MapIdentity mapId) {
        return entries.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public MapEntryData get(MapIdentity mapId) {
        return entries.get(mapId);
    }

    public boolean mergeBitmask(MapIdentity mapId, byte[] bitmask) {
        boolean changed = getOrCreate(mapId).mergeBitmask(bitmask);
        if (changed) markDirty();
        return changed;
    }

    public byte[] getPlayerDiscovery(MapIdentity mapId, UUID playerId) {
        if (playerId == null) return getOrCreate(mapId).getDiscoveredPixelsCopy();
        String uuid = playerId.toString();
        Map<String, byte[]> players = playerDiscoveries.get(mapId);
        if (players == null) {
            return getOrCreate(mapId).getDiscoveredPixelsCopy();
        }
        return players.getOrDefault(uuid, new byte[MapEntryData.BYTE_COUNT]).clone();
    }

    public byte[] aggregateDiscoveredPixels(MapIdentity mapId) {
        Map<String, byte[]> players = playerDiscoveries.get(mapId);
        if (players == null || players.isEmpty()) {
            MapEntryData entry = get(mapId);
            return entry != null ? entry.getDiscoveredPixelsCopy() : new byte[MapEntryData.BYTE_COUNT];
        }
        byte[] result = new byte[MapEntryData.BYTE_COUNT];
        for (byte[] bits : players.values()) {
            int n = Math.min(result.length, bits.length);
            for (int i = 0; i < n; i++) result[i] |= bits[i];
        }
        return result;
    }

    public boolean mergePlayerBitmask(MapIdentity mapId, UUID playerId, byte[] bitmask) {
        if (playerId == null || bitmask == null) return false;
        Map<String, byte[]> players =
                playerDiscoveries.computeIfAbsent(mapId, ignored -> new HashMap<>());
        boolean hasExistingPlayers = !players.isEmpty();
        byte[] current = players.computeIfAbsent(playerId.toString(), ignored ->
                hasExistingPlayers
                        ? new byte[MapEntryData.BYTE_COUNT]
                        : getOrCreate(mapId).getDiscoveredPixelsCopy());
        boolean changed = false;
        int count = Math.min(current.length, bitmask.length);
        for (int i = 0; i < count; i++) {
            byte before = current[i];
            current[i] |= bitmask[i];
            changed |= before != current[i];
        }

        boolean legacyChanged = getOrCreate(mapId).mergeBitmask(bitmask);
        if (changed || legacyChanged) markDirty();
        return changed;
    }

    public boolean sharePlayerDiscovery(MapIdentity mapId, UUID from, UUID recipient) {
        if (from == null || recipient == null) return false;
        byte[] source = getPlayerDiscovery(mapId, from);
        return mergePlayerBitmask(mapId, recipient, source);
    }

    public void addExpansion(MapIdentity mapId, ExpansionRecord record) {
        getOrCreate(mapId).addExpansion(record);
        markDirty();
    }

    public void addWaypoint(MapIdentity mapId, Waypoint wp) {
        getOrCreate(mapId).addWaypoint(wp);
        markDirty();
    }

    public boolean addWaypointCapacity(MapIdentity mapId) {
        getOrCreate(mapId).addWaypointCapacity();
        markDirty();
        return true;
    }

    public boolean consumeWaypointShareCharge(MapIdentity mapId) {
        MapEntryData entry = get(mapId);
        if (entry == null || !entry.consumeWaypointShareCharge()) return false;
        markDirty();
        return true;
    }

    public void removeWaypoint(MapIdentity mapId, UUID waypointId) {
        getOrCreate(mapId).removeWaypoint(waypointId);
        removeWaypointShares(mapId, waypointId);
        markDirty();
    }

    public boolean canManageWaypoint(MapIdentity mapId, UUID waypointId, UUID playerId) {
        var entry = get(mapId);
        if (entry == null || playerId == null) return false;

        Waypoint wp = entry.findWaypoint(waypointId);
        return wp != null && wp.isOwnedBy(playerId);
    }

    public boolean grantWaypointShare(MapIdentity mapId, UUID waypointId, UUID recipientId) {
        if (recipientId == null) return false;
        var entry = get(mapId);
        if (entry == null) return false;

        Waypoint waypoint = entry.findWaypoint(waypointId);
        if (waypoint == null || waypoint.isSystemOrLegacy()) return false;

        waypointShares
                .computeIfAbsent(mapId, ignored -> new HashMap<>())
                .computeIfAbsent(recipientId.toString(), ignored -> new HashSet<>())
                .add(waypointId);
        markDirty();
        return true;
    }

    public void removeWaypointShares(MapIdentity mapId, UUID waypointId) {
        var recipients = waypointShares.get(mapId);
        if (recipients == null) return;

        recipients.values().forEach(ids -> ids.remove(waypointId));
        recipients.values().removeIf(Set::isEmpty);
        if (recipients.isEmpty()) waypointShares.remove(mapId);
    }

    public List<Waypoint> visibleWaypoints(MapIdentity mapId, UUID playerId) {
        var entry = get(mapId);
        if (entry == null) return List.of();

        String playerUuid = playerId == null ? "" : playerId.toString();
        var recipients = waypointShares.getOrDefault(mapId, Map.of());
        var sharedIds = recipients.getOrDefault(playerUuid, Set.of());

        return entry.getWaypoints().stream()
                .filter(wp -> wp.isSystemOrLegacy()
                        || playerUuid.equals(wp.ownerUuid())
                        || sharedIds.contains(wp.id()))
                .toList();
    }

    public boolean isAccessibleFrom(MapIdentity heldRootId, MapIdentity targetMapId) {
        if (heldRootId == null || targetMapId == null) return false;
        if (heldRootId.equals(targetMapId)) return true;

        Set<MapIdentity> visited = new HashSet<>();
        Deque<MapIdentity> pending = new ArrayDeque<>();
        pending.add(heldRootId);

        while (!pending.isEmpty()) {
            MapIdentity currentMapId = pending.removeFirst();
            if (!visited.add(currentMapId)) continue;

            var currentEntry = get(currentMapId);
            if (currentEntry == null) continue;

            for (ExpansionRecord record : currentEntry.getExpansions()) {
                if (record.target().equals(targetMapId)) return true;
                if (!visited.contains(record.target())) {
                    pending.addLast(record.target());
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

    public MapIdentity findTileId(World dimension, int scale, int centerX, int centerZ) {
        return tileIndex.get(new TileKey(dimension.getRegistryKey().getValue().toString(), scale, centerX, centerZ));
    }

    public void recordTile(World dimension, int scale, int centerX, int centerZ, MapIdentity mapId) {
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

    private record EntryRow(MapIdentity key, MapEntryData value) {
        static final Codec<EntryRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        MapIdentity.CODEC.fieldOf("key").forGetter(EntryRow::key),
                        MapEntryData.CODEC.fieldOf("value").forGetter(EntryRow::value)
                ).apply(instance, EntryRow::new)
        );
    }

    private record TileRow(TileKey key, MapIdentity mapId) {
        static final Codec<TileRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        TileKey.CODEC.fieldOf("key").forGetter(TileRow::key),
                        MapIdentity.CODEC.fieldOf("map_id").forGetter(TileRow::mapId)
                ).apply(instance, TileRow::new)
        );
    }

    private record ShareRow(MapIdentity mapId, String recipientUuid, String waypointId) {
        static final Codec<ShareRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        MapIdentity.CODEC.fieldOf("map_id").forGetter(ShareRow::mapId),
                        Codec.STRING.fieldOf("recipient_uuid").forGetter(ShareRow::recipientUuid),
                        Codec.STRING.fieldOf("waypoint_id").forGetter(ShareRow::waypointId)
                ).apply(instance, ShareRow::new)
        );
    }

    private record DiscoveryRow(MapIdentity mapId, String playerUuid, byte[] bitmask) {
        static final Codec<DiscoveryRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        MapIdentity.CODEC.fieldOf("map_id").forGetter(DiscoveryRow::mapId),
                        Codec.STRING.fieldOf("player_uuid").forGetter(DiscoveryRow::playerUuid),
                        Codec.BYTE.listOf().xmap(
                                list -> {
                                    byte[] result = new byte[MapEntryData.BYTE_COUNT];
                                    for (int i = 0; i < Math.min(result.length, list.size()); i++) {
                                        result[i] = list.get(i);
                                    }
                                    return result;
                                },
                                bytes -> {
                                    List<Byte> result = new ArrayList<>(bytes.length);
                                    for (byte value : bytes) result.add(value);
                                    return result;
                                }
                        ).fieldOf("bitmask").forGetter(DiscoveryRow::bitmask)
                ).apply(instance, DiscoveryRow::new)
        );
    }

    private record SaveShape(List<EntryRow> entries,
                             List<TileRow> tiles,
                             List<ShareRow> shares,
                             List<DiscoveryRow> discoveries) {}

    private static final Codec<SaveShape> SHAPE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    EntryRow.CODEC.listOf().fieldOf("entries").forGetter(SaveShape::entries),
                    TileRow.CODEC.listOf().fieldOf("tiles").forGetter(SaveShape::tiles),
                    ShareRow.CODEC.listOf()
                            .optionalFieldOf("waypoint_shares", List.of())
                            .forGetter(SaveShape::shares),
                    DiscoveryRow.CODEC.listOf()
                            .optionalFieldOf("player_discoveries", List.of())
                            .forGetter(SaveShape::discoveries)
            ).apply(instance, SaveShape::new)
    );

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static final Codec<ExplorerMapSavedData> CODEC = SHAPE_CODEC.xmap(
            shape -> {
                Map<MapIdentity, MapEntryData> entryMap = new HashMap<>();
                for (EntryRow row : cap(shape.entries(), MAX_ENTRY_ROWS, "entries")) {
                    entryMap.put(row.key(), row.value());
                }

                Map<TileKey, MapIdentity> tileMap = new HashMap<>();
                for (TileRow row : cap(shape.tiles(), MAX_TILE_ROWS, "tiles")) {
                    tileMap.put(row.key(), row.mapId());
                }

                Map<MapIdentity, Map<String, Set<UUID>>> shareMap = new HashMap<>();
                for (ShareRow row : cap(shape.shares(), MAX_SHARE_ROWS, "waypoint_shares")) {
                    UUID waypointId = parseUuidOrNull(row.waypointId());
                    UUID recipient = parseUuidOrNull(row.recipientUuid());
                    if (waypointId == null || recipient == null) continue;
                    shareMap
                            .computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .computeIfAbsent(row.recipientUuid(), ignored -> new HashSet<>())
                            .add(waypointId);
                }

                Map<MapIdentity, Map<String, byte[]>> discoveryMap = new HashMap<>();
                for (DiscoveryRow row : cap(shape.discoveries(), MAX_DISCOVERY_ROWS, "player_discoveries")) {
                    if (parseUuidOrNull(row.playerUuid()) == null) continue;
                    discoveryMap.computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .put(row.playerUuid(), row.bitmask().clone());
                }

                return new ExplorerMapSavedData(entryMap, tileMap, shareMap, discoveryMap);
            },
            data -> {
                List<EntryRow> entryRows = new ArrayList<>(data.entries.size());
                data.entries.forEach((k, v) -> entryRows.add(new EntryRow(k, v)));
                List<TileRow> tileRows = new ArrayList<>(data.tileIndex.size());
                data.tileIndex.forEach((k, v) -> tileRows.add(new TileRow(k, v)));
                List<ShareRow> shareRows = new ArrayList<>();
                data.waypointShares.forEach((mapId, recipients) ->
                        recipients.forEach((recipient, ids) ->
                                ids.forEach(id ->
                                        shareRows.add(new ShareRow(mapId, recipient, id.toString())))));
                List<DiscoveryRow> discoveryRows = new ArrayList<>();
                data.playerDiscoveries.forEach((mapId, players) ->
                        players.forEach((playerUuid, bitmask) ->
                                discoveryRows.add(new DiscoveryRow(mapId, playerUuid, bitmask.clone()))));
                return new SaveShape(entryRows, tileRows, shareRows, discoveryRows);
            }
    );

    private static <T> List<T> cap(List<T> list, int max, String label) {
        if (list.size() <= max) return list;
        ExplorerMapMod.LOGGER.warn(
                "[ExplorerMap] Saved data has {} '{}' rows, more than the expected maximum of {} - "
                        + "truncating. The save may be corrupted or was edited by hand.",
                list.size(), label, max);
        return list.subList(0, max);
    }

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
