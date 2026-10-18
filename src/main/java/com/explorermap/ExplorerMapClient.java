package com.explorermap;

import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.engine.ExplorationEngine;
import com.explorermap.gui.CartographyTableScreen;
import com.explorermap.gui.ExpansionFeedback;
import com.explorermap.gui.FullMapScreen;
import com.explorermap.hud.MinimapHud;
import com.explorermap.hud.TileTextureCache;
import com.explorermap.interaction.ExplorerMapClientInteractions;
import com.explorermap.network.ExpansionFailedPayload;
import com.explorermap.network.GrantExpansionPayload;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.registry.ExplorerMapRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

@Environment(EnvType.CLIENT)
public class ExplorerMapClient implements ClientModInitializer {

    public static KeyMapping OPEN_MAP_KEY;
    private boolean minimapMouseDown;

    public static void openCartographyTable(BlockPos tablePos) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.gui.setScreen(new CartographyTableScreen(tablePos));
    }

    @Override
    public void onInitializeClient() {
        ExplorerMapConfig.load();

        GrantExpansionPayload.registerClient();
        SyncWaypointsPayload.registerClient();
        SyncDiscoveryPayload.Broadcast.registerClient();
        ExpansionFailedPayload.registerClient();
        ExplorerMapClientInteractions.register();

        // HudRenderCallback was replaced by the HudElementRegistry. The translation key for
        // this key category is "key.category.explorermap.main".
        HudElementRegistry.addLast(ExplorerMapRegistry.id("minimap"), MinimapHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(ExplorationEngine::tick);

        KeyMapping.Category category = KeyMapping.Category.register(ExplorerMapRegistry.id("main"));
        OPEN_MAP_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.explorermap.open_map",
                InputConstants.KEY_M,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientMapCache.tickRecency();

            // 26.3 replaced GLFW with SDL, so poll the mouse through vanilla's MouseHandler.
            boolean mouseDown = client.gui.screen() == null && client.mouseHandler.isLeftPressed();
            if (mouseDown && !minimapMouseDown) {
                MinimapHud.handleClick(client);
            }
            minimapMouseDown = mouseDown;

            while (OPEN_MAP_KEY.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new FullMapScreen());
                }
            }

            while (client.options.keyUse.consumeClick()) {
                if (client.gui.screen() == null
                        && client.player != null
                        && client.player.getMainHandItem().isEmpty()
                        && ExplorerMapMod.isFilledMap(client.player.getItemInHand(InteractionHand.OFF_HAND))) {
                    client.gui.setScreen(new FullMapScreen());
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            TileTextureCache.getInstance().clearAll();
            ClientMapCache.clearAll();
            SyncDiscoveryPayload.resetUploadState();
            ExpansionFeedback.clear();
        });

        ExplorerMapMod.LOGGER.info("[ExplorerMap] Client init complete.");
    }
}
