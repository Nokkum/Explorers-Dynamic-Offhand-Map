package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.config.ExplorerMapConfig;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

/**
 * C2S packet: client requests a directional map expansion.
 *
 * Flow:
 *   1. Client: player clicks expand button in FullMapScreen.
 *   2. Client: sends RequestExpansionPayload(direction, highDetail) to server.
 *   3. Server: validates resources, creates/finds adjacent MapState,
 *              replies with GrantExpansionPayload(direction, realMapId)
 *              OR ExpansionFailedPayload(direction, reason) on failure.
 *   4. Client: receives GrantExpansionPayload, records ExpansionRecord on attachment,
 *              or receives ExpansionFailedPayload and shows a toast via ExpansionFeedback.
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

    // ── Registration ──────────────────────────────────────────────────────

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
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        var world    = player.getServerWorld();
        var mapState = FilledMapItem.getMapState(offHand, world);
        if (mapState == null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        if (ExplorerMapMod.getOrCreate(mapState).hasExpansion(payload.direction())) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.ALREADY_EXPANDED);
            return;
        }

        // Check resources BEFORE creating the map, so we can report the specific
        // failure reason without having created an orphaned MapState.
        ExpansionFailedPayload.Reason resourceFailure = checkResources(player, payload.highDetail());
        if (resourceFailure != null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(), resourceFailure);
            return;
        }

        int scale     = 1 << mapState.scale;
        int tileWidth = 128 * scale;
        int adjX = mapState.centerX, adjZ = mapState.centerZ;
        switch (payload.direction()) {
            case NORTH -> adjZ -= tileWidth;
            case SOUTH -> adjZ += tileWidth;
            case WEST  -> adjX -= tileWidth;
            case EAST  -> adjX += tileWidth;
        }

        int newMapId = MapStateLocator.findOrCreate(world, adjX, adjZ, (byte) mapState.scale);

        // Resources already validated; consume now that creation succeeded.
        consumeResources(player, payload.highDetail());

        ServerPlayNetworking.send(player,
                new GrantExpansionPayload(payload.direction(), newMapId, payload.highDetail()));
    }

    /**
     * Checks (without consuming) whether the player has enough resources.
     * Returns null if resources are sufficient, or the specific failure reason.
     */
    private static ExpansionFailedPayload.Reason checkResources(ServerPlayerEntity player,
                                                                  boolean highDetail) {
        var inv  = player.getInventory();
        int cost = ExplorerMapConfig.get().expansionPaperCost;

        int paperHeld = 0;
        for (int i = 0; i < inv.size(); i++) {
            var slot = inv.getStack(i);
            if (slot.isOf(Items.PAPER)) paperHeld += slot.getCount();
        }
        if (paperHeld < cost) return ExpansionFailedPayload.Reason.MISSING_PAPER;

        if (highDetail) {
            if (!inv.contains(Items.INK_SAC.getDefaultStack())
             || !inv.contains(Items.COMPASS.getDefaultStack())) {
                return ExpansionFailedPayload.Reason.MISSING_INK_OR_COMPASS;
            }
        }
        return null;
    }

    /** Consumes the configured resources. Assumes checkResources() already passed. */
    private static void consumeResources(ServerPlayerEntity player, boolean highDetail) {
        var inv  = player.getInventory();
        int cost = ExplorerMapConfig.get().expansionPaperCost;

        int remaining = cost;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            var slot = inv.getStack(i);
            if (slot.isOf(Items.PAPER)) {
                int take = Math.min(remaining, slot.getCount());
                slot.decrement(take);
                remaining -= take;
            }
        }

        if (highDetail) {
            removeOne(inv, Items.INK_SAC);
            removeOne(inv, Items.COMPASS);
        }
    }

    private static void removeOne(PlayerInventory inv, Item item) {
        for (int i = 0; i < inv.size(); i++) {
            var slot = inv.getStack(i);
            if (slot.isOf(item)) { slot.decrement(1); return; }
        }
    }
}
