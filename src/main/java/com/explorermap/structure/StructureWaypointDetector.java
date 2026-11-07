package com.explorermap.structure;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.waypoint.Waypoint;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class StructureWaypointDetector {

    private static final int DEDUP_RADIUS = 64;
    /** Overworld structures that end this far below the surface are not visible from above. */
    private static final int SURFACE_TOLERANCE = 8;
    private static final long FULL_WARNING_INTERVAL_NANOS = 60_000_000_000L;

    private static long lastFullWarning = 0L;

    private StructureWaypointDetector() {}

    // Same packing vanilla uses for chunk positions, so keys already stored in saves stay valid.
    private static long packChunk(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | (((long) z & 0xFFFFFFFFL) << 32);
    }

    private static int chunkX(long key) { return (int) key; }

    private static int chunkZ(long key) { return (int) (key >>> 32); }

    public static void checkAndPlace(MinecraftServer server,
                                      ServerPlayer player,
                                      MapIdentity mapId,
                                      MapItemSavedData mapState,
                                      ExplorerMapSavedData savedData,
                                      Set<Integer> newlyDiscoveredBits) {
        ServerLevel world = server.getLevel(mapState.dimension);
        if (world == null) return;

        int scale      = 1 << mapState.scale;
        int halfBlocks = 64 * scale;
        int minX = mapState.centerX - halfBlocks;
        int minZ = mapState.centerZ - halfBlocks;

        MapEntryData entry = savedData.getOrCreate(mapId);
        byte[] bitmask = savedData.aggregateDiscoveredPixels(mapId);

        Set<Integer> bitsToConsider = newlyDiscoveredBits != null
                ? newlyDiscoveredBits
                : allDiscoveredBits(bitmask);

        Set<Long> chunksToScan = new HashSet<>();
        for (int bit : bitsToConsider) {
            int col = bit % 128, row = bit / 128;
            int wx = minX + col * scale + scale / 2;
            int wz = minZ + row * scale + scale / 2;
            chunksToScan.add(packChunk(wx >> 4, wz >> 4));
        }

        if (chunksToScan.isEmpty()) return;

        boolean placedAny = false;
        Set<Long> scanned = new HashSet<>();

        for (long chunkKey : chunksToScan) {
            int chunkX = chunkX(chunkKey), chunkZ = chunkZ(chunkKey);
            if (!world.hasChunk(chunkX, chunkZ)) {
                queuePending(savedData, mapId, chunkKey);
                continue;
            }
            placedAny |= scanChunk(world, entry, savedData, mapId, chunkKey, bitmask, minX, minZ, scale, scanned);
        }

        if (placedAny) {
            SyncWaypointsPayload.broadcastTo(server, mapId);
        }
    }

    public static void retryPending(MinecraftServer server,
                                     MapIdentity mapId,
                                     MapItemSavedData mapState,
                                     ExplorerMapSavedData savedData) {
        ServerLevel world = server.getLevel(mapState.dimension);
        if (world == null) return;

        MapEntryData entry = savedData.getOrCreate(mapId);
        Set<Long> pending = entry.getPendingStructureChunks();
        if (pending.isEmpty()) return;

        int scale      = 1 << mapState.scale;
        int halfBlocks = 64 * scale;
        int minX = mapState.centerX - halfBlocks;
        int minZ = mapState.centerZ - halfBlocks;
        byte[] bitmask = savedData.aggregateDiscoveredPixels(mapId);

        boolean placedAny = false;
        Set<Long> scanned = new HashSet<>();
        for (long chunkKey : pending) {
            if (!world.hasChunk(chunkX(chunkKey), chunkZ(chunkKey))) continue;

            savedData.removePendingStructureChunk(mapId, chunkKey);
            placedAny |= scanChunk(world, entry, savedData, mapId, chunkKey, bitmask, minX, minZ, scale, scanned);
        }

        if (placedAny) {
            SyncWaypointsPayload.broadcastTo(server, mapId);
        }
    }

    private static void queuePending(ExplorerMapSavedData savedData, MapIdentity mapId, long chunkKey) {
        if (savedData.addPendingStructureChunk(mapId, chunkKey)) return;
        long now = System.nanoTime();
        if (lastFullWarning == 0L || now - lastFullWarning > FULL_WARNING_INTERVAL_NANOS) {
            lastFullWarning = now;
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] The list of unloaded chunks to check for structures on map {} is full; "
                            + "structures in some unloaded areas may not get a waypoint", mapId.asKey());
        }
    }

    /**
     * Checks the structures that start in this chunk and also those that start elsewhere but reach into it:
     * a structure seen from the side is usually discovered through a chunk it doesn't start in.
     */
    private static boolean scanChunk(ServerLevel world, MapEntryData entry, ExplorerMapSavedData savedData,
                                      MapIdentity mapId, long chunkKey, byte[] bitmask,
                                      int minX, int minZ, int scale, Set<Long> scanned) {
        int chunkX = chunkX(chunkKey), chunkZ = chunkZ(chunkKey);
        var chunk = world.getChunk(chunkX, chunkZ);

        boolean placedAny = false;
        if (scanned.add(chunkKey)) {
            placedAny |= placeStarts(world, chunk.getAllStarts(), entry, savedData, mapId, bitmask, minX, minZ, scale);
        }

        for (var reference : chunk.getAllReferences().entrySet()) {
            for (long originKey : reference.getValue()) {
                if (!scanned.add(originKey)) continue;
                int originX = chunkX(originKey), originZ = chunkZ(originKey);
                if (!world.hasChunk(originX, originZ)) {
                    queuePending(savedData, mapId, originKey);
                    continue;
                }
                placedAny |= placeStarts(world, world.getChunk(originX, originZ).getAllStarts(),
                        entry, savedData, mapId, bitmask, minX, minZ, scale);
            }
        }
        return placedAny;
    }

    private static boolean placeStarts(ServerLevel world, Map<Structure, StructureStart> starts,
                                        MapEntryData entry, ExplorerMapSavedData savedData,
                                        MapIdentity mapId, byte[] bitmask, int minX, int minZ, int scale) {
        boolean placedAny = false;

        for (Map.Entry<Structure, StructureStart> se : starts.entrySet()) {
            StructureStart start = se.getValue();
            if (!start.isValid()) continue;
            if (!structureOverlapsDiscovered(start, bitmask, minX, minZ, scale)) continue;

            BoundingBox bb = start.getBoundingBox();
            double structX = (bb.minX() + bb.maxX()) / 2.0;
            double structZ = (bb.minZ() + bb.maxZ()) / 2.0;

            String path = structurePath(world, se.getKey());
            if (!visibleFromAbove(world, bb, structX, structZ)) continue;

            String nameKey = StructureIds.nameKey(path);
            if (hasDuplicateWaypoint(entry, StructureIds.normalize(path), structX, structZ)) continue;

            String name = uniqueName(entry, nameKey);
            Waypoint wp = Waypoint.create(name, structX, structZ,
                    StructureIds.icon(path), StructureIds.color(path), "");
            savedData.addWaypoint(mapId, wp);
            placedAny = true;

            ExplorerMapMod.LOGGER.info(
                    "[ExplorerMap] Auto-waypoint: {} at ({}, {}) on map {}",
                    name, (int) structX, (int) structZ, mapId.asKey());
        }

        return placedAny;
    }

    /**
     * Discovery is by map pixel (x and z only), so a structure deep underground would get a waypoint just
     * because the ground above it was seen. In the overworld, require it to reach near the surface.
     * Other dimensions have no open sky, so everything there counts.
     */
    private static boolean visibleFromAbove(ServerLevel world, BoundingBox bb, double centerX, double centerZ) {
        if (!world.dimension().equals(Level.OVERWORLD)) return true;
        int x = (int) Math.floor(centerX), z = (int) Math.floor(centerZ);
        if (!world.hasChunk(x >> 4, z >> 4)) return false;
        int surface = world.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        return bb.maxY() + SURFACE_TOLERANCE >= surface;
    }

    private static Set<Integer> allDiscoveredBits(byte[] bitmask) {
        Set<Integer> bits = new HashSet<>();
        for (int i = 0; i < bitmask.length; i++) {
            if (bitmask[i] == 0) continue;
            for (int b = 0; b < 8; b++) {
                if ((bitmask[i] & (1 << b)) != 0) bits.add(i * 8 + b);
            }
        }
        return bits;
    }

    private static boolean structureOverlapsDiscovered(StructureStart start, byte[] bitmask,
                                                         int minX, int minZ, int scale) {
        BoundingBox bb = start.getBoundingBox();
        int colMin = Math.floorDiv(bb.minX() - minX, scale);
        int colMax = Math.floorDiv(bb.maxX() - minX, scale);
        int rowMin = Math.floorDiv(bb.minZ() - minZ, scale);
        int rowMax = Math.floorDiv(bb.maxZ() - minZ, scale);

        colMin = Math.max(0, colMin);
        colMax = Math.min(127, colMax);
        rowMin = Math.max(0, rowMin);
        rowMax = Math.min(127, rowMax);
        if (colMin > colMax || rowMin > rowMax) return false; // structure is entirely outside this map tile

        for (int row = rowMin; row <= rowMax; row++) {
            int base = row * 128;
            for (int col = colMin; col <= colMax; col++) {
                int bit = base + col;
                if ((bitmask[bit >> 3] & (1 << (bit & 7))) != 0) return true;
            }
        }
        return false;
    }

    /** Only an automatic waypoint of the same type close by counts: a player's own waypoint, or another type, doesn't. */
    private static boolean hasDuplicateWaypoint(MapEntryData entry, String type, double wx, double wz) {
        for (Waypoint existing : entry.getWaypoints()) {
            if (!existing.isSystemOrLegacy()) continue;
            if (!StructureIds.typeOfStoredName(existing.name()).equals(type)) continue;
            double dx = existing.worldX() - wx;
            double dz = existing.worldZ() - wz;
            if (Math.sqrt(dx * dx + dz * dz) < DEDUP_RADIUS) return true;
        }
        return false;
    }

    private static String uniqueName(MapEntryData entry, String baseName) {
        Set<String> existingNames = new HashSet<>();
        for (Waypoint wp : entry.getWaypoints()) existingNames.add(wp.name());
        if (!existingNames.contains(baseName)) return baseName;
        int suffix = 2;
        String candidate;
        do {
            candidate = baseName + " (" + suffix + ")";
            suffix++;
        } while (existingNames.contains(candidate));
        return candidate;
    }

    private static String structurePath(ServerLevel world, Structure structure) {
        var registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Identifier key = registry.getKey(structure);
        return key == null ? "unknown" : key.getPath();
    }
}
