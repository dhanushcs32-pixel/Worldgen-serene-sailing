package com.sereneworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Radial blend weight for the guaranteed starter island around (0, 0).
 * <p>
 * Returns 1.0 inside {@code coreRadius}, fades smoothly (cosine) to 0.0 at {@code radius}, and is 0.0
 * beyond. This is the raw, perfectly circular weight; {@code island_weight.json} warps it with noise so the
 * coastline is organic, and the data pack uses that to <em>blend</em> the natural terrain toward a fixed island
 * height (see {@code terrain_offset.json}, {@code island_offset.json}), rather than adding a boost on top of whatever the
 * natural terrain happens to be. An additive boost made the spawn height depend on the seed (from y~150 up
 * to near the build limit) and produced ~900 block wide landmasses; a blend gives a predictable, small island.
 * <p>
 * The registered type id stays {@code sereneworldgen:spawn_island_bias} for compatibility.
 */
public record SpawnIslandBias(double radius, double coreRadius) implements DensityFunction.SimpleFunction {

    /** Outer edge of the blend. With the default terrain this gives a shoreline ~60 blocks from spawn. */
    public static final double DEFAULT_RADIUS = 130.0;
    /** Inside this distance the island is fully "on". */
    public static final double DEFAULT_CORE_RADIUS = 40.0;

    public static final MapCodec<SpawnIslandBias> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.DOUBLE.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(SpawnIslandBias::radius),
                    Codec.DOUBLE.optionalFieldOf("core_radius", DEFAULT_CORE_RADIUS).forGetter(SpawnIslandBias::coreRadius)
            ).apply(instance, SpawnIslandBias::new)
    );

    public static final KeyDispatchDataCodec<SpawnIslandBias> KEY_CODEC = KeyDispatchDataCodec.of(CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        if (ctx == null) {
            return 0.0;
        }

        double safeRadius = (Double.isFinite(radius) && radius > 0.0) ? radius : DEFAULT_RADIUS;
        double safeCore = Double.isFinite(coreRadius) ? Math.clamp(coreRadius, 0.0, safeRadius * 0.99) : Math.min(DEFAULT_CORE_RADIUS, safeRadius * 0.99);

        double x = ctx.blockX();
        double z = ctx.blockZ();
        double dist = Math.sqrt(x * x + z * z);

        if (!Double.isFinite(dist) || dist >= safeRadius) {
            return 0.0;
        }

        if (dist <= safeCore) {
            return 1.0;
        }

        double t = (dist - safeCore) / (safeRadius - safeCore);
        return Math.clamp(0.5 * (1.0 + Math.cos(t * Math.PI)), 0.0, 1.0);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_CODEC;
    }
}
