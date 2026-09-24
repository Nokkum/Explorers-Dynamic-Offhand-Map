package com.explorermap.interaction;

import com.explorermap.ExplorerMapClient;
import com.explorermap.ExplorerMapMod;
import com.explorermap.gui.FullMapScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

@Environment(EnvType.CLIENT)
public final class ExplorerMapClientInteractions {

    private ExplorerMapClientInteractions() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.getBlockState(hit.getBlockPos()).isOf(net.minecraft.block.Blocks.CARTOGRAPHY_TABLE)
                    && hand == Hand.OFF_HAND
                    && ExplorerMapMod.isFilledMap(player.getStackInHand(hand))) {
                ExplorerMapClient.openCartographyTable(hit.getBlockPos());
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient
                    && (hand == Hand.OFF_HAND || hand == Hand.MAIN_HAND)
                    && ExplorerMapMod.isFilledMap(player.getStackInHand(hand))) {
                MinecraftClient.getInstance().setScreen(new FullMapScreen(
                        com.explorermap.data.MapIdentity.rawIdOf(player.getStackInHand(hand))));
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient && entity instanceof ItemFrameEntity frame
                    && hand == Hand.MAIN_HAND
                    && player.getStackInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getHeldItemStack())) {
                MinecraftClient.getInstance().setScreen(new FullMapScreen(
                        com.explorermap.data.MapIdentity.rawIdOf(frame.getHeldItemStack())));
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }
}