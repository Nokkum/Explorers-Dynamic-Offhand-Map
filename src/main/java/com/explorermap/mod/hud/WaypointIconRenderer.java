package com.explorermap.mod.hud;

import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Draws waypoint icons on the mini-map HUD and full-map screen.
 *
 * Each icon is a 16×16 RGBA PNG stored at:
 *   assets/explorermap/textures/waypoints/<name>.png
 *
 * Rendering strategy
 * ──────────────────
 * DrawContext.drawTexture() renders a region from an atlas texture. Since our
 * icons are individual files, we register each as a standalone texture and
 * draw the full 16×16 region (u=0, v=0, regionW=16, regionH=16).
 *
 * At small HUD sizes the icon is scaled down to iconSize×iconSize screen pixels.
 * At full-map zoom it is scaled up, capped at 16px to keep crisp pixel art.
 *
 * Colour tinting: DrawContext.drawTexture respects the current render colour.
 * We set the waypoint's stored tint color before drawing so users can recolour
 * icons (e.g. a red pin vs a blue pin) without needing separate textures.
 *
 * Fallback: if the texture isn't loaded yet (first frame), draw a coloured dot.
 */
@Environment(EnvType.CLIENT)
public final class WaypointIconRenderer {

    /** Icon display size in the HUD (screen pixels). */
    public static final int HUD_ICON_SIZE  = 7;

    /** Icon display size in the full-map screen (screen pixels, pre-zoom). */
    public static final int MAP_ICON_SIZE  = 10;

    /** Icon display size in the WaypointEditScreen picker. */
    public static final int PICK_ICON_SIZE = 16;

    private WaypointIconRenderer() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Draws a waypoint icon centred at (cx, cy) at the given display size.
     * Falls back to a tinted dot if the texture is unavailable.
     *
     * @param ctx       DrawContext.
     * @param waypoint  Waypoint whose iconId and color to use.
     * @param cx        Screen X centre.
     * @param cy        Screen Y centre.
     * @param size      Rendered size in screen pixels.
     */
    public static void draw(DrawContext ctx, Waypoint waypoint, int cx, int cy, int size) {
        Identifier texture = ExplorerMapRegistry.getWaypointTexture(waypoint.iconId());
        draw(ctx, texture, waypoint.color(), cx, cy, size);
    }

    /**
     * Draws an icon by texture + tint directly (used by WaypointEditScreen picker).
     */
    public static void draw(DrawContext ctx, Identifier texture, int tintARGB,
                             int cx, int cy, int size) {
        int x = cx - size / 2;
        int y = cy - size / 2;

        // Outline/shadow dot behind the icon
        ctx.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x88000000);

        // Draw texture with colour tint.
        // drawTexture signature (1.21): drawTexture(id, x, y, u, v, width, height,
        //                                           textureWidth, textureHeight)
        // We draw the full 16×16 source scaled to (size×size) on screen.
        ctx.drawTexture(texture, x, y, size, size, 0, 0, 16, 16, 16, 16);

        // Apply tint as a colour-multiply overlay (semi-transparent if white)
        if (tintARGB != Waypoint.DEFAULT_COLOR) {
            // Blend tint at ~50% over the icon for a recolour effect
            int tintOverlay = (tintARGB & 0x00FFFFFF) | 0x80000000;
            ctx.fill(x, y, x + size, y + size, tintOverlay);
        }
    }

    // ── Map-overlay helpers ───────────────────────────────────────────────

    /**
     * Draws a waypoint label + icon centred at (sx, sy) in map/HUD screen space.
     * Used by TileRenderer and FullMapScreen.
     *
     * @param ctx        DrawContext.
     * @param waypoint   The waypoint.
     * @param sx         Screen X of waypoint world position.
     * @param sy         Screen Y of waypoint world position.
     * @param iconSize   Icon size in screen pixels.
     * @param showLabel  Whether to draw the name label.
     */
    public static void drawOnMap(DrawContext ctx, Waypoint waypoint,
                                  int sx, int sy, int iconSize, boolean showLabel) {
        draw(ctx, waypoint, sx, sy, iconSize);

        if (showLabel) {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.textRenderer != null) {
                int labelX = sx + iconSize / 2 + 2;
                int labelY = sy - 4;
                // Shadow
                ctx.drawText(client.textRenderer, waypoint.name(),
                        labelX + 1, labelY + 1, 0x88000000, false);
                // Label
                ctx.drawText(client.textRenderer, waypoint.name(),
                        labelX, labelY, waypoint.color(), false);
            }
        }
    }

    // ── Picker row (WaypointEditScreen) ───────────────────────────────────

    /**
     * Draws a single icon in the picker grid at (x, y) top-left.
     * Highlights the selected icon with a white border.
     */
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
