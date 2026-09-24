package com.explorermap;

import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ClientMapCache;
import com.explorermap.engine.ExplorationEngine;
import com.explorermap.gui.ExpansionFeedback;
import com.explorermap.gui.FullMapScreen;
import com.explorermap.gui.CartographyTableScreen;
import com.explorermap.hud.MinimapHud;
import com.explorermap.interaction.ExplorerMapClientInteractions;
import com.explorermap.hud.TileTextureCache;
import com.explorermap.network.ExpansionFailedPayload;
import com.explorermap.network.GrantExpansionPayload;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.network.SyncWaypointsPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class ExplorerMapClient implements ClientModInitializer {

    public static KeyBinding OPEN_MAP_KEY;
    private boolean minimapMouseDown;

    public static void openCartographyTable(net.minecraft.util.math.BlockPos tablePos) {
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player != null) client.setScreen(new CartographyTableScreen(tablePos));
    }

    @Override
    public void onInitializeClient() {
        ExplorerMapConfig.load();

        GrantExpansionPayload.registerClient();

        SyncWaypointsPayload.registerClient();

        SyncDiscoveryPayload.Broadcast.registerClient();

        ExpansionFailedPayload.registerClient();
        ExplorerMapClientInteractions.register();

        HudRenderCallback.EVENT.register(MinimapHud::render);

        ClientTickEvents.END_CLIENT_TICK.register(ExplorationEngine::tick);

        OPEN_MAP_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.explorermap.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.explorermap"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientMapCache.tickRecency();
            boolean mouseDown = client.currentScreen == null
                    && GLFW.glfwGetMouseButton(
                            client.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_1) == GLFW.GLFW_PRESS;
            if (mouseDown && !minimapMouseDown) {
                MinimapHud.handleClick(client);
            }
            minimapMouseDown = mouseDown;

            while (OPEN_MAP_KEY.wasPressed()) {
                if (client.player != null) {
                    client.setScreen(new FullMapScreen());
                }
            }

            while (client.options.useKey.wasPressed()) {
                if (client.currentScreen == null
                        && client.player != null
                        && client.player.getMainHandStack().isEmpty()
                        && ExplorerMapMod.isFilledMap(client.player.getStackInHand(Hand.OFF_HAND))) {
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
