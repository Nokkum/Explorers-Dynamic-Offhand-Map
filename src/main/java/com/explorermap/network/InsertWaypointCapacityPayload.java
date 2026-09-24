package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

public record InsertWaypointCapacityPayload(BlockPos tablePos) implements CustomPayload {

    public static final CustomPayload.Id<InsertWaypointCapacityPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("insert_waypoint_compass"));

    public static final PacketCodec<PacketByteBuf, InsertWaypointCapacityPayload> CODEC =
            PacketCodec.tuple(
                    BlockPos.PACKET_CODEC, InsertWaypointCapacityPayload::tablePos,
                    InsertWaypointCapacityPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ID, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
                context.server().execute(() -> handleOnServer(context.player(), payload)));
    }

    private static void handleOnServer(ServerPlayerEntity player,
                                       InsertWaypointCapacityPayload payload) {
        if (!player.getServerWorld().getBlockState(payload.tablePos()).isOf(Blocks.CARTOGRAPHY_TABLE)
                || player.squaredDistanceTo(payload.tablePos().toCenterPos()) > 64.0) {
            return;
        }

        var map = player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(map)) return;
        int mapId = MapIdentity.rawIdOf(map);
        if (mapId < 0) return;

        int compassSlot = -1;
        for (int i = 0; i < player.getInventory().size(); i++) {
            if (player.getInventory().getStack(i).isOf(Items.COMPASS)) {
                compassSlot = i;
                break;
            }
        }
        if (compassSlot < 0) return;

        player.getInventory().getStack(compassSlot).decrement(1);
        ExplorerMapSavedData.get(player.getServer()).addWaypointCapacity(mapId);
        SyncWaypointsPayload.sendTo(player, mapId);
    }
}