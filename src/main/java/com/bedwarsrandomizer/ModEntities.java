package com.bedwarsrandomizer;

import com.bedwarsrandomizer.fireball.BwrFireball;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BedwarsRandomizer.MOD_ID);

    public static final RegistryObject<EntityType<BwrFireball>> FIREBALL = ENTITIES.register("fireball",
            () -> EntityType.Builder.<BwrFireball>of(BwrFireball::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build("fireball"));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }

    private ModEntities() {}
}
