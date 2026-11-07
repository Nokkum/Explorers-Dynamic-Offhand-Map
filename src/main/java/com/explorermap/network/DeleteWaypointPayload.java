package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

public record DeleteWaypointPayload(MapIdentity mapId, UUID waypointId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DeleteWaypointPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("delete_waypoint"));

    public static final StreamCodec<FriendlyByteBuf, DeleteWaypointPayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC,   DeleteWaypointPayload::mapId,
                    UUIDUtil.STREAM_CODEC,         DeleteWaypointPayload::waypointId,
                    DeleteWaypointPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                handleOnServer(context.player(), payload));
    }

    private static void handleOnServer(ServerPlayer player, DeleteWaypointPayload payload) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            RejectLog.warn(player, "DeleteWaypoint: no map in off-hand");
            return;
        }

        MapIdentity heldRoot = MapIdentity.ofStack(offHand, player.level());
        var savedData = ExplorerMapSavedData.get(player.level().getServer());

        var mapState = MapIdentity.resolveAuthorized(
                player.level().getServer(), savedData, heldRoot, payload.mapId());
        if (mapState == null) {
            RejectLog.warn(player, "DeleteWaypoint: map {} is invalid or not accessible from the held map",
                    payload.mapId());
            return;
        }

        boolean admin = ExplorerMapMod.canManageSystemWaypoints(player);
        if (!savedData.canManageWaypoint(payload.mapId(), payload.waypointId(), player.getUUID(), admin)) {
            RejectLog.warn(player, "DeleteWaypoint: not allowed to delete that waypoint");
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        savedData.removeWaypoint(payload.mapId(), payload.waypointId());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Deleted waypoint {} from map {}",
                payload.waypointId(), payload.mapId().asKey());

        SyncWaypointsPayload.broadcastTo(player.level().getServer(), payload.mapId());
    }
}
