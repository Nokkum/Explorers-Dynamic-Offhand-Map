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

        var savedData = ExplorerMapSavedData.get(player.getServer());
        if (!payload.previousName().isEmpty() && !payload.previousName().equals(payload.waypoint().name())) {
            savedData.removeWaypoint(payload.mapId(), payload.previousName());
        }
        savedData.removeWaypoint(payload.mapId(), payload.waypoint().name());
        savedData.addWaypoint(payload.mapId(), payload.waypoint());

        ExplorerMapMod.LOGGER.debug("[ExplorerMap] Saved waypoint '{}' on map #{}",
                payload.waypoint().name(), payload.mapId());

        SyncWaypointsPayload.broadcastTo(player.getServer(), payload.mapId());
    }
}
