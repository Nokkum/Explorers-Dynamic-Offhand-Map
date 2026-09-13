package com.explorermap.mod.network;

import com.explorermap.mod.ExplorerMapMod;
import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.FilledMapItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/**
 * S2C: server grants an expansion, sending the real vanilla map ID back to client.
 * Now carries highDetail flag so the client can record it accurately.
 */
public record GrantExpansionPayload(
        ExpansionRecord.Direction direction,
        int mapId,
        boolean highDetail
) implements CustomPayload {

    public static final CustomPayload.Id<GrantExpansionPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("grant_expansion"));

    public static final PacketCodec<PacketByteBuf, GrantExpansionPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING.xmap(
                            ExpansionRecord.Direction::valueOf,
                            ExpansionRecord.Direction::name
                    ), GrantExpansionPayload::direction,
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
        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        var offHand = client.player.getStackInHand(net.minecraft.util.Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)) return;

        var mapState = FilledMapItem.getMapState(offHand, client.world);
        if (mapState == null) return;

        var attachment = ExplorerMapMod.getOrCreate(mapState);
        if (!attachment.hasExpansion(payload.direction())) {
            attachment.addExpansion(new ExpansionRecord(
                    payload.direction(), payload.mapId(), payload.highDetail()));
            ExplorerMapMod.LOGGER.info("[ExplorerMap] Expansion granted: {} → map #{} (HD:{})",
                    payload.direction(), payload.mapId(), payload.highDetail());
        }
    }
}
