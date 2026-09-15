package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.data.ExplorerMapSavedData;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import com.explorermap.mod.waypoint.Waypoint;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * C2S: client saves a new (or updated) waypoint for a specific map.
 *
 * Server-side validation
 * ───────────────────────
 * - The map must actually exist in the requesting player's dimension.
 * - Waypoint coordinates are rejected if NaN or infinite. A naive range
 *   check such as `Math.abs(x - center) > maxDist` does NOT catch NaN,
 *   since every comparison against NaN in Java evaluates to false — NaN
 *   would silently pass an unguarded range check and get persisted.
 * - Coordinates must be within a generous radius of the map's center to
 *   prevent a waypoint being planted arbitrarily far away.
 * - Name length and icon/color are bounded by Waypoint's own codec limits.
 *
 * "Replace" semantics: a waypoint with the same name is replaced.
 *
 * On success, the server pushes the updated list to every player currently
 * holding this map (SyncWaypointsPayload.broadcastTo), not just the sender —
 * otherwise a second player holding the same map would not see the change
 * until their next relog.
 */
public record SaveWaypointPayload(int mapId, Waypoint waypoint) implements CustomPayload {

    public static final CustomPayload.Id<SaveWaypointPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("save_waypoint"));

    public static final PacketCodec<PacketByteBuf, SaveWaypointPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT,                SaveWaypointPayload::mapId,
                    PacketCodecs.codec(Waypoint.CODEC),  SaveWaypointPayload::waypoint,
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
        var world    = player.getServerWorld();
        var mapState = world.getMapState(new MapIdComponent(payload.mapId()));

        if (mapState == null) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: map #{} not found for {}",
                    payload.mapId(), player.getName().getString());
            return;
        }

        double wx = payload.waypoint().worldX();
        double wz = payload.waypoint().worldZ();

        // Reject NaN/Infinity explicitly — a bare range check would let NaN
        // through silently, since every comparison against NaN is false.
        if (!Double.isFinite(wx) || !Double.isFinite(wz)) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: non-finite coordinates rejected from {}",
                    player.getName().getString());
            return;
        }

        int scale   = 1 << mapState.scale;
        int maxDist = 256 * scale; // 2 tile radii
        if (Math.abs(wx - mapState.centerX) > maxDist || Math.abs(wz - mapState.centerZ) > maxDist) {
            ExplorerMapMod.LOGGER.warn("[ExplorerMap] SaveWaypoint: coordinates out of range, rejected");
            return;
        }

        var savedData = ExplorerMapSavedData.get(player.getServer());
        savedData.removeWaypoint(world, payload.mapId(), payload.waypoint().name());
        savedData.addWaypoint(world, payload.mapId(), payload.waypoint());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Saved waypoint '{}' on map #{}",
                payload.waypoint().name(), payload.mapId());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }
}
