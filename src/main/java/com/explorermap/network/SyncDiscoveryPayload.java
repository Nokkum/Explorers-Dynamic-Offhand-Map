package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapEntryData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import com.explorermap.structure.StructureWaypointDetector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class SyncDiscoveryPayload {

    public record Upload(MapIdentity mapId, byte[] bitmask) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Upload> TYPE =
                new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("upload_discovery"));

        public static final StreamCodec<FriendlyByteBuf, Upload> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Upload::mapId,
                        ByteBufCodecs.byteArray(MapEntryData.BYTE_COUNT), Upload::bitmask,
                        Upload::new
                );

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void register() {
            PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
            ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) ->
                    ctx.server().execute(() -> handleOnServer(ctx.server(), ctx.player(), payload)));
        }

        private static void handleOnServer(MinecraftServer server, ServerPlayer sender, Upload payload) {

            var offHand = sender.getItemInHand(InteractionHand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) return;

            MapIdentity heldRoot = MapIdentity.ofStack(offHand, sender.level());
            var savedData = ExplorerMapSavedData.get(server);

            var mapState = MapIdentity.resolveAuthorized(server, savedData, heldRoot, payload.mapId());
            if (mapState == null) {
                ExplorerMapMod.LOGGER.warn(
                        "[ExplorerMap] Rejected discovery upload for map {} from {} - invalid or not accessible from the held map",
                        payload.mapId().asKey(), sender.getName().getString());
                return;
            }

            Set<Integer> newlyDiscovered = savedData.mergePlayerBitmask(
                    payload.mapId(), sender.getUUID(), payload.bitmask());
            if (newlyDiscovered.isEmpty()) return;

            ExplorerMapMod.LOGGER.debug("[ExplorerMap] Merged discovery for map {} from {}",
                    payload.mapId().asKey(), sender.getName().getString());

            StructureWaypointDetector.checkAndPlace(
                    server, sender, payload.mapId(), mapState, savedData, newlyDiscovered);

            sendTo(sender, payload.mapId(), savedData);
        }
    }

    public record Broadcast(MapIdentity mapId, byte[] bitmask) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Broadcast> TYPE =
                new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("broadcast_discovery"));

        public static final StreamCodec<FriendlyByteBuf, Broadcast> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Broadcast::mapId,
                        ByteBufCodecs.byteArray(MapEntryData.BYTE_COUNT), Broadcast::bitmask,
                        Broadcast::new
                );

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void registerCommon() {
            PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        }

        @Environment(EnvType.CLIENT)
        public static void registerClient() {
            ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) ->
                    ctx.client().execute(() -> handleOnClient(payload)));
        }

        @Environment(EnvType.CLIENT)
        private static void handleOnClient(Broadcast payload) {
            var mapEntry = ClientMapCache.getOrCreate(payload.mapId());
            mapEntry.replaceBitmask(payload.bitmask());
        }
    }

    public static void sendTo(ServerPlayer player, MapIdentity mapId) {
        sendTo(player, mapId, ExplorerMapSavedData.get(player.level().getServer()));
    }

    private static void sendTo(ServerPlayer player, MapIdentity mapId, ExplorerMapSavedData savedData) {
        ServerPlayNetworking.send(player,
                new Broadcast(mapId, savedData.getPlayerDiscovery(mapId, player.getUUID())));
    }

    public static final int UPLOAD_INTERVAL_TICKS = 60;

    private static int ticksSinceLastUpload = 0;
    private static final Map<MapIdentity, Long> lastUploadedGeneration = new HashMap<>();

    @Environment(EnvType.CLIENT)
    public static void resetUploadState() {
        ticksSinceLastUpload = 0;
        lastUploadedGeneration.clear();
    }

    @Environment(EnvType.CLIENT)
    public static boolean uploadWindowOpen() {
        ticksSinceLastUpload++;
        if (ticksSinceLastUpload < UPLOAD_INTERVAL_TICKS) return false;
        ticksSinceLastUpload = 0;
        return true;
    }

    @Environment(EnvType.CLIENT)
    public static boolean isDirty(MapIdentity mapId, long currentGeneration) {
        Long previous = lastUploadedGeneration.get(mapId);
        return previous == null || previous != currentGeneration;
    }

    @Environment(EnvType.CLIENT)
    public static void markUploaded(MapIdentity mapId, long generation) {
        lastUploadedGeneration.put(mapId, generation);
    }
}
