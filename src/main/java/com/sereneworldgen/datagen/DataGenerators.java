package com.sereneworldgen.datagen;

import com.sereneworldgen.SereneOceanWorldgen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.data.event.GatherDataEvent;

import java.util.Map;
import java.util.Optional;
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
    /** Biome-side continentalness for the preset: remap spline + starter island (data/.../router_continents.json). */
    public static final ResourceKey<DensityFunction> ROUTER_CONTINENTS =
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "router_continents"));

    /**
     * Preset-only copies of vanilla's {@code overworld/depth} and {@code overworld/sloped_cheese}.
     * The mod must NOT override the vanilla files: that would change terrain in every default world.
     * Instead the endless_ocean router is built against these copies (see {@link #redirecting}).
     */
    public static final ResourceKey<DensityFunction> DEPTH =
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "depth"));
    public static final ResourceKey<DensityFunction> SLOPED_CHEESE =
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "sloped_cheese"));

    private static final Map<ResourceKey<DensityFunction>, ResourceKey<DensityFunction>> VANILLA_REDIRECTS = Map.of(
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld/depth")), DEPTH,
            ResourceKey.create(Registries.DENSITY_FUNCTION, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld/sloped_cheese")), SLOPED_CHEESE
    );

    /** The Serene Sailing noise router preset. */
    public static final ResourceKey<NoiseGeneratorSettings> ENDLESS_OCEAN_KEY =
            ResourceKey.create(Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath(SereneOceanWorldgen.MOD_ID, "endless_ocean"));

    /**
     * Wraps a density-function lookup so that vanilla's overworld depth / sloped_cheese resolve to the
     * mod's own copies. Everything else (offset, factor, jaggedness, caves...) stays vanilla.
     */
    private static HolderGetter<DensityFunction> redirecting(HolderGetter<DensityFunction> delegate) {
        return new HolderGetter<>() {
            @Override
            public Optional<Holder.Reference<DensityFunction>> get(ResourceKey<DensityFunction> key) {
                return delegate.get(VANILLA_REDIRECTS.getOrDefault(key, key));
            }

            @Override
            public Optional<HolderSet.Named<DensityFunction>> get(TagKey<DensityFunction> tag) {
                return delegate.get(tag);
            }
        };
    }

    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput packOutput = generator.getPackOutput();
        CompletableFuture<HolderLookup.Provider> lookupProvider = event.getLookupProvider();

        RegistrySetBuilder builder = new RegistrySetBuilder()
                .add(Registries.NOISE_SETTINGS, context -> {
                    HolderGetter<DensityFunction> densityLookup = context.lookup(Registries.DENSITY_FUNCTION);

                    // 1. Build vanilla base settings and router
                    NoiseGeneratorSettings vanilla = NoiseGeneratorSettings.overworld(context, false, false);
                    HolderGetter<NormalNoise.NoiseParameters> noiseLookup = context.lookup(Registries.NOISE);
                    // Same router vanilla builds, but wired to sereneworldgen:depth / :sloped_cheese
                    NoiseRouter baseRouter = NoiseRouterData.overworld(redirecting(densityLookup), noiseLookup, false, false);

                    // 2. Biome continentalness: oceanic remap spline, raised to inland only inside the
                    // starter island (see router_continents.json). Terrain is blended separately via depth.json.
                    DensityFunction moddedContinents = new DensityFunctions.HolderHolder(densityLookup.getOrThrow(ROUTER_CONTINENTS));

                    NoiseRouter customRouter = new NoiseRouter(
                            baseRouter.barrierNoise(),
                            baseRouter.fluidLevelFloodednessNoise(),
                            baseRouter.fluidLevelSpreadNoise(),
                            baseRouter.lavaNoise(),
                            baseRouter.temperature(),
                            baseRouter.vegetation(),
                            moddedContinents, // ocean remap spline + starter island
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
