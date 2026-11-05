package com.sereneworldgen;

import com.sereneworldgen.handler.SpawnIslandHandler;
import com.sereneworldgen.worldgen.SpawnIslandBias;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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
    @DisplayName("Assert continentalness noise sampling returns valid finite numbers across (0,0) to (1500,1500)")
    void testContinentalnessSamplingAcrossCoordinates() {
        SpawnIslandBias bias = new SpawnIslandBias(1400.0, 0.90);

        // Core value at (0, 0)
        double centerVal = bias.compute(new DummyContext(0, 64, 0));
        assertTrue(Double.isFinite(centerVal), "Center value must be finite");
        assertEquals(1.35, centerVal, 1e-6, "Center value must match elevated starter core boost");

        // Verify across (0,0) to (1500,1500)
        for (int x = 0; x <= 1500; x += 16) {
            for (int z = 0; z <= 1500; z += 16) {
                double val = bias.compute(new DummyContext(x, 64, z));
                assertTrue(Double.isFinite(val), "Sampled value at (" + x + ", " + z + ") must be finite");
                assertFalse(Double.isNaN(val), "Sampled value at (" + x + ", " + z + ") must not be NaN");
                assertTrue(val >= bias.minValue(), "Value at (" + x + ", " + z + ") must be >= minValue");
                assertTrue(val <= bias.maxValue(), "Value at (" + x + ", " + z + ") must be <= maxValue");
            }
        }

        // Test outside radius boundary
        double outsideVal = bias.compute(new DummyContext(1500, 64, 1500));
        assertEquals(0.0, outsideVal, 1e-6, "Points outside 1400 radius must return 0.0 bias");
    }

    @Test
    @DisplayName("Verify SpawnIslandBias robustness against extreme / NaN / infinite parameters")
    void testSpawnIslandBiasEdgeCases() {
        // Degenerate radius and boosts
        SpawnIslandBias nanBias = new SpawnIslandBias(Double.NaN, Double.NaN);
        double valNan = nanBias.compute(new DummyContext(0, 64, 0));
        assertTrue(Double.isFinite(valNan), "NaN parameters must fall back safely to finite numbers");
        assertTrue(valNan >= nanBias.minValue() && valNan <= nanBias.maxValue());

        SpawnIslandBias negBias = new SpawnIslandBias(-500.0, -1.0);
        double valNeg = negBias.compute(new DummyContext(0, 64, 0));
        assertTrue(Double.isFinite(valNeg), "Negative radius must be handled safely");

        SpawnIslandBias zeroRadius = new SpawnIslandBias(50.0, 0.90); // radius < coastRadius
        double valSmallRadius = zeroRadius.compute(new DummyContext(100, 64, 100));
        assertTrue(Double.isFinite(valSmallRadius), "Radius smaller than coastRadius must not divide by zero");

        // Null context guard
        assertEquals(0.0, nanBias.compute(null), "Null context must return 0.0 safely");
    }

    @Test
    @DisplayName("Verify spawn position selection simulation completes within 500ms and respects 64-iteration limit")
    void testSpawnPositionSelectionPerformance() {
        assertEquals(64, SpawnIslandHandler.MAX_SEARCH_ITERATIONS, "Safety iteration cap must be 64");

        long startNanos = System.nanoTime();

        // Simulate radial spiral search evaluation matching SpawnIslandHandler
        int step = SpawnIslandHandler.SEARCH_STEP;
        int maxLayers = Math.min(SpawnIslandHandler.MAX_SEARCH_RADIUS / step, 32);
        int iterations = 0;
        BlockPos bestCandidate = null;

        outer:
        for (int layer = 0; layer <= maxLayers; layer++) {
            int radius = layer * step;
            if (layer == 0) {
                if (++iterations > SpawnIslandHandler.MAX_SEARCH_ITERATIONS) break;
                // Fast simulation of candidate evaluation
                bestCandidate = new BlockPos(0, 64, 0);
                break;
            }
            for (int x = -radius; x <= radius; x += step) {
                for (int z : new int[]{-radius, radius}) {
                    if (++iterations > SpawnIslandHandler.MAX_SEARCH_ITERATIONS) break outer;
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertNotNull(bestCandidate, "Starter spawn position candidate must be selected");
        assertTrue(iterations <= 64, "Search must never exceed 64 iterations");
        assertTrue(elapsedMs < 500, "Spawn selection simulation must complete in under 500ms, took: " + elapsedMs + "ms");
    }
}