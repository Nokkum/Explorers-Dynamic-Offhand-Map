package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GrantExpansionPayload(
        ExpansionRecord.Direction direction,
        MapIdentity sourceMap,
        MapIdentity targetMap,
        boolean highDetail
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<GrantExpansionPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("grant_expansion"));

    public static final StreamCodec<FriendlyByteBuf, GrantExpansionPayload> CODEC =
            StreamCodec.composite(
                    ExpansionRecord.Direction.PACKET_CODEC, GrantExpansionPayload::direction,
                    MapIdentity.PACKET_CODEC, GrantExpansionPayload::sourceMap,
                    MapIdentity.PACKET_CODEC, GrantExpansionPayload::targetMap,
                    ByteBufCodecs.BOOL,        GrantExpansionPayload::highDetail,
                    GrantExpansionPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(GrantExpansionPayload payload) {
        if (!payload.sourceMap().dimension().equals(payload.targetMap().dimension())) {
            ExplorerMapMod.LOGGER.warn(
                    "[ExplorerMap] Ignoring a cross-dimension expansion grant: {} -> {}",
                    payload.sourceMap().asKey(), payload.targetMap().asKey());
            return;
        }

        var mapEntry = ClientMapCache.getOrCreate(payload.sourceMap());
        if (!mapEntry.hasExpansion(payload.direction())) {
            mapEntry.addExpansion(new ExpansionRecord(
                    payload.direction(), payload.targetMap(), payload.highDetail()));
            ExplorerMapMod.LOGGER.info("[ExplorerMap] Expansion granted: {} {} -> map {} (HD:{})",
                    payload.sourceMap().asKey(), payload.direction(),
                    payload.targetMap().asKey(), payload.highDetail());
        }
    }
}
