package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Hand;

public record GrantExpansionPayload(
        ExpansionRecord.Direction direction,
        int mapId,
        boolean highDetail
) implements CustomPayload {

    public static final CustomPayload.Id<GrantExpansionPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("grant_expansion"));

    public static final PacketCodec<PacketByteBuf, GrantExpansionPayload> CODEC =
            PacketCodec.tuple(
                    ExpansionRecord.Direction.PACKET_CODEC, GrantExpansionPayload::direction,
                    PacketCodecs.VAR_INT,  GrantExpansionPayload::mapId,
                    PacketCodecs.BOOL,     GrantExpansionPayload::highDetail,
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
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        var offHand = client.player.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        var mapState = MapIdentity.stateOf(offHand, client.world);
        if (mapState == null) return;

        int mapId = MapIdentity.rawIdOf(offHand);
        if (mapId < 0) return;

        var mapEntry = ClientMapCache.getOrCreate(mapId);
        if (!mapEntry.hasExpansion(payload.direction())) {
            mapEntry.addExpansion(new ExpansionRecord(
                    payload.direction(), payload.mapId(), payload.highDetail()));
            ExplorerMapMod.LOGGER.info("[ExplorerMap] Expansion granted: {} -> map #{} (HD:{})",
                    payload.direction(), payload.mapId(), payload.highDetail());
        }
    }
}
