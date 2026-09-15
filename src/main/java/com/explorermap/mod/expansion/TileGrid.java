package com.explorermap.mod.expansion;

import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapEntryData;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.map.MapState;

import java.util.ArrayList;
import java.util.List;

public final class TileGrid {

    public record TileEntry(
            int mapId,
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

    public static TileGrid build(int rootMapId, MapState rootState, MapEntryData rootEntry, ClientWorld world) {
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

            MapState adjState = world.getMapState(new MapIdComponent(exp.mapId()));
            if (adjState == null) continue;

            MapEntryData adjEntry = ClientMapCache.getOrCreate(adjState, exp.mapId());
            grid.tiles.add(new TileEntry(exp.mapId(), adjState, adjEntry, gx, gz));
        }

        return grid;
    }
}
