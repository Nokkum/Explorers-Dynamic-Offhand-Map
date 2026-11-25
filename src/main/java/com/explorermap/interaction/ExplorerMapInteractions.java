package com.explorermap.interaction;

import com.explorermap.ExplorerMapMod;
import com.explorermap.banner.BannerMapActions;
import com.explorermap.data.MapIdentity;
import com.explorermap.network.ExpansionFamilySync;
import com.explorermap.network.SyncDiscoveryPayload;
import com.explorermap.network.SyncWaypointsPayload;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

public final class ExplorerMapInteractions {

    private ExplorerMapInteractions() {}

    public static void registerCommon() {
        // A filled map used on a banner adds the banner's name to the map, or removes it again, as in vanilla.
        // The click is claimed here, so vanilla does not also add its own marker with the banner's icon.
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            ItemStack held = player.getItemInHand(hand);
            if (!ExplorerMapMod.isFilledMap(held)
                    || !world.getBlockState(hit.getBlockPos()).is(BlockTags.BANNERS)) {
                return InteractionResult.PASS;
            }
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                BannerMapActions.toggle(serverPlayer, held, hit.getBlockPos());
            }
            return InteractionResult.SUCCESS;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.getBlockState(hit.getBlockPos()).is(Blocks.CARTOGRAPHY_TABLE)) {
                return InteractionResult.PASS;
            }
            // The main hand is handled first and would open the vanilla table, so a map in the off-hand
            // claims the interaction whichever hand it arrives on.
            if (!world.isClientSide()
                    && ExplorerMapMod.isFilledMap(player.getItemInHand(InteractionHand.OFF_HAND))) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClientSide() && entity instanceof ItemFrame frame
                    && hand == InteractionHand.MAIN_HAND
                    && player.getItemInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getItem())) {
                // The client opens the map screen; give it this player's discovery and waypoints for it.
                if (player instanceof ServerPlayer serverPlayer) syncFramedMap(serverPlayer, frame.getItem());
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    private static void syncFramedMap(ServerPlayer player, ItemStack stack) {
        MapIdentity mapId = MapIdentity.ofStack(stack, player.level());
        if (mapId == null) return;
        SyncDiscoveryPayload.sendTo(player, mapId);
        SyncWaypointsPayload.sendTo(player, mapId);
        ExpansionFamilySync.sendTo(player.level().getServer(), player, mapId);
    }
}