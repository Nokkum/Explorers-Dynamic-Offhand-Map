package com.explorermap.mod.gui;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.hud.WaypointIconRenderer;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.FilledMapItem;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.explorermap.mod.network.SaveWaypointPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoint creation / editing dialog.
 *
 * Layout (320 × 200 dialog centred on screen):
 *
 *   ┌──────────────────────────────────────────┐
 *   │  Name: [__________________________]       │
 *   │                                           │
 *   │  Icon:  [pin] [village] [temple]          │
 *   │         [dungeon] [base]                  │
 *   │                                           │
 *   │  Color: ● ● ● ● ● ● ● ●                   │
 *   │                                           │
 *   │  Preview: [icon]  "My Waypoint"           │
 *   │                                           │
 *   │          [  Save  ]  [ Cancel ]           │
 *   └──────────────────────────────────────────┘
 *
 * Icons are rendered via WaypointIconRenderer as actual textures.
 * Color swatches are 14×14 px filled squares with a white selection ring.
 */
@Environment(EnvType.CLIENT)
public class WaypointEditScreen extends Screen {

    // ── Color palette ─────────────────────────────────────────────────────
    private static final int[] COLORS = {
        0xFFFFFFFF,  // white
        0xFFFF5555,  // red
        0xFF55FF55,  // lime
        0xFF5599FF,  // blue
        0xFFFFFF55,  // yellow
        0xFFFF55FF,  // magenta
        0xFF55FFFF,  // cyan
        0xFFFFAA00,  // orange
        0xFFAA55FF,  // purple
        0xFFFF8855,  // coral
        0xFF55FFAA,  // mint
        0xFFCCCCCC,  // silver
    };

    private static final int SWATCH_SIZE = 14;
    private static final int SWATCH_GAP  = 4;
    private static final int ICON_CELL   = 22;
    private static final int ICON_GAP    = 4;

    // ── State ─────────────────────────────────────────────────────────────
    private final Screen parent;
    private final Waypoint editingWaypoint;

    private TextFieldWidget nameField;
    private List<String>    iconIds = new ArrayList<>();
    private int selectedIconIndex   = 0;
    private int selectedColorIndex  = 0;

    // Layout anchors computed in init()
    private int dlgX, dlgY, dlgW, dlgH;
    private int iconGridX, iconGridY;
    private int colorGridX, colorGridY;
    private int previewX, previewY;

    // ── Constructors ──────────────────────────────────────────────────────

    public WaypointEditScreen(Screen parent) {
        this(parent, null);
    }

    public WaypointEditScreen(Screen parent, Waypoint existing) {
        super(Text.translatable("screen.explorermap.waypoint_edit"));
        this.parent          = parent;
        this.editingWaypoint = existing;
    }

    // ── Init ──────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        iconIds = List.copyOf(ExplorerMapRegistry.allIconIds());

        // Seed from existing waypoint if editing
        if (editingWaypoint != null) {
            int idx = iconIds.indexOf(editingWaypoint.iconId());
            if (idx >= 0) selectedIconIndex = idx;
            for (int i = 0; i < COLORS.length; i++) {
                if (COLORS[i] == editingWaypoint.color()) { selectedColorIndex = i; break; }
            }
        }

        // Dialog box centred on screen
        dlgW = 280; dlgH = 200;
        dlgX = (this.width  - dlgW) / 2;
        dlgY = (this.height - dlgH) / 2;

        int pad = 12;

        // ── Name field ────────────────────────────────────────────────────
        int fieldY = dlgY + pad + 14;
        nameField = new TextFieldWidget(this.textRenderer,
                dlgX + pad, fieldY, dlgW - pad * 2, 18,
                Text.translatable("label.explorermap.waypoint_name"));
        nameField.setMaxLength(32);
        nameField.setText(editingWaypoint != null ? editingWaypoint.name() : "");
        nameField.setPlaceholder(Text.translatable("explorermap.waypoint.default_name"));
        addDrawableChild(nameField);

        // ── Icon grid (clickable cells) ───────────────────────────────────
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

        // ── Color swatches ────────────────────────────────────────────────
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

        // ── Preview strip ─────────────────────────────────────────────────
        previewY = colorGridY + colorRows * (SWATCH_SIZE + SWATCH_GAP) + 10;
        previewX = dlgX + pad;

        // ── Buttons ───────────────────────────────────────────────────────
        int btnY = dlgY + dlgH - 28;
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.save"),
                btn -> save()).dimensions(dlgX + dlgW / 2 - 54, btnY, 50, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.cancel"),
                btn -> client.setScreen(parent))
                .dimensions(dlgX + dlgW / 2 + 4, btnY, 50, 20).build());
    }

    // ── Render ────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);

        // Dialog background
        ctx.fill(dlgX, dlgY, dlgX + dlgW, dlgY + dlgH, 0xEE1A1A1A);
        ctx.drawBorder(dlgX, dlgY, dlgW, dlgH, 0xFF555555);

        int pad = 12;

        // Title
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.translatable("screen.explorermap.waypoint_edit"), dlgX + dlgW / 2, dlgY + pad, 0xFFFFFFFF);

        // "Name:" label
        ctx.drawTextWithShadow(this.textRenderer, "Name:",
                dlgX + pad, dlgY + pad + 4, 0xFFAAAAAA); // "Name:" intentionally short — not worth a key

        // "Icon:" label
        ctx.drawTextWithShadow(this.textRenderer, "Icon:",
                dlgX + pad, iconGridY - 11, 0xFFAAAAAA);

        // Icon grid cells
        int cols = Math.max(1, (dlgW - pad * 2 + ICON_GAP) / (ICON_CELL + ICON_GAP));
        for (int i = 0; i < iconIds.size(); i++) {
            int col = i % cols, row = i / cols;
            int bx = iconGridX + col * (ICON_CELL + ICON_GAP);
            int by = iconGridY + row * (ICON_CELL + ICON_GAP);
            Identifier tex = ExplorerMapRegistry.getWaypointTexture(iconIds.get(i));
            WaypointIconRenderer.drawPickerCell(ctx, tex, COLORS[selectedColorIndex],
                    bx, by, ICON_CELL, i == selectedIconIndex);
        }

        // "Color:" label
        ctx.drawTextWithShadow(this.textRenderer, "Color:",
                dlgX + pad, colorGridY - 11, 0xFFAAAAAA);

        // Color swatches
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

        // Preview
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

    // ── Save ──────────────────────────────────────────────────────────────

    private void save() {
        if (client == null || client.player == null) return;

        String name = nameField.getText().trim();
        if (name.isEmpty()) name = Text.translatable("explorermap.waypoint.default_name").getString();

        String iconId = iconIds.isEmpty() ? Waypoint.DEFAULT_ICON : iconIds.get(selectedIconIndex);
        int color = COLORS[selectedColorIndex];

        Waypoint wp = new Waypoint(name, client.player.getX(), client.player.getZ(), iconId, color);

        var offHand = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand) || client.world == null) {
            client.setScreen(parent);
            return;
        }

        Integer mapId = FilledMapItem.getMapId(offHand);
        if (mapId == null) { client.setScreen(parent); return; }

        // Optimistic local update so the HUD reflects the change immediately
        // (server will echo back authoritative state via SyncWaypointsPayload)
        var mapState = FilledMapItem.getMapState(offHand, client.world);
        if (mapState != null) {
            var attachment = ExplorerMapMod.getOrCreate(mapState);
            if (editingWaypoint != null) attachment.removeWaypoint(editingWaypoint.name());
            attachment.addWaypoint(wp);
        }

        // Persist on server
        ClientPlayNetworking.send(new SaveWaypointPayload(mapId, wp));

        client.setScreen(parent);
    }

    @Override public boolean shouldPause() { return false; }
}
