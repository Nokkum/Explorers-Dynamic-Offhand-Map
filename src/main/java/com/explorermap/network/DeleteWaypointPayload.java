package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

public record DeleteWaypointPayload(int mapId, String waypointName) implements CustomPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPayload.Id<DeleteWaypointPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("delete_waypoint"));

    public static final PacketCodec<PacketByteBuf, DeleteWaypointPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,                      DeleteWaypointPayload::mapId,
                    PacketCodecs.string(MAX_NAME_LENGTH),      DeleteWaypointPayload::waypointName,
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

        int heldRootId = MapIdentity.rawIdOf(offHand);
        var savedData  = ExplorerMapSavedData.get(player.getServer());

        if (!savedData.isAccessibleFrom(heldRootId, payload.mapId())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] DeleteWaypoint: rejected from {} - map #{} is not the held map or a known expansion of it",
                    player.getName().getString(), payload.mapId());
            return;
        }

        var world    = player.getServerWorld();
        var mapState = world.getMapState(new MapIdComponent(payload.mapId()));
        if (mapState == null) return;

        savedData.removeWaypoint(payload.mapId(), payload.waypointName());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Deleted waypoint '{}' from map #{}",
                payload.waypointName(), payload.mapId());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }
}
