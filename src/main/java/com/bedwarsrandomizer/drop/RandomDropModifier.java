package com.bedwarsrandomizer.drop;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.LootModifier;

import java.util.function.Supplier;

/**
 * Replaces the drops of every block in {@link ModTags#RANDOM_DROP_BLOCKS} with one random roll
 * from {@link RandomDropPool}. Works for mining and explosions (vanilla explosion decay applies).
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
        if (state == null || !state.is(ModTags.RANDOM_DROP_BLOCKS)) {
            return generatedLoot;
        }

        ObjectArrayList<ItemStack> loot = new ObjectArrayList<>();
        Float explosionRadius = context.getParamOrNull(LootContextParams.EXPLOSION_RADIUS);
        if (explosionRadius != null && explosionRadius > 1f && context.getRandom().nextFloat() > 1f / explosionRadius) {
            return loot;
        }

        ItemStack roll = RandomDropPool.get(context.getLevel()).roll(context.getRandom());
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
