package com.explorermap.gui;

import com.explorermap.data.MapIdentity;
import com.explorermap.network.ShareDiscoveryPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public final class DiscoveryShareScreen extends Screen {

    private final Screen parent;
    private final MapIdentity mapId;
    private EditBox playerName;
    private int boxX;
    private int boxY;

    public DiscoveryShareScreen(Screen parent, MapIdentity mapId) {
        super(Component.translatable("screen.explorermap.discovery_share"));
        this.parent = parent;
        this.mapId = mapId;
    }

    @Override
    protected void init() {
        super.init();
        boxX = (width - 260) / 2;
        boxY = (height - 105) / 2;
        playerName = new EditBox(font, boxX + 12, boxY + 38,
                236, 20, Component.translatable("label.explorermap.player_name"));
        playerName.setMaxLength(64);
        addRenderableWidget(playerName);
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.share"),
                        button -> share())
                .bounds(boxX + 54, boxY + 73, 70, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("label.explorermap.cancel"),
                        button -> minecraft.gui.setScreen(parent))
                .bounds(boxX + 136, boxY + 73, 70, 20).build());
    }

    private void share() {
        String target = playerName.getValue().trim();
        if (target.isEmpty()) return;
        ClientPlayNetworking.send(new ShareDiscoveryPayload(mapId, target));
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // Vanilla draws the dim/blur background here and then the widgets; content drawn in this
        // method therefore ends up *under* the buttons, which is what these screens want.
        super.extractBackground(context, mouseX, mouseY, delta);
        context.fill(boxX, boxY, boxX + 260, boxY + 105, 0xEE1A1A1A);
        context.outline(boxX, boxY, 260, 105, 0xFF555555);
        context.centeredText(font, title, width / 2, boxY + 10, 0xFFFFFFFF);
        context.text(font,
                Component.translatable("explorermap.discovery.share_target"),
                boxX + 12, boxY + 26, 0xFFAAAAAA, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}