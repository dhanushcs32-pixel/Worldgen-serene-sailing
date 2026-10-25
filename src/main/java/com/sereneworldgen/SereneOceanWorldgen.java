package com.sereneworldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.sereneworldgen.datagen.DataGenerators;
import com.sereneworldgen.handler.SpawnIslandHandler;
import com.sereneworldgen.worldgen.SpawnIslandBias;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(SereneOceanWorldgen.MOD_ID)
public class SereneOceanWorldgen {
    public static final String MOD_ID = "sereneworldgen";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(BuiltInRegistries.DENSITY_FUNCTION_TYPE, MOD_ID);

    public static final DeferredHolder<MapCodec<? extends DensityFunction>, MapCodec<SpawnIslandBias>> SPAWN_ISLAND_BIAS =
            DENSITY_FUNCTION_TYPES.register("spawn_island_bias", () -> SpawnIslandBias.CODEC);

    public SereneOceanWorldgen(IEventBus modEventBus) {
        LOGGER.info("[SereneOceanWorldgen] Initializing Oceanic Worldgen Presets.");
        DENSITY_FUNCTION_TYPES.register(modEventBus);
        modEventBus.addListener(DataGenerators::gatherData);

        // Register game event listeners for guaranteed island spawn and sheep placement
        NeoForge.EVENT_BUS.register(SpawnIslandHandler.class);
    }
}
