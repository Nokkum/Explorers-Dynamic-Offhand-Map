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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.util.Hand;

@Environment(EnvType.CLIENT)
public class MinimapHud {

    public static boolean handleClick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null || client.currentScreen != null) {
            return false;
        }

        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (!cfg.showHud || (cfg.hideWhenSneaking && client.player.isSneaking())) return false;

        ItemStack offHand = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return false;

        int mapId = MapIdentity.rawIdOf(offHand);
        MapState mapState = mapId >= 0 ? MapIdentity.stateOf(offHand, client.world) : null;
        if (mapState == null
                || !DimensionMapTracker.isMapRelevantForCurrentDimension(client.player, mapState)) {
            return false;
        }

        int size = cfg.mapSize;
        int padding = cfg.padding;
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        int boxX, boxY;
        switch (cfg.corner) {
            case TOP_LEFT -> { boxX = padding; boxY = padding; }
            case TOP_RIGHT -> { boxX = sw - size - padding; boxY = padding; }
            case BOTTOM_LEFT -> { boxX = padding; boxY = sh - size - padding; }
            default -> { boxX = sw - size - padding; boxY = sh - size - padding; }
        }

        double mouseX = client.mouse.getX() * sw / Math.max(1, client.getWindow().getWidth());
        double mouseY = client.mouse.getY() * sh / Math.max(1, client.getWindow().getHeight());
        if (mouseX < boxX || mouseX >= boxX + size || mouseY < boxY || mouseY >= boxY + size) {
            return false;
        }

        client.setScreen(new com.explorermap.gui.FullMapScreen());
        return true;
    }

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) return;

        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (!cfg.showHud) return;
        if (cfg.hideWhenSneaking && player.isSneaking()) return;
        if (cfg.hideInMenus && client.currentScreen != null) return;

        ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        int rawMapId = MapIdentity.rawIdOf(offHand);
        if (rawMapId < 0) return;

        MapState mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState == null) return;

        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapIdentity mapId = MapIdentity.of(mapState, rawMapId);
        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapId);

        int size    = cfg.mapSize;
        int padding = cfg.padding;
        int sw      = context.getScaledWindowWidth();
        int sh      = context.getScaledWindowHeight();

        int boxX, boxY;
        switch (cfg.corner) {
            case TOP_LEFT    -> { boxX = padding;             boxY = padding; }
            case TOP_RIGHT   -> { boxX = sw - size - padding; boxY = padding; }
            case BOTTOM_LEFT -> { boxX = padding;             boxY = sh - size - padding; }
            default          -> { boxX = sw - size - padding; boxY = sh - size - padding; }
        }

        int bgAlpha = ((int) (cfg.opacity() * 0.55f * 255) << 24);
        context.fill(boxX - 2, boxY - 2, boxX + size + 2, boxY + size + 2, bgAlpha);

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.world);

        TileRenderer.renderHud(context, grid, mapState,
                boxX, boxY, size,
                player.getX(), player.getZ(), player.getYaw());

        renderWaypoints(context, grid, mapState, boxX, boxY, size);

        if (cfg.showCompass) {
            renderCompass(context, boxX + size - 18, boxY + 2);
        }

        if (cfg.showCoordinates) {
            renderCoordinates(context, player, boxX, boxY, size, cfg);
        }

        renderDimensionLabel(context, player, boxX, boxY, size);

        if (cfg.showExpansionArrows) {
            renderExpansionArrows(context, mapEntry, boxX, boxY, size);
        }

        context.drawBorder(boxX - 2, boxY - 2, size + 4, size + 4,
                ((int) (cfg.opacity() * 180) << 24) | 0x888888);
    }

    private static void renderWaypoints(DrawContext ctx, TileGrid grid, MapState rootState,
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

    private static void renderCompass(DrawContext ctx, int x, int y) {
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "N", x + 3, y, 0xFFFF5555, true);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x,      y + 10, 0xFF888888, false);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x + 10, y + 10, 0xFF888888, false);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x + 4,  y + 18, 0xFF888888, false);
    }

    private static void renderDimensionLabel(DrawContext ctx, ClientPlayerEntity player,
                                              int boxX, int boxY, int size) {
        if (DimensionMapTracker.isOverworld(player)) return;
        String label = DimensionMapTracker.dimensionLabel(player);
        var tr = MinecraftClient.getInstance().textRenderer;
        int lw = tr.getWidth(label);
        ctx.drawText(tr, label, boxX + size / 2 - lw / 2, boxY + size + 2, 0xFFAAAAAA, true);
    }

    private static void renderCoordinates(DrawContext ctx, ClientPlayerEntity player,
                                          int boxX, int boxY, int size, ExplorerMapConfig cfg) {
        var tr = MinecraftClient.getInstance().textRenderer;
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
            ctx.drawText(tr, coordinates[i], textX, textY + i * 10, 0xFFD0D0D0, true);
        }
    }

    private static void renderExpansionArrows(DrawContext ctx, MapEntryData mapEntry,
                                               int bx, int by, int size) {
        var exps = mapEntry.getExpansions();
        int cx = bx + size / 2;
        int cy = by + size / 2;
        var tr = MinecraftClient.getInstance().textRenderer;

        boolean hasN = hasDir(exps, ExpansionRecord.Direction.NORTH);
        boolean hasS = hasDir(exps, ExpansionRecord.Direction.SOUTH);
        boolean hasW = hasDir(exps, ExpansionRecord.Direction.WEST);
        boolean hasE = hasDir(exps, ExpansionRecord.Direction.EAST);

        int available = 0xCCFFFF88;

        if (!hasN) ctx.drawText(tr, "▲", cx - 3, by - 10, available, true);
        if (!hasS) ctx.drawText(tr, "▼", cx - 3, by + size + 2, available, true);
        if (!hasW) ctx.drawText(tr, "◄", bx - 10, cy - 4, available, true);
        if (!hasE) ctx.drawText(tr, "►", bx + size + 2, cy - 4, available, true);
    }

    private static boolean hasDir(java.util.List<ExpansionRecord> list, ExpansionRecord.Direction dir) {
        return list.stream().anyMatch(r -> r.direction() == dir);
    }
}
