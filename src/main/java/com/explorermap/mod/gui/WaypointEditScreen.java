package com.explorermap.mod.gui;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.hud.WaypointIconRenderer;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.explorermap.mod.network.SaveWaypointPayload;

import java.util.ArrayList;
import java.util.List;

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

    private TextFieldWidget nameField;
    private List<String>    iconIds = new ArrayList<>();
    private int selectedIconIndex   = 0;
    private int selectedColorIndex  = 0;

    private int dlgX, dlgY, dlgW, dlgH;
    private int iconGridX, iconGridY;
    private int colorGridX, colorGridY;
    private int previewX, previewY;

    public WaypointEditScreen(Screen parent) {
        this(parent, null);
    }

    public WaypointEditScreen(Screen parent, Waypoint existing) {
        super(Text.translatable("screen.explorermap.waypoint_edit"));
        this.parent          = parent;
        this.editingWaypoint = existing;
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

        dlgW = 280; dlgH = 200;
        dlgX = (this.width  - dlgW) / 2;
        dlgY = (this.height - dlgH) / 2;

        int pad = 12;

        int fieldY = dlgY + pad + 14;
        nameField = new TextFieldWidget(this.textRenderer,
                dlgX + pad, fieldY, dlgW - pad * 2, 18,
                Text.translatable("label.explorermap.waypoint_name"));
        nameField.setMaxLength(32);
        nameField.setText(editingWaypoint != null ? editingWaypoint.name() : "");
        nameField.setPlaceholder(Text.translatable("explorermap.waypoint.default_name"));
        addDrawableChild(nameField);

        iconGridY = fieldY + 28;
        iconGridX = dlgX + pad;

        int cols = Math.max(1, (dlgW - pad * 2 + ICON_GAP) / (ICON_CELL + ICON_GAP));
        for (int i = 0; i < iconIds.size(); i++) {
            final int idx = i;
            int col = i % cols, row = i / cols;
            int bx = iconGridX + col * (ICON_CELL + ICON_GAP);
            int by = iconGridY + row * (ICON_CELL + ICON_GAP);
            addDrawableChild(ButtonWidget.builder(Text.empty(),
                    btn -> selectedIconIndex = idx)
                    .dimensions(bx, by, ICON_CELL, ICON_CELL).build());
        }
        int iconRows = (iconIds.size() + cols - 1) / Math.max(1, cols);

        colorGridY = iconGridY + iconRows * (ICON_CELL + ICON_GAP) + 10;
        colorGridX = dlgX + pad;

        int colorCols = Math.max(1, (dlgW - pad * 2 + SWATCH_GAP) / (SWATCH_SIZE + SWATCH_GAP));
        for (int i = 0; i < COLORS.length; i++) {
            final int idx = i;
            int col = i % colorCols, row = i / colorCols;
            int bx = colorGridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int by = colorGridY + row * (SWATCH_SIZE + SWATCH_GAP);
            addDrawableChild(ButtonWidget.builder(Text.empty(),
                    btn -> selectedColorIndex = idx)
                    .dimensions(bx, by, SWATCH_SIZE, SWATCH_SIZE).build());
        }
        int colorRows = (COLORS.length + colorCols - 1) / Math.max(1, colorCols);

        previewY = colorGridY + colorRows * (SWATCH_SIZE + SWATCH_GAP) + 10;
        previewX = dlgX + pad;

        int btnY = dlgY + dlgH - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.save"),
                btn -> save()).dimensions(dlgX + dlgW / 2 - 54, btnY, 50, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.cancel"),
                btn -> client.setScreen(parent))
                .dimensions(dlgX + dlgW / 2 + 4, btnY, 50, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);

        ctx.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xEE1A1A1A);
        ctx.drawBorder(dlgX, dlgY, dlgW, dlgH, 0xFF555555);

        int pad = 12;

        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.explorermap.waypoint_edit"), dlgX + dlgW / 2, dlgY + pad, 0xFFFFFFFF);

        ctx.drawTextWithShadow(this.textRenderer, "Name:",
                dlgX + pad, dlgY + pad + 4, 0xFFAAAAAA);

        ctx.drawTextWithShadow(this.textRenderer, "Icon:",
                dlgX + pad, iconGridY - 11, 0xFFAAAAAA);

        int cols = Math.max(1, (dlgW - pad * 2 + ICON_GAP) / (ICON_CELL + ICON_GAP));
        for (int i = 0; i < iconIds.size(); i++) {
            int col = i % cols, row = i / cols;
            int bx = iconGridX + col * (ICON_CELL + ICON_GAP);
            int by = iconGridY + row * (ICON_CELL + ICON_GAP);
            Identifier tex = ExplorerMapRegistry.getWaypointTexture(iconIds.get(i));
            WaypointIconRenderer.drawPickerCell(ctx, tex, COLORS[selectedColorIndex],
                    bx, by, ICON_CELL, i == selectedIconIndex);
        }

        ctx.drawTextWithShadow(this.textRenderer, "Color:",
                dlgX + pad, colorGridY - 11, 0xFFAAAAAA);

        int colorCols = Math.max(1, (dlgW - pad * 2 + SWATCH_GAP) / (SWATCH_SIZE + SWATCH_GAP));
        for (int i = 0; i < COLORS.length; i++) {
            int col = i % colorCols, row = i / colorCols;
            int sx = colorGridX + col * (SWATCH_SIZE + SWATCH_GAP);
            int sy = colorGridY + row * (SWATCH_SIZE + SWATCH_GAP);
            ctx.fill(sx, sy, sx + SWATCH_SIZE, sy + SWATCH_SIZE, COLORS[i]);
            if (i == selectedColorIndex) {
                ctx.drawBorder(sx - 2, sy - 2, SWATCH_SIZE + 4, SWATCH_SIZE + 4, 0xFFFFFFFF);
            }
        }

        if (!iconIds.isEmpty()) {
            String name = nameField.getText().isEmpty() ? Text.translatable("explorermap.waypoint.default_name").getString() : nameField.getText();
            Identifier tex = ExplorerMapRegistry.getWaypointTexture(iconIds.get(selectedIconIndex));
            WaypointIconRenderer.drawPickerCell(ctx, tex, COLORS[selectedColorIndex],
                    previewX, previewY, ICON_CELL, false);
            ctx.drawTextWithShadow(this.textRenderer, name,
                    previewX + ICON_CELL + 6, previewY + ICON_CELL / 2 - 4,
                    COLORS[selectedColorIndex]);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void save() {
        if (client == null || client.player == null) return;

        String name = nameField.getText().trim();
        if (name.isEmpty()) name = Text.translatable("explorermap.waypoint.default_name").getString();

        String iconId = iconIds.isEmpty() ? Waypoint.DEFAULT_ICON : iconIds.get(selectedIconIndex);
        int color = COLORS[selectedColorIndex];

        double wx, wz;
        if (editingWaypoint != null) {
            wx = editingWaypoint.worldX();
            wz = editingWaypoint.worldZ();
        } else {
            wx = client.player.getX();
            wz = client.player.getZ();
        }

        Waypoint wp = new Waypoint(name, wx, wz, iconId, color);

        var offHand = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand) || client.world == null) {
            client.setScreen(parent);
            return;
        }

        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) { client.setScreen(parent); return; }

        var mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState != null) {
            var mapEntry = ClientMapCache.getOrCreate(mapState, mapId);
            if (editingWaypoint != null) mapEntry.removeWaypoint(editingWaypoint.name());
            mapEntry.addWaypoint(wp);
        }

        ClientPlayNetworking.send(new SaveWaypointPayload(mapId, wp));

        client.setScreen(parent);
    }

    @Override public boolean shouldPause() { return false; }
}
