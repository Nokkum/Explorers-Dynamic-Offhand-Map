package com.explorermap.data;

import com.explorermap.ExplorerMapMod;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.waypoint.Waypoint;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.Level;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ExplorerMapSavedData extends SavedData {


    private static final int MAX_ENTRY_ROWS = 20_000;
    private static final int MAX_TILE_ROWS = 20_000;
    private static final int MAX_SHARE_ROWS = 100_000;
    private static final int MAX_DISCOVERY_ROWS = 100_000;
    private static final int MAX_ALLOWANCE_ROWS = 100_000;

    private final Map<MapIdentity, MapEntryData> entries = new HashMap<>();
    private final Map<TileKey, MapIdentity> tileIndex = new HashMap<>();
    private final Map<MapIdentity, Map<String, Set<UUID>>> waypointShares = new HashMap<>();
    private final Map<MapIdentity, Map<String, byte[]>> playerDiscoveries = new HashMap<>();
    // Per-player waypoint slots and share charges, keyed by the root map the compass was inserted into.
    private final Map<MapIdentity, Map<String, Allowance>> allowances = new HashMap<>();

    private static final class Allowance {
        int capacity;
        int shareCharges;

        Allowance(int capacity, int shareCharges) {
            this.capacity = Math.max(0, capacity);
            this.shareCharges = Math.max(0, shareCharges);
        }
    }

    public ExplorerMapSavedData() {}

    private ExplorerMapSavedData(Map<MapIdentity, MapEntryData> entries,
                                 Map<TileKey, MapIdentity> tileIndex,
                                 Map<MapIdentity, Map<String, Set<UUID>>> waypointShares,
                                 Map<MapIdentity, Map<String, byte[]>> playerDiscoveries,
                                 Map<MapIdentity, Map<String, Allowance>> allowances) {
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
        allowances.forEach((mapId, players) -> this.allowances.put(mapId, new HashMap<>(players)));
    }

    public MapEntryData getOrCreate(MapIdentity mapId) {
        return entries.computeIfAbsent(mapId, k -> new MapEntryData());
    }

    public MapEntryData get(MapIdentity mapId) {
        return entries.get(mapId);
    }

    public List<MapIdentity> mapsWithPendingStructureChunks() {
        List<MapIdentity> result = new ArrayList<>();
        entries.forEach((id, entry) -> {
            if (!entry.getPendingStructureChunks().isEmpty()) result.add(id);
        });
        return result;
    }

    public boolean mergeBitmask(MapIdentity mapId, byte[] bitmask) {
        boolean changed = getOrCreate(mapId).mergeBitmask(bitmask);
        if (changed) setDirty();
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

    private record MergeResult(boolean playerChanged, Set<Integer> aggregateDelta) {
        static final MergeResult NONE = new MergeResult(false, Set.of());
    }

    /** @return pixels newly discovered by anyone (the aggregate delta). */
    public Set<Integer> mergePlayerBitmask(MapIdentity mapId, UUID playerId, byte[] bitmask) {
        return merge(mapId, playerId, bitmask).aggregateDelta();
    }

    private MergeResult merge(MapIdentity mapId, UUID playerId, byte[] bitmask) {
        if (playerId == null || bitmask == null) return MergeResult.NONE;

        byte[] aggregateBefore = aggregateDiscoveredPixels(mapId);

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
        if (changed || legacyChanged) setDirty();

        if (!changed) return MergeResult.NONE;

        byte[] aggregateAfter = aggregateDiscoveredPixels(mapId);
        Set<Integer> newlyDiscovered = new HashSet<>();
        for (int i = 0; i < MapEntryData.BYTE_COUNT; i++) {
            byte delta = (byte) (aggregateAfter[i] & ~aggregateBefore[i]);
            if (delta == 0) continue;
            for (int bit = 0; bit < 8; bit++) {
                if ((delta & (1 << bit)) != 0) newlyDiscovered.add(i * 8 + bit);
            }
        }
        return new MergeResult(true, newlyDiscovered);
    }

    /** @return true if the recipient's own discovery changed (even if others had already found those pixels). */
    public boolean sharePlayerDiscovery(MapIdentity mapId, UUID from, UUID recipient) {
        if (from == null || recipient == null) return false;
        byte[] source = getPlayerDiscovery(mapId, from);
        return merge(mapId, recipient, source).playerChanged();
    }

    public void addExpansion(MapIdentity mapId, ExpansionRecord record) {
        if (record != null && record.target() != null
                && !record.target().dimension().equals(mapId.dimension())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Refusing a cross-dimension expansion: {} -> {}",
                    mapId.asKey(), record.target().asKey());
            return;
        }
        getOrCreate(mapId).addExpansion(record);
        setDirty();
    }

    public void addWaypoint(MapIdentity mapId, Waypoint wp) {
        getOrCreate(mapId).addWaypoint(wp);
        setDirty();
    }

    private Allowance allowanceOf(MapIdentity root, UUID player) {
        if (root == null || player == null) return null;
        Map<String, Allowance> players = allowances.get(root);
        return players == null ? null : players.get(player.toString());
    }

    public int waypointCapacity(MapIdentity root, UUID player) {
        Allowance a = allowanceOf(root, player);
        return a == null ? 0 : a.capacity;
    }

    public int waypointShareCharges(MapIdentity root, UUID player) {
        Allowance a = allowanceOf(root, player);
        return a == null ? 0 : a.shareCharges;
    }

    public void addWaypointCapacity(MapIdentity root, UUID player) {
        if (root == null || player == null) return;
        allowances.computeIfAbsent(root, ignored -> new HashMap<>())
                .computeIfAbsent(player.toString(), ignored -> new Allowance(0, 0));
        Allowance a = allowanceOf(root, player);
        a.capacity += 5;
        a.shareCharges++;
        setDirty();
    }

    public boolean consumeWaypointShareCharge(MapIdentity root, UUID player) {
        Allowance a = allowanceOf(root, player);
        if (a == null || a.shareCharges <= 0) return false;
        a.shareCharges--;
        setDirty();
        return true;
    }

    /** Waypoints owned by the player across the given maps (a root map and its expansion tiles). */
    public int ownedWaypointCount(Collection<MapIdentity> maps, UUID player) {
        int count = 0;
        for (MapIdentity id : maps) {
            MapEntryData entry = get(id);
            if (entry == null) continue;
            for (Waypoint wp : entry.getWaypoints()) {
                if (wp.isOwnedBy(player)) count++;
            }
        }
        return count;
    }

    public boolean isWaypointSharedWith(MapIdentity mapId, UUID waypointId, UUID recipientId) {
        if (recipientId == null) return false;
        return waypointShares.getOrDefault(mapId, Map.of())
                .getOrDefault(recipientId.toString(), Set.of()).contains(waypointId);
    }

    public boolean addPendingStructureChunk(MapIdentity mapId, long chunkPosLong) {
        boolean ok = getOrCreate(mapId).addPendingStructureChunk(chunkPosLong);
        setDirty();
        return ok;
    }

    public void removePendingStructureChunk(MapIdentity mapId, long chunkPosLong) {
        MapEntryData entry = get(mapId);
        if (entry == null) return;
        entry.removePendingStructureChunk(chunkPosLong);
        setDirty();
    }

    public void removeWaypoint(MapIdentity mapId, UUID waypointId) {
        getOrCreate(mapId).removeWaypoint(waypointId);
        removeWaypointShares(mapId, waypointId);
        setDirty();
    }

    /** Owners manage their waypoints; admins can additionally manage system and legacy ones. */
    public boolean canManageWaypoint(MapIdentity mapId, UUID waypointId, UUID playerId, boolean admin) {
        var entry = get(mapId);
        if (entry == null || playerId == null) return false;

        Waypoint wp = entry.findWaypoint(waypointId);
        if (wp == null) return false;
        return wp.isOwnedBy(playerId) || (admin && wp.isSystemOrLegacy());
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
        setDirty();
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
                        CodecUtil.boundedString(CodecUtil.MAX_DIMENSION_ID_LENGTH)
                                .fieldOf("dimension").forGetter(TileKey::dimension),
                        Codec.INT.fieldOf("scale").forGetter(TileKey::scale),
                        Codec.INT.fieldOf("center_x").forGetter(TileKey::centerX),
                        Codec.INT.fieldOf("center_z").forGetter(TileKey::centerZ)
                ).apply(instance, TileKey::new)
        );
    }

    public MapIdentity findTileId(Level dimension, int scale, int centerX, int centerZ) {
        return tileIndex.get(new TileKey(dimension.dimension().identifier().toString(), scale, centerX, centerZ));
    }

    public void recordTile(Level dimension, int scale, int centerX, int centerZ, MapIdentity mapId) {
        tileIndex.put(new TileKey(dimension.dimension().identifier().toString(), scale, centerX, centerZ), mapId);
        setDirty();
    }

    public static ExplorerMapSavedData get(MinecraftServer server) {
        // 26.1+: global saved data lives on the server's own data storage.
        return server.getDataStorage().computeIfAbsent(TYPE);
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
                        CodecUtil.boundedString(CodecUtil.MAX_UUID_STRING_LENGTH)
                                .fieldOf("recipient_uuid").forGetter(ShareRow::recipientUuid),
                        CodecUtil.boundedString(CodecUtil.MAX_UUID_STRING_LENGTH)
                                .fieldOf("waypoint_id").forGetter(ShareRow::waypointId)
                ).apply(instance, ShareRow::new)
        );
    }

    private record DiscoveryRow(MapIdentity mapId, String playerUuid, byte[] bitmask) {
        static final Codec<DiscoveryRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        MapIdentity.CODEC.fieldOf("map_id").forGetter(DiscoveryRow::mapId),
                        CodecUtil.boundedString(CodecUtil.MAX_UUID_STRING_LENGTH)
                                .fieldOf("player_uuid").forGetter(DiscoveryRow::playerUuid),
                        // Byte array on disk (older saves used a list of bytes, which still decodes).
                        Codec.BYTE_BUFFER.xmap(
                                buffer -> {
                                    byte[] result = new byte[MapEntryData.BYTE_COUNT];
                                    ByteBuffer view = buffer.duplicate();
                                    view.get(result, 0, Math.min(result.length, view.remaining()));
                                    return result;
                                },
                                bytes -> ByteBuffer.wrap(bytes.clone())
                        ).fieldOf("bitmask").forGetter(DiscoveryRow::bitmask)
                ).apply(instance, DiscoveryRow::new)
        );
    }

    private record AllowanceRow(MapIdentity mapId, String playerUuid, int capacity, int shareCharges) {
        static final Codec<AllowanceRow> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        MapIdentity.CODEC.fieldOf("map_id").forGetter(AllowanceRow::mapId),
                        CodecUtil.boundedString(CodecUtil.MAX_UUID_STRING_LENGTH)
                                .fieldOf("player_uuid").forGetter(AllowanceRow::playerUuid),
                        Codec.INT.fieldOf("capacity").forGetter(AllowanceRow::capacity),
                        Codec.INT.fieldOf("share_charges").forGetter(AllowanceRow::shareCharges)
                ).apply(instance, AllowanceRow::new)
        );
    }

    private record SaveShape(List<EntryRow> entries,
                             List<TileRow> tiles,
                             List<ShareRow> shares,
                             List<DiscoveryRow> discoveries,
                             List<AllowanceRow> allowances) {}

    private static final Codec<SaveShape> SHAPE_CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    CodecUtil.boundedList(EntryRow.CODEC, MAX_ENTRY_ROWS, "entries")
                            .fieldOf("entries").forGetter(SaveShape::entries),
                    CodecUtil.boundedList(TileRow.CODEC, MAX_TILE_ROWS, "tiles")
                            .fieldOf("tiles").forGetter(SaveShape::tiles),
                    CodecUtil.boundedList(ShareRow.CODEC, MAX_SHARE_ROWS, "waypoint_shares")
                            .optionalFieldOf("waypoint_shares", List.of())
                            .forGetter(SaveShape::shares),
                    CodecUtil.boundedList(DiscoveryRow.CODEC, MAX_DISCOVERY_ROWS, "player_discoveries")
                            .optionalFieldOf("player_discoveries", List.of())
                            .forGetter(SaveShape::discoveries),
                    CodecUtil.boundedList(AllowanceRow.CODEC, MAX_ALLOWANCE_ROWS, "waypoint_allowances")
                            .optionalFieldOf("waypoint_allowances", List.of())
                            .forGetter(SaveShape::allowances)
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
                for (EntryRow row : shape.entries()) {
                    entryMap.put(row.key(), row.value());
                }

                Map<TileKey, MapIdentity> tileMap = new HashMap<>();
                for (TileRow row : shape.tiles()) {
                    tileMap.put(row.key(), row.mapId());
                }

                Map<MapIdentity, Map<String, Set<UUID>>> shareMap = new HashMap<>();
                for (ShareRow row : shape.shares()) {
                    UUID waypointId = parseUuidOrNull(row.waypointId());
                    UUID recipient = parseUuidOrNull(row.recipientUuid());
                    if (waypointId == null || recipient == null) continue;
                    shareMap
                            .computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .computeIfAbsent(row.recipientUuid(), ignored -> new HashSet<>())
                            .add(waypointId);
                }

                Map<MapIdentity, Map<String, byte[]>> discoveryMap = new HashMap<>();
                for (DiscoveryRow row : shape.discoveries()) {
                    if (parseUuidOrNull(row.playerUuid()) == null) continue;
                    discoveryMap.computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .put(row.playerUuid(), row.bitmask().clone());
                }

                Map<MapIdentity, Map<String, Allowance>> allowanceMap = new HashMap<>();
                for (AllowanceRow row : shape.allowances()) {
                    if (parseUuidOrNull(row.playerUuid()) == null) continue;
                    allowanceMap.computeIfAbsent(row.mapId(), ignored -> new HashMap<>())
                            .put(row.playerUuid(), new Allowance(row.capacity(), row.shareCharges()));
                }

                // Saves from before per-player allowances stored one shared pool per map.
                // Every current waypoint owner on that map keeps the full pool, as they could use it before.
                entryMap.forEach((mapId, entry) -> {
                    if (entry.getWaypointCapacity() > 0 || entry.getWaypointShareCharges() > 0) {
                        Set<String> owners = new HashSet<>();
                        for (Waypoint wp : entry.getWaypoints()) {
                            if (!wp.isSystemOrLegacy() && parseUuidOrNull(wp.ownerUuid()) != null) {
                                owners.add(wp.ownerUuid());
                            }
                        }
                        Map<String, Allowance> perPlayer =
                                allowanceMap.computeIfAbsent(mapId, ignored -> new HashMap<>());
                        for (String owner : owners) {
                            perPlayer.putIfAbsent(owner,
                                    new Allowance(entry.getWaypointCapacity(), entry.getWaypointShareCharges()));
                        }
                    }
                    entry.clearLegacyAllowance();
                });

                return new ExplorerMapSavedData(entryMap, tileMap, shareMap, discoveryMap, allowanceMap);
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
                List<AllowanceRow> allowanceRows = new ArrayList<>();
                data.allowances.forEach((mapId, players) ->
                        players.forEach((playerUuid, a) ->
                                allowanceRows.add(new AllowanceRow(mapId, playerUuid, a.capacity, a.shareCharges))));
                return new SaveShape(entryRows, tileRows, shareRows, discoveryRows, allowanceRows);
            }
    );

    public static final SavedDataType<ExplorerMapSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ExplorerMapMod.MOD_ID, "data"),
            () -> new ExplorerMapSavedData(),
            CODEC,
            DataFixTypes.LEVEL
    );
}
