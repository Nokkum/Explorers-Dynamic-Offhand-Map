package com.explorermap.expansion;

import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.ExplorerMapMod;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;

import java.util.ArrayList;
import java.util.List;

public final class TileGrid {

    public record TileEntry(
            MapIdentity mapId,
            MapState state,
            MapEntryData entry,
            int gridX,
            int gridZ
    ) {}

    private final List<TileEntry> tiles = new ArrayList<>();

    private TileGrid() {}

    public List<TileEntry> tiles() { return tiles; }

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

    public static TileGrid build(MapIdentity rootMapId, MapState rootState, MapEntryData rootEntry, ClientWorld world) {
        TileGrid grid = new TileGrid();
        grid.tiles.add(new TileEntry(rootMapId, rootState, rootEntry, 0, 0));

        List<ExpansionRecord> expansionSnapshot = rootEntry.getExpansions();

        for (ExpansionRecord exp : expansionSnapshot) {
            int gx = 0, gz = 0;
            switch (exp.direction()) {
                case NORTH -> gz = -1;
                case SOUTH -> gz =  1;
                case WEST  -> gx = -1;
                case EAST  -> gx =  1;
            }

            MapIdentity target = exp.target();
            if (!target.dimension().equals(world.getRegistryKey())) {
                ExplorerMapMod.LOGGER.warn(
                        "[ExplorerMap] Skipping expansion to {} - it isn't in the current dimension {}",
                        target.asKey(), world.getRegistryKey().getValue());
                continue;
            }

            MapState adjState = world.getMapState(new MapIdComponent(target.mapId()));
            if (adjState == null) continue;

            MapEntryData adjEntry = ClientMapCache.getOrCreate(target);
            grid.tiles.add(new TileEntry(target, adjState, adjEntry, gx, gz));
        }

        return grid;
    }
}
