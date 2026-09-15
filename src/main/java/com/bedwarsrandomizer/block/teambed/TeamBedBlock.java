package com.bedwarsrandomizer.block.teambed;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A bed that marks a team (its color). It looks and places like a vanilla bed, but can't be slept in, never sets a
 * spawn point and never explodes in the Nether/End.
 */
public class TeamBedBlock extends BedBlock {

    public TeamBedBlock(DyeColor color, Properties properties) {
        super(color, properties);
    }

    public MutableComponent teamName() {
        return Component.translatable("color.minecraft." + getColor().getName()).withStyle(style -> style.withColor(getColor().getTextColor()));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.displayClientMessage(Component.translatable("message.bedwarsrandomizer.team_bed", teamName()), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Not a bed as far as sleeping and respawning are concerned. */
    @Override
    public boolean isBed(BlockState state, BlockGetter level, BlockPos pos, @Nullable Entity entity) {
        return false;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TeamBedBlockEntity(pos, state);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.bedwarsrandomizer.team_bed.team", teamName()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.bedwarsrandomizer.team_bed.no_sleep").withStyle(ChatFormatting.DARK_GRAY));
    }
}
