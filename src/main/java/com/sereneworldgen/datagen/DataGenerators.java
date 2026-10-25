package com.sereneworldgen.datagen;

import com.sereneworldgen.SereneOceanWorldgen;
import com.sereneworldgen.worldgen.SpawnIslandBias;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class DataGenerators {

    /** Ocean depth offset spline reference. */
    public static final ResourceKey<DensityFunction> OCEAN_DEPTH_OFFSET =
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "ocean_depth_offset"));

    /** Custom continents remap spline that deepens the vanilla ocean gradient. */
    public static final ResourceKey<DensityFunction> CONTINENTS_OVERWRITE =
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "continents"));
    public static final ResourceKey<DensityFunction> CUSTOM_CONTINENTS = CONTINENTS_OVERWRITE;

    /** The Serene Sailing noise router preset. */
    public static final ResourceKey<NoiseGeneratorSettings> ENDLESS_OCEAN_KEY =
            ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "endless_ocean"));

    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();

        RegistrySetBuilder builder = new RegistrySetBuilder()
                .add(Registries.NOISE_SETTINGS, context -> {
                    HolderGetter<DensityFunction> densityLookup = context.lookup(Registries.DENSITY_FUNCTION);

                    // 1. Build vanilla base settings and router
                    NoiseGeneratorSettings vanilla = NoiseGeneratorSettings.overworld(context, false, false);
                    NoiseRouter baseRouter = vanilla.noiseRouter();

                    // 2. Clean routing: continentalness remapped to oceanic spline,
                    // with radial spawn bias boosting continents within 1400 blocks of (0,0)
                    DensityFunction rawContinents = new DensityFunctions.HolderHolder(densityLookup.getOrThrow(CONTINENTS_OVERWRITE));

                    // Bias continents upward within 1400 blocks of (0,0) to create a tight starter archipelago
                    DensityFunction moddedContinents = DensityFunctions.add(
                            rawContinents,
                            new SpawnIslandBias(1400.0, 0.90)
                    );

                    NoiseRouter customRouter = new NoiseRouter(
                            baseRouter.barrierNoise(),
                            baseRouter.fluidLevelFloodednessNoise(),
                            baseRouter.fluidLevelSpreadNoise(),
                            baseRouter.lavaNoise(),
                            baseRouter.temperature(),
                            baseRouter.vegetation(),
                            moddedContinents, // custom ocean remap spline with spawn island bias
                            baseRouter.erosion(),
                            baseRouter.depth(),
                            baseRouter.ridges(),
                            baseRouter.initialDensityWithoutJaggedness(), // clean vanilla wiring
                            baseRouter.finalDensity(),                    // clean vanilla wiring
                            baseRouter.veinToggle(),
                            baseRouter.veinRidged(),
                            baseRouter.veinGap()
                    );

                    // 3. Register endless_ocean settings with our cleanly routed router
                    context.register(ENDLESS_OCEAN_KEY, new NoiseGeneratorSettings(
                            vanilla.noiseSettings(),
                            vanilla.defaultBlock(),
                            vanilla.defaultFluid(),
                            customRouter,
                            vanilla.surfaceRule(),
                            vanilla.spawnTarget(),
                            vanilla.seaLevel(),
                            vanilla.disableMobGeneration(),
                            vanilla.aquifersEnabled(),
                            vanilla.oreVeinsEnabled(),
                            vanilla.useLegacyRandomSource()
                    ));
                });

        generator.addProvider(event.includeServer(),
                new DatapackBuiltinEntriesProvider(packOutput, lookupProvider, builder, Set.of(SereneOceanWorldgen.MOD_ID)));
    }
}
