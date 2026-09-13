package com.explorermap.mod.hud;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.dimension.DimensionMapTracker;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.expansion.TileGrid;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.world.storage.MapState;

/**
 * Mini-map HUD overlay.
 *
 * Renders the root tile plus all stitched expansion tiles via TileGrid + TileRenderer.
 * The HUD size always shows the root tile at cfg.mapSize pixels; expansion tiles
 * extend beyond that boundary (clipped by scissor to mapSize × mapSize for a clean edge).
 * When expansions are present, the root tile is centred so neighbouring tiles are visible.
 */
@Environment(EnvType.CLIENT)
public class MinimapHud {

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        ExplorerMapConfig cfg = ExplorerMapConfig.get();
        if (!cfg.showHud) return;
        if (cfg.hideWhenSneaking && player.isSneaking()) return;
        if (cfg.hideInMenus && client.currentScreen != null) return;

        ItemStack offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        MapState mapState = FilledMapItem.getMapState(offHand, client.world);
        if (mapState == null) return;

        // ── Dimension gate ────────────────────────────────────────────────
        // Suppress HUD if the map is from a different dimension.
        if (!DimensionMapTracker.isMapRelevantForCurrentDimension(player, mapState)) return;

        MapDiscoveryAttachment attachment = ExplorerMapMod.getOrCreate(mapState);

        // ── Layout ────────────────────────────────────────────────────────
        int size    = cfg.mapSize;
        int padding = cfg.padding;
        int sw      = context.getScaledWindowWidth();
        int sh      = context.getScaledWindowHeight();

        // Outer HUD box top-left
        int boxX, boxY;
        switch (cfg.corner) {
            case TOP_LEFT    -> { boxX = padding;           boxY = padding; }
            case TOP_RIGHT   -> { boxX = sw - size - padding; boxY = padding; }
            case BOTTOM_LEFT -> { boxX = padding;           boxY = sh - size - padding; }
            default          -> { boxX = sw - size - padding; boxY = sh - size - padding; }
        }

        // ── Background ────────────────────────────────────────────────────
        int bgAlpha = ((int)(cfg.opacity() * 0.55f * 255) << 24);
        context.fill(boxX - 2, boxY - 2, boxX + size + 2, boxY + size + 2, bgAlpha);

        // ── Build tile grid ────────────────────────────────────────────────
        TileGrid grid = TileGrid.build(mapState, attachment, client.world);

        // ── Render tiles ──────────────────────────────────────────────────
        TileRenderer.renderHud(context, grid, mapState,
                boxX, boxY, size,
                player.getX(), player.getZ(), player.getYaw());

        // ── Compass ───────────────────────────────────────────────────────
        if (cfg.showCompass) {
            renderCompass(context, boxX + size - 18, boxY + 2);
        }

        // ── Dimension label (Nether / The End) ────────────────────────────
        renderDimensionLabel(context, player, boxX, boxY, size);

        // ── Expansion arrows (directions not yet unlocked) ────────────────
        if (cfg.showExpansionArrows) {
            renderExpansionArrows(context, attachment, boxX, boxY, size);
        }

        // ── Border ────────────────────────────────────────────────────────
        context.drawBorder(boxX - 2, boxY - 2, size + 4, size + 4,
                ((int)(cfg.opacity() * 180) << 24) | 0x888888);
    }

    // ── Compass ───────────────────────────────────────────────────────────

    private static void renderCompass(DrawContext ctx, int x, int y) {
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "N", x + 3, y, 0xFFFF5555, true);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x,      y + 10, 0xFF888888, false);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x + 10, y + 10, 0xFF888888, false);
        ctx.drawText(MinecraftClient.getInstance().textRenderer, "·", x + 4,  y + 18, 0xFF888888, false);
    }

    private static void renderDimensionLabel(DrawContext ctx, ClientPlayerEntity player,
                                              int boxX, int boxY, int size) {
        String label = DimensionMapTracker.dimensionLabel(player);
        // Only show non-Overworld labels to avoid visual clutter
        if (label.equals("Overworld")) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        int lw = tr.getWidth(label);
        ctx.drawText(tr, label, boxX + size / 2 - lw / 2, boxY + size + 2, 0xFFAAAAAA, true);
    }

    // ── Expansion arrows ──────────────────────────────────────────────────

    private static void renderExpansionArrows(DrawContext ctx,
                                               MapDiscoveryAttachment attachment,
                                               int bx, int by, int size) {
        var exps = attachment.getExpansions();
        int cx   = bx + size / 2;
        int cy   = by + size / 2;
        var tr   = MinecraftClient.getInstance().textRenderer;

        // Show arrow for directions NOT yet expanded (i.e., available to unlock)
        boolean hasN = hasDir(exps, ExpansionRecord.Direction.NORTH);
        boolean hasS = hasDir(exps, ExpansionRecord.Direction.SOUTH);
        boolean hasW = hasDir(exps, ExpansionRecord.Direction.WEST);
        boolean hasE = hasDir(exps, ExpansionRecord.Direction.EAST);

        int available = 0xCCFFFF88;  // yellow — can expand here
        int locked    = 0x44888888;  // dim gray — already expanded / border

        if (!hasN) ctx.drawText(tr, "▲", cx - 3, by - 10,  available, true);
        if (!hasS) ctx.drawText(tr, "▼", cx - 3, by + size + 2, available, true);
        if (!hasW) ctx.drawText(tr, "◄", bx - 10, cy - 4,  available, true);
        if (!hasE) ctx.drawText(tr, "►", bx + size + 2, cy - 4, available, true);
    }

    private static boolean hasDir(java.util.List<ExpansionRecord> list,
                                   ExpansionRecord.Direction dir) {
        return list.stream().anyMatch(r -> r.direction() == dir);
    }
}
