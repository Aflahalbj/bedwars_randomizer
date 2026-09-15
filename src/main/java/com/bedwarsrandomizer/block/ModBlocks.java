package com.bedwarsrandomizer.block;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.block.selection.SelectionPasteProcessor;
import com.bedwarsrandomizer.block.selection.SelectionBlock;
import com.bedwarsrandomizer.block.selection.SelectionBlockEntity;
import com.bedwarsrandomizer.block.teambed.TeamBedBlock;
import com.bedwarsrandomizer.block.teambed.TeamBedBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, BedwarsRandomizer.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, BedwarsRandomizer.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BedwarsRandomizer.MOD_ID);
    public static final DeferredRegister<StructureProcessorType<?>> PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, BedwarsRandomizer.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BedwarsRandomizer.MOD_ID);

    public static final RegistryObject<SelectionBlock> SELECTION_BLOCK = BLOCKS.register("selection_block",
            () -> new SelectionBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .requiresCorrectToolForDrops()
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()));
    public static final RegistryObject<Item> SELECTION_BLOCK_ITEM = ITEMS.register("selection_block",
            () -> new BlockItem(SELECTION_BLOCK.get(), new Item.Properties().rarity(Rarity.EPIC)));

    /** One team bed per dye color, in dye order. */
    public static final Map<DyeColor, RegistryObject<TeamBedBlock>> TEAM_BEDS = new EnumMap<>(DyeColor.class);

    static {
        for (DyeColor color : DyeColor.values()) {
            String name = color.getName() + "_team_bed";
            RegistryObject<TeamBedBlock> bed = BLOCKS.register(name, () -> new TeamBedBlock(color, BlockBehaviour.Properties.of()
                    .mapColor(state -> state.getValue(TeamBedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                            ? color.getMapColor() : MapColor.WOOL)
                    .sound(SoundType.WOOD)
                    .strength(0.2F)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable()));
            TEAM_BEDS.put(color, bed);
            ITEMS.register(name, () -> new BedItem(bed.get(), new Item.Properties().stacksTo(1)));
        }
    }

    public static final RegistryObject<BlockEntityType<SelectionBlockEntity>> SELECTION_BLOCK_ENTITY = BLOCK_ENTITIES.register("selection_block",
            () -> BlockEntityType.Builder.of(SelectionBlockEntity::new, SELECTION_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<TeamBedBlockEntity>> TEAM_BED_ENTITY = BLOCK_ENTITIES.register("team_bed",
            () -> BlockEntityType.Builder.of(TeamBedBlockEntity::new,
                    TEAM_BEDS.values().stream().map(RegistryObject::get).toArray(Block[]::new)).build(null));

    public static final RegistryObject<StructureProcessorType<SelectionPasteProcessor>> SELECTION_PASTE_PROCESSOR =
            PROCESSORS.register("selection_paste", () -> () -> SelectionPasteProcessor.CODEC);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + BedwarsRandomizer.MOD_ID))
            .icon(() -> new ItemStack(TEAM_BEDS.get(DyeColor.RED).get()))
            .displayItems((params, output) -> {
                output.accept(SELECTION_BLOCK_ITEM.get());
                TEAM_BEDS.values().forEach(bed -> output.accept(bed.get()));
            })
            .build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        PROCESSORS.register(modBus);
        TABS.register(modBus);
    }

    private ModBlocks() {}
}
