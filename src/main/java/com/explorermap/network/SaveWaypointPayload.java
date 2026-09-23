package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.item.Items;

public record SaveWaypointPayload(int mapId, String previousName, Waypoint waypoint) implements CustomPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPayload.Id<SaveWaypointPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("save_waypoint"));

    public static final PacketCodec<PacketByteBuf, SaveWaypointPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,                    SaveWaypointPayload::mapId,
                    PacketCodecs.string(MAX_NAME_LENGTH),    SaveWaypointPayload::previousName,
                    PacketCodecs.codec(Waypoint.CODEC),      SaveWaypointPayload::waypoint,
                    SaveWaypointPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayerEntity player, SaveWaypointPayload payload) {
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: rejected from {} - no map in off-hand",
                    player.getName().getString());
            return;
        }

        int heldRootId = MapIdentity.rawIdOf(offHand);
        var savedData  = ExplorerMapSavedData.get(player.getServer());

        if (!savedData.isAccessibleFrom(heldRootId, payload.mapId())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: rejected from {} - map #{} is not the held map or a known expansion of it",
                    player.getName().getString(), payload.mapId());
            return;
        }

        var world    = player.getServerWorld();
        var mapState = world.getMapState(new MapIdComponent(payload.mapId()));

        if (mapState == null) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: map #{} not found for {}",
                    payload.mapId(), player.getName().getString());
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

        var entry = savedData.getOrCreate(payload.mapId());
        String playerUuid = player.getUuid().toString();
        Waypoint previous = entry.getWaypoints().stream()
                .filter(wp -> wp.name().equals(payload.previousName()))
                .findFirst()
                .orElse(null);

        if (!payload.previousName().isEmpty()
                && (previous == null || !previous.isOwnedBy(player.getUuid()))) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: {} tried to edit a waypoint they do not own",
                    player.getName().getString());
            return;
        }

        Waypoint nameConflict = entry.getWaypoints().stream()
                .filter(wp -> wp.name().equals(name))
                .findFirst()
                .orElse(null);
        if (nameConflict != null && nameConflict != previous) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: duplicate waypoint name '{}' rejected",
                    name);
            return;
        }

        boolean editing = previous != null;
        int compassCount = countCompasses(player);
        int waypointCapacity = compassCount * 5;
        long ownedWaypointCount = entry.getWaypoints().stream()
                .filter(wp -> wp.isOwnedBy(player.getUuid()))
                .count();
        if (!editing && ownedWaypointCount >= waypointCapacity) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] SaveWaypoint: {} has reached waypoint capacity ({}/{}); a compass is required for every five waypoints",
                    player.getName().getString(), ownedWaypointCount, waypointCapacity);
            SyncWaypointsPayload.sendTo(player, payload.mapId());
            return;
        }

        String ownerUuid = previous != null ? previous.ownerUuid() : playerUuid;
        Waypoint sanitized = new Waypoint(
                name,
                wx,
                wz,
                iconId,
                payload.waypoint().color(),
                ownerUuid
        );

        if (previous != null && !previous.name().equals(name)) {
            savedData.removeWaypoint(payload.mapId(), payload.previousName());
        }
        savedData.removeWaypoint(payload.mapId(), name);
        savedData.addWaypoint(payload.mapId(), sanitized);

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Saved waypoint '{}' on map #{}",
                payload.waypoint().name(), payload.mapId());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }

    private static int countCompasses(ServerPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(Items.COMPASS)) count += player.getInventory().getStack(i).getCount();
        }
        return count;
    }
}
