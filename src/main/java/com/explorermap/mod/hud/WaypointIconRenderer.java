package com.explorermap.mod.hud;

import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.systems.RenderSystem;

@Environment(EnvType.CLIENT)
public final class WaypointIconRenderer {

    public static final int HUD_ICON_SIZE  = 7;

    public static final int MAP_ICON_SIZE  = 10;

    public static final int PICK_ICON_SIZE = 16;

    private WaypointIconRenderer() {}

    public static void draw(DrawContext ctx, Waypoint waypoint, int cx, int cy, int size) {
        Identifier texture = ExplorerMapRegistry.getWaypointTexture(waypoint.iconId());
        draw(ctx, texture, waypoint.color(), cx, cy, size);
    }

    public static void draw(DrawContext ctx, Identifier texture, int tintARGB,
                             int cx, int cy, int size) {
        int x = cx - size / 2;
        int y = cy - size / 2;

        ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x88000000);

        float r = ((tintARGB >> 16) & 0xFF) / 255f;
        float g = ((tintARGB >>  8) & 0xFF) / 255f;
        float b = ( tintARGB        & 0xFF) / 255f;
        RenderSystem.setShaderColor(r, g, b, 1f);

        var matrices = ctx.getMatrices();
        matrices.push();
        if (size != 16) {
            float sc = size / 16f;
            matrices.translate((float) x, (float) y, 0f);
            matrices.scale(sc, sc, 1f);
            ctx.drawTexture(texture, 0, 0, 0, 0, 16, 16, 16, 16);
        } else {
            ctx.drawTexture(texture, x, y, 0, 0, 16, 16, 16, 16);
        }
        matrices.pop();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    public static void drawOnMap(DrawContext ctx, Waypoint waypoint,
                                  int sx, int sy, int iconSize, boolean showLabel) {
        draw(ctx, waypoint, sx, sy, iconSize);

        if (showLabel) {
            var client = MinecraftClient.getInstance();
            if (client.textRenderer != null) {
                int labelX = sx + iconSize / 2 + 2;
                int labelY = sy - 4;

                ctx.drawText(client.textRenderer, waypoint.name(),
                        labelX + 1, labelY + 1, 0x88000000, false);

                ctx.drawText(client.textRenderer, waypoint.name(),
                        labelX, labelY, waypoint.color(), false);
            }
        }
    }

    public static void drawPickerCell(DrawContext ctx, Identifier texture,
                                       int tint, int x, int y,
                                       int cellSize, boolean selected) {
        if (selected) {
            ctx.fill(x - 2, y - 2, x + cellSize + 2, y + cellSize + 2, 0xFFFFFFFF);
            ctx.fill(x - 1, y - 1, x + cellSize + 1, y + cellSize + 1, 0xFF222222);
        }
        draw(ctx, texture, tint, x + cellSize / 2, y + cellSize / 2, cellSize);
    }
}
