package com.explorermap.mod.expansion;

import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapEntryData;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;

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
 * Map ID resolution
 * ─────────────────
 * An earlier revision of this class scanned up to tens of thousands of
 * candidate IDs to discover the root tile's own map ID, because it only had
 * the MapState object and no way (it believed) to recover the ID from it.
 * That was unnecessary: the caller always already holds the ItemStack this
 * MapState came from, and MapIdentity.rawIdOf(stack) reads the ID directly
 * off its MapIdComponent data component in O(1). build() now takes the root
 * map's ID as a parameter instead of rediscovering it.
 */
public final class TileGrid {

    public record TileEntry(
            int mapId,
            MapState state,
            MapEntryData entry,
            int gridX,   // column offset from root (West = negative)
            int gridZ    // row offset from root (North = negative)
    ) {}

    private final List<TileEntry> tiles = new ArrayList<>();

    private TileGrid() {}

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
     * Builds a TileGrid from a root MapState and its client-side mirror entry.
     *
     * @param rootMapId The root map's own integer ID (read by the caller via
     *                  MapIdentity.rawIdOf on the held ItemStack — never
     *                  rediscovered by scanning).
     * @param rootState The map currently in the off-hand.
     * @param rootEntry Its client-side discovery mirror (ClientMapCache entry).
     * @param world     Client world, used to resolve expansion tiles by ID.
     */
    public static TileGrid build(int rootMapId, MapState rootState, MapEntryData rootEntry, ClientWorld world) {
        TileGrid grid = new TileGrid();
        grid.tiles.add(new TileEntry(rootMapId, rootState, rootEntry, 0, 0));

        // Snapshot before iterating — a GrantExpansionPayload arriving on the
        // network thread could otherwise mutate this list mid-iteration.
        List<ExpansionRecord> expansionSnapshot = rootEntry.getExpansions();

        for (ExpansionRecord exp : expansionSnapshot) {
            int gx = 0, gz = 0;
            switch (exp.direction()) {
                case NORTH -> gz = -1;
                case SOUTH -> gz =  1;
                case WEST  -> gx = -1;
                case EAST  -> gx =  1;
            }

            MapState adjState = world.getMapState(new MapIdComponent(exp.mapId()));
            if (adjState == null) continue; // not yet synced from server

            MapEntryData adjEntry = ClientMapCache.getOrCreate(adjState, exp.mapId());
            grid.tiles.add(new TileEntry(exp.mapId(), adjState, adjEntry, gx, gz));
        }

        return grid;
    }
}
