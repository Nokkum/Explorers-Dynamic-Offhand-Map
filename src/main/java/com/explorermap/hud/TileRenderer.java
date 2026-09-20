package com.explorermap.hud;

import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.config.ExplorerMapConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.item.map.MapState;

@Environment(EnvType.CLIENT)
public final class TileRenderer {

    private static final int FADE_WIDTH_PX = 6;

    private TileRenderer() {}

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
        renderGridLines(context, grid, canvas, originX, originY, pixSize);
        context.disableScissor();

        context.enableScissor(hudBoxX - 8, hudBoxY - 8, hudBoxX + hudSize + 8, hudBoxY + hudSize + 8);
        int cpx = canvas.worldToCanvasX(playerX);
        int cpz = canvas.worldToCanvasZ(playerZ);
        int ax  = originX + Math.round(cpx * pixSize);
        int ay  = originY + Math.round(cpz * pixSize);
        drawRotatedArrow(context, ax, ay, yawDeg, 5);
        context.disableScissor();
    }

    public static void renderFullMap(DrawContext context, TileGrid grid, MapState rootState,
                                      int screenCentreX, int screenCentreY,
                                      float zoom, float panX, float panY,
                                      int clipX0, int clipY0, int clipX1, int clipY1,
                                      double playerX, double playerZ, float yawDeg) {
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, rootState);
        int originX   = canvas.defaultScreenOriginX(screenCentreX, zoom, panX);
        int originY   = canvas.defaultScreenOriginY(screenCentreY, zoom, panY);
        float pixSize = zoom;

        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        renderAllTiles(context, grid, canvas, originX, originY, pixSize, -1);
        renderGridLines(context, grid, canvas, originX, originY, pixSize);
        context.disableScissor();

        context.enableScissor(clipX0, clipY0, clipX1, clipY1);
        int cpx = canvas.worldToCanvasX(playerX);
        int cpz = canvas.worldToCanvasZ(playerZ);
        int ax  = originX + Math.round(cpx * pixSize);
        int ay  = originY + Math.round(cpz * pixSize);
        drawRotatedArrow(context, ax, ay, yawDeg, 7);
        context.disableScissor();
    }

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

    private static void renderGridLines(DrawContext context, TileGrid grid,
                                        MultiTileCanvas canvas, int originX, int originY,
                                        float pixSize) {
        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (!cfg.showGridLines) return;

        int blockSpacing = cfg.gridSpacing == ExplorerMapConfig.GridSpacing.CHUNK ? 16 : 32;
        int pixelsPerLine = Math.max(1, blockSpacing / canvas.scale);
        int color = 0x66333333;

        for (TileGrid.TileEntry tile : grid.tiles()) {
            int tileX = originX + Math.round(canvas.tileCanvasX(tile.gridX()) * pixSize);
            int tileY = originY + Math.round(canvas.tileCanvasZ(tile.gridZ()) * pixSize);
            int tileSize = Math.round(128 * pixSize);

            for (int px = pixelsPerLine; px < 128; px += pixelsPerLine) {
                int x = tileX + Math.round(px * pixSize);
                context.fill(x, tileY, x + 1, tileY + tileSize, color);
            }
            for (int pz = pixelsPerLine; pz < 128; pz += pixelsPerLine) {
                int y = tileY + Math.round(pz * pixSize);
                context.fill(tileX, y, tileX + tileSize, y + 1, color);
            }
        }
    }

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

        context.drawTexture(tex, 0, 0, 0, 0, 128, 128, 128, 128);
        matrices.pop();
    }

    private static void renderFadedEdge(DrawContext context,
                                         TileGrid grid,
                                         TileGrid.TileEntry tile,
                                         int tileScreenX, int tileScreenY,
                                         float pixSize) {
        int tileSize = Math.round(128 * pixSize);
        if (tileSize <= 0) return;

        int fadeW = Math.max(2, Math.round(FADE_WIDTH_PX * pixSize));

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

    private static void drawFade(DrawContext ctx,
                                   int x0, int y0, int x1, int y1,
                                   int dx, int dy,
                                   int fadeW, int edgeLen) {
        for (int i = 0; i < fadeW; i++) {

            int alpha = (int)(255 * (float)(fadeW - i) / fadeW * 0.75f);
            int color = (alpha << 24);

            int ox = dx * i, oy = dy * i;

            if (dx == 0) {

                int rowY = y0 + oy;
                ctx.fill(x0, rowY, x1, rowY + 1, color);
            } else {

                int colX = x0 + ox;
                ctx.fill(colX, y0, colX + 1, y1, color);
            }
        }
    }

    public static void drawRotatedArrow(DrawContext ctx, int cx, int cy,
                                         float yawDeg, int halfSize) {
        double angle = Math.toRadians(yawDeg + 180.0);
        double cos   =  Math.cos(angle);
        double sin   =  Math.sin(angle);

        double tipY   = -halfSize;
        double baseY  =  halfSize - 1;
        double notchY =  halfSize / 2.0 - 1;
        double wingX  =  halfSize - 1;

        double[][] leftTri  = { {0,tipY}, {-wingX,baseY}, {0,notchY} };
        double[][] rightTri = { {0,tipY}, {0,notchY},     { wingX,baseY} };

        int[] lsx = new int[3], lsy = new int[3];
        int[] rsx = new int[3], rsy = new int[3];
        for (int i = 0; i < 3; i++) {
            lsx[i] = cx + (int)Math.round( leftTri[i][0]  * cos + leftTri[i][1]  * sin);
            lsy[i] = cy + (int)Math.round(-leftTri[i][0]  * sin + leftTri[i][1]  * cos);
            rsx[i] = cx + (int)Math.round( rightTri[i][0] * cos + rightTri[i][1] * sin);
            rsy[i] = cy + (int)Math.round(-rightTri[i][0] * sin + rightTri[i][1] * cos);
        }

        int[] lcx_ = new int[3], lcy_ = new int[3];
        int[] rcx_ = new int[3], rcy_ = new int[3];
        inflateTri(cx, cy, lsx, lsy, lcx_, lcy_, 1);
        inflateTri(cx, cy, rsx, rsy, rcx_, rcy_, 1);

        rasteriseTri(ctx, lcx_, lcy_, 0xDD000000);
        rasteriseTri(ctx, rcx_, rcy_, 0xDD000000);
        rasteriseTri(ctx, lsx,  lsy,  0xFFFFFFFF);
        rasteriseTri(ctx, rsx,  rsy,  0xFFFFFFFF);
    }

    private static void inflateTri(int centX, int centY,
                                    int[] sx, int[] sy,
                                    int[] outX, int[] outY, int r) {

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
