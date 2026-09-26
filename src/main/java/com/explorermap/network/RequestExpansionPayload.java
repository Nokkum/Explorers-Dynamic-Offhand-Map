package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapConfig;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

public record RequestExpansionPayload(
        ExpansionRecord.Direction direction,
        boolean highDetail
) implements CustomPayload {

    public static final CustomPayload.Id<RequestExpansionPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("request_expansion"));

    public static final PacketCodec<PacketByteBuf, RequestExpansionPayload> CODEC =
            PacketCodec.tuple(
                    ExpansionRecord.Direction.PACKET_CODEC, RequestExpansionPayload::direction,
                    PacketCodecs.BOOL, RequestExpansionPayload::highDetail,
                    RequestExpansionPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleOnServer(player, payload));
        });
    }

    private static void handleOnServer(ServerPlayerEntity player, RequestExpansionPayload payload) {
        var offHand = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        var mapState = MapIdentity.stateOf(offHand, player.getServerWorld());
        if (mapState == null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        ServerWorld mapWorld = player.getServer().getWorld(mapState.dimension);
        if (mapWorld == null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        int rawMapId = MapIdentity.rawIdOf(offHand);
        if (rawMapId < 0) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }
        MapIdentity mapId = MapIdentity.of(mapState, rawMapId);

        var savedData = ExplorerMapSavedData.get(player.getServer());

        if (savedData.getOrCreate(mapId).hasExpansion(payload.direction())) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.ALREADY_EXPANDED);
            return;
        }

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

        MapIdentity newMapId = MapStateLocator.findOrCreate(mapWorld, savedData, adjX, adjZ, (byte) mapState.scale);
        if (newMapId == null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        consumeResources(player, payload.highDetail());

        savedData.addExpansion(mapId,
                new ExpansionRecord(payload.direction(), newMapId, payload.highDetail()));

        ServerPlayNetworking.send(player,
                new GrantExpansionPayload(payload.direction(), newMapId, payload.highDetail()));
    }

    private static ExpansionFailedPayload.Reason checkResources(ServerPlayerEntity player, boolean highDetail) {
        if (player.isCreative()) return null;

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

    private static void consumeResources(ServerPlayerEntity player, boolean highDetail) {
        if (player.isCreative()) return;

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
