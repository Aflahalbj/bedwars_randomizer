package com.bedwarsrandomizer.drop;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Replaces the drops of every randomizer block ({@link ModTags#RANDOM_DROP_BLOCKS}) with one roll from that block's
 * {@link RandomDropPool}. Works for mining and explosions (vanilla explosion decay applies).
 */
public class RandomDropModifier extends LootModifier {
    public static final Supplier<Codec<RandomDropModifier>> CODEC = Suppliers.memoize(() ->
            RecordCodecBuilder.create(inst -> codecStart(inst).apply(inst, RandomDropModifier::new)));

    public RandomDropModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        BlockState state = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        RandomizerSource source = state == null || !state.is(ModTags.RANDOM_DROP_BLOCKS) ? null : RandomizerSource.of(state);
        if (source == null) {
            return generatedLoot;
        }
        RandomDropPool pool = RandomDropPool.get(context.getLevel(), source);
        if (!pool.enabled()) {
            return generatedLoot; // this randomizer is switched off: the block drops normally
        }

        ObjectArrayList<ItemStack> loot = new ObjectArrayList<>();
        Float explosionRadius = context.getParamOrNull(LootContextParams.EXPLOSION_RADIUS);
        if (explosionRadius != null && explosionRadius > 1f && context.getRandom().nextFloat() > 1f / explosionRadius) {
            return loot;
        }

        // colored drops from glazed terracotta match its color
        DyeColor color = source == RandomizerSource.GLAZED_TERRACOTTA ? ColoredBlocks.colorOf(state.getBlock()) : null;
        ItemStack roll = pool.roll(context.getRandom(), color);
        if (!roll.isEmpty()) {
            loot.add(roll);
        }
        return loot;
    }

    @Override
    public Codec<? extends net.minecraftforge.common.loot.IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
