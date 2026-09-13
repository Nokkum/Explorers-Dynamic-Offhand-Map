package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * C2S packet: client requests a directional map expansion.
 *
 * Flow:
 *   1. Client: player clicks expand button in FullMapScreen.
 *   2. Client: sends RequestExpansionPayload(direction, highDetail) to server.
 *   3. Server: validates resources, creates/finds adjacent MapState,
 *              replies with GrantExpansionPayload(direction, realMapId).
 *   4. Client: receives GrantExpansionPayload, records ExpansionRecord on attachment.
 *
 * This packet handles step 2. See GrantExpansionPayload for step 3→4.
 */
public record RequestExpansionPayload(
        ExpansionRecord.Direction direction,
        boolean highDetail
) implements CustomPayload {

    public static final CustomPayload.Id<RequestExpansionPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("request_expansion"));

    public static final PacketCodec<PacketByteBuf, RequestExpansionPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING.xmap(
                            ExpansionRecord.Direction::valueOf,
                            ExpansionRecord.Direction::name
                    ), RequestExpansionPayload::direction,
                    PacketCodecs.BOOL, RequestExpansionPayload::highDetail,
                    RequestExpansionPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    // ── Registration (call from ExplorerMapMod.onInitialize) ──────────────

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleOnServer(player, payload));
        });
    }

    // ── Server handler ────────────────────────────────────────────────────

    private static void handleOnServer(ServerPlayerEntity player,
                                        RequestExpansionPayload payload) {
        // 1. Validate player has the map in their off-hand
        var offHand = player.getStackInHand(net.minecraft.util.Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        // 2. Resolve current MapState on the server
        var world    = player.getServerWorld();
        var mapState = net.minecraft.item.FilledMapItem.getMapState(offHand, world);
        if (mapState == null) return;

        // 3. Guard: already expanded this direction?
        if (ExplorerMapMod.getOrCreate(mapState).hasExpansion(payload.direction())) return;

        // 4. Compute adjacent tile center
        int scale      = 1 << mapState.scale;
        int tileWidth  = 128 * scale;
        int adjX = mapState.centerX, adjZ = mapState.centerZ;
        switch (payload.direction()) {
            case NORTH -> adjZ -= tileWidth;
            case SOUTH -> adjZ += tileWidth;
            case WEST  -> adjX -= tileWidth;
            case EAST  -> adjX += tileWidth;
        }

        // 4. Find or create a vanilla MapState at that position (real search via MapStateLocator)
        int newMapId = MapStateLocator.findOrCreate(
                player.getServerWorld(), adjX, adjZ, (byte) mapState.scale);

        // 5. Consume resources (authoritative — after we know creation will succeed)
        if (!consumeServerSide(player, payload.highDetail())) return;

        // 6. Reply to client with real map ID and highDetail flag
        ServerPlayNetworking.send(player,
                new GrantExpansionPayload(payload.direction(), newMapId, payload.highDetail()));
    }

    private static boolean consumeServerSide(ServerPlayerEntity player, boolean highDetail) {
        var inv = player.getInventory();
        if (!inv.contains(net.minecraft.item.Items.PAPER.getDefaultStack())) return false;
        if (highDetail) {
            if (!inv.contains(net.minecraft.item.Items.INK_SAC.getDefaultStack())) return false;
            if (!inv.contains(net.minecraft.item.Items.COMPASS.getDefaultStack())) return false;
        }
        removeOne(inv, net.minecraft.item.Items.PAPER);
        if (highDetail) {
            removeOne(inv, net.minecraft.item.Items.INK_SAC);
            removeOne(inv, net.minecraft.item.Items.COMPASS);
        }
        return true;
    }

    private static void removeOne(net.minecraft.entity.player.PlayerInventory inv,
                                   net.minecraft.item.Item item) {
        for (int i = 0; i < inv.size(); i++) {
            var slot = inv.getStack(i);
            if (slot.isOf(item)) { slot.decrement(1); return; }
        }
    }
}
