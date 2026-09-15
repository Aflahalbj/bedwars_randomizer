package com.bedwarsrandomizer.block.selection;

import com.bedwarsrandomizer.block.ModBlocks;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GlazedTerracottaBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Paste rules for one target area: selection blocks already standing there are never overwritten, and when the target
 * has a team, every glazed terracotta and team bed takes that team's color.
 */
public final class SelectionPasteProcessor extends StructureProcessor {
    public static final Codec<SelectionPasteProcessor> CODEC = DyeColor.CODEC.optionalFieldOf("team")
            .xmap(team -> new SelectionPasteProcessor(team.orElse(null)), processor -> Optional.ofNullable(processor.team))
            .codec();

    @Nullable
    private final DyeColor team;

    public SelectionPasteProcessor(@Nullable DyeColor team) {
        this.team = team;
    }

    @Nullable
    @Override
    public StructureTemplate.StructureBlockInfo process(LevelReader level, BlockPos offset, BlockPos pos,
                                                        StructureTemplate.StructureBlockInfo raw,
                                                        StructureTemplate.StructureBlockInfo placed,
                                                        StructurePlaceSettings settings, @Nullable StructureTemplate template) {
        if (level.getBlockState(placed.pos()).getBlock() instanceof SelectionBlock) return null;
        BlockState recolored = recolor(placed.state(), team);
        return recolored == placed.state() ? placed : new StructureTemplate.StructureBlockInfo(placed.pos(), recolored, placed.nbt());
    }

    /** The same block in the team's color (keeping facing etc.), or the state unchanged if it isn't team-colored. */
    public static BlockState recolor(BlockState state, @Nullable DyeColor team) {
        if (team == null) return state;
        Block block = state.getBlock();
        Block colored;
        if (block instanceof GlazedTerracottaBlock) {
            colored = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("minecraft", team.getName() + "_glazed_terracotta"));
        } else if (block instanceof TeamBedBlock) {
            colored = ModBlocks.TEAM_BEDS.get(team).get();
        } else {
            return state;
        }
        if (colored == null || colored == block) return state;
        BlockState result = colored.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (result.hasProperty(property)) result = copyValue(state, result, property);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyValue(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModBlocks.SELECTION_PASTE_PROCESSOR.get();
    }
}
