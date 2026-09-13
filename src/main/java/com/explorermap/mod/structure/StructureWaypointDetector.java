package com.explorermap.mod.structure;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.waypoint.Waypoint;
import net.minecraft.item.FilledMapItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.storage.MapState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/**
 * Automatically places waypoints when a player's fog-of-discovery reveals
 * a structure for the first time.
 *
 * How it works
 * ────────────
 * When the server receives an Upload from SyncDiscoveryPayload, it checks
 * whether any newly-discovered pixels overlap a vanilla structure's bounding
 * box. If so and no waypoint already exists at that structure, it creates one.
 *
 * This is called from SyncDiscoveryPayload.Upload.handleOnServer() after a
 * successful merge, so it runs at most once per upload interval (~3 seconds)
 * and only when new pixels were actually discovered.
 *
 * Structure icon mapping
 * ──────────────────────
 * We map vanilla StructureType registry keys to our waypoint icon IDs.
 * Unmapped structures get the generic "pin" icon.
 *
 * Deduplication
 * ─────────────
 * The attachment's waypoint list is checked for existing waypoints within
 * DEDUP_RADIUS blocks of the structure center before placing a new one.
 * This prevents duplicate waypoints from multiple players uploading at once.
 */
public final class StructureWaypointDetector {

    /** Minimum block distance between two structure waypoints. */
    private static final int DEDUP_RADIUS = 64;

    private StructureWaypointDetector() {}

    /**
     * Called after a successful discovery merge on the server.
     * Scans structures under newly-discovered pixels and auto-places waypoints.
     *
     * @param server    The Minecraft server.
     * @param player    The player who sent the upload.
     * @param mapId     Vanilla map integer ID.
     * @param mapState  The map's MapState.
     * @param attachment The merged MapDiscoveryAttachment.
     */
    public static void checkAndPlace(MinecraftServer server,
                                      ServerPlayerEntity player,
                                      int mapId,
                                      MapState mapState,
                                      MapDiscoveryAttachment attachment) {
        // Resolve the server world matching the map's coordinate space.
        // Maps store Overworld coords universally, so always search the Overworld.
        ServerWorld world = server.getWorld(World.OVERWORLD);
        if (world == null) return;

        int scale      = 1 << mapState.scale;
        int halfBlocks = 64 * scale;
        int minX = mapState.centerX - halfBlocks;
        int minZ = mapState.centerZ - halfBlocks;

        // Collect all discovered pixels' chunk positions
        Set<ChunkPos> discoveredChunks = new HashSet<>();
        byte[] bitmask = attachment.getDiscoveredPixelsCopy();
        for (int row = 0; row < 128; row++) {
            for (int col = 0; col < 128; col++) {
                int bit = row * 128 + col;
                if ((bitmask[bit >> 3] & (1 << (bit & 7))) == 0) continue;

                // Convert pixel → world block
                int wx = minX + col * scale + scale / 2;
                int wz = minZ + row * scale + scale / 2;
                discoveredChunks.add(new ChunkPos(new BlockPos(wx, 64, wz)));
            }
        }

        if (discoveredChunks.isEmpty()) return;

        // For each discovered chunk, check for structure starts
        for (ChunkPos chunkPos : discoveredChunks) {
            // Only check chunks that are loaded (don't force-load)
            if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) continue;

            var chunk = world.getChunk(chunkPos.x, chunkPos.z);
            Map<Structure, StructureStart> starts = chunk.getStructureStarts();
            if (starts.isEmpty()) continue;

            for (Map.Entry<Structure, StructureStart> entry : starts.entrySet()) {
                StructureStart start = entry.getValue();
                if (!start.hasChildren()) continue;

                // Get the structure's center position
                var bb = start.getBoundingBox();
                double structX = (bb.getMinX() + bb.getMaxX()) / 2.0;
                double structZ = (bb.getMinZ() + bb.getMaxZ()) / 2.0;

                // Skip if a waypoint already exists nearby
                if (hasDuplicateWaypoint(attachment, structX, structZ)) continue;

                // Map structure type to icon and name
                String iconId = iconForStructure(world, entry.getKey());
                String name   = nameForStructure(world, entry.getKey());
                int color     = colorForStructure(world, entry.getKey());

                Waypoint wp = new Waypoint(name, structX, structZ, iconId, color);
                attachment.addWaypoint(wp);

                ExplorerMapMod.LOGGER.info(
                        "[ExplorerMap] Auto-waypoint: {} at ({}, {}) on map #{}",
                        name, (int)structX, (int)structZ, mapId);

                // Persist on server and sync back to all map holders
                // (Re-use the SaveWaypoint server path without a C2S packet —
                //  we call the attachment directly since we're already server-side,
                //  then trigger a waypoint sync broadcast.)
                broadcastWaypointSync(server, player, mapId);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static boolean hasDuplicateWaypoint(MapDiscoveryAttachment attachment,
                                                  double wx, double wz) {
        for (Waypoint existing : attachment.getWaypoints()) {
            double dx = existing.worldX() - wx;
            double dz = existing.worldZ() - wz;
            if (Math.sqrt(dx * dx + dz * dz) < DEDUP_RADIUS) return true;
        }
        return false;
    }

    private static void broadcastWaypointSync(MinecraftServer server,
                                               ServerPlayerEntity triggeringPlayer,
                                               int mapId) {
        // SyncWaypointsPayload.sendTo() for every player holding this map
        for (var p : server.getPlayerManager().getPlayerList()) {
            var offHand = p.getStackInHand(Hand.OFF_HAND);
            if (ExplorerMapMod.isFilledMap(offHand)) {
                Integer heldId = FilledMapItem.getMapId(offHand);
                if (heldId != null && heldId == mapId) {
                    com.explorermap.mod.network.SyncWaypointsPayload.sendTo(p, mapId);
                }
            }
        }
    }

    // ── Structure → icon/name/color mapping ──────────────────────────────

    private static String iconForStructure(ServerWorld world, Structure structure) {
        String type = structureTypeName(world, structure);
        return switch (type) {
            case "village"        -> "explorermap:village";
            case "desert_pyramid",
                 "jungle_pyramid",
                 "jungle_temple"  -> "explorermap:temple";
            case "stronghold",
                 "dungeon",
                 "ancient_city",
                 "pillager_outpost",
                 "woodland_mansion",
                 "mineshaft"      -> "explorermap:dungeon";
            default               -> "explorermap:pin";
        };
    }

    private static String nameForStructure(ServerWorld world, Structure structure) {
        String type = structureTypeName(world, structure);
        // Convert snake_case to Title Case
        String[] parts = type.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            sb.append(part.substring(1));
        }
        return sb.toString();
    }

    private static int colorForStructure(ServerWorld world, Structure structure) {
        String type = structureTypeName(world, structure);
        return switch (type) {
            case "village"           -> 0xFFFFFF55; // yellow
            case "desert_pyramid",
                 "jungle_pyramid",
                 "jungle_temple"     -> 0xFFFF8800; // orange
            case "stronghold"        -> 0xFFAA55FF; // purple
            case "ancient_city"      -> 0xFF55FFFF; // cyan
            case "woodland_mansion"  -> 0xFFFF5555; // red
            case "pillager_outpost"  -> 0xFFFF5555; // red
            default                  -> 0xFFFFFFFF; // white
        };
    }

    private static String structureTypeName(ServerWorld world, Structure structure) {
        // Look up the registry key for this structure instance
        var registry = world.getRegistryManager()
                .get(RegistryKeys.STRUCTURE);
        var key = registry.getKey(structure);
        return key.map(k -> k.getValue().getPath()).orElse("unknown");
    }
}
