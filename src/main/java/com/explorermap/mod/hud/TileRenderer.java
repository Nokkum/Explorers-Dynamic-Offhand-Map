package com.explorermap.mod.hud;

import com.explorermap.mod.expansion.MultiTileCanvas;
import com.explorermap.mod.expansion.TileGrid;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.item.map.MapState;

/**
 * Shared pixel-rendering utility for the mini-map HUD and the full map screen.
 *
 * All coordinate math flows through MultiTileCanvas so the rendering logic
 * is identical for both callers — only the zoom, pan, and screen origin differ.
 *
 * Rendering model
 * ───────────────
 * 1. Build a MultiTileCanvas from the TileGrid.
 * 2. Compute screenOriginX/Y from the canvas (differs between HUD and full map).
 * 3. For each tile, compute its canvas-space top-left, then convert to screen coords.
 * 4. Iterate 128×128 pixels; draw discovered ones with vanilla map colors,
 *    undiscovered ones with the fog color.
 * 5. Draw faded-edge overlays at tile boundaries (where adjacent tile not yet loaded).
 * 6. Draw the player arrow via the scanline rasteriser.
 */
@Environment(EnvType.CLIENT)
public final class TileRenderer {

    private static final int FADE_WIDTH_PX = 6; // canvas pixels wide

    private TileRenderer() {}

    // ── HUD entry point ───────────────────────────────────────────────────

    /**
     * Renders the full tile grid in HUD mode (fixed scale, no zoom).
     *
     * @param context   DrawContext.
     * @param grid      TileGrid from TileGrid.build().
     * @param rootState Root MapState (for MultiTileCanvas construction).
     * @param hudBoxX   Screen X of HUD box top-left.
     * @param hudBoxY   Screen Y of HUD box top-left.
     * @param hudSize   HUD box side in screen pixels.
     */
    public static void renderHud(DrawContext context, TileGrid grid, MapState rootState,
                                  int hudBoxX, int hudBoxY, int hudSize,
                                  double playerX, double playerZ, float yawDeg) {
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, rootState);
        int tileSize  = canvas.hudRenderedTileSize(hudSize);
        int originX   = canvas.hudScreenOriginX(hudBoxX, hudSize);
        int originY   = canvas.hudScreenOriginY(hudBoxY, hudSize);
        float pixSize = tileSize / 128f;

        context.enableScissor(hudBoxX, hudBoxY, hudBoxX + hudSize, hudBoxY + hudSize);
        renderAllTiles(context, grid, canvas, originX, originY, pixSize, tileSize);
        context.disableScissor();

        // Player arrow (clipped independently — allow it to sit slightly outside tiles)
        context.enableScissor(hudBoxX - 8, hudBoxY - 8, hudBoxX + hudSize + 8, hudBoxY + hudSize + 8);
        int cpx = canvas.worldToCanvasX(playerX);
        int cpz = canvas.worldToCanvasZ(playerZ);
        int ax  = originX + Math.round(cpx * pixSize);
        int ay  = originY + Math.round(cpz * pixSize);
        drawRotatedArrow(context, ax, ay, yawDeg, 5);
        context.disableScissor();
    }

    // ── Full map entry point ───────────────────────────────────────────────

    /**
     * Renders the full tile grid for the FullMapScreen (zoom + pan).
     *
     * @param context       DrawContext.
     * @param grid          TileGrid.
     * @param rootState     Root MapState.
     * @param screenCentreX Screen X at which to centre the root tile (absent pan).
     * @param screenCentreY Screen Y at which to centre the root tile.
     * @param zoom          Zoom factor (0.5 – 4.0).
     * @param panX          Pan offset in screen pixels.
     * @param panY          Pan offset in screen pixels.
     * @param clipX0        Scissor left.
     * @param clipY0        Scissor top.
     * @param clipX1        Scissor right.
     * @param clipY1        Scissor bottom.
     * @param playerX       Player world X (for arrow).
     * @param playerZ       Player world Z.
     * @param yawDeg        Player yaw.
     */
    public static void renderFullMap(DrawContext context, TileGrid grid, MapState rootState,
                                      int screenCentreX, int screenCentreY,
                                      float zoom, float panX, float panY,
                                      int clipX0, int clipY0, int clipX1, int clipY1,
                                      double playerX, double playerZ, float yawDeg) {
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, rootState);
        int originX   = canvas.defaultScreenOriginX(screenCentreX, zoom, panX);
        int originY   = canvas.defaultScreenOriginY(screenCentreY, zoom, panY);
        float pixSize = zoom; // 1 canvas px = zoom screen px

        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        renderAllTiles(context, grid, canvas, originX, originY, pixSize, -1);
        context.disableScissor();

        // Player arrow
        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        int cpx = canvas.worldToCanvasX(playerX);
        int cpz = canvas.worldToCanvasZ(playerZ);
        int ax  = originX + Math.round(cpx * pixSize);
        int ay  = originY + Math.round(cpz * pixSize);
        drawRotatedArrow(context, ax, ay, yawDeg, 7);
        context.disableScissor();
    }

    // ── Core render loop ──────────────────────────────────────────────────

    /**
     * Renders all tiles in the grid.
     *
     * @param renderedTileSize Pixels per tile side (used for faded-edge width).
     *                         Pass -1 when zoom is fractional (full map mode);
     *                         the fade width is then computed from pixSize directly.
     */
    private static void renderAllTiles(DrawContext context,
                                        TileGrid grid,
                                        MultiTileCanvas canvas,
                                        int originX, int originY,
                                        float pixSize,
                                        int renderedTileSize) {
        for (TileGrid.TileEntry tile : grid.tiles()) {
            int tileCanvasPxX = canvas.tileCanvasX(tile.gridX());
            int tileCanvasPxZ = canvas.tileCanvasZ(tile.gridZ());

            int tileScreenX = originX + Math.round(tileCanvasPxX * pixSize);
            int tileScreenY = originY + Math.round(tileCanvasPxZ * pixSize);

            renderTilePixels(context, tile, tileScreenX, tileScreenY, pixSize);
            renderFadedEdge(context, grid, tile, tileScreenX, tileScreenY, pixSize);
        }
    }

    // ── Per-tile pixel rendering ──────────────────────────────────────────

    /**
     * Renders one tile using a cached GPU texture.
     *
     * TileTextureCache maintains one NativeImage + DynamicTexture per map ID.
     * It rebuilds the texture only when new pixels are discovered (generation
     * counter changes), so this is typically a no-op CPU-side and a single
     * textured quad GPU-side — replacing the previous 16 384 fill() calls.
     *
     * The texture is drawn scaled to cover (128*pixSize) × (128*pixSize)
     * screen pixels via a matrix push/scale/pop, exactly as WaypointIconRenderer
     * does for waypoint icons.
     */
    private static void renderTilePixels(DrawContext context,
                                          TileGrid.TileEntry tile,
                                          int tileScreenX, int tileScreenY,
                                          float pixSize) {
        Identifier tex = TileTextureCache.getInstance()
                .getOrUpdate(tile.mapId(), tile.state(), tile.entry());

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate((float) tileScreenX, (float) tileScreenY, 0f);
        if (pixSize != 1f) {
            matrices.scale(pixSize, pixSize, 1f);
        }
        // Draw the full 128×128 texture at (0,0) in scaled space.
        // drawTexture(id, x, y, u, v, width, height, texW, texH)
        context.drawTexture(tex, 0, 0, 0, 0, 128, 128, 128, 128);
        matrices.pop();
    }

    // ── Faded edge overlay ────────────────────────────────────────────────

    /**
     * Draws a darkening gradient along the edges of a tile that border
     * an unexpanded (not-yet-unlocked) direction.
     *
     * The fade is drawn as a series of increasingly opaque fill strips,
     * simulating a vignette that signals "map coverage ends here."
     *
     * Only draws on edges where the adjacent tile has NOT been unlocked
     * in that direction.
     */
    private static void renderFadedEdge(DrawContext context,
                                         TileGrid grid,
                                         TileGrid.TileEntry tile,
                                         int tileScreenX, int tileScreenY,
                                         float pixSize) {
        int tileSize = Math.round(128 * pixSize);
        if (tileSize <= 0) return;

        int fadeW = Math.max(2, Math.round(FADE_WIDTH_PX * pixSize));

        // Check each cardinal direction for missing neighbours
        boolean missingN = !grid.hasTileAt(tile.gridX(), tile.gridZ() - 1);
        boolean missingS = !grid.hasTileAt(tile.gridX(), tile.gridZ() + 1);
        boolean missingW = !grid.hasTileAt(tile.gridX() - 1, tile.gridZ());
        boolean missingE = !grid.hasTileAt(tile.gridX() + 1, tile.gridZ());

        int tx  = tileScreenX;
        int ty  = tileScreenY;
        int tx2 = tileScreenX + tileSize;
        int ty2 = tileScreenY + tileSize;

        if (missingN) drawFade(context, tx, ty,  tx2, ty,  0, 1,  fadeW, tileSize);
        if (missingS) drawFade(context, tx, ty2, tx2, ty2, 0, -1, fadeW, tileSize);
        if (missingW) drawFade(context, tx, ty,  tx,  ty2, 1, 0,  fadeW, tileSize);
        if (missingE) drawFade(context, tx2,ty,  tx2, ty2, -1,0,  fadeW, tileSize);
    }

    /**
     * Draws a multi-strip fade along one edge.
     *
     * @param x0,y0  Start of the edge line.
     * @param x1,y1  End of the edge line.
     * @param dx,dy  Inward direction unit vector (integer: ±1 or 0).
     * @param fadeW  Fade width in pixels.
     * @param edgeLen Edge length (used for perpendicular extent).
     */
    private static void drawFade(DrawContext ctx,
                                   int x0, int y0, int x1, int y1,
                                   int dx, int dy,
                                   int fadeW, int edgeLen) {
        for (int i = 0; i < fadeW; i++) {
            // Alpha increases as we approach the edge (i=0 is innermost)
            int alpha = (int)(255 * (float)(fadeW - i) / fadeW * 0.75f);
            int color = (alpha << 24);

            int ox = dx * i, oy = dy * i;

            // Draw one strip perpendicular to the inward direction
            if (dx == 0) {
                // Horizontal edge: strip is a 1-pixel-tall row at y0 + oy
                int rowY = y0 + oy;
                ctx.fill(x0, rowY, x1, rowY + 1, color);
            } else {
                // Vertical edge: strip is a 1-pixel-wide column at x0 + ox
                int colX = x0 + ox;
                ctx.fill(colX, y0, colX + 1, y1, color);
            }
        }
    }

    // ── Player arrow ──────────────────────────────────────────────────────

    /**
     * Draws a filled rotated chevron arrow centred at (cx, cy).
     *
     * Shape: isoceles triangle (tip + two base corners) with a V-notch
     * cut into the base, making the facing direction unambiguous even at
     * halfSize=5 (HUD). Drawn black outline → white fill.
     *
     * Rotation convention (north-up map):
     *   Minecraft yaw 0°  = south = screen DOWN
     *   Adding 180° makes yaw 0° point screen UP (north).
     *
     * Screen-space rotation (y-axis points DOWN):
     *   screenX =  lx·cos + ly·sin
     *   screenY = −lx·sin + ly·cos
     *
     * The concave V-notch is rendered by drawing the two left/right
     * wing triangles separately, avoiding concave-polygon winding issues.
     *
     * @param halfSize  Tip-to-centre distance in pixels. 5 = HUD, 7 = full map.
     */
    public static void drawRotatedArrow(DrawContext ctx, int cx, int cy,
                                         float yawDeg, int halfSize) {
        double angle = Math.toRadians(yawDeg + 180.0);
        double cos   =  Math.cos(angle);
        double sin   =  Math.sin(angle);

        // Local-space coords (tip at top, y-up):
        double tipY   = -halfSize;
        double baseY  =  halfSize - 1;
        double notchY =  halfSize / 2.0 - 1;   // V-notch depth
        double wingX  =  halfSize - 1;          // half-width at base

        // Decompose chevron into two triangles sharing the tip and notch centre:
        //   Left wing:  tip(0,tipY) → leftBase(-wingX,baseY) → notch(0,notchY)
        //   Right wing: tip(0,tipY) → notch(0,notchY)        → rightBase(wingX,baseY)
        double[][] leftTri  = { {0,tipY}, {-wingX,baseY}, {0,notchY} };
        double[][] rightTri = { {0,tipY}, {0,notchY},     { wingX,baseY} };

        // Rotate all 6 points to screen space
        int[] lsx = new int[3], lsy = new int[3];
        int[] rsx = new int[3], rsy = new int[3];
        for (int i = 0; i < 3; i++) {
            lsx[i] = cx + (int)Math.round( leftTri[i][0]  * cos + leftTri[i][1]  * sin);
            lsy[i] = cy + (int)Math.round(-leftTri[i][0]  * sin + leftTri[i][1]  * cos);
            rsx[i] = cx + (int)Math.round( rightTri[i][0] * cos + rightTri[i][1] * sin);
            rsy[i] = cy + (int)Math.round(-rightTri[i][0] * sin + rightTri[i][1] * cos);
        }

        // Outline: draw both triangles 1px larger in all directions.
        // Simple approach: inflate each vertex away from centroid by 1px.
        int[] lcx_ = new int[3], lcy_ = new int[3];
        int[] rcx_ = new int[3], rcy_ = new int[3];
        inflateTri(cx, cy, lsx, lsy, lcx_, lcy_, 1);
        inflateTri(cx, cy, rsx, rsy, rcx_, rcy_, 1);

        // Draw: outline then fill
        rasteriseTri(ctx, lcx_, lcy_, 0xDD000000);
        rasteriseTri(ctx, rcx_, rcy_, 0xDD000000);
        rasteriseTri(ctx, lsx,  lsy,  0xFFFFFFFF);
        rasteriseTri(ctx, rsx,  rsy,  0xFFFFFFFF);
    }

    /**
     * Inflates each vertex of a triangle outward from the centroid by {@code r} pixels.
     * Writes results into {@code outX} / {@code outY}.
     */
    private static void inflateTri(int centX, int centY,
                                    int[] sx, int[] sy,
                                    int[] outX, int[] outY, int r) {
        // Use actual tri centroid, not map canvas centre
        double triCX = (sx[0] + sx[1] + sx[2]) / 3.0;
        double triCY = (sy[0] + sy[1] + sy[2]) / 3.0;
        for (int i = 0; i < 3; i++) {
            double dx = sx[i] - triCX;
            double dy = sy[i] - triCY;
            double len = Math.sqrt(dx*dx + dy*dy);
            if (len < 0.01) { outX[i] = sx[i]; outY[i] = sy[i]; continue; }
            outX[i] = (int)Math.round(sx[i] + dx/len * r);
            outY[i] = (int)Math.round(sy[i] + dy/len * r);
        }
    }

    // ── Scanline triangle rasteriser ──────────────────────────────────────

    /**
     * Fills a single triangle with a scanline algorithm (even-odd fill).
     * Only handles triangles (3 vertices) — avoids concave-polygon issues.
     */
    private static void rasteriseTri(DrawContext ctx, int[] xs, int[] ys, int color) {
        int minY = Math.min(ys[0], Math.min(ys[1], ys[2]));
        int maxY = Math.max(ys[0], Math.max(ys[1], ys[2]));

        for (int y = minY; y <= maxY; y++) {
            int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE;
            for (int e = 0; e < 3; e++) {
                int x0 = xs[e],           y0 = ys[e];
                int x1 = xs[(e + 1) % 3], y1 = ys[(e + 1) % 3];
                if ((y0 <= y && y < y1) || (y1 <= y && y < y0)) {
                    int x = x0 + (y - y0) * (x1 - x0) / (y1 - y0);
                    xMin = Math.min(xMin, x);
                    xMax = Math.max(xMax, x);
                }
            }
            if (xMin <= xMax) {
                ctx.fill(xMin, y, xMax + 1, y + 1, color);
            }
        }
    }
}
