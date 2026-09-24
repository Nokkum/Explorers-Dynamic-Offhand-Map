package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

public record WaypointSharePayload(int mapId, String waypointName, String targetName)
        implements CustomPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPayload.Id<WaypointSharePayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("share_waypoint"));

    public static final PacketCodec<PacketByteBuf, WaypointSharePayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, WaypointSharePayload::mapId,
                    PacketCodecs.string(MAX_NAME_LENGTH), WaypointSharePayload::waypointName,
                    PacketCodecs.string(MAX_NAME_LENGTH), WaypointSharePayload::targetName,
                    WaypointSharePayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayerEntity sender, WaypointSharePayload payload) {
        var offHand = sender.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        int heldRootId = MapIdentity.rawIdOf(offHand);
        var savedData = ExplorerMapSavedData.get(sender.getServer());
        if (!savedData.isAccessibleFrom(heldRootId, payload.mapId())) return;

        var entry = savedData.get(payload.mapId());
        if (entry == null) return;

        Waypoint waypoint = entry.getWaypoints().stream()
                .filter(wp -> wp.name().equals(payload.waypointName()))
                .findFirst()
                .orElse(null);
        if (waypoint == null || !waypoint.isOwnedBy(sender.getUuid())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] ShareWaypoint: {} tried to share a waypoint they do not own",
                    sender.getName().getString());
            return;
        }

        ServerPlayerEntity target = sender.getServer().getPlayerManager().getPlayer(payload.targetName());
        if (target == null || target == sender) return;

        if (!savedData.consumeWaypointShareCharge(payload.mapId())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] ShareWaypoint: {} has no waypoint share charge",
                    sender.getName().getString());
            return;
        }

        if (!savedData.grantWaypointShare(payload.mapId(), waypoint.name(), target.getUuid())) {
            return;
        }

        SyncWaypointsPayload.broadcastTo(sender.getServer(), payload.mapId());
        SyncWaypointsPayload.sendTo(target, payload.mapId());
        ExplorerMapMod.LOGGER.info(
                "[ExplorerMap] Shared waypoint '{}' from {} to {} using one compass",
                waypoint.name(), sender.getName().getString(), target.getName().getString());
    }

}