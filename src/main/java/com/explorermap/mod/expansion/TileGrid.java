package com.explorermap.mod.expansion;

import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import net.minecraft.world.storage.MapState;

import java.util.ArrayList;
import java.util.List;

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

    public record TileEntry(
            MapState state,
            MapDiscoveryAttachment attachment,
            int gridX,   // column offset from root (West = negative)
            int gridZ    // row offset from root (North = negative)
    ) {}

    private final List<TileEntry> tiles = new ArrayList<>();
    private final int tileBlockSize; // blocks per tile side = 128 * scale

    private TileGrid(int tileBlockSize) {
        this.tileBlockSize = tileBlockSize;
    }

    public List<TileEntry> tiles()         { return tiles; }
    public int             tileBlockSize() { return tileBlockSize; }

    /** Returns true if a tile at the given grid position exists in this grid. */
    public boolean hasTileAt(int gridX, int gridZ) {
        for (TileEntry t : tiles) {
            if (t.gridX() == gridX && t.gridZ() == gridZ) return true;
        }
        return false;
    }

    /**
     * Computes the screen pixel offset of a tile relative to the root tile's
     * top-left corner at the given rendered tile size (pixels).
     */
    public static int screenOffsetX(TileEntry tile, int renderedTileSize) {
        return tile.gridX() * renderedTileSize;
    }

    public static int screenOffsetZ(TileEntry tile, int renderedTileSize) {
        return tile.gridZ() * renderedTileSize;
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
                                  net.minecraft.client.world.ClientWorld world) {

        int scale         = 1 << rootState.scale;
        int tileBlockSize = 128 * scale;
        TileGrid grid     = new TileGrid(tileBlockSize);

        // Always include root at (0, 0)
        grid.tiles.add(new TileEntry(rootState, rootAttachment, 0, 0));

        // Add each expansion
        for (ExpansionRecord exp : rootAttachment.getExpansions()) {
            int gx = 0, gz = 0;
            switch (exp.direction()) {
                case NORTH -> gz = -1;
                case SOUTH -> gz =  1;
                case WEST  -> gx = -1;
                case EAST  -> gx =  1;
            }

            // Resolve the vanilla MapState for this tile
            var key      = net.minecraft.item.FilledMapItem.getMapName(exp.mapId());
            var adjState = world.getMapState(key);
            if (adjState == null) continue; // not yet synced from server

            // Get or create attachment for this adjacent tile
            var adjAttachment = com.explorermap.mod.ExplorerMapMod.getOrCreate(adjState);

            grid.tiles.add(new TileEntry(adjState, adjAttachment, gx, gz));
        }

        return grid;
    }
}
