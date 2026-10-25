package com.explorermap.expansion;

import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.ExplorerMapMod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TileGrid {

    private static final Set<MapIdentity> TRUNCATION_REPORTED = new HashSet<>();
    private static final Set<String> DIMENSION_SKIP_REPORTED = new HashSet<>();

    public record TileEntry(
            MapIdentity mapId,
            MapItemSavedData state,
            MapEntryData entry,
            int gridX,
            int gridZ
    ) {}

    private final List<TileEntry> tiles = new ArrayList<>();

    private TileGrid() {}

    public List<TileEntry> tiles() { return tiles; }

    public static void resetReportedWarnings() {
        TRUNCATION_REPORTED.clear();
        DIMENSION_SKIP_REPORTED.clear();
    }

    public boolean hasTileAt(int gridX, int gridZ) {
        for (TileEntry t : tiles) {
            if (t.gridX() == gridX && t.gridZ() == gridZ) return true;
        }
        return false;
    }

    public boolean isWorldPositionDiscovered(MultiTileCanvas canvas, double worldX, double worldZ) {
        TileEntry owner = findTileContaining(canvas, worldX, worldZ);
        if (owner == null) return false;
        int canvasX = canvas.worldToCanvasX(worldX);
        int canvasZ = canvas.worldToCanvasZ(worldZ);
        int localCol = canvasX - canvas.tileCanvasX(owner.gridX());
        int localRow = canvasZ - canvas.tileCanvasZ(owner.gridZ());
        return owner.entry().isDiscovered(localCol, localRow);
    }

    public TileEntry findTileContaining(MultiTileCanvas canvas, double worldX, double worldZ) {
        int canvasX = canvas.worldToCanvasX(worldX);
        int canvasZ = canvas.worldToCanvasZ(worldZ);

        for (TileEntry t : tiles) {
            int tileOriginX = canvas.tileCanvasX(t.gridX());
            int tileOriginZ = canvas.tileCanvasZ(t.gridZ());
            int localCol = canvasX - tileOriginX;
            int localRow = canvasZ - tileOriginZ;
            if (localCol >= 0 && localCol < 128 && localRow >= 0 && localRow < 128) {
                return t;
            }
        }
        return null;
    }

    public static TileGrid build(MapIdentity rootMapId, MapItemSavedData rootState, MapEntryData rootEntry, ClientLevel world) {
        int maxTiles = ExplorerMapConfig.get().expansionTileLimit();
        boolean truncated = false;

        TileGrid grid = new TileGrid();
        grid.tiles.add(new TileEntry(rootMapId, rootState, rootEntry, 0, 0));

        Set<MapIdentity> visited = new HashSet<>();
        visited.add(rootMapId);

        Deque<TileEntry> queue = new ArrayDeque<>();
        queue.add(grid.tiles.get(0));

        while (!queue.isEmpty()) {
            TileEntry current = queue.removeFirst();

            for (ExpansionRecord exp : current.entry().getExpansions()) {
                MapIdentity target = exp.target();
                if (!visited.add(target)) continue;

                if (!target.dimension().equals(world.dimension())) {
                    if (DIMENSION_SKIP_REPORTED.add(target.asKey() + "@" + world.dimension().identifier())) {
                        ExplorerMapMod.LOGGER.warn(
                                "[ExplorerMap] Skipping expansion to {} - it isn't in the current dimension {}",
                                target.asKey(), world.dimension().identifier());
                    }
                    continue;
                }

                MapItemSavedData adjState = world.getMapData(new MapId(target.mapId()));
                if (adjState == null) continue;

                if (grid.tiles.size() >= maxTiles) {
                    truncated = true;
                    continue;
                }

                int gx = current.gridX(), gz = current.gridZ();
                switch (exp.direction()) {
                    case NORTH -> gz -= 1;
                    case SOUTH -> gz += 1;
                    case WEST  -> gx -= 1;
                    case EAST  -> gx += 1;
                }

                MapEntryData adjEntry = ClientMapCache.getOrCreate(target);
                TileEntry adjTile = new TileEntry(target, adjState, adjEntry, gx, gz);
                grid.tiles.add(adjTile);
                queue.addLast(adjTile);
            }
        }

        if (truncated && TRUNCATION_REPORTED.add(rootMapId)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] The expansion grid for map {} was limited to {} tiles; further expanded maps are not displayed. "
                            + "The limit can be raised with the 'Max Expansion Tiles' setting.",
                    rootMapId.asKey(), maxTiles);
        }

        return grid;
    }
}
