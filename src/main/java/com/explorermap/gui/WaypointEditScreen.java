package com.explorermap.gui;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.MultiTileCanvas;
import com.explorermap.expansion.TileGrid;
import com.explorermap.hud.WaypointIconRenderer;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.explorermap.network.SaveWaypointPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Environment(EnvType.CLIENT)
public class WaypointEditScreen extends Screen {

    private static final int[] COLORS = {
        0xFFFFFFFF,
        0xFFFF5555,
        0xFF55FF55,
        0xFF5599FF,
        0xFFFFFF55,
        0xFFFF55FF,
        0xFF55FFFF,
        0xFFFFAA00,
        0xFFAA55FF,
        0xFFFF8855,
        0xFF55FFAA,
        0xFFCCCCCC,
    };

    private static final int SWATCH_SIZE = 14;
    private static final int SWATCH_GAP  = 4;
    private static final int ICON_CELL   = 22;
    private static final int ICON_GAP    = 4;

    private final Screen parent;
    private final Waypoint editingWaypoint;
    private final MapIdentity editingMapId;

    private EditBox nameField;
    private List<String>    iconIds = new ArrayList<>();
    private int selectedIconIndex   = 0;
    private int selectedColorIndex  = 0;

    private int dlgX, dlgY, dlgW, dlgH;
    private int iconGridX, iconGridY;
    private int colorGridX, colorGridY;
    private int previewX, previewY;

    public WaypointEditScreen(Screen parent) {
        this(parent, null, null);
    }

    public WaypointEditScreen(Screen parent, Waypoint existing) {
        this(parent, existing, null);
    }

    public WaypointEditScreen(Screen parent, Waypoint existing, MapIdentity owningMapId) {
        super(Component.translatable("screen.explorermap.waypoint_edit"));
        this.parent          = parent;
        this.editingWaypoint = existing;
        this.editingMapId    = owningMapId;
    }

    @Override
    protected void init() {
        super.init();

        iconIds = List.copyOf(ExplorerMapRegistry.allIconIds());

        if (editingWaypoint != null) {
            int idx = iconIds.indexOf(editingWaypoint.iconId());
            if (idx >= 0) selectedIconIndex = idx;
            for (int i = 0; i < COLORS.length; i++) {
                if (COLORS[i] == editingWaypoint.color()) { selectedColorIndex = i; break; }
            }
        }

        dlgW = 280;
        int pad = 12;

        int cols = Math.max(1, (dlgW - pad * 2 + ICON_GAP) / (ICON_CELL + ICON_GAP));
        int iconRows = (iconIds.size() + cols - 1) / Math.max(1, cols);

        int colorCols = Math.max(1, (dlgW - pad * 2 + SWATCH_GAP) / (SWATCH_SIZE + SWATCH_GAP));
        int colorRows = (COLORS.length + colorCols - 1) / Math.max(1, colorCols);

        int contentHeight = pad + 14 + 28
                + iconRows * (ICON_CELL + ICON_GAP)
                + 10
                + colorRows * (SWATCH_SIZE + SWATCH_GAP)
                + 10 + ICON_CELL
                + 28 + pad;

        dlgH = Math.max(200, contentHeight);
        dlgX = (this.width  - dlgW) / 2;
        dlgY = (this.height - dlgH) / 2;

        int fieldY = dlgY + pad + 14;
        nameField = new EditBox(this.font,
                dlgX + pad, fieldY, dlgW - pad * 2, 18,
                Component.translatable("label.explorermap.waypoint_name"));
        nameField.setMaxLength(32);
        nameField.setValue(editingWaypoint != null ? editingWaypoint.name() : "");
        nameField.setHint(Component.translatable("explorermap.waypoint.default_name"));
        addRenderableWidget(nameField);

        iconGridY = fieldY + 28;
        iconGridX = dlgX + pad;

        for (int i = 0; i < iconIds.size(); i++) {
            final int idx = i;
            int col = i % cols, row = i / cols;
            int bx = iconGridX + col * (ICON_CELL + ICON_GAP);
            int by = iconGridY + row * (ICON_CELL + ICON_GAP);
            addRenderableWidget(Button.builder(Component.empty(),
                    btn -> selectedIconIndex = idx)
                    .bounds(bx, by, ICON_CELL, ICON_CELL).build());
        }

        colorGridY = iconGridY + iconRows * (ICON_CELL + ICON_GAP) + 10;
        colorGridX = dlgX + pad;

        for (int i = 0; i < COLORS.length; i++) {
            final int idx = i;
            int col = i % colorCols, row = i / colorCols;
            int bx = colorGridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int by = colorGridY + row * (SWATCH_SIZE + SWATCH_GAP);
            addRenderableWidget(Button.builder(Component.empty(),
                    btn -> selectedColorIndex = idx)
                    .bounds(bx, by, SWATCH_SIZE, SWATCH_SIZE).build());
        }

        previewY = colorGridY + colorRows * (SWATCH_SIZE + SWATCH_GAP) + 10;
        previewX = dlgX + pad;

        int btnY = dlgY + dlgH - 28;
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.save"),
                btn -> save()).bounds(dlgX + dlgW / 2 - 54, btnY, 50, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.cancel"),
                btn -> minecraft.gui.setScreen(parent))
                .bounds(dlgX + dlgW / 2 + 4, btnY, 50, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractBackground(ctx, mouseX, mouseY, delta);

        ctx.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xEE1A1A1A);
        ctx.outline(dlgX, dlgY, dlgW, dlgH, 0xFF555555);

        int pad = 12;

        ctx.centeredText(this.font,
                Component.translatable("screen.explorermap.waypoint_edit"), dlgX + dlgW / 2, dlgY + pad, 0xFFFFFFFF);

        ctx.text(this.font, "Name:",
                dlgX + pad, dlgY + pad + 4, 0xFFAAAAAA, true);

        ctx.text(this.font, "Icon:",
                dlgX + pad, iconGridY - 11, 0xFFAAAAAA, true);

        ctx.text(this.font, "Color:",
                dlgX + pad, colorGridY - 11, 0xFFAAAAAA, true);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        // Background + buttons first, then the picker visuals drawn on top of the (empty) buttons.
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        int pad = 12;

        int cols = Math.max(1, (dlgW - pad * 2 + ICON_GAP) / (ICON_CELL + ICON_GAP));
        for (int i = 0; i < iconIds.size(); i++) {
            int col = i % cols, row = i / cols;
            int bx = iconGridX + col * (ICON_CELL + ICON_GAP);
            int by = iconGridY + row * (ICON_CELL + ICON_GAP);
            Identifier tex = ExplorerMapRegistry.getWaypointTexture(iconIds.get(i));
            WaypointIconRenderer.drawPickerCell(ctx, tex, COLORS[selectedColorIndex],
                    bx, by, ICON_CELL, i == selectedIconIndex);
        }

        int colorCols = Math.max(1, (dlgW - pad * 2 + SWATCH_GAP) / (SWATCH_SIZE + SWATCH_GAP));
        for (int i = 0; i < COLORS.length; i++) {
            int col = i % colorCols, row = i / colorCols;
            int sx = colorGridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int sy = colorGridY + row * (SWATCH_SIZE + SWATCH_GAP);
            ctx.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, COLORS[i]);
            if (i == selectedColorIndex) {
                ctx.outline(sx - 2, sy - 2, SWATCH_SIZE + 4, SWATCH_SIZE + 4, 0xFFFFFFFF);
            }
        }

        if (!iconIds.isEmpty()) {
            String name = nameField.getValue().isEmpty()
                    ? Component.translatable("explorermap.waypoint.default_name").getString()
                    : nameField.getValue();
            Identifier tex = ExplorerMapRegistry.getWaypointTexture(iconIds.get(selectedIconIndex));
            WaypointIconRenderer.drawPickerCell(ctx, tex, COLORS[selectedColorIndex],
                    previewX, previewY, ICON_CELL, false);
            ctx.text(this.font, name,
                    previewX + ICON_CELL + 6, previewY + ICON_CELL / 2 - 4,
                    COLORS[selectedColorIndex], true);
        }
    }

    private void save() {
        if (minecraft == null || minecraft.player == null) return;

        String name = nameField.getValue().trim();
        if (name.isEmpty()) {
            name = Component.translatable("explorermap.waypoint.default_name").getString()
                    + " (" + (int) minecraft.player.getX() + ", " + (int) minecraft.player.getZ() + ")";
        }

        String iconId = iconIds.isEmpty() ? Waypoint.DEFAULT_ICON : iconIds.get(selectedIconIndex);
        int color = COLORS[selectedColorIndex];

        double wx, wz;
        if (editingWaypoint != null) {
            wx = editingWaypoint.worldX();
            wz = editingWaypoint.worldZ();
        } else {
            wx = minecraft.player.getX();
            wz = minecraft.player.getZ();
        }

        UUID id = editingWaypoint != null ? editingWaypoint.id() : UUID.randomUUID();
        String ownerUuid = editingWaypoint != null
                ? editingWaypoint.ownerUuid()
                : minecraft.player.getUUID().toString();
        Waypoint wp = new Waypoint(id, name, wx, wz, iconId, color, ownerUuid);

        MapIdentity mapId;
        if (editingWaypoint != null && editingMapId != null) {
            mapId = editingMapId;
        } else {
            var offHand = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand) || minecraft.level == null) {
                minecraft.gui.setScreen(parent);
                return;
            }
            var rootMapState = MapIdentity.stateOf(offHand, minecraft.level);
            if (rootMapState == null) { minecraft.gui.setScreen(parent); return; }
            int rawRootMapId = MapIdentity.rawIdOf(offHand);
            if (rawRootMapId < 0) { minecraft.gui.setScreen(parent); return; }
            MapIdentity rootMapId = MapIdentity.of(rootMapState, rawRootMapId);

            var rootEntry = ClientMapCache.getOrCreate(rootMapId);
            var grid = TileGrid.build(rootMapId, rootMapState, rootEntry, minecraft.level);
            var canvas = MultiTileCanvas.from(grid, rootMapState);
            var owningTile = grid.findTileContaining(canvas, wx, wz);
            mapId = owningTile != null ? owningTile.mapId() : rootMapId;
        }
        if (mapId == null) { minecraft.gui.setScreen(parent); return; }

        var mapEntry = ClientMapCache.getOrCreate(mapId);
        mapEntry.addWaypoint(wp);

        ClientPlayNetworking.send(new SaveWaypointPayload(mapId, wp));

        minecraft.gui.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
}
