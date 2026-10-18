package com.explorermap.gui;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionHandler;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.hud.TileRenderer;
import com.explorermap.hud.WaypointIconRenderer;
import com.explorermap.network.DeleteWaypointPayload;
import com.explorermap.network.RequestExpansionPayload;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

@Environment(EnvType.CLIENT)
public class FullMapScreen extends Screen {

    private static final int BASE_CANVAS = 512;

    private MapIdentity mapId;
    private final MapIdentity forcedMapId;
    private MapItemSavedData mapState;
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
    private MapIdentity contextMenuMapId;
    private int contextMenuX, contextMenuY;

    public FullMapScreen() {
        this(null);
    }

    public FullMapScreen(MapIdentity mapId) {
        super(Component.translatable("screen.explorermap.full_map"));
        this.forcedMapId = mapId;
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

        boolean hasResources = minecraft.player != null && ExpansionHandler.canExpand(minecraft.player, hdMode);
        for (ExpansionRecord.Direction dir : ExpansionRecord.Direction.values()) {
            boolean done   = minecraft.player != null && ExpansionHandler.alreadyExpanded(minecraft.player, dir);
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
            var btn = Button.builder(Component.literal(label),
                    b -> { if (!done) expand(dir, hdMode); })
                    .bounds(cx + offset, toolY, 46, 20).build();
            btn.active = active || done;
            addRenderableWidget(btn);
        }

        addRenderableWidget(Button.builder(
                Component.literal(hdMode ? "[HD]" : " HD "),
                btn -> {
                    FullMapScreen next = new FullMapScreen();
                    next.hdMode = !this.hdMode;
                    next.zoom   = this.zoom;
                    next.panX   = this.panX;
                    next.panY   = this.panY;
                    if (minecraft != null) minecraft.gui.setScreen(next);
                }
        ).bounds(cx + 200, toolY, 32, 20).build());

        addRenderableWidget(Button.builder(Component.literal("+"),   btn -> adjustZoom(+0.25f)).bounds(cx + 238, toolY, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-"),   btn -> adjustZoom(-0.25f)).bounds(cx + 260, toolY, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal("1:1"), btn -> { zoom = 1f; panX = 0; panY = 0; }).bounds(cx + 282, toolY, 30, 20).build());

        addRenderableWidget(Button.builder(Component.literal("+ Waypoint"),
                btn -> minecraft.gui.setScreen(new WaypointEditScreen(this))).bounds(cx + 318, toolY, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.share_discovery"),
                btn -> minecraft.gui.setScreen(new DiscoveryShareScreen(this, mapId)))
                .bounds(cx + 318, toolY + 22, 90, 20).build());

        addRenderableWidget(Button.builder(Component.literal("X"),
                btn -> this.onClose()).bounds(cx + size - 20, toolY, 20, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Vanilla draws the dim/blur background here and then the widgets; content drawn in this
        // method therefore ends up *under* the buttons, which is what these screens want.
        super.extractBackground(context, mouseX, mouseY, delta);
        resolveMap();

        int cx   = canvasX();
        int cy   = canvasY();
        int size = canvasSize();

        context.fill(cx - 2, cy - 2, cx + size + 2, cy + size + 2, 0xFF444444);

        if (mapState == null || mapEntry == null) {
            context.centeredText(this.font,
                    Component.translatable("explorermap.hud.no_map"),
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            return;
        }

        context.enableScissor(cx, cy, cx + size, cy + size);

        if (minecraft.player != null) {
            TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, minecraft.level);
            TileRenderer.renderFullMap(context, grid, mapState,
                    cx + size / 2, cy + size / 2,
                    zoom, panX, panY,
                    cx, cy, cx + size, cy + size,
                    minecraft.player.getX(), minecraft.player.getZ(), minecraft.player.getYRot());

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
            String hdLabel = Component.translatable("explorermap.expansion.hd_cost").getString();
            int labelW = this.font.width(hdLabel);
            context.text(this.font,
                    hdLabel, cx + size / 2 - labelW / 2, cy - 10, 0xFFFFCC44, true);
        }

        var failedDir = ExpansionFeedback.getActiveFailureDirection();
        if (failedDir != null) {
            String msg = ExpansionFeedback.getActiveFailureMessage();
            if (msg != null) {
                int msgW = this.font.width(msg);
                context.text(this.font, msg,
                        cx + size / 2 - msgW / 2, cy - 22, 0xFFFF5555, true);
            }
        }
    }

    private void renderWaypoints(GuiGraphicsExtractor ctx, TileGrid grid, MultiTileCanvas canvas,
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

    private void renderCompass(GuiGraphicsExtractor ctx, int x, int y) {
        ctx.text(this.font, "N", x + 5, y,      0xFFFF5555, true);
        ctx.text(this.font, "S", x + 5, y + 20, 0xFFAAAAAA, true);
        ctx.text(this.font, "W", x,     y + 10, 0xFFAAAAAA, true);
        ctx.text(this.font, "E", x + 12,y + 10, 0xFFAAAAAA, true);
        ctx.fill(x + 7, y + 11, x + 9, y + 13, 0xFF888888);
    }

    private void renderProgressBar(GuiGraphicsExtractor ctx, int cx, int cy, int size) {
        float frac = mapEntry.discoveryFraction();
        ctx.fill(cx, cy + size + 4, cx + size,               cy + size + 10, 0xFF222222);
        ctx.fill(cx, cy + size + 4, cx + (int) (size * frac), cy + size + 10, 0xFF44AA44);
        ctx.text(this.font,
                Component.translatable("explorermap.hud.explored", String.format("%.1f", frac * 100f)),
                cx + 2, cy + size + 13, 0xFF888888, true);
        String waypointCapacity = String.format("Waypoints %d/%d",
                mapEntry.getWaypoints().size(), mapEntry.getWaypointCapacity());
        ctx.text(this.font, waypointCapacity,
                cx + 2, cy + size + 25, 0xFF888888, true);

        if (minecraft.player != null) {
            String pos = String.format("X %.0f  Y %.0f  Z %.0f",
                    minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
            ctx.text(this.font, pos,
                    cx + size - this.font.width(pos) - 2, cy + size + 13, 0xFF888888, true);
        }
    }

    private void renderCoordinatePopup(GuiGraphicsExtractor ctx) {
        if (coordinatePopup == null || System.currentTimeMillis() >= coordinatePopupUntil) return;
        int width = this.font.width(coordinatePopup) + 16;
        int x = (this.width - width) / 2;
        int y = canvasY() + 8;
        ctx.fill(x, y, x + width, y + 22, 0xDD111111);
        ctx.outline(x, y, width, 22, 0xFFAAAAAA);
        ctx.centeredText(this.font, coordinatePopup,
                x + width / 2, y + 7, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        adjustZoom((float) vAmt * 0.15f);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
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
        return super.mouseClicked(event, doubleClick);
    }

    private record WaypointHit(Waypoint waypoint, MapIdentity mapId) {}

    private WaypointHit findWaypointNear(double mx, double my) {
        if (mapEntry == null || mapState == null || minecraft == null || minecraft.level == null) return null;

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, minecraft.level);
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
        this.clearWidgets();
        this.init();

        if (contextMenuWaypoint == null) return;

        addRenderableWidget(Button.builder(
                Component.translatable("label.explorermap.edit_waypoint"),
                b -> {
                    Waypoint toEdit = contextMenuWaypoint;
                    MapIdentity owningMapId = contextMenuMapId;
                    closeContextMenu();
                    if (minecraft != null) minecraft.gui.setScreen(new WaypointEditScreen(this, toEdit, owningMapId));
                }
        ).bounds(contextMenuX, contextMenuY, 90, 18).build());

        addRenderableWidget(Button.builder(
                Component.translatable("label.explorermap.delete_waypoint"),
                b -> {
                    deleteWaypoint(contextMenuWaypoint, contextMenuMapId);
                    closeContextMenu();
                }
        ).bounds(contextMenuX, contextMenuY + 20, 90, 18).build());

        addRenderableWidget(Button.builder(
                Component.translatable("label.explorermap.share_waypoint"),
                b -> {
                    Waypoint toShare = contextMenuWaypoint;
                    MapIdentity owningMapId = contextMenuMapId;
                    closeContextMenu();
                    if (minecraft != null) {
                        minecraft.gui.setScreen(new WaypointShareScreen(this, owningMapId, toShare));
                    }
                }
        ).bounds(contextMenuX, contextMenuY + 40, 90, 18).build());
    }

    private void deleteWaypoint(Waypoint wp, MapIdentity owningMapId) {
        if (minecraft == null || minecraft.player == null || owningMapId == null) return;

        var entry = ClientMapCache.get(owningMapId);
        if (entry != null) entry.removeWaypoint(wp.id());

        ItemStack offHand = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
        if (ExplorerMapMod.isFilledMap(offHand)) {
            ClientPlayNetworking.send(new DeleteWaypointPayload(owningMapId, wp.id()));
        }
    }

    private void closeContextMenu() {
        if (contextMenuWaypoint != null) {
            contextMenuWaypoint = null;
            contextMenuMapId = null;
            this.clearWidgets();
            this.init();
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (dragging && button == 0) { panX = panStartX + (float) (mx - dragStartX); panY = panStartY + (float) (my - dragStartY); }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (button == 0) {
            if (dragging && Math.hypot(mx - clickStartX, my - clickStartY) < 4.0) {
                showCoordinatePopup(mx, my);
            }
            dragging = false;
        }
        return super.mouseReleased(event);
    }

    private void showCoordinatePopup(double mouseX, double mouseY) {
        if (mapState == null || mapEntry == null || minecraft == null || minecraft.level == null) return;
        int cx = canvasX(), cy = canvasY(), size = canvasSize();
        if (mouseX < cx || mouseX >= cx + size || mouseY < cy || mouseY >= cy + size) return;

        TileGrid grid = TileGrid.build(mapId, mapState, mapEntry, minecraft.level);
        MultiTileCanvas canvas = MultiTileCanvas.from(grid, mapState);
        int originX = canvas.defaultScreenOriginX(cx + size / 2, zoom, panX);
        int originY = canvas.defaultScreenOriginY(cy + size / 2, zoom, panY);
        int canvasX = (int) Math.floor((mouseX - originX) / zoom);
        int canvasZ = (int) Math.floor((mouseY - originY) / zoom);
        if (canvasX < 0 || canvasZ < 0
                || canvasX >= canvas.canvasWidthPx || canvasZ >= canvas.canvasHeightPx) return;

        int worldX = canvas.worldOriginX + canvasX * canvas.scale;
        int worldZ = canvas.worldOriginZ + canvasZ * canvas.scale;
        int worldY = minecraft.player == null ? 0 : minecraft.player.getBlockY();
        coordinatePopup = String.format("X %d  Y %d  Z %d", worldX, worldY, worldZ);
        coordinatePopupUntil = System.currentTimeMillis() + 7000L;
    }

    private void adjustZoom(float delta) { zoom = Math.clamp(zoom + delta, 0.5f, 4.0f); }

    private void expand(ExpansionRecord.Direction dir, boolean highDetail) {
        ClientPlayNetworking.send(new RequestExpansionPayload(dir, highDetail));
    }

    private void resolveMap() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            mapId = null; mapState = null; mapEntry = null; return;
        }
        if (forcedMapId != null) {
            mapId = forcedMapId;
            mapState = MapIdentity.stateOf(forcedMapId.mapId(), minecraft.level);
        } else {
            ItemStack off = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(off)) {
                mapId = null; mapState = null; mapEntry = null; return;
            }
            mapState = MapIdentity.stateOf(off, minecraft.level);
            mapId = mapState != null ? MapIdentity.of(mapState, MapIdentity.rawIdOf(off)) : null;
        }
        mapEntry = (mapState != null && mapId != null) ? ClientMapCache.getOrCreate(mapId) : null;
    }

    @Override public boolean isPauseScreen() { return false; }
}
