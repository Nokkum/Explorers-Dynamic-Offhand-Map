package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
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

public record SyncWaypointsPayload(
        MapIdentity mapId,
        List<Waypoint> waypoints,
        int waypointCapacity,
        int waypointShareCharges
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
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void sendTo(ServerPlayer player, MapIdentity mapId) {
        var savedData = ExplorerMapSavedData.get(player.level().getServer());
        List<Waypoint> waypoints = savedData.visibleWaypoints(mapId, player.getUUID());
        if (waypoints.size() > MAX_WAYPOINTS) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Map {} has {} waypoints visible to {}; only the first {} are synced",
                    mapId.asKey(), waypoints.size(), player.getName().getString(), MAX_WAYPOINTS);
            waypoints = List.copyOf(waypoints.subList(0, MAX_WAYPOINTS));
        }
        var entry = savedData.getOrCreate(mapId);
        ServerPlayNetworking.send(player, new SyncWaypointsPayload(
                mapId, waypoints, entry.getWaypointCapacity(), entry.getWaypointShareCharges()));
    }

    public static void broadcastTo(MinecraftServer server, MapIdentity mapId) {
        var savedData = ExplorerMapSavedData.get(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (ExpansionFamilySync.hasSynced(p.getUUID(), mapId) || canViewFromHeldMap(savedData, p, mapId)) {
                sendTo(p, mapId);
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
        mapEntry.setWaypointCapacity(payload.waypointCapacity(), payload.waypointShareCharges());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map {}",
                payload.waypoints().size(), payload.mapId().asKey());
    }
}
