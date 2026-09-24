package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

public record ShareDiscoveryPayload(int mapId, String targetName) implements CustomPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPayload.Id<ShareDiscoveryPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("share_discovery"));

    public static final PacketCodec<PacketByteBuf, ShareDiscoveryPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.VAR_INT, ShareDiscoveryPayload::mapId,
                    PacketCodecs.string(MAX_NAME_LENGTH), ShareDiscoveryPayload::targetName,
                    ShareDiscoveryPayload::new
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

    private static void handleOnServer(ServerPlayerEntity sender, ShareDiscoveryPayload payload) {
        var offHand = sender.getStackInHand(Hand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)
                || MapIdentity.rawIdOf(offHand) != payload.mapId()) return;

        ServerPlayerEntity target =
                sender.getServer().getPlayerManager().getPlayer(payload.targetName().trim());
        if (target == null || target == sender) return;

        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(sender.getServer());
        if (!savedData.sharePlayerDiscovery(payload.mapId(), sender.getUuid(), target.getUuid())) return;
        SyncDiscoveryPayload.sendTo(target, payload.mapId());
    }
}