package com.explorermap.gui;

import com.explorermap.ExplorerMapMod;
import com.explorermap.expansion.ExpansionHandler;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.network.ClientNet;
import com.explorermap.network.InsertWaypointCapacityPayload;
import com.explorermap.network.RequestExpansionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

@Environment(EnvType.CLIENT)
public final class CartographyTableScreen extends Screen {

    private static final int PANEL_MAX_WIDTH = 300;
    private static final int PANEL_HEIGHT = 168;
    private static final int MARGIN = 12;
    private static final int GAP = 6;

    private final BlockPos tablePos;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private boolean highDetail;
    private int lastSignature = Integer.MIN_VALUE;

    public CartographyTableScreen(BlockPos tablePos) {
        super(Component.translatable("screen.explorermap.cartography_table"));
        this.tablePos = tablePos;
    }

    @Override
    protected void init() {
        super.init();
        // The panel shrinks with the window so the buttons stay on screen at high GUI scales.
        panelWidth = Math.min(PANEL_MAX_WIDTH, width - 8);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(2, (height - PANEL_HEIGHT) / 2);
        int inner = panelWidth - 2 * MARGIN;

        var player = minecraft != null ? minecraft.player : null;
        boolean hasMap = player != null && ExplorerMapMod.isFilledMap(player.getItemInHand(InteractionHand.OFF_HAND));
        boolean canExpand = hasMap && ExpansionHandler.canExpand(player, highDetail);

        int dirWidth = (inner - 3 * GAP) / 4;
        int index = 0;
        for (ExpansionRecord.Direction direction : ExpansionRecord.Direction.values()) {
            final ExpansionRecord.Direction selected = direction;
            boolean expanded = player != null && ExpansionHandler.alreadyExpanded(player, direction);
            Button button = Button.builder(GuiText.expandLabel(direction, expanded),
                            clickedButton -> ClientNet.send(
                                    new RequestExpansionPayload(selected, highDetail, tablePos)))
                    .bounds(panelX + MARGIN + index++ * (dirWidth + GAP), panelY + 40, dirWidth, 20)
                    .build();
            button.active = canExpand && !expanded;
            addRenderableWidget(button);
        }

        int half = (inner - GAP) / 2;
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.insert_compass"),
                        button -> ClientNet.send(new InsertWaypointCapacityPayload(tablePos)))
                .bounds(panelX + MARGIN, panelY + 66, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.waypoint"),
                        button -> minecraft.gui.setScreen(new WaypointEditScreen(this)))
                .bounds(panelX + MARGIN + half + GAP, panelY + 66, half, 20).build());

        addRenderableWidget(Button.builder(GuiText.hdToggle(highDetail),
                        button -> {
                            highDetail = !highDetail;
                            clearWidgets();
                            init();
                        })
                .bounds(panelX + MARGIN, panelY + 92, 60, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.cancel"),
                        button -> onClose())
                .bounds(panelX + panelWidth / 2 - 40, panelY + 118, 80, 20).build());

        lastSignature = signature();
    }

    /** Everything the buttons' enabled state depends on, so they can be rebuilt when it changes. */
    private int signature() {
        var player = minecraft != null ? minecraft.player : null;
        if (player == null) return -1;
        int signature = ExplorerMapMod.isFilledMap(player.getItemInHand(InteractionHand.OFF_HAND)) ? 1 : 0;
        if (ExpansionHandler.canExpand(player, highDetail)) signature |= 2;
        for (ExpansionRecord.Direction direction : ExpansionRecord.Direction.values()) {
            if (ExpansionHandler.alreadyExpanded(player, direction)) signature |= 4 << direction.ordinal();
        }
        return signature;
    }

    @Override
    public void tick() {
        super.tick();
        if (signature() != lastSignature) {
            clearWidgets();
            init();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Vanilla draws the dim/blur background here and then the widgets; content drawn in this
        // method therefore ends up *under* the buttons, which is what these screens want.
        super.extractBackground(context, mouseX, mouseY, delta);
        context.fill(panelX, panelY, panelX + panelWidth, panelY + PANEL_HEIGHT, 0xEE1A1A1A);
        context.outline(panelX, panelY, panelWidth, PANEL_HEIGHT, 0xFF555555);
        int centerX = panelX + panelWidth / 2;
        context.centeredText(font, title, centerX, panelY + 10, 0xFFFFFFFF);
        context.centeredText(font,
                Component.translatable("explorermap.cartography_table.instructions"),
                centerX, panelY + 24, 0xFFAAAAAA);
        context.text(font, GuiText.expansionMode(highDetail),
                panelX + MARGIN + 66, panelY + 98, 0xFFFFCC44, true);

        String failure = ExpansionFeedback.getActiveFailureMessage();
        if (failure != null) {
            context.centeredText(font, Component.literal(failure), centerX, panelY + 148, 0xFFFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
