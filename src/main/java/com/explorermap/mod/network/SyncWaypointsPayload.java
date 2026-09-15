package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ClientMapCache;
import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.data.MapIdentity;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
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
        int mapId,
        List<Waypoint> waypoints
) implements CustomPayload {

    private static final int MAX_WAYPOINTS = 512;

    public static final CustomPayload.Id<SyncWaypointsPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("sync_waypoints"));

    public static final PacketCodec<PacketByteBuf, SyncWaypointsPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,
                    SyncWaypointsPayload::mapId,
                    PacketCodecs.collection(ArrayList::new, PacketCodecs.codec(Waypoint.CODEC)),
                    SyncWaypointsPayload::waypoints,
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void sendTo(ServerPlayerEntity player, int mapId) {
        var world = player.getServerWorld();
        var entry = ExplorerMapSavedData.get(player.getServer()).get(world, mapId);
        List<Waypoint> waypoints = entry != null ? entry.getWaypoints() : List.of();
        ServerPlayNetworking.send(player, new SyncWaypointsPayload(mapId, waypoints));
    }

    public static void broadcastTo(MinecraftServer server, int mapId) {
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            var offHand = p.getStackInHand(Hand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) continue;
            if (MapIdentity.rawIdOf(offHand) == mapId) {
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

        var mapState = MapIdentity.stateOf(payload.mapId(), client.world);
        if (mapState == null) return;

        var mapEntry = ClientMapCache.getOrCreate(mapState, payload.mapId());
        mapEntry.replaceAllWaypoints(payload.waypoints());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map #{}",
                payload.waypoints().size(), payload.mapId());
    }
}
