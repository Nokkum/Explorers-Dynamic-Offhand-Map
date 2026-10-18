package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

public record WaypointSharePayload(MapIdentity mapId, UUID waypointId, String targetName)
        implements CustomPacketPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPacketPayload.Type<WaypointSharePayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("share_waypoint"));

    public static final StreamCodec<FriendlyByteBuf, WaypointSharePayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC, WaypointSharePayload::mapId,
                    UUIDUtil.STREAM_CODEC, WaypointSharePayload::waypointId,
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), WaypointSharePayload::targetName,
                    WaypointSharePayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayer sender, WaypointSharePayload payload) {
        var offHand = sender.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        MapIdentity heldRoot = MapIdentity.ofStack(offHand, sender.level());
        var savedData = ExplorerMapSavedData.get(sender.level().getServer());

        if (MapIdentity.resolveAuthorized(sender.level().getServer(), savedData, heldRoot, payload.mapId()) == null) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] ShareWaypoint: rejected from {} - map {} is invalid or not accessible from the held map",
                    sender.getName().getString(), payload.mapId().asKey());
            return;
        }

        var entry = savedData.get(payload.mapId());
        if (entry == null) return;

        Waypoint waypoint = entry.findWaypoint(payload.waypointId());
        if (waypoint == null || !waypoint.isOwnedBy(sender.getUUID())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] ShareWaypoint: {} tried to share a waypoint they do not own",
                    sender.getName().getString());
            return;
        }

        ServerPlayer target = sender.level().getServer().getPlayerList().getPlayerByName(payload.targetName());
        if (target == null || target == sender) return;

        if (!savedData.consumeWaypointShareCharge(payload.mapId())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] ShareWaypoint: {} has no waypoint share charge",
                    sender.getName().getString());
            return;
        }

        if (!savedData.grantWaypointShare(payload.mapId(), payload.waypointId(), target.getUUID())) {
            return;
        }

        SyncWaypointsPayload.broadcastTo(sender.level().getServer(), payload.mapId());

        MapIdentity targetHeld = MapIdentity.ofStack(
                target.getItemInHand(InteractionHand.OFF_HAND), target.level());
        if (targetHeld != null
                && !targetHeld.equals(payload.mapId())
                && savedData.isAccessibleFrom(targetHeld, payload.mapId())) {
            SyncWaypointsPayload.sendTo(target, payload.mapId());
        }
        ExplorerMapMod.LOGGER.info(
                "[ExplorerMap] Shared waypoint '{}' from {} to {} using one compass",
                waypoint.name(), sender.getName().getString(), target.getName().getString());
    }

}
