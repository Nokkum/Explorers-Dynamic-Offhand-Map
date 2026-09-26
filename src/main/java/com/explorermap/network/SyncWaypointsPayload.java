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
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;

public record SyncWaypointsPayload(
        MapIdentity mapId,
        List<Waypoint> waypoints,
        int waypointCapacity,
        int waypointShareCharges
) implements CustomPayload {

    private static final int MAX_WAYPOINTS = 512;

    public static final CustomPayload.Id<SyncWaypointsPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("sync_waypoints"));

    public static final PacketCodec<PacketByteBuf, SyncWaypointsPayload> CODEC =
            PacketCodec.tuple(
                    MapIdentity.PACKET_CODEC,
                    SyncWaypointsPayload::mapId,
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.codec(Waypoint.CODEC)),
                    SyncWaypointsPayload::waypoints,
                    PacketCodecs.VAR_INT, SyncWaypointsPayload::waypointCapacity,
                    PacketCodecs.VAR_INT, SyncWaypointsPayload::waypointShareCharges,
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void sendTo(ServerPlayerEntity player, MapIdentity mapId) {
        var savedData = ExplorerMapSavedData.get(player.getServer());
        List<Waypoint> waypoints = savedData.visibleWaypoints(mapId, player.getUuid());
        var entry = savedData.getOrCreate(mapId);
        ServerPlayNetworking.send(player, new SyncWaypointsPayload(
                mapId, waypoints, entry.getWaypointCapacity(), entry.getWaypointShareCharges()));
    }

    public static void broadcastTo(MinecraftServer server, MapIdentity mapId) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            var offHand = p.getStackInHand(Hand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) continue;
            if (MapIdentity.rawIdOf(offHand) == mapId.mapId()) {
                sendTo(p, mapId);
            }
        }
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(SyncWaypointsPayload payload) {
        if (payload.waypoints().size() > MAX_WAYPOINTS) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] Ignoring oversized waypoint sync ({} entries)",
                    payload.waypoints().size());
            return;
        }

        var client = MinecraftClient.getInstance();
        if (client.world == null) return;

        var mapState = MapIdentity.stateOf(payload.mapId().mapId(), client.world);
        if (mapState == null) return;

        var mapEntry = ClientMapCache.getOrCreate(payload.mapId());
        mapEntry.replaceAllWaypoints(payload.waypoints());
        mapEntry.setWaypointCapacity(payload.waypointCapacity(), payload.waypointShareCharges());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map {}",
                payload.waypoints().size(), payload.mapId().asKey());
    }
}
