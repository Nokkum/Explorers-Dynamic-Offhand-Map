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

import java.util.Set;
import java.util.UUID;

public final class SyncDiscoveryPayload {

    public static final int UPLOAD_INTERVAL_TICKS = 60;
    public static final int UPLOADS_PER_WINDOW = 8;
    public static final long UPLOAD_WINDOW_MILLIS = UPLOAD_INTERVAL_TICKS * 50L;

    private static final DiscoveryUploadLimiter LIMITER = new DiscoveryUploadLimiter(
            System::nanoTime, UPLOADS_PER_WINDOW, UPLOAD_WINDOW_MILLIS, MapEntryData.BYTE_COUNT);

    private static final DiscoveryUploadTracker<MapIdentity> UPLOAD_TRACKER =
            new DiscoveryUploadTracker<>(System::nanoTime);

    private static int ticksSinceLastUpload = 0;

    public record Upload(MapIdentity mapId, long generation, byte[] bitmask) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Upload> TYPE =
                new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("upload_discovery"));

        public static final StreamCodec<FriendlyByteBuf, Upload> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Upload::mapId,
                        ByteBufCodecs.VAR_LONG, Upload::generation,
                        ByteBufCodecs.byteArray(MapEntryData.BYTE_COUNT), Upload::bitmask,
                        Upload::new
                );

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void register() {
            PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
            ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) -> {
                ServerPlayer sender = ctx.player();
                if (!LIMITER.tryAcquire(sender.getUUID(), payload.bitmask().length)) return;
                ctx.server().execute(() -> handleOnServer(ctx.server(), sender, payload));
            });
        }

        private static void handleOnServer(MinecraftServer server, ServerPlayer sender, Upload payload) {
            if (payload.generation() < 0L || payload.bitmask().length != MapEntryData.BYTE_COUNT) {
                penalize(sender);
                return;
            }

            var offHand = sender.getItemInHand(InteractionHand.OFF_HAND);
            if (!ExplorerMapMod.isFilledMap(offHand)) {
                penalize(sender);
                return;
            }

            MapIdentity heldRoot = MapIdentity.ofStack(offHand, sender.level());
            var savedData = ExplorerMapSavedData.get(server);

            var mapState = MapIdentity.resolveAuthorized(server, savedData, heldRoot, payload.mapId());
            if (mapState == null) {
                ExplorerMapMod.LOGGER.warn(
                        "[ExplorerMap] Rejected discovery upload for map {} from {} - invalid or not accessible from the held map",
                        payload.mapId().asKey(), sender.getName().getString());
                penalize(sender);
                return;
            }

            Set<Integer> newlyDiscovered = savedData.mergePlayerBitmask(
                    payload.mapId(), sender.getUUID(), payload.bitmask());
            ServerPlayNetworking.send(sender, new Ack(payload.mapId(), payload.generation()));
            if (newlyDiscovered.isEmpty()) return;

            ExplorerMapMod.LOGGER.debug("[ExplorerMap] Merged discovery for map {} from {}",
                    payload.mapId().asKey(), sender.getName().getString());

            StructureWaypointDetector.checkAndPlace(
                    server, sender, payload.mapId(), mapState, savedData, newlyDiscovered);

            sendTo(sender, payload.mapId(), savedData);
        }
    }

    public record Ack(MapIdentity mapId, long generation) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<Ack> TYPE =
                new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("ack_discovery"));

        public static final StreamCodec<FriendlyByteBuf, Ack> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Ack::mapId,
                        ByteBufCodecs.VAR_LONG, Ack::generation,
                        Ack::new
                );

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void registerCommon() {
            PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
        }

        @Environment(EnvType.CLIENT)
        public static void registerClient() {
            ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) ->
                    ctx.client().execute(() ->
                            SyncDiscoveryPayload.acknowledge(payload.mapId(), payload.generation())));
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
            if (SyncDiscoveryPayload.hasUnacknowledged(payload.mapId(), mapEntry.getDiscoveryGeneration())) {
                mapEntry.mergeBitmask(payload.bitmask());
                return;
            }
            mapEntry.replaceBitmask(payload.bitmask());
            SyncDiscoveryPayload.acknowledge(payload.mapId(), mapEntry.getDiscoveryGeneration());
        }
    }

    public static void sendTo(ServerPlayer player, MapIdentity mapId) {
        sendTo(player, mapId, ExplorerMapSavedData.get(player.level().getServer()));
    }

    private static void sendTo(ServerPlayer player, MapIdentity mapId, ExplorerMapSavedData savedData) {
        ServerPlayNetworking.send(player,
                new Broadcast(mapId, savedData.getPlayerDiscovery(mapId, player.getUUID())));
    }

    private static void penalize(ServerPlayer sender) {
        if (LIMITER.recordInvalid(sender.getUUID())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Suspending discovery uploads from {} for {} seconds after repeated invalid packets",
                    sender.getName().getString(), DiscoveryUploadLimiter.COOLDOWN_MILLIS / 1000L);
        }
    }

    public static void forgetPlayer(UUID playerId) {
        LIMITER.forget(playerId);
    }

    @Environment(EnvType.CLIENT)
    public static void resetUploadState() {
        ticksSinceLastUpload = 0;
        UPLOAD_TRACKER.clear();
    }

    @Environment(EnvType.CLIENT)
    public static boolean uploadWindowOpen() {
        ticksSinceLastUpload++;
        if (ticksSinceLastUpload < UPLOAD_INTERVAL_TICKS) return false;
        ticksSinceLastUpload = 0;
        return true;
    }

    @Environment(EnvType.CLIENT)
    public static boolean needsUpload(MapIdentity mapId, long currentGeneration) {
        return UPLOAD_TRACKER.needsUpload(mapId, currentGeneration);
    }

    @Environment(EnvType.CLIENT)
    public static void markUploaded(MapIdentity mapId, long generation) {
        UPLOAD_TRACKER.markSent(mapId, generation);
    }

    @Environment(EnvType.CLIENT)
    public static void acknowledge(MapIdentity mapId, long generation) {
        UPLOAD_TRACKER.acknowledge(mapId, generation);
    }

    @Environment(EnvType.CLIENT)
    public static boolean hasUnacknowledged(MapIdentity mapId, long currentGeneration) {
        return UPLOAD_TRACKER.hasUnacknowledged(mapId, currentGeneration);
    }
}
