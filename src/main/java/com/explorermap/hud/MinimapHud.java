package com.explorermap.hud;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.dimension.DimensionMapTracker;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.InteractionHand;

@Environment(EnvType.CLIENT)
public class MinimapHud {

    public static void render(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || client.level == null) return;

        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (!cfg.showHud) return;
        if (cfg.hideWhenSneaking && player.isShiftKeyDown()) return;
        if (cfg.hideInMenus && client.gui.screen() != null) return;

        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        int rawMapId = MapIdentity.rawIdOf(offHand);
        if (rawMapId < 0) return;

        MapItemSavedData mapState = MapIdentity.stateOf(offHand, client.level);
        if (mapState == null) return;

        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapIdentity mapId = MapIdentity.of(mapState, rawMapId);
        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapId);

        int size    = cfg.mapSize;
        int padding = cfg.padding;
        int sw      = context.guiWidth();
        int sh      = context.guiHeight();

        int boxX, boxY;
        switch (cfg.corner) {
            case TOP_LEFT    -> { boxX = padding;             boxY = padding; }
            case TOP_RIGHT   -> { boxX = sw - size - padding; boxY = padding; }
            case BOTTOM_LEFT -> { boxX = padding;             boxY = sh - size - padding; }
            default          -> { boxX = sw - size - padding; boxY = sh - size - padding; }
        }

        int bgAlpha = ((int) (cfg.opacity() * 0.55f * 255) << 24);
        context.fill(boxX - 2, boxY - 2, boxX + size + 2, boxY + size + 2, bgAlpha);

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.level);

        TileRenderer.renderHud(context, grid, mapState,
                boxX, boxY, size,
                player.getX(), player.getZ(), player.getYRot());

        renderWaypoints(context, grid, mapState, boxX, boxY, size);

        if (cfg.showCompass) {
            renderCompass(context, boxX + size - 18, boxY + 2);
        }

        if (cfg.showCoordinates) {
            renderCoordinates(context, player, boxX, boxY, size, cfg);
        }

        renderDimensionLabel(context, player, boxX, boxY, size, sh);

        if (cfg.showExpansionArrows) {
            renderExpansionArrows(context, mapEntry, boxX, boxY, size, padding >= 12);
        }

        context.outline(boxX - 2, boxY - 2, size + 4, size + 4,
                ((int) (cfg.opacity() * 180) << 24) | 0x888888);
    }

    private static void renderWaypoints(GuiGraphicsExtractor ctx, TileGrid grid, MapItemSavedData rootState,
                                         int boxX, int boxY, int size) {
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, rootState);
        int tileSize = canvas.hudRenderedTileSize(size);
        int originX  = canvas.hudScreenOriginX(boxX, size);
        int originY  = canvas.hudScreenOriginY(boxY, size);
        float pixSize = tileSize / 128f;

        ctx.enableScissor(boxX, boxY, boxX + size, boxY + size);
        for (TileGrid.TileEntry tile : grid.tiles()) {
            for (Waypoint wp : tile.entry().getWaypoints()) {
                if (!grid.isWorldPositionDiscovered(canvas, wp.worldX(), wp.worldZ())) continue;
                int cpx = canvas.worldToCanvasX(wp.worldX());
                int cpz = canvas.worldToCanvasZ(wp.worldZ());
                int sx  = originX + Math.round(cpx * pixSize);
                int sy  = originY + Math.round(cpz * pixSize);
                WaypointIconRenderer.drawOnMap(ctx, wp, sx, sy, WaypointIconRenderer.HUD_ICON_SIZE, false);
            }
        }
        ctx.disableScissor();
    }

    private static void renderCompass(GuiGraphicsExtractor ctx, int x, int y) {
        ctx.text(Minecraft.getInstance().font, Component.translatable("explorermap.compass.north").getString(), x + 3, y, 0xFFFF5555, true);
        ctx.text(Minecraft.getInstance().font, "·", x,      y + 10, 0xFF888888, false);
        ctx.text(Minecraft.getInstance().font, "·", x + 10, y + 10, 0xFF888888, false);
        ctx.text(Minecraft.getInstance().font, "·", x + 4,  y + 18, 0xFF888888, false);
    }

    private static void renderDimensionLabel(GuiGraphicsExtractor ctx, LocalPlayer player,
                                              int boxX, int boxY, int size, int screenHeight) {
        if (DimensionMapTracker.isOverworld(player)) return;
        String label = DimensionMapTracker.dimensionLabel(player);
        var tr = Minecraft.getInstance().font;
        int lw = tr.width(label);
        // Below the box, or inside its bottom edge when there is no room below it.
        int labelY = boxY + size + 2;
        if (labelY + 9 > screenHeight) labelY = boxY + size - 11;
        ctx.text(tr, label, boxX + size / 2 - lw / 2, labelY, 0xFFAAAAAA, true);
    }

    private static void renderCoordinates(GuiGraphicsExtractor ctx, LocalPlayer player,
                                          int boxX, int boxY, int size, ExplorerMapConfig cfg) {
        var tr = Minecraft.getInstance().font;
        String[] coordinates = {
                String.format("X %.0f", player.getX()),
                String.format("Y %.0f", player.getY()),
                String.format("Z %.0f", player.getZ())
        };
        int textX = switch (cfg.corner) {
            case TOP_LEFT, BOTTOM_LEFT -> boxX + size + 5;
            case TOP_RIGHT, BOTTOM_RIGHT -> boxX - 54;
        };
        int textY = boxY + size / 2 - 12;
        for (int i = 0; i < coordinates.length; i++) {
            ctx.text(tr, coordinates[i], textX, textY + i * 10, 0xFFD0D0D0, true);
        }
    }

    private static void renderExpansionArrows(GuiGraphicsExtractor ctx, MapEntryData mapEntry,
                                               int bx, int by, int size, boolean outside) {
        var exps = mapEntry.getExpansions();
        int cx = bx + size / 2;
        int cy = by + size / 2;
        var tr = Minecraft.getInstance().font;

        boolean hasN = hasDir(exps, ExpansionRecord.Direction.NORTH);
        boolean hasS = hasDir(exps, ExpansionRecord.Direction.SOUTH);
        boolean hasW = hasDir(exps, ExpansionRecord.Direction.WEST);
        boolean hasE = hasDir(exps, ExpansionRecord.Direction.EAST);

        int available = 0xCCFFFF88;

        // Outside the box when the padding leaves room for it, otherwise just inside its edge.
        if (!hasN) ctx.text(tr, "▲", cx - 3, outside ? by - 10 : by + 2, available, true);
        if (!hasS) ctx.text(tr, "▼", cx - 3, outside ? by + size + 2 : by + size - 10, available, true);
        if (!hasW) ctx.text(tr, "◄", outside ? bx - 10 : bx + 2, cy - 4, available, true);
        if (!hasE) ctx.text(tr, "►", outside ? bx + size + 2 : bx + size - 10, cy - 4, available, true);
    }

    private static boolean hasDir(java.util.List<ExpansionRecord> list, ExpansionRecord.Direction dir) {
        return list.stream().anyMatch(r -> r.direction() == dir);
    }
}
