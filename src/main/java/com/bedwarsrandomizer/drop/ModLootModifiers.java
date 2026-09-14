package com.bedwarsrandomizer.drop;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.mojang.serialization.Codec;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModLootModifiers {
    public static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, BedwarsRandomizer.MOD_ID);

    public static final RegistryObject<Codec<RandomDropModifier>> RANDOM_DROP =
            SERIALIZERS.register("random_drop", RandomDropModifier.CODEC);

    private ModLootModifiers() {}
}
