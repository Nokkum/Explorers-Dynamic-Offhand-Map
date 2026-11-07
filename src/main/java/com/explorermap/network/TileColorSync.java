package com.explorermap.network;

import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ClientTileData;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Expansion tiles are maps nobody holds, so vanilla neither paints them nor sends them to clients.
 * This keeps the tiles near the player painted and sends their colors to the player's client.
 */
public final class TileColorSync {

    private static final int PAINT_INTERVAL_TICKS = 4;
    private static final int SYNC_INTERVAL_TICKS = 20;
    private static final int FAMILY_REFRESH_TICKS = 40;
    private static final int MAX_SENDS_PER_PASS = 3;
    /** Vanilla paints up to 128 blocks around the viewer, so a tile is only worth painting within that. */
    private static final int PAINT_MARGIN_BLOCKS = 128;

    private static final class PlayerState {
        List<MapIdentity> tiles = List.of();
        MapIdentity root;
        long familyTick = Long.MIN_VALUE;
        final Map<MapIdentity, Integer> sentHashes = new HashMap<>();
    }

    private static final Map<UUID, PlayerState> STATES = new HashMap<>();
    private static long tickCounter;

    private TileColorSync() {}

    public record Payload(MapIdentity mapId, byte[] colors) implements CustomPacketPayload {

        public static final int COLOR_BYTES = 128 * 128;

        public static final CustomPacketPayload.Type<Payload> TYPE =
                new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("tile_colors"));

        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Payload::mapId,
                        ByteBufCodecs.byteArray(COLOR_BYTES), Payload::colors,
                        Payload::new
                );

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE, Payload.CODEC);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(Payload.TYPE, (payload, ctx) ->
                ctx.client().execute(() -> ClientTileData.put(payload.mapId(), payload.colors())));
    }

    public static void forget(UUID playerId) {
        STATES.remove(playerId);
    }

    public static void tick(MinecraftServer server) {
        tickCounter++;
        boolean paint = tickCounter % PAINT_INTERVAL_TICKS == 0;
        boolean sync = tickCounter % SYNC_INTERVAL_TICKS == 0;
        if (!paint && !sync) return;

        var savedData = ExplorerMapSavedData.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
            if (!(offHand.getItem() instanceof MapItem mapItem)) continue;
            MapIdentity root = MapIdentity.ofStack(offHand, player.level());
            if (root == null) continue;

            PlayerState state = STATES.computeIfAbsent(player.getUUID(), id -> new PlayerState());
            if (!root.equals(state.root) || tickCounter - state.familyTick >= FAMILY_REFRESH_TICKS) {
                List<MapIdentity> tiles = new ArrayList<>(ExpansionFamilySync.familyOf(savedData, root).maps());
                tiles.remove(root); // the held map is painted and sent by vanilla
                state.tiles = tiles;
                state.root = root;
                state.familyTick = tickCounter;
            }
            if (state.tiles.isEmpty()) continue;

            if (paint) paintNearbyTiles(server, player, mapItem, state);
            if (sync) sendChangedTiles(server, player, state);
        }
    }

    private static void paintNearbyTiles(MinecraftServer server, ServerPlayer player, MapItem mapItem, PlayerState state) {
        for (MapIdentity tile : state.tiles) {
            MapItemSavedData data = MapIdentity.resolveAndVerify(server, tile);
            if (data == null || !isNear(player, data)) continue;
            TilePainter.paint(mapItem, player, data);
        }
    }

    private static void sendChangedTiles(MinecraftServer server, ServerPlayer player, PlayerState state) {
        List<MapIdentity> byDistance = new ArrayList<>(state.tiles);
        byDistance.sort((a, b) -> Double.compare(distanceSqr(server, player, a), distanceSqr(server, player, b)));

        int sent = 0;
        for (MapIdentity tile : byDistance) {
            if (sent >= MAX_SENDS_PER_PASS) break;
            MapItemSavedData data = MapIdentity.resolveAndVerify(server, tile);
            if (data == null || data.colors.length != Payload.COLOR_BYTES) continue;

            Integer previous = state.sentHashes.get(tile);
            // Far tiles only ever change when somebody holds them, so they are sent once and not re-hashed.
            if (previous != null && !isNear(player, data)) continue;
            int hash = Arrays.hashCode(data.colors);
            if (previous != null && previous == hash) continue;

            state.sentHashes.put(tile, hash);
            ServerPlayNetworking.send(player, new Payload(tile, data.colors.clone()));
            sent++;
        }
    }

    private static boolean isNear(ServerPlayer player, MapItemSavedData data) {
        if (!player.level().dimension().equals(data.dimension)) return false;
        int half = 64 * (1 << data.scale) + PAINT_MARGIN_BLOCKS;
        return Math.abs(player.getX() - data.centerX) <= half && Math.abs(player.getZ() - data.centerZ) <= half;
    }

    private static double distanceSqr(MinecraftServer server, ServerPlayer player, MapIdentity tile) {
        MapItemSavedData data = MapIdentity.resolveAndVerify(server, tile);
        if (data == null) return Double.MAX_VALUE;
        double dx = player.getX() - data.centerX;
        double dz = player.getZ() - data.centerZ;
        return dx * dx + dz * dz;
    }
}
