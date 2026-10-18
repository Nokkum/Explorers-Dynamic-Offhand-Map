package com.explorermap.network;

import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.gui.ExpansionFeedback;
import com.explorermap.registry.ExplorerMapRegistry;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record ExpansionFailedPayload(ExpansionRecord.Direction direction, Reason reason)
        implements CustomPacketPayload {

    public enum Reason {
        MISSING_PAPER,
        MISSING_INK_OR_COMPASS,
        ALREADY_EXPANDED,
        NO_MAP_IN_OFFHAND,
        CYCLE_DETECTED;

        static final StreamCodec<FriendlyByteBuf, Reason> PACKET_CODEC = StreamCodec.ofMember(
                (Reason value, FriendlyByteBuf buf) -> buf.writeUtf(value.name()),
                (FriendlyByteBuf buf) -> {
                    String raw = buf.readUtf();
                    try {
                        return Reason.valueOf(raw);
                    } catch (IllegalArgumentException e) {
                        throw new DecoderException("Invalid ExpansionFailedPayload.Reason: " + raw);
                    }
                }
        );
    }

    public static final CustomPacketPayload.Type<ExpansionFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("expansion_failed"));

    public static final StreamCodec<FriendlyByteBuf, ExpansionFailedPayload> CODEC =
            StreamCodec.composite(
                    ExpansionRecord.Direction.PACKET_CODEC, ExpansionFailedPayload::direction,
                    Reason.PACKET_CODEC, ExpansionFailedPayload::reason,
                    ExpansionFailedPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, ctx) ->
                ctx.client().execute(() -> handleOnClient(payload)));
    }

    public static void sendTo(ServerPlayer player, ExpansionRecord.Direction dir, Reason reason) {
        ServerPlayNetworking.send(player, new ExpansionFailedPayload(dir, reason));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(ExpansionFailedPayload payload) {
        ExpansionFeedback.reportFailure(payload.direction(), payload.reason());
    }
}
