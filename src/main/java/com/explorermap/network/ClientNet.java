package com.explorermap.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server sends, skipped when the server doesn't have this mod's channels. */
@Environment(EnvType.CLIENT)
public final class ClientNet {

    private ClientNet() {}

    public static void send(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }
}
