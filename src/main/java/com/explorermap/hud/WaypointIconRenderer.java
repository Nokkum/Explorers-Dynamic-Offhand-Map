package com.explorermap.hud;

import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

@Environment(EnvType.CLIENT)
public final class WaypointIconRenderer {

    public static final int HUD_ICON_SIZE  = 7;

    public static final int MAP_ICON_SIZE  = 10;

    public static final int PICK_ICON_SIZE = 16;

    private WaypointIconRenderer() {}

    public static void draw(GuiGraphicsExtractor ctx, Waypoint waypoint, int cx, int cy, int size) {
        Identifier texture = ExplorerMapRegistry.getWaypointTexture(waypoint.iconId());
        draw(ctx, texture, waypoint.color(), cx, cy, size);
    }

    public static void draw(GuiGraphicsExtractor ctx, Identifier texture, int tintARGB,
                             int cx, int cy, int size) {
        int x = cx - size / 2;
        int y = cy - size / 2;

        ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x88000000);

        // RenderSystem.setShaderColor() no longer exists; the tint is now an argument to blit().
        int tint = 0xFF000000 | (tintARGB & 0x00FFFFFF);

        var pose = ctx.pose();
        pose.pushMatrix();
        if (size != 16) {
            float sc = size / 16f;
            pose.translate((float) x, (float) y);
            pose.scale(sc, sc);
            ctx.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f, 16, 16, 16, 16, 16, 16, tint);
        } else {
            ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, 16, 16, 16, 16, 16, 16, tint);
        }
        pose.popMatrix();
    }

    public static void drawOnMap(GuiGraphicsExtractor ctx, Waypoint waypoint,
                                  int sx, int sy, int iconSize, boolean showLabel) {
        draw(ctx, waypoint, sx, sy, iconSize);

        if (showLabel) {
            var client = Minecraft.getInstance();
            if (client.font != null) {
                int labelX = sx + iconSize / 2 + 2;
                int labelY = sy - 4;

                ctx.text(client.font, waypoint.name(),
                        labelX + 1, labelY + 1, 0x88000000, false);

                // Alpha is now mandatory when drawing text (an alpha of 0 draws nothing).
                ctx.text(client.font, waypoint.name(),
                        labelX, labelY, 0xFF000000 | waypoint.color(), false);
            }
        }
    }

    public static void drawPickerCell(GuiGraphicsExtractor ctx, Identifier texture,
                                       int tint, int x, int y,
                                       int cellSize, boolean selected) {
        if (selected) {
            ctx.fill(x - 2, y - 2, x + cellSize + 2, y + cellSize + 2, 0xFFFFFFFF);
            ctx.fill(x - 1, y - 1, x + cellSize + 1, y + cellSize + 1, 0xFF222222);
        }
        draw(ctx, texture, tint, x + cellSize / 2, y + cellSize / 2, cellSize);
    }
}
