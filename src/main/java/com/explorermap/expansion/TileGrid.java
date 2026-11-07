package com.explorermap.expansion;

import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.ExplorerMapMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TileGrid {

    private static final Set<MapIdentity> TRUNCATION_REPORTED = new HashSet<>();
    private static final Set<String> DIMENSION_SKIP_REPORTED = new HashSet<>();

    // The grid only changes when expansions or the root map change, so it is built once and reused.
    private static TileGrid cached;
    private static MapIdentity cachedRootId;
    private static MapItemSavedData cachedRootState;
    private static MapEntryData cachedRootEntry;
    private static ClientLevel cachedWorld;
    private static long cachedTopology;
    private static int cachedMaxTiles;

    /**
     * A tile on the grid. {@code state} is vanilla map data when the client had it at build time; otherwise
     * the colors come from the server sync, and {@code placeholder} supplies the geometry until they arrive.
     */
    public record TileEntry(
            MapIdentity mapId,
            MapItemSavedData state,
            MapEntryData entry,
            int gridX,
            int gridZ,
            TileMapView placeholder
    ) {
        public TileMapView view() {
            MapItemSavedData data = state;
            if (data == null) {
                ClientLevel level = Minecraft.getInstance().level;
                if (level != null) data = level.getMapData(new MapId(mapId.mapId()));
            }
            if (data != null && data.dimension.equals(mapId.dimension())) return TileMapView.of(data);

            byte[] synced = ClientTileData.get(mapId);
            if (synced == null || synced.length != placeholder.colors().length) return placeholder;
            return new TileMapView(synced, placeholder.blocksPerPixel(),
                    placeholder.centerX(), placeholder.centerZ(), placeholder.dimension());
        }
    }

    private final List<TileEntry> tiles = new ArrayList<>();
    private final Map<Long, TileEntry> byGrid = new HashMap<>();
    private MultiTileCanvas canvas;
    private MapItemSavedData canvasRoot;

    private TileGrid() {}

    public List<TileEntry> tiles() { return tiles; }

    public static void resetReportedWarnings() {
        TRUNCATION_REPORTED.clear();
        DIMENSION_SKIP_REPORTED.clear();
        cached = null;
    }

    private static long gridKey(int gridX, int gridZ) {
        return ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
    }

    private void add(TileEntry tile) {
        tiles.add(tile);
        byGrid.put(gridKey(tile.gridX(), tile.gridZ()), tile);
    }

    MultiTileCanvas canvasFor(MapItemSavedData rootState) {
        if (canvas == null || canvasRoot != rootState) {
            canvas = MultiTileCanvas.compute(this, rootState);
            canvasRoot = rootState;
        }
        return canvas;
    }

    public boolean hasTileAt(int gridX, int gridZ) {
        return byGrid.containsKey(gridKey(gridX, gridZ));
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
        int gridX = canvas.minGridX + Math.floorDiv(canvas.worldToCanvasX(worldX), 128);
        int gridZ = canvas.minGridZ + Math.floorDiv(canvas.worldToCanvasZ(worldZ), 128);
        return byGrid.get(gridKey(gridX, gridZ));
    }

    public static TileGrid build(MapIdentity rootMapId, MapItemSavedData rootState, MapEntryData rootEntry, ClientLevel world) {
        int maxTiles = ExplorerMapConfig.get().expansionTileLimit();
        long topology = MapEntryData.topologyGeneration();

        if (cached != null && rootMapId.equals(cachedRootId) && rootState == cachedRootState
                && rootEntry == cachedRootEntry && world == cachedWorld
                && topology == cachedTopology && maxTiles == cachedMaxTiles) {
            return cached;
        }

        boolean truncated = false;
        int scale = 1 << rootState.scale;
        int tileWidth = 128 * scale;

        TileGrid grid = new TileGrid();
        grid.add(new TileEntry(rootMapId, rootState, rootEntry, 0, 0, null));

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

                // Vanilla map data is only on the client for maps it holds, so the geometry is derived from
                // the root; the colors come from the server sync (see ClientTileData).
                TileMapView placeholder = new TileMapView(new byte[128 * 128], scale,
                        rootState.centerX + gx * tileWidth, rootState.centerZ + gz * tileWidth, world.dimension());
                MapItemSavedData adjState = world.getMapData(new MapId(target.mapId()));
                MapEntryData adjEntry = ClientMapCache.getOrCreate(target);
                TileEntry adjTile = new TileEntry(target, adjState, adjEntry, gx, gz, placeholder);
                grid.add(adjTile);
                queue.addLast(adjTile);
            }
        }

        if (truncated && TRUNCATION_REPORTED.add(rootMapId)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] The expansion grid for map {} was limited to {} tiles; further expanded maps are not displayed. "
                            + "The limit can be raised with the 'Max Expansion Tiles' setting.",
                    rootMapId.asKey(), maxTiles);
        }

        cached = grid;
        cachedRootId = rootMapId;
        cachedRootState = rootState;
        cachedRootEntry = rootEntry;
        cachedWorld = world;
        cachedTopology = topology;
        cachedMaxTiles = maxTiles;
        return grid;
    }
}
