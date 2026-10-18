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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class StructureWaypointDetector {

    private static final int DEDUP_RADIUS = 64;

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

        for (long chunkKey : chunksToScan) {
            int chunkX = chunkX(chunkKey), chunkZ = chunkZ(chunkKey);
            if (!world.hasChunk(chunkX, chunkZ)) {
                entry.addPendingStructureChunk(chunkKey);
                continue;
            }
            placedAny |= scanChunkForStructures(world, entry, savedData, mapId, chunkX, chunkZ, bitmask, minX, minZ, scale);
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
        for (long chunkKey : pending) {
            int chunkX = chunkX(chunkKey), chunkZ = chunkZ(chunkKey);
            if (!world.hasChunk(chunkX, chunkZ)) continue;

            entry.removePendingStructureChunk(chunkKey);
            placedAny |= scanChunkForStructures(world, entry, savedData, mapId, chunkX, chunkZ, bitmask, minX, minZ, scale);
        }

        if (placedAny) {
            SyncWaypointsPayload.broadcastTo(server, mapId);
        }
    }

    private static boolean scanChunkForStructures(ServerLevel world, MapEntryData entry, ExplorerMapSavedData savedData,
                                                    MapIdentity mapId, int chunkX, int chunkZ, byte[] bitmask,
                                                    int minX, int minZ, int scale) {
        var chunk = world.getChunk(chunkX, chunkZ);
        Map<Structure, StructureStart> starts = chunk.getAllStarts();
        if (starts.isEmpty()) return false;

        boolean placedAny = false;

        for (Map.Entry<Structure, StructureStart> se : starts.entrySet()) {
            StructureStart start = se.getValue();
            if (!start.isValid()) continue;
            if (!structureOverlapsDiscovered(start, bitmask, minX, minZ, scale)) continue;

            BoundingBox bb = start.getBoundingBox();
            double structX = (bb.minX() + bb.maxX()) / 2.0;
            double structZ = (bb.minZ() + bb.maxZ()) / 2.0;

            if (hasDuplicateWaypoint(entry, structX, structZ)) continue;

            String iconId   = iconForStructure(world, se.getKey());
            String baseName = nameForStructure(world, se.getKey());
            String name     = uniqueDisplayName(entry, baseName);
            int color       = colorForStructure(world, se.getKey());

            Waypoint wp = Waypoint.create(name, structX, structZ, iconId, color, "");
            savedData.addWaypoint(mapId, wp);
            placedAny = true;

            ExplorerMapMod.LOGGER.info(
                    "[ExplorerMap] Auto-waypoint: {} at ({}, {}) on map {}",
                    name, (int) structX, (int) structZ, mapId.asKey());
        }

        return placedAny;
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

    private static boolean hasDuplicateWaypoint(MapEntryData entry, double wx, double wz) {
        for (Waypoint existing : entry.getWaypoints()) {
            double dx = existing.worldX() - wx;
            double dz = existing.worldZ() - wz;
            if (Math.sqrt(dx * dx + dz * dz) < DEDUP_RADIUS) return true;
        }
        return false;
    }

    private static String uniqueDisplayName(MapEntryData entry, String baseName) {
        Set<String> existingNames = entry.getWaypoints().stream()
                .map(Waypoint::name)
                .collect(Collectors.toSet());
        if (!existingNames.contains(baseName)) return baseName;
        int suffix = 2;
        String candidate;
        do {
            candidate = baseName + " (" + suffix + ")";
            suffix++;
        } while (existingNames.contains(candidate));
        return candidate;
    }

    private static String iconForStructure(ServerLevel world, Structure structure) {
        String type = structureTypeName(world, structure);
        return switch (type) {
            case "village" -> "explorermap:village";
            case "desert_pyramid", "jungle_pyramid", "jungle_temple" -> "explorermap:temple";
            case "stronghold", "dungeon", "ancient_city", "pillager_outpost",
                 "woodland_mansion", "mineshaft" -> "explorermap:dungeon";
            default -> "explorermap:pin";
        };
    }

    private static String nameForStructure(ServerLevel world, Structure structure) {
        String type = structureTypeName(world, structure);
        String[] parts = type.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.isEmpty() ? "Structure" : sb.toString();
    }

    private static int colorForStructure(ServerLevel world, Structure structure) {
        String type = structureTypeName(world, structure);
        return switch (type) {
            case "village" -> 0xFFFFFF55;
            case "desert_pyramid", "jungle_pyramid", "jungle_temple" -> 0xFFFF8800;
            case "stronghold" -> 0xFFAA55FF;
            case "ancient_city" -> 0xFF55FFFF;
            case "woodland_mansion", "pillager_outpost" -> 0xFFFF5555;
            default -> 0xFFFFFFFF;
        };
    }

    private static String structureTypeName(ServerLevel world, Structure structure) {
        var registry = world.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Identifier key = registry.getKey(structure);
        return key == null ? "unknown" : key.getPath();
    }
}
