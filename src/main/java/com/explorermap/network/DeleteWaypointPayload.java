package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.Uuids;

import java.util.UUID;

public record DeleteWaypointPayload(MapIdentity mapId, UUID waypointId) implements CustomPayload {

    public static final CustomPayload.Id<DeleteWaypointPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("delete_waypoint"));

    public static final PacketCodec<PacketByteBuf, DeleteWaypointPayload> CODEC =
            PacketCodec.tuple(
                    MapIdentity.PACKET_CODEC,   DeleteWaypointPayload::mapId,
                    Uuids.PACKET_CODEC,         DeleteWaypointPayload::waypointId,
                    DeleteWaypointPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayerEntity player, DeleteWaypointPayload payload) {
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] DeleteWaypoint: rejected from {} - no map in off-hand",
                    player.getName().getString());
            return;
        }

        MapIdentity heldRoot = MapIdentity.ofStack(offHand, player.getServerWorld());
        var savedData = ExplorerMapSavedData.get(player.getServer());

        if (heldRoot == null || !savedData.isAccessibleFrom(heldRoot, payload.mapId())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] DeleteWaypoint: rejected from {} - map {} is not the held map or a known expansion of it",
                    player.getName().getString(), payload.mapId().asKey());
            return;
        }

        var mapState = MapIdentity.resolveAndVerify(player.getServerWorld(), payload.mapId());
        if (mapState == null) return;

        if (!savedData.canManageWaypoint(payload.mapId(), payload.waypointId(), player.getUuid())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] DeleteWaypoint: {} tried to delete a waypoint they do not own",
                    player.getName().getString());
            return;
        }

        savedData.removeWaypoint(payload.mapId(), payload.waypointId());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Deleted waypoint {} from map {}",
                payload.waypointId(), payload.mapId().asKey());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }
}
