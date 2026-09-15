package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Draws the selection outline for operators in creative, like the structure block's white box. */
public class SelectionBlockRenderer implements BlockEntityRenderer<SelectionBlockEntity> {
    private static final float[] NO_TEAM = {0.3F, 0.9F, 1.0F};

    public SelectionBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SelectionBlockEntity selection, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !(player.canUseGameMasterBlocks() || player.isSpectator()) || !selection.settings().showBox()) return;
        BoundingBox box = selection.worldBox();
        if (box == null) return;

        // team color, or cyan without a team
        DyeColor team = selection.settings().team();
        float[] rgb = team == null ? NO_TEAM : team.getTextureDiffuseColors();
        BlockPos origin = selection.getBlockPos();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(pose, lines,
                box.minX() - origin.getX(), box.minY() - origin.getY(), box.minZ() - origin.getZ(),
                box.maxX() + 1 - origin.getX(), box.maxY() + 1 - origin.getY(), box.maxZ() + 1 - origin.getZ(),
                rgb[0], rgb[1], rgb[2], 1.0F, rgb[0], rgb[1], rgb[2]);
    }

    @Override
    public boolean shouldRenderOffScreen(SelectionBlockEntity selection) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 128;
    }
}
