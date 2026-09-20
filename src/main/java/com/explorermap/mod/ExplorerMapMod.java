package com.explorermap.mod;

import com.explorermap.mod.network.DeleteWaypointPayload;
import com.explorermap.mod.network.ExpansionFailedPayload;
import com.explorermap.mod.network.GrantExpansionPayload;
import com.explorermap.mod.network.RequestExpansionPayload;
import com.explorermap.mod.network.SaveWaypointPayload;
import com.explorermap.mod.network.SyncDiscoveryPayload;
import com.explorermap.mod.network.SyncWaypointsPayload;
import com.explorermap.mod.network.WaypointSharePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplorerMapMod implements ModInitializer {

    public static final String MOD_ID = "explorermap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {

        PayloadTypeRegistry.playS2C().register(GrantExpansionPayload.ID,  GrantExpansionPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SyncWaypointsPayload.ID,   SyncWaypointsPayload.CODEC);
        SyncDiscoveryPayload.Broadcast.registerCommon();
        ExpansionFailedPayload.registerCommon();

        RequestExpansionPayload.register();
        SaveWaypointPayload.register();
        DeleteWaypointPayload.register();
        WaypointSharePayload.register();
        SyncDiscoveryPayload.Upload.register();

        ServerEventHandler.register();

        LOGGER.info("[ExplorerMap] Common init complete.");
    }

    public static boolean isFilledMap(ItemStack stack) {
        return stack.getItem() instanceof FilledMapItem;
    }
}
