package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.FilledMapItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;
import net.minecraft.client.MinecraftClient;

/**
 * S2C: server pushes the full waypoint list for a specific map to the client.
 *
 * Sent:
 *   - Immediately after the server processes a SaveWaypointPayload.
 *   - When the player logs in / rejoins (triggered by ServerPlayConnectionEvents.JOIN).
 *   - When the player equips a map with waypoints for the first time in a session.
 *
 * The client replaces its in-memory waypoint list entirely (last-write-wins),
 * so this is safe to send multiple times.
 */
public record SyncWaypointsPayload(
        int mapId,
        List<Waypoint> waypoints
) implements CustomPayload {

    public static final CustomPayload.Id<SyncWaypointsPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("sync_waypoints"));

    // Codec: mapId (varint) + waypoint list
    public static final PacketCodec<PacketByteBuf, SyncWaypointsPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,
                    SyncWaypointsPayload::mapId,
                    PacketCodecs.collection(java.util.ArrayList::new,
                            PacketCodecs.codec(Waypoint.CODEC)),
                    SyncWaypointsPayload::waypoints,
                    SyncWaypointsPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    // ── Server-side: send to one player ───────────────────────────────────

    /**
     * Sends a player all waypoints for the given map ID.
     * The MapState must already be loaded on the server.
     */
    public static void sendTo(ServerPlayerEntity player, int mapId) {
        var world    = player.getServerWorld();
        var mapState = world.getMapState(FilledMapItem.getMapName(mapId));
        if (mapState == null) return;

        var attachment = ExplorerMapMod.getOrCreate(mapState);
        var payload    = new SyncWaypointsPayload(mapId, List.copyOf(attachment.getWaypoints()));
        ServerPlayNetworking.send(player, payload);
    }

    // ── Client handler ────────────────────────────────────────────────────

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(SyncWaypointsPayload payload) {
        var client = MinecraftClient.getInstance();
        if (client.world == null) return;

        var mapState = client.world.getMapState(FilledMapItem.getMapName(payload.mapId()));
        if (mapState == null) return;

        var attachment = ExplorerMapMod.getOrCreate(mapState);

        // Replace waypoint list atomically via the synchronized helper.
        // This prevents a ConcurrentModificationException if the render
        // thread is iterating waypoints at the same moment.
        attachment.syncWaypoints(payload.waypoints());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Synced {} waypoints for map #{}",
                payload.waypoints().size(), payload.mapId());
    }
}
