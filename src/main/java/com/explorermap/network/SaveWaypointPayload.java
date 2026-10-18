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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.UUID;

public record SaveWaypointPayload(MapIdentity mapId, Waypoint waypoint) implements CustomPacketPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPacketPayload.Type<SaveWaypointPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("save_waypoint"));

    public static final StreamCodec<FriendlyByteBuf, SaveWaypointPayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC,           SaveWaypointPayload::mapId,
                    Waypoint.PACKET_CODEC,               SaveWaypointPayload::waypoint,
                    SaveWaypointPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayer player, SaveWaypointPayload payload) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: rejected from {} - no map in off-hand",
                    player.getName().getString());
            return;
        }

        MapIdentity heldRoot = MapIdentity.ofStack(offHand, player.level());
        var savedData = ExplorerMapSavedData.get(player.level().getServer());

        var mapState = MapIdentity.resolveAuthorized(
                player.level().getServer(), savedData, heldRoot, payload.mapId());
        if (mapState == null) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: rejected from {} - map {} is invalid or not accessible from the held map",
                    player.getName().getString(), payload.mapId().asKey());
            return;
        }

        double wx = payload.waypoint().worldX();
        double wz = payload.waypoint().worldZ();

        if (!Double.isFinite(wx) || !Double.isFinite(wz)) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: non-finite coordinates rejected from {}",
                    player.getName().getString());
            return;
        }

        int scale   = 1 << mapState.scale;
        int maxDist = 256 * scale;
        if (Math.abs(wx - mapState.centerX) > maxDist || Math.abs(wz - mapState.centerZ) > maxDist) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: coordinates out of range, rejected");
            return;
        }

        String iconId = payload.waypoint().iconId();
        if (!ExplorerMapRegistry.isRegisteredIcon(iconId)) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: unknown iconId '{}' rejected from {}",
                    iconId, player.getName().getString());
            return;
        }

        String name = payload.waypoint().name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: invalid name rejected from {}",
                    player.getName().getString());
            return;
        }

        UUID incomingId = payload.waypoint().id();
        if (incomingId == null) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: missing waypoint id rejected from {}",
                    player.getName().getString());
            return;
        }

        var entry = savedData.getOrCreate(payload.mapId());
        String playerUuid = player.getUUID().toString();
        Waypoint previous = entry.findWaypoint(incomingId);

        boolean editing = previous != null;
        if (editing && !previous.isOwnedBy(player.getUUID())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: {} tried to edit a waypoint they do not own",
                    player.getName().getString());
            return;
        }

        int waypointCapacity = entry.getWaypointCapacity();
        long ownedWaypointCount = entry.getWaypoints().stream()
                .filter(wp -> wp.isOwnedBy(player.getUUID()))
                .count();
        if (!editing && ownedWaypointCount >= waypointCapacity) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: {} has reached waypoint capacity ({}/{}); insert a compass at a cartography table to add five slots",
                    player.getName().getString(), ownedWaypointCount, waypointCapacity);
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        UUID id = editing ? previous.id() : incomingId;
        String ownerUuid = editing ? previous.ownerUuid() : playerUuid;
        Waypoint sanitized = new Waypoint(id, name, wx, wz, iconId, payload.waypoint().color(), ownerUuid);

        savedData.addWaypoint(payload.mapId(), sanitized);

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Saved waypoint '{}' on map {}",
                sanitized.name(), payload.mapId().asKey());

        SyncWaypointsPayload.broadcastTo(player.level().getServer(), payload.mapId());
    }
}
