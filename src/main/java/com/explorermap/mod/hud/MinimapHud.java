package com.explorermap.mod.hud;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapEntryData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.dimension.DimensionMapTracker;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.expansion.TileGrid;
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

        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) return;

        MapState mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState == null) return;

        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapEntryData mapEntry = ClientMapCache.getOrCreate(mapState, mapId);

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

        if (cfg.showCompass) {
            renderCompass(context, boxX + size - 18, boxY + 2);
        }

        renderDimensionLabel(context, player, boxX, boxY, size);

        if (cfg.showExpansionArrows) {
            renderExpansionArrows(context, mapEntry, boxX, boxY, size);
        }

        context.drawBorder(boxX - 2, boxY - 2, size + 4, size + 4,
                ((int) (cfg.opacity() * 180) << 24) | 0x888888);
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
