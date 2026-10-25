package com.explorermap.network;

import com.explorermap.data.ClientMapCache;
import com.explorermap.data.MapIdentity;
import com.explorermap.expansion.ExpansionRecord;
import com.explorermap.registry.ExplorerMapRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

public record SyncExpansionsPayload(MapIdentity root, List<Entry> entries) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = ExpansionFamilySync.MAX_FAMILY_MAPS;

    public record Entry(MapIdentity mapId, List<ExpansionRecord> expansions) {

        public static final StreamCodec<FriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        MapIdentity.PACKET_CODEC, Entry::mapId,
                        ByteBufCodecs.collection(ArrayList::new, ExpansionRecord.PACKET_CODEC,
                                ExpansionRecord.Direction.values().length), Entry::expansions,
                        Entry::new
                );
    }

    public static final CustomPacketPayload.Type<SyncExpansionsPayload> TYPE =
            new CustomPacketPayload.Type<>(ExplorerMapRegistry.id("sync_expansions"));

    public static final StreamCodec<FriendlyByteBuf, SyncExpansionsPayload> CODEC =
            StreamCodec.composite(
                    MapIdentity.PACKET_CODEC, SyncExpansionsPayload::root,
                    ByteBufCodecs.collection(ArrayList::new, Entry.CODEC, MAX_ENTRIES), SyncExpansionsPayload::entries,
                    SyncExpansionsPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
    }

    @Environment(EnvType.CLIENT)
    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) ->
                context.client().execute(() -> handleOnClient(payload)));
    }

    @Environment(EnvType.CLIENT)
    private static void handleOnClient(SyncExpansionsPayload payload) {
        var dimension = payload.root().dimension();
        for (Entry entry : payload.entries()) {
            if (!entry.mapId().dimension().equals(dimension)) continue;
            List<ExpansionRecord> accepted = new ArrayList<>(entry.expansions().size());
            for (ExpansionRecord record : entry.expansions()) {
                if (record.target().dimension().equals(dimension)) accepted.add(record);
            }
            ClientMapCache.getOrCreate(entry.mapId()).replaceExpansions(accepted);
        }
    }
}
