package com.bedwarsrandomizer.block.selection;

import com.bedwarsrandomizer.block.ModBlocks;
import com.bedwarsrandomizer.client.SelectionScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Like a structure block, but the selection box is stored relative to the block's facing (which points at the player
 * who placed it). Only operators can open it; Save copies the selection into every other selection block's area.
 */
public class SelectionBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public SelectionBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SelectionBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // operators in creative only, like the structure block
        if (!player.canUseGameMasterBlocks() || !(level.getBlockEntity(pos) instanceof SelectionBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SelectionScreen.open(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            SelectionTracker.get(server).add(pos);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel server && !newState.is(this)) {
            SelectionTracker.get(server).remove(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Pick block keeps the selection offset and size, so the copy gets the same area when placed. */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof SelectionBlockEntity selection) {
            BlockItem.setBlockEntityData(stack, ModBlocks.SELECTION_BLOCK_ENTITY.get(), selection.writeSettings(new CompoundTag()));
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = BlockItem.getBlockEntityData(stack);
        if (tag != null && tag.contains(SelectionBlockEntity.TAG_SIZE)) {
            SelectionBlockEntity.Settings settings = SelectionBlockEntity.Settings.read(tag);
            tooltip.add(Component.translatable("tooltip.bedwarsrandomizer.selection_block.size",
                    settings.size().getX(), settings.size().getY(), settings.size().getZ()).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.bedwarsrandomizer.selection_block.offset",
                    settings.offset().getX(), settings.offset().getY(), settings.offset().getZ()).withStyle(ChatFormatting.GRAY));
            if (settings.team() != null) {
                tooltip.add(Component.translatable("tooltip.bedwarsrandomizer.team_bed.team", teamName(settings.team())).withStyle(ChatFormatting.GRAY));
            }
        }
    }

    public static Component teamName(net.minecraft.world.item.DyeColor team) {
        return Component.translatable("color.minecraft." + team.getName()).withStyle(style -> style.withColor(team.getTextColor()));
    }
}
