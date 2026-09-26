package com.explorermap.interaction;

import com.explorermap.ExplorerMapClient;
import com.explorermap.ExplorerMapMod;
import com.explorermap.data.MapIdentity;
import com.explorermap.gui.FullMapScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;

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
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient
                    && (hand == Hand.OFF_HAND || hand == Hand.MAIN_HAND)
                    && ExplorerMapMod.isFilledMap(stack)) {
                MapIdentity mapId = MapIdentity.ofStack(stack, world);
                if (mapId != null) {
                    MinecraftClient.getInstance().setScreen(new FullMapScreen(mapId));
                    return TypedActionResult.success(stack, true);
                }
            }
            return TypedActionResult.pass(stack);
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient && entity instanceof ItemFrameEntity frame
                    && hand == Hand.MAIN_HAND
                    && player.getStackInHand(hand).isEmpty()
                    && ExplorerMapMod.isFilledMap(frame.getHeldItemStack())) {
                MapIdentity mapId = MapIdentity.ofStack(frame.getHeldItemStack(), world);
                if (mapId != null) {
                    MinecraftClient.getInstance().setScreen(new FullMapScreen(mapId));
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });
    }
}
