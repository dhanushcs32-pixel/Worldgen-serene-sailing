package com.sereneworldgen.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public record SpawnIslandBias(double radius, double boost) implements DensityFunction.SimpleFunction {

    public static final double DEFAULT_RADIUS = 1400.0;
    public static final double DEFAULT_BOOST = 0.90;
    public static final double CORE_RADIUS = 70.0;
    public static final double COAST_RADIUS = 120.0;

    public static final MapCodec<SpawnIslandBias> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.DOUBLE.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(SpawnIslandBias::radius),
                    Codec.DOUBLE.optionalFieldOf("boost", DEFAULT_BOOST).forGetter(SpawnIslandBias::boost)
            ).apply(instance, SpawnIslandBias::new)
    );

    public static final KeyDispatchDataCodec<SpawnIslandBias> KEY_CODEC = KeyDispatchDataCodec.of(CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        if (ctx == null) {
            return 0.0;
        }

        double safeRadius = (Double.isFinite(radius) && radius > 0.0) ? radius : DEFAULT_RADIUS;
        double safeBoost = Double.isFinite(boost) ? boost : DEFAULT_BOOST;

        double x = ctx.blockX();
        double z = ctx.blockZ();
        double distSq = x * x + z * z;
        double rSq = safeRadius * safeRadius;

        if (distSq >= rSq || distSq < 0.0 || Double.isNaN(distSq)) {
            return 0.0;
        }

        double dist = Math.sqrt(distSq);
        if (!Double.isFinite(dist)) {
            return 0.0;
        }

        double coreBoost = Math.max(safeBoost + 0.45, 1.35);

        double result;
        // 1. Guaranteed elevated starter island core near (0, 0)
        if (dist <= CORE_RADIUS) {
            result = coreBoost;
        } else if (dist <= COAST_RADIUS) {
            // 2. Smooth coastal transition to beach and shoreline
            double t = (dist - CORE_RADIUS) / (COAST_RADIUS - CORE_RADIUS);
            double smoothT = 0.5 * (1.0 + Math.cos(t * Math.PI));
            result = safeBoost + (coreBoost - safeBoost) * smoothT;
        } else {
            // 3. Archipelago spread outward to radius
            if (safeRadius <= COAST_RADIUS) {
                result = 0.0;
            } else {
                double tArch = (dist - COAST_RADIUS) / (safeRadius - COAST_RADIUS);
                tArch = Math.clamp(tArch, 0.0, 1.0);
                result = safeBoost * (1.0 - tArch);
            }
        }

        if (!Double.isFinite(result)) {
            return 0.0;
        }

        return Math.clamp(result, minValue(), maxValue());
    }

    @Override
    public double minValue() {
        double safeBoost = Double.isFinite(boost) ? boost : DEFAULT_BOOST;
        return Math.min(0.0, safeBoost);
    }

    @Override
    public double maxValue() {
        double safeBoost = Double.isFinite(boost) ? boost : DEFAULT_BOOST;
        return Math.max(0.0, Math.max(safeBoost, Math.max(safeBoost + 0.45, 1.35)));
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KEY_CODEC;
    }
}
