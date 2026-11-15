package com.sereneworldgen;

import com.sereneworldgen.handler.SpawnIslandHandler;
import com.sereneworldgen.handler.SpawnSearchPattern;
import com.sereneworldgen.worldgen.SpawnIslandBias;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class WorldgenLoadTest {

    @BeforeAll
    static void setupMinecraft() {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    record DummyContext(int blockX, int blockY, int blockZ) implements DensityFunction.FunctionContext {
        @Override
        public Blender getBlender() {
            return Blender.empty();
        }
    }

    @Test
    @DisplayName("Island weight: 1 in the core, smooth fade, 0 at and beyond the radius; always within [min, max]")
    void testIslandWeightProfile() {
        SpawnIslandBias weight = new SpawnIslandBias(130.0, 40.0);

        assertEquals(1.0, weight.compute(new DummyContext(0, 64, 0)), 1e-9, "spawn is fully inside the island");
        assertEquals(1.0, weight.compute(new DummyContext(40, 64, 0)), 1e-9, "core edge");
        assertEquals(0.5, weight.compute(new DummyContext(85, 64, 0)), 1e-9, "midpoint of the fade (40..130)");
        assertEquals(0.0, weight.compute(new DummyContext(130, 64, 0)), 1e-9, "outer edge");
        assertEquals(0.0, weight.compute(new DummyContext(1500, 64, 1500)), 1e-9, "far away: untouched natural world");

        for (int x = -300; x <= 300; x += 7) {
            for (int z = -300; z <= 300; z += 7) {
                double v = weight.compute(new DummyContext(x, 64, z));
                assertTrue(Double.isFinite(v), "finite at " + x + "," + z);
                assertTrue(v >= weight.minValue() && v <= weight.maxValue(), "within [min,max] at " + x + "," + z);
            }
        }
    }

    @Test
    @DisplayName("Island weight never increases with distance and has no cliff at the edge, for any radius")
    void testWeightIsMonotonicAndContinuous() {
        for (double radius : new double[]{20.0, 50.0, 130.0, 400.0}) {
            SpawnIslandBias weight = new SpawnIslandBias(radius, radius * 0.3);

            double previous = weight.compute(new DummyContext(0, 64, 0));
            for (int x = 1; x <= (int) radius + 5; x++) {
                double value = weight.compute(new DummyContext(x, 64, 0));
                assertTrue(value <= previous + 1e-9, "radius " + radius + ": weight increased at x=" + x);
                assertTrue(previous - value < 0.25, "radius " + radius + ": jump of " + (previous - value) + " at x=" + x);
                previous = value;
            }
            assertEquals(0.0, previous, 1e-9, "radius " + radius + ": zero beyond the edge");
        }
    }

    @Test
    @DisplayName("Island weight is robust against NaN / infinite / negative / inverted parameters")
    void testWeightEdgeCases() {
        for (SpawnIslandBias bias : new SpawnIslandBias[]{
                new SpawnIslandBias(Double.NaN, Double.NaN),
                new SpawnIslandBias(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
                new SpawnIslandBias(-500.0, -1.0),
                new SpawnIslandBias(50.0, 500.0),   // core larger than radius
                new SpawnIslandBias(0.0, 0.0)}) {
            for (int x : new int[]{0, 10, 60, 200, 5000}) {
                double v = bias.compute(new DummyContext(x, 64, 0));
                assertTrue(Double.isFinite(v), bias + " must be finite at x=" + x);
                assertTrue(v >= bias.minValue() && v <= bias.maxValue(), bias + " out of range at x=" + x);
            }
        }
        assertEquals(0.0, new SpawnIslandBias(130.0, 40.0).compute(null), "null context must return 0.0 safely");
    }

    @Test
    @DisplayName("Spawn search iteration cap must cover the whole configured search radius")
    void testSpawnSearchCapCoversConfiguredRadius() {
        assertEquals(SpawnIslandHandler.MAX_SEARCH_RADIUS,
                SpawnIslandHandler.MAX_SEARCH_LAYERS * SpawnIslandHandler.SEARCH_STEP,
                "MAX_SEARCH_RADIUS must be a whole number of rings");
        assertTrue(SpawnIslandHandler.MAX_SEARCH_ITERATIONS
                        >= SpawnSearchPattern.candidateCount(SpawnIslandHandler.MAX_SEARCH_LAYERS),
                "iteration cap must not cut the search short of MAX_SEARCH_RADIUS (it used to stop at ~64 blocks)");
    }
}
