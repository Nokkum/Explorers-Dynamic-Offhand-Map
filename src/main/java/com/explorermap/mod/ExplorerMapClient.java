package com.explorermap.mod;

import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.engine.ExplorationEngine;
import com.explorermap.mod.gui.ExpansionFeedback;
import com.explorermap.mod.gui.FullMapScreen;
import com.explorermap.mod.hud.MinimapHud;
import com.explorermap.mod.hud.TileTextureCache;
import com.explorermap.mod.network.ExpansionFailedPayload;
import com.explorermap.mod.network.GrantExpansionPayload;
import com.explorermap.mod.network.SyncDiscoveryPayload;
import com.explorermap.mod.network.SyncWaypointsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class ExplorerMapClient implements ClientModInitializer {

    public static KeyBinding OPEN_MAP_KEY;

    @Override
    public void onInitializeClient() {
        ExplorerMapConfig.load();

        GrantExpansionPayload.registerClient();

        SyncWaypointsPayload.registerClient();

        SyncDiscoveryPayload.Broadcast.registerClient();

        ExpansionFailedPayload.registerClient();

        HudRenderCallback.EVENT.register(MinimapHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(ExplorationEngine::tick);

        OPEN_MAP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.explorermap.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.explorermap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MAP_KEY.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new FullMapScreen());
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
