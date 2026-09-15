package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.ModEntities;
import com.bedwarsrandomizer.block.ModBlocks;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BedwarsRandomizer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlocks.SELECTION_BLOCK_ENTITY.get(), SelectionBlockRenderer::new);
        event.registerBlockEntityRenderer(ModBlocks.TEAM_BED_ENTITY.get(), BedRenderer::new);
        event.registerEntityRenderer(ModEntities.FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
    }

    private ClientModEvents() {}
}
