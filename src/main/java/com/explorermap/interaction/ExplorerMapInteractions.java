package com.explorermap.interaction;

import com.explorermap.ExplorerMapMod;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.Blocks;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

public final class ExplorerMapInteractions {

    private ExplorerMapInteractions() {}

    public static void registerCommon() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.getBlockState(hit.getBlockPos()).isOf(Blocks.CARTOGRAPHY_TABLE)) {
                return ActionResult.PASS;
            }
            if (!world.isClient && hand == Hand.OFF_HAND
                    && ExplorerMapMod.isFilledMap(player.getStackInHand(hand))) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && entity instanceof ItemFrameEntity frame
                    && hand == Hand.MAIN_HAND
                    && player.getStackInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getHeldItemStack())) {
                // A framed map is a display/inspection surface, not an editable map item.
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }

}