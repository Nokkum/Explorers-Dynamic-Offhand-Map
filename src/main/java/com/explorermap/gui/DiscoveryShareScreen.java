package com.explorermap.gui;

import com.explorermap.data.MapIdentity;
import com.explorermap.network.ShareDiscoveryPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class DiscoveryShareScreen extends Screen {

    private final Screen parent;
    private final MapIdentity mapId;
    private TextFieldWidget playerName;
    private int boxX;
    private int boxY;

    public DiscoveryShareScreen(Screen parent, MapIdentity mapId) {
        super(Text.translatable("screen.explorermap.discovery_share"));
        this.parent = parent;
        this.mapId = mapId;
    }

    @Override
    protected void init() {
        super.init();
        boxX = (width - 260) / 2;
        boxY = (height - 105) / 2;
        playerName = new TextFieldWidget(textRenderer, boxX + 12, boxY + 38,
                236, 20, Text.translatable("label.explorermap.player_name"));
        playerName.setMaxLength(64);
        addDrawableChild(playerName);
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.share"),
                        button -> share())
                .dimensions(boxX + 54, boxY + 73, 70, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("label.explorermap.cancel"),
                        button -> client.setScreen(parent))
                .dimensions(boxX + 136, boxY + 73, 70, 20).build());
    }

    private void share() {
        String target = playerName.getText().trim();
        if (target.isEmpty()) return;
        ClientPlayNetworking.send(new ShareDiscoveryPayload(mapId, target));
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.fill(boxX, boxY, boxX + 260, boxY + 105, 0xEE1A1A1A);
        context.drawBorder(boxX, boxY, 260, 105, 0xFF555555);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, boxY + 10, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer,
                Text.translatable("explorermap.discovery.share_target"),
                boxX + 12, boxY + 26, 0xFFAAAAAA);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}