package com.explorermap.interaction;

import com.explorermap.ExplorerMapMod;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;

public final class ExplorerMapInteractions {

    private ExplorerMapInteractions() {}

    public static void registerCommon() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.getBlockState(hit.getBlockPos()).is(Blocks.CARTOGRAPHY_TABLE)) {
                return InteractionResult.PASS;
            }
            if (!world.isClientSide() && hand == InteractionHand.OFF_HAND
                    && ExplorerMapMod.isFilledMap(player.getItemInHand(hand))) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClientSide() && entity instanceof ItemFrame frame
                    && hand == InteractionHand.MAIN_HAND
                    && player.getItemInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getItem())) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

}