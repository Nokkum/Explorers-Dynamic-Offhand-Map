package com.explorermap.expansion;

import net.minecraft.item.map.MapState;

public final class MultiTileCanvas {

    public final int canvasWidthPx;

    public final int canvasHeightPx;

    public final int minGridX;

    public final int minGridZ;

    public final int gridCols;

    public final int gridRows;

    public final int scale;

    public final int worldOriginX;

    public final int worldOriginZ;

    private MultiTileCanvas(int minGridX, int minGridZ, int gridCols, int gridRows,
                             int scale, int rootCenterX, int rootCenterZ) {
        this.minGridX       = minGridX;
        this.minGridZ       = minGridZ;
        this.gridCols       = gridCols;
        this.gridRows       = gridRows;
        this.scale          = scale;
        this.canvasWidthPx  = gridCols * 128;
        this.canvasHeightPx = gridRows * 128;

        int tileBlocks     = 128 * scale;
        int rootTileOriginX = rootCenterX - tileBlocks / 2;
        int rootTileOriginZ = rootCenterZ - tileBlocks / 2;
        this.worldOriginX  = rootTileOriginX + minGridX * tileBlocks;
        this.worldOriginZ  = rootTileOriginZ + minGridZ * tileBlocks;
    }

    public static MultiTileCanvas from(TileGrid grid, MapState rootState) {
        int minGX = 0, maxGX = 0, minGZ = 0, maxGZ = 0;
        for (TileGrid.TileEntry t : grid.tiles()) {
            minGX = Math.min(minGX, t.gridX()); maxGX = Math.max(maxGX, t.gridX());
            minGZ = Math.min(minGZ, t.gridZ()); maxGZ = Math.max(maxGZ, t.gridZ());
        }
        int cols  = maxGX - minGX + 1;
        int rows  = maxGZ - minGZ + 1;
        int scale = 1 << rootState.scale;
        return new MultiTileCanvas(minGX, minGZ, cols, rows,
                scale, rootState.centerX, rootState.centerZ);
    }

    public int worldToCanvasX(double worldX) {
        return (int)((worldX - worldOriginX) / scale);
    }

    public int worldToCanvasZ(double worldZ) {
        return (int)((worldZ - worldOriginZ) / scale);
    }

    public int defaultScreenOriginX(int screenCentreX, float zoom, float panX) {

        int rootCanvasLeft  = (-minGridX) * 128;
        int rootCanvasCentre = rootCanvasLeft + 64;
        return screenCentreX - Math.round(rootCanvasCentre * zoom) + (int)panX;
    }

    public int defaultScreenOriginY(int screenCentreY, float zoom, float panY) {
        int rootCanvasTop    = (-minGridZ) * 128;
        int rootCanvasCentre = rootCanvasTop + 64;
        return screenCentreY - Math.round(rootCanvasCentre * zoom) + (int)panY;
    }

    public int tileCanvasX(int gridX) { return (gridX - minGridX) * 128; }

    public int tileCanvasZ(int gridZ) { return (gridZ - minGridZ) * 128; }

    public int hudRenderedTileSize(int hudSize) {
        return Math.max(4, Math.min(hudSize / gridCols, hudSize / gridRows));
    }

    public int hudScreenOriginX(int hudBoxX, int hudSize) {
        int tileSize   = hudRenderedTileSize(hudSize);
        int totalWidth = gridCols * tileSize;
        return hudBoxX + (hudSize - totalWidth) / 2 - minGridX * tileSize;
    }

    public int hudScreenOriginY(int hudBoxY, int hudSize) {
        int tileSize    = hudRenderedTileSize(hudSize);
        int totalHeight = gridRows * tileSize;
        return hudBoxY + (hudSize - totalHeight) / 2 - minGridZ * tileSize;
    }
}
