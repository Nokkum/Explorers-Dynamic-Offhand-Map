package com.explorermap.gui;

import com.explorermap.ExplorerMapMod;
import com.explorermap.expansion.ExpansionHandler;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.network.InsertWaypointCapacityPayload;
import com.explorermap.network.RequestExpansionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

@Environment(EnvType.CLIENT)
public final class CartographyTableScreen extends Screen {

    private final BlockPos tablePos;
    private int panelX;
    private int panelY;
    private boolean highDetail;

    public CartographyTableScreen(BlockPos tablePos) {
        super(Text.translatable("screen.explorermap.cartography_table"));
        this.tablePos = tablePos;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - 300) / 2;
        panelY = (height - 150) / 2;

        boolean hasMap = client != null && client.player != null
                && ExplorerMapMod.isFilledMap(client.player.getStackInHand(Hand.OFF_HAND));
        boolean canExpand = hasMap && client.player != null
                && ExpansionHandler.canExpand(client.player, highDetail);

        int index = 0;
        for (ExpansionRecord.Direction direction : ExpansionRecord.Direction.values()) {
            final ExpansionRecord.Direction selected = direction;
            boolean expanded = client.player != null
                    && ExpansionHandler.alreadyExpanded(client.player, direction);
            ButtonWidget button = ButtonWidget.builder(Text.literal(label(direction, expanded)),
                            button -> ClientPlayNetworking.send(
                                    new RequestExpansionPayload(selected, highDetail)))
                    .dimensions(panelX + 12 + index++ * 68, panelY + 48, 62, 20)
                    .build();
            button.active = canExpand && !expanded;
            addDrawableChild(button);
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.insert_compass"),
                        button -> ClientPlayNetworking.send(
                                new InsertWaypointCapacityPayload(tablePos)))
                .dimensions(panelX + 12, panelY + 82, 132, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(highDetail ? "[HD]" : " HD "),
                        button -> {
                            highDetail = !highDetail;
                            clearChildren();
                            init();
                        })
                .dimensions(panelX + 12, panelY + 116, 60, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.waypoint"),
                        button -> client.setScreen(new WaypointEditScreen(this)))
                .dimensions(panelX + 152, panelY + 82, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.cancel"),
                        button -> close())
                .dimensions(panelX + 208, panelY + 116, 80, 20).build());
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(panelX, panelY, panelX + 300, panelY + 150, 0xEE1A1A1A);
        context.drawBorder(panelX, panelY, 300, 150, 0xFF555555);
        context.drawCenteredTextWithShadow(textRenderer, title,
                panelX + 150, panelY + 16, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("explorermap.cartography_table.instructions"),
                panelX + 150, panelY + 31, 0xFFAAAAAA);
        context.drawTextWithShadow(textRenderer,
                Text.literal(highDetail ? "High-detail expansion" : "Standard expansion"),
                panelX + 80, panelY + 121, 0xFFFFCC44);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}