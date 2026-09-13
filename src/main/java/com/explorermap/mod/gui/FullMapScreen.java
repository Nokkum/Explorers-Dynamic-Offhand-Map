package com.explorermap.mod.gui;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.expansion.ExpansionHandler;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.expansion.TileGrid;
import com.explorermap.mod.hud.TileRenderer;
import com.explorermap.mod.hud.WaypointIconRenderer;
import com.explorermap.mod.network.RequestExpansionPayload;
import com.explorermap.mod.waypoint.Waypoint;
import com.explorermap.mod.expansion.MultiTileCanvas;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.world.storage.MapState;

/**
 * Full-scale map GUI.
 *
 * Features:
 *   - Discovered-pixel-only rendering (fog on unseen areas)
 *   - Mouse scroll zoom (0.5×–4×)
 *   - Left-click drag pan
 *   - Expansion buttons → real C2S packet
 *   - + Waypoint → WaypointEditScreen
 *   - Compass rose
 *   - Discovery progress bar
 *   - Player arrow
 */
@Environment(EnvType.CLIENT)
public class FullMapScreen extends Screen {

    private static final int BASE_CANVAS = 512;

    private MapState mapState;
    private MapDiscoveryAttachment attachment;

    private float zoom = 1.0f;
    private float panX = 0f, panY = 0f;
    private boolean dragging = false;
    private double dragStartX, dragStartY;
    private float panStartX, panStartY;
    private boolean hdMode = false;

    public FullMapScreen() {
        super(Text.translatable("screen.explorermap.full_map"));
    }

    // ── Layout ────────────────────────────────────────────────────────────

    private int canvasSize() { return Math.min(BASE_CANVAS, Math.min(this.width, this.height) - 80); }
    private int canvasX()    { return (this.width  - canvasSize()) / 2; }
    private int canvasY()    { return (this.height - canvasSize()) / 2; }

    // ── Init ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        resolveMap();

        int cx      = canvasX();
        int toolY   = canvasY() - 24;
        int size    = canvasSize();

        // Expansion buttons — disabled if already expanded or lacking resources
        boolean hasResources = client.player != null && ExpansionHandler.canExpand(client.player, hdMode);
        for (ExpansionRecord.Direction dir : ExpansionRecord.Direction.values()) {
            boolean done   = client.player != null && ExpansionHandler.alreadyExpanded(client.player, dir);
            boolean active = hasResources && !done;
            String label = switch (dir) {
                case NORTH -> done ? "✓ N" : "▲ N";
                case SOUTH -> done ? "✓ S" : "▼ S";
                case WEST  -> done ? "✓ W" : "◄ W";
                case EAST  -> done ? "✓ E" : "► E";
            };
            int offset = switch (dir) {
                case NORTH -> 0; case SOUTH -> 50; case WEST -> 100; case EAST -> 150;
            };
            var btn = ButtonWidget.builder(Text.literal(label),
                    b -> { if (!done) expand(dir, hdMode); })
                    .dimensions(cx + offset, toolY, 46, 20).build();
            btn.active = active || done; // done buttons are visible but inert
            addDrawableChild(btn);
        }

        // HD toggle: cycles between Standard and HD expansion mode
        // HD costs Paper + Ink Sac + Compass but enables waypoints in that tile
        addDrawableChild(ButtonWidget.builder(
                Text.literal(hdMode ? "[HD]" : " HD "),
                btn -> {
                    hdMode = !hdMode;
                    // Rebuild buttons so label updates
                    clearChildren();
                    init();
                }
        ).dimensions(cx + 200, toolY, 32, 20).build());

        // Zoom
        addDrawableChild(ButtonWidget.builder(Text.literal("+"),   btn -> adjustZoom(+0.25f)).dimensions(cx + 238, toolY, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("−"),   btn -> adjustZoom(-0.25f)).dimensions(cx + 260, toolY, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("1:1"), btn -> { zoom = 1f; panX = 0; panY = 0; }).dimensions(cx + 282, toolY, 30, 20).build());

        // Waypoint
        addDrawableChild(ButtonWidget.builder(Text.literal("+ Waypoint"),
                btn -> client.setScreen(new WaypointEditScreen(this))).dimensions(cx + 318, toolY, 90, 20).build());

        // Close
        addDrawableChild(ButtonWidget.builder(Text.literal("✕"),
                btn -> this.close()).dimensions(cx + size - 20, toolY, 20, 20).build());
    }

    // ── Render ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        resolveMap();

        int cx   = canvasX();
        int cy   = canvasY();
        int size = canvasSize();

        // Canvas border
        context.fill(cx - 2, cy - 2, cx + size + 2, cy + size + 2, 0xFF444444);

        if (mapState == null || attachment == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("No filled map in off-hand"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        context.enableScissor(cx, cy, cx + size, cy + size);

        if (client.player != null) {
            // Build tile grid and render via TileRenderer
            TileGrid grid = TileGrid.build(mapState, attachment, client.world);
            TileRenderer.renderFullMap(context, grid, mapState,
                    cx + size / 2, cy + size / 2,
                    zoom, panX, panY,
                    cx, cy, cx + size, cy + size,
                    client.player.getX(), client.player.getZ(), client.player.getYaw());

            // Waypoints rendered on top using MultiTileCanvas for coords
            MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);
            int originX = canvas.defaultScreenOriginX(cx + size / 2, zoom, panX);
            int originY = canvas.defaultScreenOriginY(cy + size / 2, zoom, panY);
            renderWaypoints(context, canvas, originX, originY, zoom);
        }

        context.disableScissor();

        renderCompass(context, cx + size - 26, cy + 6);
        renderProgressBar(context, cx, cy, size);

        // HD mode status line just below the toolbar
        if (hdMode) {
            String hdLabel = "HD mode: Paper + Ink Sac + Compass per expansion";
            int labelW = this.textRenderer.getWidth(hdLabel);
            context.drawTextWithShadow(this.textRenderer,
                    hdLabel, cx + size / 2 - labelW / 2, cy - 10, 0xFFFFCC44);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    // ── Map pixels ────────────────────────────────────────────────────────

    // ── Waypoints ─────────────────────────────────────────────────────────

    private void renderWaypoints(DrawContext ctx, MultiTileCanvas canvas,
                                  int originX, int originY, float zoom) {
        if (attachment == null) return;
        // Scale icon size with zoom, clamped to [6, 16]
        int iconSize = Math.min(16, Math.max(6, (int)(WaypointIconRenderer.MAP_ICON_SIZE * zoom)));
        boolean showLabels = zoom >= 0.75f; // hide labels when very zoomed out
        for (Waypoint wp : attachment.getWaypoints()) {
            int cpx = canvas.worldToCanvasX(wp.worldX());
            int cpz = canvas.worldToCanvasZ(wp.worldZ());
            int sx  = originX + Math.round(cpx * zoom);
            int sy  = originY + Math.round(cpz * zoom);
            WaypointIconRenderer.drawOnMap(ctx, wp, sx, sy, iconSize, showLabels);
        }
    }

    // ── Compass rose ──────────────────────────────────────────────────────

    private void renderCompass(DrawContext ctx, int x, int y) {
        ctx.drawTextWithShadow(this.textRenderer, "N", x + 5, y,      0xFFFF5555);
        ctx.drawTextWithShadow(this.textRenderer, "S", x + 5, y + 20, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "W", x,     y + 10, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "E", x + 12,y + 10, 0xFFAAAAAA);
        // Centre dot
        ctx.fill(x + 7, y + 11, x + 9, y + 13, 0xFF888888);
    }

    // ── Progress bar ──────────────────────────────────────────────────────

    private void renderProgressBar(DrawContext ctx, int cx, int cy, int size) {
        float frac = attachment.discoveryFraction();
        ctx.fill(cx,                      cy + size + 4, cx + size,                  cy + size + 10, 0xFF222222);
        ctx.fill(cx,                      cy + size + 4, cx + (int)(size * frac),    cy + size + 10, 0xFF44AA44);
        ctx.drawTextWithShadow(this.textRenderer,
                String.format("Explored: %.1f%%", frac * 100f), cx + 2, cy + size + 13, 0xFF888888);

        if (client.player != null) {
            String pos = String.format("X %.0f  Z %.0f", client.player.getX(), client.player.getZ());
            ctx.drawTextWithShadow(this.textRenderer, pos,
                    cx + size - this.textRenderer.getWidth(pos) - 2, cy + size + 13, 0xFF888888);
        }
    }

    // ── Mouse ─────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        adjustZoom((float) vAmt * 0.15f);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) { dragging = true; dragStartX = mx; dragStartY = my; panStartX = panX; panStartY = panY; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) { panX = panStartX + (float)(mx - dragStartX); panY = panStartY + (float)(my - dragStartY); }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void adjustZoom(float delta) { zoom = Math.clamp(zoom + delta, 0.5f, 4.0f); }

    private void expand(ExpansionRecord.Direction dir, boolean highDetail) {
        ClientPlayNetworking.send(new RequestExpansionPayload(dir, highDetail));
    }

    private void resolveMap() {
        if (client == null || client.player == null || client.world == null) { mapState = null; attachment = null; return; }
        ItemStack off = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(off)) { mapState = null; attachment = null; return; }
        mapState   = FilledMapItem.getMapState(off, client.world);
        attachment = mapState != null ? ExplorerMapMod.getOrCreate(mapState) : null;
    }

    @Override public boolean shouldPause() { return false; }
}
