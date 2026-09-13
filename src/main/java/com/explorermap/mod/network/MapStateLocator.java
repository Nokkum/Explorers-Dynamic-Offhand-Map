package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.storage.MapState;

/**
 * Finds an existing vanilla MapState whose center is within one pixel's tolerance
 * of (targetX, targetZ) at the given scale, or creates a new one.
 *
 * IMPORTANT — ServerWorld.getNextMapId() is NOT a read-only peek.
 * It atomically increments and returns a persistent counter as a side effect —
 * every call allocates a real ID, whether or not a map is ever created with it.
 * This class must never call it directly; instead:
 *   - To bound a search, scan by null-run (like TileGrid.resolveMapId on the client)
 *     rather than treating getNextMapId() as "the current map count".
 *   - To learn the ID of a newly created map, read it back off the created
 *     ItemStack via FilledMapItem.getMapId(stack) — never assume it equals
 *     a separately-fetched getNextMapId() value, since each call to that
 *     method burns a different, unrelated ID.
 *
 * Search strategy:
 *   1. Scan map IDs from 0 upward, stopping after a run of consecutive
 *      missing IDs (same heuristic as TileGrid's client-side cache).
 *   2. For each existing map, compare center + scale within tolerance.
 *   3. If a match is found, return its ID.
 *   4. Otherwise create a fresh map and read its real assigned ID back
 *      from the created ItemStack's data component.
 *
 * Tolerance: half a pixel (scale/2 blocks) to handle off-by-one centers
 * from maps created by vanilla cartography tables.
 */
public final class MapStateLocator {

    /** Stop scanning after this many consecutive missing map IDs. */
    private static final int NULL_RUN_LIMIT = 64;

    /** Hard ceiling to guarantee termination even in pathological cases. */
    private static final int MAX_SCAN = 1 << 20;

    private MapStateLocator() {}

    /**
     * Returns the integer map ID of a MapState centered near (cx, cz)
     * at the given scale. Creates a new map if none is found.
     */
    public static int findOrCreate(ServerWorld world, int cx, int cz, byte scale) {
        int tolerance = Math.max(1, (1 << scale) / 2);

        int nullRun = 0;
        for (int id = 0; id < MAX_SCAN; id++) {
            MapState state = world.getMapState(FilledMapItem.getMapName(id));
            if (state == null) {
                if (++nullRun > NULL_RUN_LIMIT) break;
                continue;
            }
            nullRun = 0;

            if (state.scale != scale) continue;
            if (Math.abs(state.centerX - cx) <= tolerance
             && Math.abs(state.centerZ - cz) <= tolerance) {
                ExplorerMapMod.LOGGER.debug(
                        "[ExplorerMap] Reusing existing map #{} for center ({},{})", id, cx, cz);
                return id;
            }
        }

        // No match — create a new vanilla map and read its real ID back
        // from the stack's data component (never re-derive it separately).
        int newId = createMap(world, cx, cz, scale);
        ExplorerMapMod.LOGGER.info(
                "[ExplorerMap] Created new map #{} for center ({},{})", newId, cx, cz);
        return newId;
    }

    /**
     * Creates a new vanilla map and returns its assigned integer ID.
     *
     * FilledMapItem.createMap() internally allocates the ID itself (via its
     * own single call to the world's map-ID counter) and stores it in the
     * returned ItemStack's data component. We read it back from there rather
     * than calling getNextMapId() ourselves, which would allocate a second,
     * unrelated ID and silently desynchronize from the map that was actually
     * created — the bug this replaces.
     */
    private static int createMap(ServerWorld world, int cx, int cz, byte scale) {
        ItemStack mapStack = FilledMapItem.createMap(world, cx, cz, scale, true, false);
        Integer id = FilledMapItem.getMapId(mapStack);
        if (id == null) {
            ExplorerMapMod.LOGGER.error(
                    "[ExplorerMap] FilledMapItem.createMap() returned a stack with no map ID!");
            return -1;
        }
        return id;
    }
}

