package com.explorermap.gui;

import com.explorermap.data.MapIdentity;
import com.explorermap.network.WaypointSharePayload;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class WaypointShareScreen extends Screen {

    private final Screen parent;
    private final MapIdentity mapId;
    private final Waypoint waypoint;
    private EditBox playerName;
    private int boxX;
    private int boxY;

    public WaypointShareScreen(Screen parent, MapIdentity mapId, Waypoint waypoint) {
        super(Component.translatable("screen.explorermap.waypoint_share"));
        this.parent = parent;
        this.mapId = mapId;
        this.waypoint = waypoint;
    }

    @Override
    protected void init() {
        super.init();
        int boxW = 260;
        int boxH = 105;
        boxX = (width - boxW) / 2;
        boxY = (height - boxH) / 2;

        playerName = new EditBox(font, boxX + 12, boxY + 38,
                boxW - 24, 20, Component.translatable("label.explorermap.player_name"));
        playerName.setMaxLength(64);
        playerName.setHint(Component.translatable("explorermap.waypoint.share_player_placeholder"));
        addRenderableWidget(playerName);

        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.share"),
                button -> share()).bounds(boxX + 54, boxY + 73, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.cancel"),
                button -> minecraft.gui.setScreen(parent)).bounds(boxX + 136, boxY + 73, 70, 20).build());
    }

    private void share() {
        String target = playerName.getValue().trim();
        if (target.isEmpty() || minecraft == null) return;
        ClientPlayNetworking.send(new WaypointSharePayload(mapId, waypoint.id(), target));
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Vanilla draws the dim/blur background here and then the widgets; content drawn in this
        // method therefore ends up *under* the buttons, which is what these screens want.
        super.extractBackground(context, mouseX, mouseY, delta);
        int boxW = 260;
        int boxH = 105;
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A1A);
        context.outline(boxX, boxY, boxW, boxH, 0xFF555555);
        context.centeredText(font,
                Component.translatable("screen.explorermap.waypoint_share"),
                width / 2, boxY + 10, 0xFFFFFFFF);
        context.text(font,
                Component.translatable("explorermap.waypoint.share_target", waypoint.name()),
                boxX + 12, boxY + 26, 0xFFAAAAAA, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}