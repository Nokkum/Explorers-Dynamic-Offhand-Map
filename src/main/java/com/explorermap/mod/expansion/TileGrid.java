package com.explorermap.mod.expansion;

import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import net.minecraft.world.storage.MapState;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.FilledMapItem;
import com.explorermap.mod.ExplorerMapMod;

/**
 * Represents the full stitched grid of map tiles known to the player.
 *
 * The "root" tile is always the one currently held in the off-hand.
 * Expansions add neighbouring tiles at grid offsets (gridX, gridZ) relative
 * to the root, where (0,0) is the root itself:
 *
 *   (-1,-1)  (0,-1)  (1,-1)   ← row Z = -1  (North of root)
 *   (-1, 0)  (0, 0)  (1, 0)   ← row Z =  0  (root row)
 *   (-1, 1)  (0, 1)  (1, 1)   ← row Z = +1  (South of root)
 *
 * A TileEntry holds the MapState + MapDiscoveryAttachment for one tile,
 * along with its grid position so renderers can compute screen offsets.
 *
 * build() is cheap (called per render frame). It reads the attachment's
 * expansion list, which is already in memory, and resolves the MapStates
 * via the vanilla world map registry (client-side).
 */
public final class TileGrid {

    /**
     * Client-side cache: MapState instance → vanilla integer map ID.
     * MapState objects are singletons per ID on the client, so identity is safe.
     * WeakHashMap lets entries be GC'd once a MapState is no longer referenced.
     */
    private static final java.util.WeakHashMap<MapState, Integer> MAP_ID_CACHE =
            new java.util.WeakHashMap<>();

    /** Call on disconnect to prevent stale cache entries carrying into a new world/session. */
    public static void clearMapIdCache() {
        MAP_ID_CACHE.clear();
    }

    public record TileEntry(
            int mapId,           // vanilla integer map ID (for texture cache key)
            MapState state,
            MapDiscoveryAttachment attachment,
            int gridX,           // column offset from root (West = negative)
            int gridZ            // row offset from root (North = negative)
    ) {}

    private final List<TileEntry> tiles = new ArrayList<>();
    private final int tileBlockSize; // blocks per tile side = 128 * scale

    private TileGrid(int tileBlockSize) {
        this.tileBlockSize = tileBlockSize;
    }

    public List<TileEntry> tiles() { return tiles; }

    /** Returns true if a tile at the given grid position exists in this grid. */
    public boolean hasTileAt(int gridX, int gridZ) {
        for (TileEntry t : tiles) {
            if (t.gridX() == gridX && t.gridZ() == gridZ) return true;
        }
        return false;
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Builds a TileGrid from a root MapState and its attachment.
     * Resolves expansion MapStates from the vanilla client-side world.
     *
     * @param rootState      The map currently in the off-hand.
     * @param rootAttachment Its discovery attachment.
     * @param world          Client world (for MapState lookup by ID).
     */
    public static TileGrid build(MapState rootState,
                                  MapDiscoveryAttachment rootAttachment,
                                  ClientWorld world) {

        int scale         = 1 << rootState.scale;
        int tileBlockSize = 128 * scale;
        TileGrid grid     = new TileGrid(tileBlockSize);

        // Resolve root map ID from the world's map registry
        int rootMapId = resolveMapId(rootState, world);

        // Always include root at (0, 0)
        grid.tiles.add(new TileEntry(rootMapId, rootState, rootAttachment, 0, 0));

        // Snapshot the expansion list before iterating — if the network thread
        // receives a GrantExpansionPayload concurrently, addExpansion() would modify
        // the list underneath us and throw ConcurrentModificationException.
        List<ExpansionRecord> expansionSnapshot = new ArrayList<>(rootAttachment.getExpansions());

        // Add each expansion
        for (ExpansionRecord exp : expansionSnapshot) {
            int gx = 0, gz = 0;
            switch (exp.direction()) {
                case NORTH -> gz = -1;
                case SOUTH -> gz =  1;
                case WEST  -> gx = -1;
                case EAST  -> gx =  1;
            }

            // Resolve the vanilla MapState for this tile
            var key      = FilledMapItem.getMapName(exp.mapId());
            var adjState = world.getMapState(key);
            if (adjState == null) continue; // not yet synced from server

            // Get or create attachment for this adjacent tile
            var adjAttachment = ExplorerMapMod.getOrCreate(adjState);

            grid.tiles.add(new TileEntry(exp.mapId(), adjState, adjAttachment, gx, gz));
        }

        return grid;
    }

    /** Returns the integer map ID for the given MapState, using the cache when possible. */
    private static int resolveMapId(MapState state, ClientWorld world) {
        Integer cached = MAP_ID_CACHE.get(state);
        if (cached != null) return cached;

        // Cache miss: scan the world's map registry once, caching every ID found
        // along the way so subsequent lookups for other tiles are also fast.
        int nullRun = 0;
        for (int id = 0; id < 32768; id++) {
            var candidate = world.getMapState(FilledMapItem.getMapName(id));
            if (candidate == null) {
                if (++nullRun > 20) break;
                continue;
            }
            nullRun = 0;
            MAP_ID_CACHE.put(candidate, id);
            if (candidate == state) return id;
        }
        return -1;
    }
}
