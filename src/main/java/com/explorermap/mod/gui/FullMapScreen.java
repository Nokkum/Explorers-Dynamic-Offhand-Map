package com.explorermap.mod.gui;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapEntryData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.expansion.ExpansionHandler;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.expansion.MultiTileCanvas;
import com.explorermap.mod.expansion.TileGrid;
import com.explorermap.mod.hud.TileRenderer;
import com.explorermap.mod.hud.WaypointIconRenderer;
import com.explorermap.mod.network.DeleteWaypointPayload;
import com.explorermap.mod.network.RequestExpansionPayload;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

@Environment(EnvType.CLIENT)
public class FullMapScreen extends Screen {

    private static final int BASE_CANVAS = 512;

    private int mapId = -1;
    private MapState mapState;
    private MapEntryData mapEntry;

    float zoom = 1.0f;
    float panX = 0f, panY = 0f;
    private boolean dragging = false;
    private double dragStartX, dragStartY;
    private double clickStartX, clickStartY;
    private float panStartX, panStartY;
    boolean hdMode = false;
    private String coordinatePopup;
    private long coordinatePopupUntil;

    private Waypoint contextMenuWaypoint;
    private int contextMenuMapId = -1;
    private int contextMenuX, contextMenuY;

    public FullMapScreen() {
        super(Text.translatable("screen.explorermap.full_map"));
    }

    private int canvasSize() { return Math.max(120, Math.min(BASE_CANVAS, Math.min(this.width, this.height) - 80)); }
    private int canvasX()    { return (this.width  - canvasSize()) / 2; }
    private int canvasY()    { return (this.height - canvasSize()) / 2; }

    @Override
    protected void init() {
        super.init();
        resolveMap();

        int cx    = canvasX();
        int toolY = canvasY() - 24;
        int size  = canvasSize();

        boolean hasResources = client.player != null && ExpansionHandler.canExpand(client.player, hdMode);
        for (ExpansionRecord.Direction dir : ExpansionRecord.Direction.values()) {
            boolean done   = client.player != null && ExpansionHandler.alreadyExpanded(client.player, dir);
            boolean active = hasResources && !done;
            String label = switch (dir) {
                case NORTH -> done ? "OK N" : "^ N";
                case SOUTH -> done ? "OK S" : "v S";
                case WEST  -> done ? "OK W" : "< W";
                case EAST  -> done ? "OK E" : "> E";
            };
            int offset = switch (dir) {
                case NORTH -> 0; case SOUTH -> 50; case WEST -> 100; case EAST -> 150;
            };
            var btn = ButtonWidget.builder(Text.literal(label),
                    b -> { if (!done) expand(dir, hdMode); })
                    .dimensions(cx + offset, toolY, 46, 20).build();
            btn.active = active || done;
            addDrawableChild(btn);
        }

        addDrawableChild(ButtonWidget.builder(
                Text.literal(hdMode ? "[HD]" : " HD "),
                btn -> {
                    FullMapScreen next = new FullMapScreen();
                    next.hdMode = !this.hdMode;
                    next.zoom   = this.zoom;
                    next.panX   = this.panX;
                    next.panY   = this.panY;
                    if (client != null) client.setScreen(next);
                }
        ).dimensions(cx + 200, toolY, 32, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"),   btn -> adjustZoom(+0.25f)).dimensions(cx + 238, toolY, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("-"),   btn -> adjustZoom(-0.25f)).dimensions(cx + 260, toolY, 20, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("1:1"), btn -> { zoom = 1f; panX = 0; panY = 0; }).dimensions(cx + 282, toolY, 30, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+ Waypoint"),
                btn -> client.setScreen(new WaypointEditScreen(this))).dimensions(cx + 318, toolY, 90, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("X"),
                btn -> this.close()).dimensions(cx + size - 20, toolY, 20, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        resolveMap();

        int cx   = canvasX();
        int cy   = canvasY();
        int size = canvasSize();

        context.fill(cx - 2, cy - 2, cx + size + 2, cy + size + 2, 0xFF444444);

        if (mapState == null || mapEntry == null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.translatable("explorermap.hud.no_map"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        context.enableScissor(cx, cy, cx + size, cy + size);

        if (client.player != null) {
            TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.world);
            TileRenderer.renderFullMap(context, grid, mapState,
                    cx + size / 2, cy + size / 2,
                    zoom, panX, panY,
                    cx, cy, cx + size, cy + size,
                    client.player.getX(), client.player.getZ(), client.player.getYaw());

            MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);
            int originX = canvas.defaultScreenOriginX(cx + size / 2, zoom, panX);
            int originY = canvas.defaultScreenOriginY(cy + size / 2, zoom, panY);
            renderWaypoints(context, grid, canvas, originX, originY, zoom);
        }

        context.disableScissor();

        renderCompass(context, cx + size - 26, cy + 6);
        renderProgressBar(context, cx, cy, size);
        renderCoordinatePopup(context);

        if (hdMode) {
            String hdLabel = Text.translatable("explorermap.expansion.hd_cost").getString();
            int labelW = this.textRenderer.getWidth(hdLabel);
            context.drawTextWithShadow(this.textRenderer,
                    hdLabel, cx + size / 2 - labelW / 2, cy - 10, 0xFFFFCC44);
        }

        var failedDir = ExpansionFeedback.getActiveFailureDirection();
        if (failedDir != null) {
            String msg = ExpansionFeedback.getActiveFailureMessage();
            if (msg != null) {
                int msgW = this.textRenderer.getWidth(msg);
                context.drawTextWithShadow(this.textRenderer, msg,
                        cx + size / 2 - msgW / 2, cy - 22, 0xFFFF5555);
            }
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderWaypoints(DrawContext ctx, TileGrid grid, MultiTileCanvas canvas,
                                  int originX, int originY, float zoom) {
        int iconSize = Math.min(16, Math.max(6, (int) (WaypointIconRenderer.MAP_ICON_SIZE * zoom)));
        boolean showLabels = zoom >= 0.75f;
        for (TileGrid.TileEntry tile : grid.tiles()) {
            for (Waypoint wp : tile.entry().getWaypoints()) {
                if (!grid.isWorldPositionDiscovered(canvas, wp.worldX(), wp.worldZ())) continue;
                int cpx = canvas.worldToCanvasX(wp.worldX());
                int cpz = canvas.worldToCanvasZ(wp.worldZ());
                int sx  = originX + Math.round(cpx * zoom);
                int sy  = originY + Math.round(cpz * zoom);
                WaypointIconRenderer.drawOnMap(ctx, wp, sx, sy, iconSize, showLabels);
            }
        }
    }

    private void renderCompass(DrawContext ctx, int x, int y) {
        ctx.drawTextWithShadow(this.textRenderer, "N", x + 5, y,      0xFFFF5555);
        ctx.drawTextWithShadow(this.textRenderer, "S", x + 5, y + 20, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "W", x,     y + 10, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, "E", x + 12,y + 10, 0xFFAAAAAA);
        ctx.fill(x + 7, y + 11, x + 9, y + 13, 0xFF888888);
    }

    private void renderProgressBar(DrawContext ctx, int cx, int cy, int size) {
        float frac = mapEntry.discoveryFraction();
        ctx.fill(cx, cy + size + 4, cx + size,               cy + size + 10, 0xFF222222);
        ctx.fill(cx, cy + size + 4, cx + (int) (size * frac), cy + size + 10, 0xFF44AA44);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("explorermap.hud.explored", String.format("%.1f", frac * 100f)),
                cx + 2, cy + size + 13, 0xFF888888);

        if (client.player != null) {
            String pos = String.format("X %.0f  Y %.0f  Z %.0f",
                    client.player.getX(), client.player.getY(), client.player.getZ());
            ctx.drawTextWithShadow(this.textRenderer, pos,
                    cx + size - this.textRenderer.getWidth(pos) - 2, cy + size + 13, 0xFF888888);
        }
    }

    private void renderCoordinatePopup(DrawContext ctx) {
        if (coordinatePopup == null || System.currentTimeMillis() >= coordinatePopupUntil) return;
        int width = this.textRenderer.getWidth(coordinatePopup) + 16;
        int x = (this.width - width) / 2;
        int y = canvasY() + 8;
        ctx.fill(x, y, x + width, y + 22, 0xDD111111);
        ctx.drawBorder(x, y, width, 22, 0xFFAAAAAA);
        ctx.drawCenteredTextWithShadow(this.textRenderer, coordinatePopup,
                x + width / 2, y + 7, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        adjustZoom((float) vAmt * 0.15f);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 1) {
            WaypointHit hit = findWaypointNear(mx, my);
            if (hit != null) {
                contextMenuWaypoint = hit.waypoint();
                contextMenuMapId = hit.mapId();
                contextMenuX = (int) mx;
                contextMenuY = (int) my;
                rebuildContextMenuButtons();
                return true;
            } else {
                closeContextMenu();
            }
        }
        if (button == 0) {
            closeContextMenu();
            dragging = true;
            dragStartX = clickStartX = mx;
            dragStartY = clickStartY = my;
            panStartX = panX;
            panStartY = panY;
        }
        return super.mouseClicked(mx, my, button);
    }

    private record WaypointHit(Waypoint waypoint, int mapId) {}

    private WaypointHit findWaypointNear(double mx, double my) {
        if (mapEntry == null || mapState == null || client == null || client.world == null) return null;

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.world);
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);
        int cx = canvasX(), cy = canvasY(), size = canvasSize();
        int originX = canvas.defaultScreenOriginX(cx + size / 2, zoom, panX);
        int originY = canvas.defaultScreenOriginY(cy + size / 2, zoom, panY);

        final int HIT_RADIUS = 8;
        for (TileGrid.TileEntry tile : grid.tiles()) {
            for (Waypoint wp : tile.entry().getWaypoints()) {
                if (!grid.isWorldPositionDiscovered(canvas, wp.worldX(), wp.worldZ())) continue;
                int cpx = canvas.worldToCanvasX(wp.worldX());
                int cpz = canvas.worldToCanvasZ(wp.worldZ());
                int sx  = originX + Math.round(cpx * zoom);
                int sy  = originY + Math.round(cpz * zoom);
                double dist = Math.hypot(mx - sx, my - sy);
                if (dist <= HIT_RADIUS) return new WaypointHit(wp, tile.mapId());
            }
        }
        return null;
    }

    private void rebuildContextMenuButtons() {
        this.clearChildren();
        this.init();

        if (contextMenuWaypoint == null) return;

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("label.explorermap.edit_waypoint"),
                b -> {
                    Waypoint toEdit = contextMenuWaypoint;
                    int owningMapId = contextMenuMapId;
                    closeContextMenu();
                    if (client != null) client.setScreen(new WaypointEditScreen(this, toEdit, owningMapId));
                }
        ).dimensions(contextMenuX, contextMenuY, 90, 18).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("label.explorermap.delete_waypoint"),
                b -> {
                    deleteWaypoint(contextMenuWaypoint, contextMenuMapId);
                    closeContextMenu();
                }
        ).dimensions(contextMenuX, contextMenuY + 20, 90, 18).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("label.explorermap.share_waypoint"),
                b -> {
                    Waypoint toShare = contextMenuWaypoint;
                    int owningMapId = contextMenuMapId;
                    closeContextMenu();
                    if (client != null) {
                        client.setScreen(new WaypointShareScreen(this, owningMapId, toShare));
                    }
                }
        ).dimensions(contextMenuX, contextMenuY + 40, 90, 18).build());
    }

    private void deleteWaypoint(Waypoint wp, int owningMapId) {
        if (client == null || client.player == null || owningMapId < 0) return;

        var entry = ClientMapCache.get(owningMapId);
        if (entry != null) entry.removeWaypoint(wp.name());

        ItemStack offHand = client.player.getStackInHand(Hand.OFF_HAND);
        if (ExplorerMapMod.isFilledMap(offHand)) {
            ClientPlayNetworking.send(new DeleteWaypointPayload(owningMapId, wp.name()));
        }
    }

    private void closeContextMenu() {
        if (contextMenuWaypoint != null) {
            contextMenuWaypoint = null;
            contextMenuMapId = -1;
            this.clearChildren();
            this.init();
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging && button == 0) { panX = panStartX + (float) (mx - dragStartX); panY = panStartY + (float) (my - dragStartY); }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            if (dragging && Math.hypot(mx - clickStartX, my - clickStartY) < 4.0) {
                showCoordinatePopup(mx, my);
            }
            dragging = false;
        }
        return super.mouseReleased(mx, my, button);
    }

    private void showCoordinatePopup(double mouseX, double mouseY) {
        if (mapState == null || mapEntry == null || client == null || client.world == null) return;
        int cx = canvasX(), cy = canvasY(), size = canvasSize();
        if (mouseX < cx || mouseX >= cx + size || mouseY < cy || mouseY >= cy + size) return;

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, client.world);
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);
        int originX = canvas.defaultScreenOriginX(cx + size / 2, zoom, panX);
        int originY = canvas.defaultScreenOriginY(cy + size / 2, zoom, panY);
        int canvasX = (int) Math.floor((mouseX - originX) / zoom);
        int canvasZ = (int) Math.floor((mouseY - originY) / zoom);
        if (canvasX < 0 || canvasZ < 0
                || canvasX >= canvas.canvasWidthPx || canvasZ >= canvas.canvasHeightPx) return;

        int worldX = canvas.worldOriginX + canvasX * canvas.scale;
        int worldZ = canvas.worldOriginZ + canvasZ * canvas.scale;
        int worldY = client.player == null ? 0 : client.player.getBlockY();
        coordinatePopup = String.format("X %d  Y %d  Z %d", worldX, worldY, worldZ);
        coordinatePopupUntil = System.currentTimeMillis() + 7000L;
    }

    private void adjustZoom(float delta) { zoom = Math.clamp(zoom + delta, 0.5f, 4.0f); }

    private void expand(ExpansionRecord.Direction dir, boolean highDetail) {
        ClientPlayNetworking.send(new RequestExpansionPayload(dir, highDetail));
    }

    private void resolveMap() {
        if (client == null || client.player == null || client.world == null) {
            mapId = -1; mapState = null; mapEntry = null; return;
        }
        ItemStack off = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(off)) {
            mapId = -1; mapState = null; mapEntry = null; return;
        }
        mapId    = MapIdentity.rawIdOf(off);
        mapState = MapIdentity.stateOf(off, client.world);
        mapEntry = (mapState != null && mapId >= 0) ? ClientMapCache.getOrCreate(mapId) : null;
    }

    @Override public boolean shouldPause() { return false; }
}
