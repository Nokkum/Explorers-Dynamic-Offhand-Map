package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.data.MapPixels;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncWaypointsPayload(
        MapIdentity mapId,
        List<Waypoint> waypoints,
        int waypointCapacity,
        int waypointShareCharges,
        boolean canManageSystem
) implements CustomPacketPayload {

    private static final int MAX_WAYPOINTS = 512;

    public static final CustomPacketPayload.Type<SyncWaypointsPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("sync_waypoints"));

    public static final StreamCodec<FriendlyByteBuf, SyncWaypointsPayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC,
                    SyncWaypointsPayload::mapId,
                    ByteBufCodecs.collection(ArrayList::new, Waypoint.PACKET_CODEC, MAX_WAYPOINTS),
                    SyncWaypointsPayload::waypoints,
                    ByteBufCodecs.VAR_INT, SyncWaypointsPayload::waypointCapacity,
                    ByteBufCodecs.VAR_INT, SyncWaypointsPayload::waypointShareCharges,
                    ByteBufCodecs.BOOL, SyncWaypointsPayload::canManageSystem,
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void sendTo(ServerPlayer player, MapIdentity mapId) {
        var server = player.level().getServer();
        var savedData = ExplorerMapSavedData.get(server);
        UUID playerId = player.getUUID();

        List<Waypoint> waypoints = visibleTo(server, savedData, playerId, mapId);
        if (waypoints.size() > MAX_WAYPOINTS) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Map {} has {} waypoints visible to {}; only the first {} are synced",
                    mapId.asKey(), waypoints.size(), player.getName().getString(), MAX_WAYPOINTS);
            waypoints = List.copyOf(waypoints.subList(0, MAX_WAYPOINTS));
        }

        // The allowance is the player's own, for the map they are holding.
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        MapIdentity heldRoot = ExplorerMapMod.isFilledMap(offHand) ? MapIdentity.ofStack(offHand, player.level()) : null;
        boolean admin = ExplorerMapMod.canManageSystemWaypoints(player);
        ServerPlayNetworking.send(player, new SyncWaypointsPayload(
                mapId, waypoints,
                savedData.waypointCapacity(heldRoot, playerId),
                savedData.waypointShareCharges(heldRoot, playerId),
                admin));
    }

    /**
     * What this player may see: their own and shared waypoints, plus system waypoints whose spot they have
     * discovered, so undiscovered coordinates never leave the server. Own waypoints come first so a cut-off
     * at the sync limit drops other people's first.
     */
    private static List<Waypoint> visibleTo(MinecraftServer server, ExplorerMapSavedData savedData,
                                            UUID playerId, MapIdentity mapId) {
        List<Waypoint> visible = savedData.visibleWaypoints(mapId, playerId);
        var mapState = MapIdentity.resolveAndVerify(server, mapId);
        byte[] discovered = savedData.getPlayerDiscovery(mapId, playerId);

        List<Waypoint> own = new ArrayList<>();
        List<Waypoint> shared = new ArrayList<>();
        List<Waypoint> system = new ArrayList<>();
        for (Waypoint wp : visible) {
            if (wp.isOwnedBy(playerId)) {
                own.add(wp);
            } else if (!wp.isSystemOrLegacy()) {
                shared.add(wp);
            } else if (mapState != null
                    && MapPixels.isWorldPositionSet(mapState, discovered, wp.worldX(), wp.worldZ())) {
                system.add(wp);
            }
        }
        List<Waypoint> result = new ArrayList<>(own.size() + shared.size() + system.size());
        result.addAll(own);
        result.addAll(shared);
        result.addAll(system);
        return result;
    }

    public static void broadcastTo(MinecraftServer server, MapIdentity mapId) {
        var savedData = ExplorerMapSavedData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (canViewFromHeldMap(savedData, p, mapId)) {
                sendTo(p, mapId);
            } else {
                ExpansionFamilySync.invalidate(p.getUUID(), mapId);
            }
        }
    }

    private static boolean canViewFromHeldMap(ExplorerMapSavedData savedData, ServerPlayer player, MapIdentity mapId) {
        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return false;
        return savedData.isAccessibleFrom(MapIdentity.ofStack(offHand, player.level()), mapId);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(SyncWaypointsPayload payload) {
        var mapEntry = ClientMapCache.getOrCreate(payload.mapId());
        mapEntry.replaceAllWaypoints(payload.waypoints());
        mapEntry.setAllowance(payload.waypointCapacity(), payload.waypointShareCharges(), payload.canManageSystem());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map {}",
                payload.waypoints().size(), payload.mapId().asKey());
    }
}
