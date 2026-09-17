package com.explorermap.mod.network;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.gui.ExpansionFeedback;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

public record ExpansionFailedPayload(ExpansionRecord.Direction direction, Reason reason)
        implements CustomPayload {

    public enum Reason {
        MISSING_PAPER,
        MISSING_INK_OR_COMPASS,
        ALREADY_EXPANDED,
        NO_MAP_IN_OFFHAND;

        static final PacketCodec<PacketByteBuf, Reason> PACKET_CODEC = PacketCodec.of(
                (Reason value, PacketByteBuf buf) -> buf.writeString(value.name()),
                (PacketByteBuf buf) -> {
                    String raw = buf.readString();
                    try {
                        return Reason.valueOf(raw);
                    } catch (IllegalArgumentException e) {
                        throw new DecoderException("Invalid ExpansionFailedPayload.Reason: " + raw);
                    }
                }
        );
    }

    public static final CustomPayload.Id<ExpansionFailedPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("expansion_failed"));

    public static final PacketCodec<PacketByteBuf, ExpansionFailedPayload> CODEC =
            PacketCodec.tuple(
                    ExpansionRecord.Direction.PACKET_CODEC, ExpansionFailedPayload::direction,
                    Reason.PACKET_CODEC, ExpansionFailedPayload::reason,
                    ExpansionFailedPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                ctx.client().execute(() -> handleOnClient(payload)));
    }

    public static void sendTo(ServerPlayerEntity player, ExpansionRecord.Direction dir, Reason reason) {
        ServerPlayNetworking.send(player, new ExpansionFailedPayload(dir, reason));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(ExpansionFailedPayload payload) {
        ExpansionFeedback.reportFailure(payload.direction(), payload.reason());
    }
}
