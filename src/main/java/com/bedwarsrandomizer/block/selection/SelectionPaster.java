package com.bedwarsrandomizer.block.selection;

import com.bedwarsrandomizer.block.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Save: copy one selection and paste it, turned to each block's facing and recolored to each block's team, into every
 * other selection block's area of the same size.
 */
public final class SelectionPaster {

    public static void saveAndPaste(ServerPlayer player, SelectionBlockEntity source) {
        ServerLevel level = player.serverLevel();
        BoundingBox box = source.worldBox();
        if (box == null) {
            player.sendSystemMessage(Component.translatable("message.bedwarsrandomizer.selection.no_size").withStyle(ChatFormatting.RED));
            return;
        }

        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, min, new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), false, ModBlocks.SELECTION_BLOCK.get());

        // Template coordinates start at the box corner; rotating around the source block's spot there keeps the
        // selection's position relative to its block. See placement math below.
        BlockPos pivot = source.getBlockPos().subtract(min);
        Rotation undoSource = SelectionBlockEntity.inverse(source.rotation());
        SelectionBlockEntity.Settings sourceSettings = source.settings();

        SelectionTracker tracker = SelectionTracker.get(level);
        int pasted = 0;
        int skipped = 0;
        for (BlockPos pos : tracker.positions()) {
            if (pos.equals(source.getBlockPos())) continue;
            if (!(level.getBlockEntity(pos) instanceof SelectionBlockEntity target)) {
                tracker.remove(pos);
                continue;
            }
            // only areas of the same size (in the block's own frame, so facing doesn't matter)
            if (!target.settings().size().equals(sourceSettings.size())) {
                skipped++;
                continue;
            }
            Rotation targetRotation = target.rotation();
            // A source block standing at `anchor` with the source's offset would select exactly the target's area.
            BlockPos anchor = pos.offset(target.settings().offset().subtract(sourceSettings.offset()).rotate(targetRotation));
            StructurePlaceSettings placement = new StructurePlaceSettings()
                    .setRotation(undoSource.getRotated(targetRotation))
                    .setRotationPivot(pivot)
                    .setIgnoreEntities(true)
                    .addProcessor(new SelectionPasteProcessor(target.settings().team()));
            BlockPos placePos = anchor.subtract(pivot);
            template.placeInWorld(level, placePos, placePos, placement, level.random, Block.UPDATE_CLIENTS);
            pasted++;
        }

        BlockPos size = sourceSettings.size();
        if (pasted == 0) {
            player.sendSystemMessage(Component.translatable("message.bedwarsrandomizer.selection.no_targets",
                    size.getX(), size.getY(), size.getZ()).withStyle(ChatFormatting.YELLOW));
        } else {
            player.sendSystemMessage(Component.translatable("message.bedwarsrandomizer.selection.pasted",
                    size.getX(), size.getY(), size.getZ(), pasted).withStyle(ChatFormatting.GREEN));
        }
        if (skipped > 0) {
            player.sendSystemMessage(Component.translatable("message.bedwarsrandomizer.selection.skipped", skipped).withStyle(ChatFormatting.GRAY));
        }
    }

    private SelectionPaster() {}
}
