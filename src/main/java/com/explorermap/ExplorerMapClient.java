package com.explorermap;

import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.engine.ExplorationEngine;
import com.explorermap.expansion.ClientTileData;
import com.explorermap.expansion.ExpansionHandler;
import com.explorermap.expansion.TileGrid;
import com.explorermap.gui.CartographyTableScreen;
import com.explorermap.gui.ExpansionFeedback;
import com.explorermap.gui.FullMapScreen;
import com.explorermap.hud.MinimapHud;
import com.explorermap.hud.TileTextureCache;
import com.explorermap.interaction.ExplorerMapClientInteractions;
import com.explorermap.network.ExpansionFailedPayload;
import com.explorermap.network.GrantExpansionPayload;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.network.SyncExpansionsPayload;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.network.TileColorSync;
import com.explorermap.registry.ExplorerMapRegistry;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

@Environment(EnvType.CLIENT)
public class ExplorerMapClient implements ClientModInitializer {

    public static KeyMapping OPEN_MAP_KEY;

    public static void openCartographyTable(BlockPos tablePos) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.gui.setScreen(new CartographyTableScreen(tablePos));
    }

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config2")) {
            throw new IllegalStateException("Explorer Map needs Cloth Config on the client (it is optional on servers)");
        }
        ExplorerMapConfig.load();

        GrantExpansionPayload.registerClient();
        SyncWaypointsPayload.registerClient();
        SyncDiscoveryPayload.Broadcast.registerClient();
        SyncDiscoveryPayload.Ack.registerClient();
        SyncExpansionsPayload.registerClient();
        ExpansionFailedPayload.registerClient();
        TileColorSync.registerClient();
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

            while (OPEN_MAP_KEY.consumeClick()) {
                if (client.player != null) {
                    client.gui.setScreen(new FullMapScreen());
                }
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            TileTextureCache.getInstance().clearAll();
            ClientMapCache.clearAll();
            SyncDiscoveryPayload.resetUploadState();
            TileGrid.resetReportedWarnings();
            ExpansionFeedback.clear();
            ClientTileData.clear();
            ExpansionHandler.resetServerState();
        });

        ExplorerMapMod.LOGGER.info("[ExplorerMap] Client init complete.");
    }
}
