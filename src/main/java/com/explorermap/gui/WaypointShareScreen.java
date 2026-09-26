package com.explorermap.gui;

import com.explorermap.data.MapIdentity;
import com.explorermap.network.WaypointSharePayload;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class WaypointShareScreen extends Screen {

    private final Screen parent;
    private final MapIdentity mapId;
    private final Waypoint waypoint;
    private TextFieldWidget playerName;
    private int boxX;
    private int boxY;

    public WaypointShareScreen(Screen parent, MapIdentity mapId, Waypoint waypoint) {
        super(Text.translatable("screen.explorermap.waypoint_share"));
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

        playerName = new TextFieldWidget(textRenderer, boxX + 12, boxY + 38,
                boxW - 24, 20, Text.translatable("label.explorermap.player_name"));
        playerName.setMaxLength(64);
        playerName.setPlaceholder(Text.translatable("explorermap.waypoint.share_player_placeholder"));
        addDrawableChild(playerName);

        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.share"),
                button -> share()).dimensions(boxX + 54, boxY + 73, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.cancel"),
                button -> client.setScreen(parent)).dimensions(boxX + 136, boxY + 73, 70, 20).build());
    }

    private void share() {
        String target = playerName.getText().trim();
        if (target.isEmpty() || client == null) return;
        ClientPlayNetworking.send(new WaypointSharePayload(mapId, waypoint.id(), target));
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        int boxW = 260;
        int boxH = 105;
        context.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xEE1A1A1A);
        context.drawBorder(boxX, boxY, boxW, boxH, 0xFF555555);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.explorermap.waypoint_share"),
                width / 2, boxY + 10, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("explorermap.waypoint.share_target", waypoint.name()),
                boxX + 12, boxY + 26, 0xFFAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}