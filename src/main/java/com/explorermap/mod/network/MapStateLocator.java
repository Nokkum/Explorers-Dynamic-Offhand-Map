package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import net.minecraft.item.FilledMapItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.storage.MapState;

/**
 * Finds an existing vanilla MapState whose center is within one pixel's tolerance
 * of (targetX, targetZ) at the given scale, or creates a new one.
 *
 * Vanilla MapState IDs are integers stored as "map_N" in level data.
 * The server tracks the next available ID via ServerWorld.getNextMapId().
 *
 * Search strategy:
 *   1. Iterate map IDs from 0 up to the current max.
 *   2. For each, load the MapState and compare center + scale.
 *   3. If a match is found within tolerance, return its ID.
 *   4. Otherwise create a fresh map and return the new ID.
 *
 * Tolerance: half a pixel (scale/2 blocks) to handle off-by-one centers
 * from maps created by vanilla cartography tables.
 */
public final class MapStateLocator {

    private MapStateLocator() {}

    /**
     * Returns the integer map ID of a MapState centered near (cx, cz)
     * at the given scale. Creates a new map if none is found.
     */
    public static int findOrCreate(ServerWorld world, int cx, int cz, byte scale) {
        int maxId     = world.getNextMapId();
        int tolerance = Math.max(1, (1 << scale) / 2);

        for (int id = 0; id < maxId; id++) {
            MapState state = world.getMapState(FilledMapItem.getMapName(id));
            if (state == null) continue;
            if (state.scale != scale) continue;
            if (Math.abs(state.centerX - cx) <= tolerance
             && Math.abs(state.centerZ - cz) <= tolerance) {
                ExplorerMapMod.LOGGER.debug(
                        "[ExplorerMap] Reusing existing map #{} for center ({},{})", id, cx, cz);
                return id;
            }
        }

        // No match — create a new vanilla map
        int newId = createMap(world, cx, cz, scale);
        ExplorerMapMod.LOGGER.info(
                "[ExplorerMap] Created new map #{} for center ({},{})", newId, cx, cz);
        return newId;
    }

    private static int createMap(ServerWorld world, int cx, int cz, byte scale) {
        // Capture the ID that will be assigned before createMap increments the counter
        int assignedId = world.getNextMapId();
        // scale parameter to createMap is the raw map scale byte (0-4), not blocks-per-pixel
        FilledMapItem.createMap(world, cx, cz, scale, true, false);
        return assignedId;
    }
}
