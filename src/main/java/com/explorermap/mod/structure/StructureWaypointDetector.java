package com.explorermap.mod.structure;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.network.SyncWaypointsPayload;
import com.explorermap.mod.waypoint.Waypoint;
import net.minecraft.item.map.MapState;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Automatically places waypoints when a player's fog-of-discovery reveals
 * a structure for the first time.
 *
 * How it works
 * ────────────
 * Called from SyncDiscoveryPayload.Upload.handleOnServer() after a
 * successful merge, so it runs at most once per upload interval (~3 seconds)
 * and only when new pixels were actually discovered. It checks whether any
 * newly-discovered pixels overlap a vanilla structure's bounding box, and if
 * so and no waypoint already exists nearby, adds one.
 *
 * Dimension resolution
 * ─────────────────────
 * An earlier revision always looked up structures in the Overworld,
 * regardless of which dimension the map actually belonged to — a map held
 * in the Nether would silently check Overworld chunks at the same numeric
 * coordinates, which is meaningless. MapState carries its own `dimension`
 * field; this now resolves the correct ServerWorld from that directly.
 *
 * All mutations to waypoint data go through ExplorerMapSavedData's wrapper
 * methods (never by touching a MapEntryData object directly) so that every
 * change is correctly marked dirty and actually gets persisted to disk.
 */
public final class StructureWaypointDetector {

    private static final int DEDUP_RADIUS = 64;

    private StructureWaypointDetector() {}

    /**
     * Called after a successful discovery merge on the server. Scans
     * structures under newly-discovered pixels and auto-places waypoints.
     */
    public static void checkAndPlace(MinecraftServer server,
                                      ServerPlayerEntity player,
                                      int mapId,
                                      MapState mapState,
                                      ExplorerMapSavedData savedData) {
        ServerWorld world = server.getWorld(mapState.dimension);
        if (world == null) return;

        int scale      = 1 << mapState.scale;
        int halfBlocks = 64 * scale;
        int minX = mapState.centerX - halfBlocks;
        int minZ = mapState.centerZ - halfBlocks;

        var entry = savedData.getOrCreate(world, mapId);
        byte[] bitmask = entry.getDiscoveredPixelsCopy();

        Set<ChunkPos> discoveredChunks = new HashSet<>();
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int bit = row * 128 + col;
                if ((bitmask[bit >> 3] & (1 << (bit & 7))) == 0) continue;

                int wx = minX + col * scale + scale / 2;
                int wz = minZ + row * scale + scale / 2;
                discoveredChunks.add(new ChunkPos(new BlockPos(wx, 64, wz)));
            }
        }

        if (discoveredChunks.isEmpty()) return;

        boolean placedAny = false;

        for (ChunkPos chunkPos : discoveredChunks) {
            if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) continue;

            var chunk = world.getChunk(chunkPos.x, chunkPos.z);
            Map<Structure, StructureStart> starts = chunk.getStructureStarts();
            if (starts.isEmpty()) continue;

            for (Map.Entry<Structure, StructureStart> se : starts.entrySet()) {
                StructureStart start = se.getValue();
                if (!start.hasChildren()) continue;

                var bb = start.getBoundingBox();
                double structX = (bb.getMinX() + bb.getMaxX()) / 2.0;
                double structZ = (bb.getMinZ() + bb.getMaxZ()) / 2.0;

                // Re-fetch the entry fresh each time since savedData.addWaypoint
                // may have just mutated it in the previous loop iteration.
                if (hasDuplicateWaypoint(savedData.getOrCreate(world, mapId), structX, structZ)) continue;

                String iconId = iconForStructure(world, se.getKey());
                String name   = nameForStructure(world, se.getKey());
                int color     = colorForStructure(world, se.getKey());

                Waypoint wp = new Waypoint(name, structX, structZ, iconId, color);
                savedData.addWaypoint(world, mapId, wp);
                placedAny = true;

                ExplorerMapMod.LOGGER.info(
                        "[ExplorerMap] Auto-waypoint: {} at ({}, {}) on map #{}",
                        name, (int) structX, (int) structZ, mapId);
            }
        }

        // Broadcast once per call, not once per structure, to avoid sending
        // redundant full waypoint-list syncs when several structures are
        // revealed in the same discovery batch.
        if (placedAny) {
            SyncWaypointsPayload.broadcastTo(server, mapId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static boolean hasDuplicateWaypoint(com.explorermap.mod.data.MapEntryData entry,
                                                 double wx, double wz) {
        for (Waypoint existing : entry.getWaypoints()) {
            double dx = existing.worldX() - wx;
            double dz = existing.worldZ() - wz;
            if (Math.sqrt(dx * dx + dz * dz) < DEDUP_RADIUS) return true;
        }
        return false;
    }

    // ── Structure -> icon/name/color mapping ────────────────────────────

    private static String iconForStructure(ServerWorld world, Structure structure) {
        String type = structureTypeName(world, structure);
        return switch (type) {
            case "village" -> "explorermap:village";
            case "desert_pyramid", "jungle_pyramid", "jungle_temple" -> "explorermap:temple";
            case "stronghold", "dungeon", "ancient_city", "pillager_outpost",
                 "woodland_mansion", "mineshaft" -> "explorermap:dungeon";
            default -> "explorermap:pin";
        };
    }

    private static String nameForStructure(ServerWorld world, Structure structure) {
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

    private static int colorForStructure(ServerWorld world, Structure structure) {
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

    private static String structureTypeName(ServerWorld world, Structure structure) {
        var registry = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        var key = registry.getKey(structure);
        return key.map(k -> k.getValue().getPath()).orElse("unknown");
    }
}
