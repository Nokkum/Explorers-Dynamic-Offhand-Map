package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.config.ExplorerMapServerConfig;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record RequestExpansionPayload(
        ExpansionRecord.Direction direction,
        boolean highDetail,
        BlockPos tablePos
) implements CustomPacketPayload {

    private static final long COOLDOWN_NANOS = 1_000_000_000L;
    private static final Map<UUID, Long> LAST_REQUEST = new ConcurrentHashMap<>();

    public static final CustomPacketPayload.Type<RequestExpansionPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("request_expansion"));

    public static final StreamCodec<FriendlyByteBuf, RequestExpansionPayload> CODEC =
            StreamCodec.composite(
                    ExpansionRecord.Direction.PACKET_CODEC, RequestExpansionPayload::direction,
                    ByteBufCodecs.BOOL, RequestExpansionPayload::highDetail,
                    BlockPos.STREAM_CODEC, RequestExpansionPayload::tablePos,
                    RequestExpansionPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                handleOnServer(context.player(), payload));
    }

    public static void forgetPlayer(UUID playerId) {
        LAST_REQUEST.remove(playerId);
    }

    private static void handleOnServer(ServerPlayer player, RequestExpansionPayload payload) {
        long now = System.nanoTime();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_NANOS) {
            ExpansionFailedPayload.sendTo(player, payload.direction(), ExpansionFailedPayload.Reason.ON_COOLDOWN);
            return;
        }
        LAST_REQUEST.put(player.getUUID(), now);

        // Distance first: the position is client-supplied and must not make the server look at far-away chunks.
        BlockPos table = payload.tablePos();
        if (player.distanceToSqr(table.getX() + 0.5, table.getY() + 0.5, table.getZ() + 0.5)
                > ExplorerMapMod.TABLE_REACH_SQR
                || !player.level().getBlockState(table).is(Blocks.CARTOGRAPHY_TABLE)) {
            ExpansionFailedPayload.sendTo(player, payload.direction(), ExpansionFailedPayload.Reason.NO_TABLE);
            return;
        }

        var offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        var mapState = MapIdentity.stateOf(offHand, player.level());
        if (mapState == null) {
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        ServerLevel mapWorld = player.level().getServer().getLevel(mapState.dimension);
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

        var savedData = ExplorerMapSavedData.get(player.level().getServer());

        if (ExpansionFamilySync.familyOf(savedData, mapId).maps().size() >= ExpansionFamilySync.MAX_FAMILY_MAPS) {
            ExpansionFailedPayload.sendTo(player, payload.direction(), ExpansionFailedPayload.Reason.FAMILY_FULL);
            return;
        }

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

        if (!newMapId.dimension().equals(mapId.dimension())) {
            RejectLog.warn(player, "expansion target {} is not in the same dimension as source {}",
                    newMapId, mapId);
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.NO_MAP_IN_OFFHAND);
            return;
        }

        if (savedData.isAccessibleFrom(newMapId, mapId)) {
            RejectLog.warn(player, "expansion {} -> {} would create a cycle", mapId, newMapId);
            ExpansionFailedPayload.sendTo(player, payload.direction(),
                    ExpansionFailedPayload.Reason.CYCLE_DETECTED);
            return;
        }

        consumeResources(player, payload.highDetail());

        savedData.addExpansion(mapId,
                new ExpansionRecord(payload.direction(), newMapId, payload.highDetail()));

        ServerPlayNetworking.send(player,
                new GrantExpansionPayload(payload.direction(), mapId, newMapId, payload.highDetail()));

        ExpansionFamilySync.onExpansionChanged(player.level().getServer(), mapId);
    }

    private static ExpansionFailedPayload.Reason checkResources(ServerPlayer player, boolean highDetail) {
        if (player.isCreative()) return null;

        var inv  = player.getInventory();
        int cost = ExplorerMapServerConfig.expansionPaperCost();

        int paperHeld = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var slot = inv.getItem(i);
            if (slot.is(Items.PAPER)) paperHeld += slot.getCount();
        }
        if (paperHeld < cost) return ExpansionFailedPayload.Reason.MISSING_PAPER;

        if (highDetail) {
            if (!hasItem(inv, Items.INK_SAC) || !hasItem(inv, Items.COMPASS)) {
                return ExpansionFailedPayload.Reason.MISSING_INK_OR_COMPASS;
            }
        }
        return null;
    }

    private static void consumeResources(ServerPlayer player, boolean highDetail) {
        if (player.isCreative()) return;

        var inv  = player.getInventory();
        int cost = ExplorerMapServerConfig.expansionPaperCost();

        int remaining = cost;
        for (int i = 0; i < inv.getContainerSize() && remaining > 0; i++) {
            var slot = inv.getItem(i);
            if (slot.is(Items.PAPER)) {
                int take = Math.min(remaining, slot.getCount());
                slot.shrink(take);
                remaining -= take;
            }
        }

        if (highDetail) {
            removeOne(inv, Items.INK_SAC);
            removeOne(inv, Items.COMPASS);
        }
    }

    private static boolean hasItem(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return true;
        }
        return false;
    }

    private static void removeOne(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var slot = inv.getItem(i);
            if (slot.is(item)) { slot.shrink(1); return; }
        }
    }
}
