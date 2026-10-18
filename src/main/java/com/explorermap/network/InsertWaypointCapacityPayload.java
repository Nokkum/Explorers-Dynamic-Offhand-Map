package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Items;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;

public record InsertWaypointCapacityPayload(BlockPos tablePos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<InsertWaypointCapacityPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("insert_waypoint_compass"));

    public static final StreamCodec<FriendlyByteBuf, InsertWaypointCapacityPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, InsertWaypointCapacityPayload::tablePos,
                    InsertWaypointCapacityPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayer player,
                                       InsertWaypointCapacityPayload payload) {
        if (!player.level().getBlockState(payload.tablePos()).is(Blocks.CARTOGRAPHY_TABLE)
                || player.distanceToSqr(payload.tablePos().getX() + 0.5, payload.tablePos().getY() + 0.5,
                        payload.tablePos().getZ() + 0.5) > 64.0) {
            return;
        }

        var map = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(map)) return;
        MapIdentity mapId = MapIdentity.ofStack(map, player.level());
        if (mapId == null) return;

        int compassSlot = -1;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(Items.COMPASS)) {
                compassSlot = i;
                break;
            }
        }
        if (compassSlot < 0) return;

        player.getInventory().getItem(compassSlot).shrink(1);
        ExplorerMapSavedData.get(player.level().getServer()).addWaypointCapacity(mapId);
        SyncWaypointsPayload.sendTo(player, mapId);
    }
}