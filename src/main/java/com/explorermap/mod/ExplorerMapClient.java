package com.explorermap.mod;

import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.engine.ExplorationEngine;
import com.explorermap.mod.gui.FullMapScreen;
import com.explorermap.mod.hud.MinimapHud;
import com.explorermap.mod.network.GrantExpansionPayload;
import com.explorermap.mod.network.SyncWaypointsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only entrypoint.
 *
 * Registers:
 *  - HudRenderCallback → MinimapHud
 *  - ClientTickEvents  → ExplorationEngine (camera-based discovery)
 *  - Key binding       → open FullMapScreen
 */
@Environment(EnvType.CLIENT)
public class ExplorerMapClient implements ClientModInitializer {

    /** Keybind: open the full-scale map GUI (default: M) */
    public static KeyBinding OPEN_MAP_KEY;

    @Override
    public void onInitializeClient() {
        ExplorerMapConfig.load();

        // Register S2C client handler for expansion grants
        GrantExpansionPayload.registerClient();

        // Register S2C client handler for waypoint sync
        SyncWaypointsPayload.registerClient();

        // Register the mini-map HUD overlay
        HudRenderCallback.EVENT.register(MinimapHud::render);

        // Register the per-tick exploration engine
        ClientTickEvents.END_CLIENT_TICK.register(ExplorationEngine::tick);

        // Register keybind for full-scale map
        OPEN_MAP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.explorermap.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.explorermap"
        ));

        // Handle keybind press → open GUI
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MAP_KEY.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new FullMapScreen());
                }
            }
        });

        ExplorerMapMod.LOGGER.info("[ExplorerMap] Client init complete.");
    }
}
