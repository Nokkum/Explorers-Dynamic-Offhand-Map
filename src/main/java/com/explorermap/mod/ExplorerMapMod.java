package com.explorermap.mod;

import com.explorermap.mod.network.DeleteWaypointPayload;
import com.explorermap.mod.network.ExpansionFailedPayload;
import com.explorermap.mod.network.GrantExpansionPayload;
import com.explorermap.mod.network.RequestExpansionPayload;
import com.explorermap.mod.network.SaveWaypointPayload;
import com.explorermap.mod.network.SyncDiscoveryPayload;
import com.explorermap.mod.network.SyncWaypointsPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (server + client) entrypoint.
 *
 * Architecture note
 * ─────────────────
 * Per-map exploration data (fog-of-discovery bitmask, expansions, waypoints)
 * is stored in com.explorermap.mod.data.ExplorerMapSavedData, a proper
 * mod-owned PersistentState retrieved via ExplorerMapSavedData.get(server).
 * An earlier revision of this mod tried to attach that data directly to
 * MapState using Fabric's Data Attachment API — an API whose AttachmentTarget
 * is only implemented on Entity, BlockEntity, ServerWorld, and Chunk, never
 * on PersistentState. That approach could not compile and has been removed.
 */
public class ExplorerMapMod implements ModInitializer {

    public static final String MOD_ID = "explorermap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Returns true if the given ItemStack is a filled map. */
    public static boolean isFilledMap(ItemStack stack) {
        return stack.getItem() instanceof FilledMapItem;
    }
}
