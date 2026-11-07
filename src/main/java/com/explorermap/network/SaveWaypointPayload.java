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
                handleOnServer(context.player(), payload));
    }

    private static void handleOnServer(ServerPlayer player, SaveWaypointPayload payload) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            RejectLog.warn(player, "SaveWaypoint: no map in off-hand");
            return;
        }

        MapIdentity heldRoot = MapIdentity.ofStack(offHand, player.level());
        var savedData = ExplorerMapSavedData.get(player.level().getServer());

        var mapState = MapIdentity.resolveAuthorized(
                player.level().getServer(), savedData, heldRoot, payload.mapId());
        if (mapState == null) {
            RejectLog.warn(player, "SaveWaypoint: map {} is invalid or not accessible from the held map",
                    payload.mapId());
            return;
        }

        // From here on the player is authorized for this map, so a rejection also resyncs their view.
        double wx = payload.waypoint().worldX();
        double wz = payload.waypoint().worldZ();

        if (!Double.isFinite(wx) || !Double.isFinite(wz)) {
            RejectLog.warn(player, "SaveWaypoint: non-finite coordinates");
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        // A tile spans 128 pixels, so its edge is 64 * scale blocks from the center.
        int scale   = 1 << mapState.scale;
        int maxDist = 64 * scale;
        if (Math.abs(wx - mapState.centerX) > maxDist || Math.abs(wz - mapState.centerZ) > maxDist) {
            RejectLog.warn(player, "SaveWaypoint: coordinates outside the map");
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        // Custom icons are registered on the client only, so the server checks the format, not a registry.
        String iconId = payload.waypoint().iconId();
        if (!Waypoint.isAcceptableIconId(iconId)) {
            RejectLog.warn(player, "SaveWaypoint: bad iconId '{}'", iconId);
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        String name = payload.waypoint().name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            RejectLog.warn(player, "SaveWaypoint: invalid name");
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        UUID incomingId = payload.waypoint().id();
        if (incomingId == null) {
            RejectLog.warn(player, "SaveWaypoint: missing waypoint id");
            return;
        }

        var entry = savedData.getOrCreate(payload.mapId());
        String playerUuid = player.getUUID().toString();
        Waypoint previous = entry.findWaypoint(incomingId);

        boolean editing = previous != null;
        boolean admin = ExplorerMapMod.canManageSystemWaypoints(player);
        if (editing && !savedData.canManageWaypoint(payload.mapId(), incomingId, player.getUUID(), admin)) {
            RejectLog.warn(player, "SaveWaypoint: not allowed to edit that waypoint");
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        if (!editing) {
            // Slots belong to the player and the held map, and count waypoints on all of its tiles.
            var family = ExpansionFamilySync.familyOf(savedData, heldRoot).maps();
            int waypointCapacity = savedData.waypointCapacity(heldRoot, player.getUUID());
            int ownedWaypointCount = savedData.ownedWaypointCount(family, player.getUUID());
            if (ownedWaypointCount >= waypointCapacity) {
                RejectLog.warn(player, "SaveWaypoint: at waypoint capacity ({}/{}); a compass adds five slots",
                        ownedWaypointCount, waypointCapacity);
                SyncWaypointsPayload.sendTo(player, payload.mapId());
                return;
            }
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
