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
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

public final class SyncDiscoveryPayload {

    public record Upload(MapIdentity mapId, byte[] bitmask) implements CustomPayload {

        public static final CustomPayload.Id<Upload> ID =
                new CustomPayload.Id<>(ExplorerMapRegistry.id("upload_discovery"));

        public static final PacketCodec<PacketByteBuf, Upload> CODEC =
                PacketCodec.tuple(
                        MapIdentity.PACKET_CODEC, Upload::mapId,
                        PacketCodecs.byteArray(MapEntryData.BYTE_COUNT), Upload::bitmask,
                        Upload::new
                );

        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

        public static void register() {
            PayloadTypeRegistry.playC2S().register(ID, CODEC);
            ServerPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                    ctx.server().execute(() -> handleOnServer(ctx.server(), ctx.player(), payload)));
        }

        private static void handleOnServer(MinecraftServer server, ServerPlayerEntity sender, Upload payload) {

            var offHand = sender.getStackInHand(Hand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) return;

            var mapState = MapIdentity.resolveAndVerify(sender.getServerWorld(), payload.mapId());
            if (mapState == null) {
                ExplorerMapMod.LOGGER.warn(
                        "[ExplorerMap] Rejected discovery upload for map {} from {} - map does not exist "
                                + "or its dimension does not match the claim",
                        payload.mapId().asKey(), sender.getName().getString());
                return;
            }

            if (MapIdentity.rawIdOf(offHand) != payload.mapId().mapId()) {
                ExplorerMapMod.LOGGER.warn(
                        "[ExplorerMap] Rejected discovery upload for map {} from {} - not holding that map",
                        payload.mapId().asKey(), sender.getName().getString());
                return;
            }

            var savedData = ExplorerMapSavedData.get(server);
            boolean changed = savedData.mergePlayerBitmask(
                    payload.mapId(), sender.getUuid(), payload.bitmask());
            if (!changed) return;

            ExplorerMapMod.LOGGER.debug("[ExplorerMap] Merged discovery for map {} from {}",
                    payload.mapId().asKey(), sender.getName().getString());

            StructureWaypointDetector.checkAndPlace(server, sender, payload.mapId(), mapState, savedData);

            sendTo(sender, payload.mapId(), savedData);
        }
    }

    public record Broadcast(MapIdentity mapId, byte[] bitmask) implements CustomPayload {

        public static final CustomPayload.Id<Broadcast> ID =
                new CustomPayload.Id<>(ExplorerMapRegistry.id("broadcast_discovery"));

        public static final PacketCodec<PacketByteBuf, Broadcast> CODEC =
                PacketCodec.tuple(
                        MapIdentity.PACKET_CODEC, Broadcast::mapId,
                        PacketCodecs.byteArray(MapEntryData.BYTE_COUNT), Broadcast::bitmask,
                        Broadcast::new
                );

        @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

        public static void registerCommon() {
            PayloadTypeRegistry.playS2C().register(ID, CODEC);
        }

        @Environment(EnvType.CLIENT)
        public static void registerClient() {
            ClientPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                    ctx.client().execute(() -> handleOnClient(payload)));
        }

        @Environment(EnvType.CLIENT)
        private static void handleOnClient(Broadcast payload) {
            var client = MinecraftClient.getInstance();
            if (client.world == null) return;

            var mapState = MapIdentity.stateOf(payload.mapId().mapId(), client.world);
            if (mapState == null) return;

            var mapEntry = ClientMapCache.getOrCreate(payload.mapId());
            mapEntry.replaceBitmask(payload.bitmask());
        }
    }

    public static void sendTo(ServerPlayerEntity player, MapIdentity mapId) {
        sendTo(player, mapId, ExplorerMapSavedData.get(player.getServer()));
    }

    private static void sendTo(ServerPlayerEntity player, MapIdentity mapId, ExplorerMapSavedData savedData) {
        ServerPlayNetworking.send(player,
                new Broadcast(mapId, savedData.getPlayerDiscovery(mapId, player.getUuid())));
    }

    public static final int UPLOAD_INTERVAL_TICKS = 60;

    private static int ticksSinceLastUpload = 0;
    private static long lastUploadedGeneration = -1L;

    @Environment(EnvType.CLIENT)
    public static void resetUploadState() {
        ticksSinceLastUpload   = 0;
        lastUploadedGeneration = -1L;
    }

    @Environment(EnvType.CLIENT)
    public static boolean shouldUpload(long currentGeneration) {
        ticksSinceLastUpload++;
        if (ticksSinceLastUpload < UPLOAD_INTERVAL_TICKS) return false;
        if (currentGeneration == lastUploadedGeneration) {
            ticksSinceLastUpload = 0;
            return false;
        }
        ticksSinceLastUpload   = 0;
        lastUploadedGeneration = currentGeneration;
        return true;
    }
}
