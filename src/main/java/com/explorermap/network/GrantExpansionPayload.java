package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record GrantExpansionPayload(
        ExpansionRecord.Direction direction,
        MapIdentity sourceMap,
        MapIdentity targetMap,
        boolean highDetail
) implements CustomPayload {

    public static final CustomPayload.Id<GrantExpansionPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("grant_expansion"));

    public static final PacketCodec<PacketByteBuf, GrantExpansionPayload> CODEC =
            PacketCodec.tuple(
                    ExpansionRecord.Direction.PACKET_CODEC, GrantExpansionPayload::direction,
                    MapIdentity.PACKET_CODEC, GrantExpansionPayload::sourceMap,
                    MapIdentity.PACKET_CODEC, GrantExpansionPayload::targetMap,
                    PacketCodecs.BOOL,        GrantExpansionPayload::highDetail,
                    GrantExpansionPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, context) ->
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
