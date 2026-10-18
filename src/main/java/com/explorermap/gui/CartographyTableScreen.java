package com.explorermap.gui;

import com.explorermap.ExplorerMapMod;
import com.explorermap.expansion.ExpansionHandler;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.network.InsertWaypointCapacityPayload;
import com.explorermap.network.RequestExpansionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

@Environment(EnvType.CLIENT)
public final class CartographyTableScreen extends Screen {

    private final BlockPos tablePos;
    private int panelX;
    private int panelY;
    private boolean highDetail;

    public CartographyTableScreen(BlockPos tablePos) {
        super(Component.translatable("screen.explorermap.cartography_table"));
        this.tablePos = tablePos;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - 300) / 2;
        panelY = (height - 150) / 2;

        var player = minecraft != null ? minecraft.player : null;
        boolean hasMap = player != null && ExplorerMapMod.isFilledMap(player.getItemInHand(InteractionHand.OFF_HAND));
        boolean canExpand = hasMap && ExpansionHandler.canExpand(player, highDetail);

        int index = 0;
        for (ExpansionRecord.Direction direction : ExpansionRecord.Direction.values()) {
            final ExpansionRecord.Direction selected = direction;
            boolean expanded = player != null && ExpansionHandler.alreadyExpanded(player, direction);
            Button button = Button.builder(Component.literal(label(direction, expanded)),
                            clickedButton -> ClientPlayNetworking.send(
                                    new RequestExpansionPayload(selected, highDetail)))
                    .bounds(panelX + 12 + index++ * 68, panelY + 48, 62, 20)
                    .build();
            button.active = canExpand && !expanded;
            addRenderableWidget(button);
        }

        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.insert_compass"),
                        button -> ClientPlayNetworking.send(
                                new InsertWaypointCapacityPayload(tablePos)))
                .bounds(panelX + 12, panelY + 82, 132, 20).build());
        addRenderableWidget(Button.builder(Component.literal(highDetail ? "[HD]" : " HD "),
                        button -> {
                            highDetail = !highDetail;
                            clearWidgets();
                            init();
                        })
                .bounds(panelX + 12, panelY + 116, 60, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.waypoint"),
                        button -> minecraft.gui.setScreen(new WaypointEditScreen(this)))
                .bounds(panelX + 152, panelY + 82, 100, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.cancel"),
                        button -> onClose())
                .bounds(panelX + 208, panelY + 116, 80, 20).build());
    }

    private static String label(ExpansionRecord.Direction direction, boolean expanded) {
        if (expanded) return "OK " + direction.name().charAt(0);
        return switch (direction) {
            case NORTH -> "^ N";
            case SOUTH -> "v S";
            case EAST -> "> E";
            case WEST -> "< W";
        };
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Vanilla draws the dim/blur background here and then the widgets; content drawn in this
        // method therefore ends up *under* the buttons, which is what these screens want.
        super.extractBackground(context, mouseX, mouseY, delta);
        context.fill(panelX, panelY, panelX + 300, panelY + 150, 0xEE1A1A1A);
        context.outline(panelX, panelY, 300, 150, 0xFF555555);
        context.centeredText(font, title,
                panelX + 150, panelY + 16, 0xFFFFFFFF);
        context.centeredText(font,
                Component.translatable("explorermap.cartography_table.instructions"),
                panelX + 150, panelY + 31, 0xFFAAAAAA);
        context.text(font,
                Component.literal(highDetail ? "High-detail expansion" : "Standard expansion"),
                panelX + 80, panelY + 121, 0xFFFFCC44, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}