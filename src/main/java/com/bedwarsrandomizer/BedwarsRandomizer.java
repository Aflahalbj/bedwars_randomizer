package com.bedwarsrandomizer;

import com.bedwarsrandomizer.drop.ModLootModifiers;
import com.bedwarsrandomizer.network.ModNetwork;
import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BedwarsRandomizer.MOD_ID)
public class BedwarsRandomizer {
    public static final String MOD_ID = "bedwarsrandomizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BedwarsRandomizer(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ModLootModifiers.SERIALIZERS.register(modBus);
        modBus.addListener(this::commonSetup);
        context.registerConfig(ModConfig.Type.COMMON, BwrConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
    }
}
