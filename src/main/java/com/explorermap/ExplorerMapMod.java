package com.explorermap;

import com.explorermap.config.ExplorerMapServerConfig;
import com.explorermap.network.DeleteWaypointPayload;
import com.explorermap.network.ExpansionFailedPayload;
import com.explorermap.network.GrantExpansionPayload;
import com.explorermap.network.RequestExpansionPayload;
import com.explorermap.network.SaveWaypointPayload;
import com.explorermap.network.TileColorSync;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.network.SyncExpansionsPayload;
import com.explorermap.network.SyncWaypointsPayload;
import com.explorermap.network.WaypointSharePayload;
import com.explorermap.network.InsertWaypointCapacityPayload;
import com.explorermap.network.ShareDiscoveryPayload;
import com.explorermap.interaction.ExplorerMapInteractions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.world.item.MapItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplorerMapMod implements ModInitializer {

    public static final String MOD_ID = "explorermap";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Squared distance within which a cartography table counts as in reach (server and client agree). */
    public static final double TABLE_REACH_SQR = 64.0;

    @Override
    public void onInitialize() {
        ExplorerMapServerConfig.load();

        PayloadTypeRegistry.clientboundPlay().register(GrantExpansionPayload.TYPE,  GrantExpansionPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SyncWaypointsPayload.TYPE,   SyncWaypointsPayload.CODEC);
        SyncDiscoveryPayload.Broadcast.registerCommon();
        SyncDiscoveryPayload.Ack.registerCommon();
        SyncExpansionsPayload.registerCommon();
        ExpansionFailedPayload.registerCommon();
        TileColorSync.registerCommon();

        RequestExpansionPayload.register();
        SaveWaypointPayload.register();
        DeleteWaypointPayload.register();
        WaypointSharePayload.register();
        InsertWaypointCapacityPayload.register();
        ShareDiscoveryPayload.register();
        SyncDiscoveryPayload.Upload.register();

        ExplorerMapInteractions.registerCommon();
        ServerEventHandler.register();

        LOGGER.info("[ExplorerMap] Common init complete.");
    }

    /** Operators (gamemaster permission) may edit and delete system waypoints. */
    public static boolean canManageSystemWaypoints(ServerPlayer player) {
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static boolean isFilledMap(ItemStack stack) {
        return stack.getItem() instanceof MapItem;
    }
}
