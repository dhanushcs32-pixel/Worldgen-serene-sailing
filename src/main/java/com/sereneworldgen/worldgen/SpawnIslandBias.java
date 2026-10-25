package com.sereneworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record SpawnIslandBias(double radius, double boost) implements DensityFunction.SimpleFunction {

    public static final MapCodec<SpawnIslandBias> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.DOUBLE.optionalFieldOf("radius", 1400.0).forGetter(SpawnIslandBias::radius),
                    Codec.DOUBLE.optionalFieldOf("boost", 0.90).forGetter(SpawnIslandBias::boost)
            ).apply(instance, SpawnIslandBias::new)
    );

    public static final KeyDispatchDataCodec<SpawnIslandBias> KEY_CODEC = KeyDispatchDataCodec.of(CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        double x = ctx.blockX();
        double z = ctx.blockZ();
        double distSq = x * x + z * z;
        double rSq = radius * radius;

        if (distSq >= rSq) {
            return 0.0;
        }

        double dist = Math.sqrt(distSq);
        double coreRadius = 70.0;
        double coastRadius = 120.0;
        double coreBoost = Math.max(boost + 0.45, 1.35);

        // 1. Guaranteed elevated starter island core near (0, 0)
        if (dist <= coreRadius) {
            return coreBoost;
        }

        // 2. Smooth coastal transition to beach and shoreline
        if (dist <= coastRadius) {
            double t = (dist - coreRadius) / (coastRadius - coreRadius);
            double smoothT = 0.5 * (1.0 + Math.cos(t * Math.PI));
            return boost + (coreBoost - boost) * smoothT;
        }

        // 3. Archipelago spread outward to radius
        double tArch = (dist - coastRadius) / (radius - coastRadius);
        return boost * (1.0 - tArch);
    }

    @Override
    public double minValue() {
        return 0.0;
    }

    @Override
    public double maxValue() {
        return Math.max(boost + 0.45, 1.35);
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_CODEC;
    }
}
