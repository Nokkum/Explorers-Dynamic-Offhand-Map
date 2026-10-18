package com.explorermap.network;

import com.explorermap.ExplorerMapMod;
import com.explorermap.data.ExplorerMapSavedData;
import com.explorermap.data.MapIdentity;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

public record ShareDiscoveryPayload(MapIdentity mapId, String targetName) implements CustomPacketPayload {

    private static final int MAX_NAME_LENGTH = 64;

    public static final CustomPacketPayload.Type<ShareDiscoveryPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("share_discovery"));

    public static final StreamCodec<FriendlyByteBuf, ShareDiscoveryPayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC, ShareDiscoveryPayload::mapId,
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), ShareDiscoveryPayload::targetName,
                    ShareDiscoveryPayload::new
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

    private static void handleOnServer(ServerPlayer sender, ShareDiscoveryPayload payload) {
        var offHand = sender.getItemInHand(InteractionHand.OFF_HAND);
        if (!ExplorerMapMod.isFilledMap(offHand)
                || MapIdentity.resolveAndVerify(sender.level().getServer(), payload.mapId()) == null
                || !payload.mapId().equals(MapIdentity.ofStack(offHand, sender.level()))) return;

        ServerPlayer target =
                sender.level().getServer().getPlayerList().getPlayerByName(payload.targetName().trim());
        if (target == null || target == sender) return;

        ExplorerMapSavedData savedData = ExplorerMapSavedData.get(sender.level().getServer());
        if (!savedData.sharePlayerDiscovery(payload.mapId(), sender.getUUID(), target.getUUID())) return;
        SyncDiscoveryPayload.sendTo(target, payload.mapId());
    }
}
