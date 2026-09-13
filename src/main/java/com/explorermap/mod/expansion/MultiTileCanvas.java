package com.explorermap.mod.expansion;

import net.minecraft.world.storage.MapState;

/**
 * Coordinate model for a stitched multi-tile canvas.
 *
 * Converts between three coordinate spaces:
 *
 *   World space   – Minecraft block coordinates (double X, Z)
 *   Canvas space  – pixel coordinates within the full stitched image
 *                   origin = top-left of the westmost/northmost tile
 *                   size   = (gridCols * 128) × (gridRows * 128) pixels
 *   Screen space  – final rendered pixels after zoom + pan + screen offset
 *
 * Arithmetic is all done in canvas space (integer pixels) so the renderers
 * only need simple scale + offset math, not per-tile conditionals.
 *
 * Usage:
 *   MultiTileCanvas canvas = MultiTileCanvas.from(grid);
 *   // screen pos of a canvas pixel:
 *   int sx = canvas.toScreenX(canvasPx, screenOriginX, zoom, panX);
 *   // canvas pixel of a world coord:
 *   int cpx = canvas.worldToCanvasX(worldX, rootState);
 */
public final class MultiTileCanvas {

    /** Total canvas width in map pixels (gridCols * 128). */
    public final int canvasWidthPx;
    /** Total canvas height in map pixels (gridRows * 128). */
    public final int canvasHeightPx;

    /** Grid offset of the westmost column (most negative gridX). Negative or 0. */
    public final int minGridX;
    /** Grid offset of the northernmost row (most negative gridZ). Negative or 0. */
    public final int minGridZ;

    /** Total grid columns. */
    public final int gridCols;
    /** Total grid rows. */
    public final int gridRows;

    /** Blocks per map pixel of the root tile. */
    public final int scale;

    /** World X of the canvas top-left corner (pixel 0,0). */
    public final int worldOriginX;
    /** World Z of the canvas top-left corner (pixel 0,0). */
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

        // World origin: top-left of the northernmost/westernmost tile
        int tileBlocks     = 128 * scale;
        int rootTileOriginX = rootCenterX - tileBlocks / 2;
        int rootTileOriginZ = rootCenterZ - tileBlocks / 2;
        this.worldOriginX  = rootTileOriginX + minGridX * tileBlocks;
        this.worldOriginZ  = rootTileOriginZ + minGridZ * tileBlocks;
    }

    /** Build a MultiTileCanvas from a TileGrid. */
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

    // ── World → Canvas ────────────────────────────────────────────────────

    /** Converts a world X coordinate to a canvas pixel column. */
    public int worldToCanvasX(double worldX) {
        return (int)((worldX - worldOriginX) / scale);
    }

    /** Converts a world Z coordinate to a canvas pixel row. */
    public int worldToCanvasZ(double worldZ) {
        return (int)((worldZ - worldOriginZ) / scale);
    }

    // ── Canvas → Screen ───────────────────────────────────────────────────

    /**
     * Converts a canvas pixel X to a screen X.
     *
     * @param canvasPx     Canvas pixel column.
     * @param screenOriginX Screen X of canvas pixel (0,0).
     * @param zoom         Zoom factor.
     */
    public int toScreenX(int canvasPx, int screenOriginX, float zoom) {
        return screenOriginX + Math.round(canvasPx * zoom);
    }

    /** Converts a canvas pixel Z to a screen Y. */
    public int toScreenY(int canvasPz, int screenOriginY, float zoom) {
        return screenOriginY + Math.round(canvasPz * zoom);
    }

    // ── Canvas pixel size on screen ───────────────────────────────────────

    /** Screen pixels per canvas pixel at the given zoom. Always at least 1. */
    public int screenPixelSize(float zoom) {
        return Math.max(1, Math.round(zoom));
    }

    // ── Screen origin helpers ─────────────────────────────────────────────

    /**
     * Returns the screen X of canvas pixel (0,0) such that the root tile (gridX=0)
     * is centred inside a screen rect of width screenW, accounting for pan.
     *
     * Used by FullMapScreen to keep the root tile in the middle when no pan applied.
     */
    public int defaultScreenOriginX(int screenCentreX, float zoom, float panX) {
        // Root tile's left edge in canvas coords: (-minGridX) * 128 px
        int rootCanvasLeft  = (-minGridX) * 128;
        int rootCanvasCentre = rootCanvasLeft + 64; // root tile centre
        return screenCentreX - Math.round(rootCanvasCentre * zoom) + (int)panX;
    }

    public int defaultScreenOriginY(int screenCentreY, float zoom, float panY) {
        int rootCanvasTop    = (-minGridZ) * 128;
        int rootCanvasCentre = rootCanvasTop + 64;
        return screenCentreY - Math.round(rootCanvasCentre * zoom) + (int)panY;
    }

    // ── Per-tile canvas origin ────────────────────────────────────────────

    /** Canvas pixel column of the top-left of tile at gridX. */
    public int tileCanvasX(int gridX) { return (gridX - minGridX) * 128; }

    /** Canvas pixel row of the top-left of tile at gridZ. */
    public int tileCanvasZ(int gridZ) { return (gridZ - minGridZ) * 128; }

    // ── HUD helper ────────────────────────────────────────────────────────

    /**
     * Computes the rendered tile pixel size for the HUD so the full grid fits
     * inside a square HUD box of hudSize pixels.
     */
    public int hudRenderedTileSize(int hudSize) {
        return Math.max(4, Math.min(hudSize / gridCols, hudSize / gridRows));
    }

    /**
     * Returns the screen X of canvas pixel (0,0) for the HUD layout,
     * centering the full grid inside the HUD box.
     */
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
