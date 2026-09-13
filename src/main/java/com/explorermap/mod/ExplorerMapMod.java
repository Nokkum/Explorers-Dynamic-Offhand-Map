package com.explorermap.mod;

import com.explorermap.mod.attachment.MapDiscoveryAttachment;
import com.explorermap.mod.network.DeleteWaypointPayload;
import com.explorermap.mod.network.ExpansionFailedPayload;
import com.explorermap.mod.network.GrantExpansionPayload;
import com.explorermap.mod.network.RequestExpansionPayload;
import com.explorermap.mod.network.SaveWaypointPayload;
import com.explorermap.mod.network.SyncDiscoveryPayload;
import com.explorermap.mod.network.SyncWaypointsPayload;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.storage.MapState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (server + client) entrypoint.
 *
 * Responsibilities:
 *  - Register the MapDiscoveryAttachment type on MapState
 *  - Register custom advancements / game-events (future)
 */
public class ExplorerMapMod implements ModInitializer {

    public static final String MOD_ID = "explorermap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * Per-MapState attachment storing the fog-of-discovery bitmask,
     * waypoints, and expansion tile records.
     */
    public static final AttachmentType<MapDiscoveryAttachment> MAP_DISCOVERY =
            AttachmentRegistry.createPersistent(
                    ExplorerMapRegistry.id("map_discovery"),
                    MapDiscoveryAttachment.CODEC
            );

    @Override
    public void onInitialize() {
        // ── S2C payload types (must be registered on common side) ─────────
        PayloadTypeRegistry.playS2C().register(GrantExpansionPayload.ID,  GrantExpansionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncWaypointsPayload.ID,   SyncWaypointsPayload.CODEC);
        SyncDiscoveryPayload.Broadcast.registerCommon();
        ExpansionFailedPayload.registerCommon();

        // ── C2S payload types + server handlers ───────────────────────────
        RequestExpansionPayload.register();
        SaveWaypointPayload.register();
        DeleteWaypointPayload.register();
        SyncDiscoveryPayload.Upload.register();

        // ── Server events (join sync, etc.) ───────────────────────────────
        ServerEventHandler.register();

        LOGGER.info("[ExplorerMap] Common init complete.");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Returns true if the given ItemStack is a filled map.
     */
    public static boolean isFilledMap(ItemStack stack) {
        return stack.getItem() instanceof FilledMapItem;
    }

    /**
     * Gets or creates the MapDiscoveryAttachment for a given MapState.
     */
    public static MapDiscoveryAttachment getOrCreate(MapState state) {
        return state.getAttachedOrSet(MAP_DISCOVERY, new MapDiscoveryAttachment());
    }
}
