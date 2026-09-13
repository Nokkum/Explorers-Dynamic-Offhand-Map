package com.explorermap.mod.network;

import com.explorermap.mod.expansion.ExpansionRecord;
import com.explorermap.mod.gui.ExpansionFeedback;
import com.explorermap.mod.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * S2C: server notifies the client that a RequestExpansionPayload failed,
 * so the UI can show clear feedback instead of the button silently doing nothing.
 *
 * Reasons are a small closed set (not free text) to keep the packet tiny and
 * so the client can localize the message via translation keys.
 */
public record ExpansionFailedPayload(ExpansionRecord.Direction direction, Reason reason)
        implements CustomPayload {

    public enum Reason {
        MISSING_PAPER,
        MISSING_INK_OR_COMPASS,
        ALREADY_EXPANDED,
        NO_MAP_IN_OFFHAND;

        static final PacketCodec<PacketByteBuf, Reason> CODEC =
                PacketCodecs.STRING.xmap(Reason::valueOf, Reason::name);
    }

    public static final CustomPayload.Id<ExpansionFailedPayload> ID =
            new CustomPayload.Id<>(ExplorerMapRegistry.id("expansion_failed"));

    public static final PacketCodec<PacketByteBuf, ExpansionFailedPayload> CODEC =
            PacketCodec.tuple(
                    PacketCodecs.STRING.xmap(
                            ExpansionRecord.Direction::valueOf,
                            ExpansionRecord.Direction::name
                    ), ExpansionFailedPayload::direction,
                    Reason.CODEC, ExpansionFailedPayload::reason,
                    ExpansionFailedPayload::new
            );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }

    // ── Registration ──────────────────────────────────────────────────────

    /** Call from ExplorerMapMod.onInitialize() (common side, S2C payload type). */
    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(ID, CODEC);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(ID, (payload, ctx) ->
                ctx.client().execute(() -> handleOnClient(payload)));
    }

    /** Convenience for server code to send a failure notice. */
    public static void sendTo(ServerPlayerEntity player, ExpansionRecord.Direction dir, Reason reason) {
        ServerPlayNetworking.send(player, new ExpansionFailedPayload(dir, reason));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(ExpansionFailedPayload payload) {
        // Store the failure so FullMapScreen can render a toast/flash on its next frame.
        ExpansionFeedback.reportFailure(payload.direction(), payload.reason());
    }
}
