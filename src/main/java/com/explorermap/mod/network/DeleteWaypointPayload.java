package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

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
        var world    = player.getServerWorld();
        var mapState = world.getMapState(new MapIdComponent(payload.mapId()));
        if (mapState == null) return;

        var savedData = ExplorerMapSavedData.get(player.getServer());
        savedData.removeWaypoint(payload.mapId(), payload.waypointName());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Deleted waypoint '{}' from map #{}",
                payload.waypointName(), payload.mapId());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }
}
