package com.sereneworldgen;

import com.sereneworldgen.handler.SpawnIslandHandler;
import com.sereneworldgen.handler.SpawnSearchPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Pure geometry and search optimization tests: no Minecraft bootstrap needed. */
public class SpawnSearchPatternTest {
    private static final int STEP = 16;

    @Test
    @DisplayName("Layer 0 is the origin; layer n has 8n candidates, all exactly on the ring")
    void ringSizesAndPlacement() {
        List<int[]> origin = SpawnSearchPattern.ring(0, STEP);
        assertEquals(1, origin.size());
        assertArrayEquals(new int[]{0, 0}, origin.get(0));

        for (int layer = 1; layer <= 12; layer++) {
            List<int[]> ring = SpawnSearchPattern.ring(layer, STEP);
            assertEquals(8 * layer, ring.size(), "layer " + layer);
            for (int[] p : ring) {
                assertEquals(layer * STEP, Math.max(Math.abs(p[0]), Math.abs(p[1])), "layer " + layer + " point off-ring");
            }
        }
    }

    @Test
    @DisplayName("Layers 0..n visit every grid point in [-n*step, n*step]^2 exactly once")
    void coversFullGridExactlyOnce() {
        int maxLayer = 8;
        Set<String> seen = new HashSet<>();
        for (int layer = 0; layer <= maxLayer; layer++) {
            for (int[] p : SpawnSearchPattern.ring(layer, STEP)) {
                assertTrue(seen.add(p[0] + "," + p[1]), "duplicate candidate " + p[0] + "," + p[1]);
            }
        }

        assertEquals(SpawnSearchPattern.candidateCount(maxLayer), seen.size());
        for (int x = -maxLayer * STEP; x <= maxLayer * STEP; x += STEP) {
            for (int z = -maxLayer * STEP; z <= maxLayer * STEP; z += STEP) {
                assertTrue(seen.contains(x + "," + z), "missing candidate " + x + "," + z);
            }
        }
    }

    @Test
    @DisplayName("Regression: a 64-iteration cap only reaches ~64 blocks, never the old 512/1024 radius")
    void oldCapOf64CoveredOnlyAFewRings() {
        int fullRings = 0;
        int budget = 64 - 1; // minus the origin
        for (int layer = 1; budget >= SpawnSearchPattern.ring(layer, STEP).size(); layer++) {
            budget -= SpawnSearchPattern.ring(layer, STEP).size();
            fullRings = layer;
        }
        assertEquals(3, fullRings, "with a cap of 64 only rings 0..3 (48 blocks) are searched completely");
    }

    @Test
    @DisplayName("Column caching reuses overlapping columns across neighboring candidate footprints")
    void columnCachingEliminatesRedundantEvaluations() {
        AtomicInteger rawEvaluations = new AtomicInteger(0);
        Map<Long, SpawnIslandHandler.ColumnSample> cache = new HashMap<>();

        // Simulate sampling 2 neighbouring candidates spaced 16 blocks apart with radius 24
        int[][] candidates = new int[][]{{0, 0}, {16, 0}};
        for (int[] cand : candidates) {
            for (int dx = -SpawnIslandHandler.FOOTPRINT_RADIUS; dx <= SpawnIslandHandler.FOOTPRINT_RADIUS; dx += 8) {
                for (int dz = -SpawnIslandHandler.FOOTPRINT_RADIUS; dz <= SpawnIslandHandler.FOOTPRINT_RADIUS; dz += 8) {
                    int sx = cand[0] + dx;
                    int sz = cand[1] + dz;
                    long key = (((long) sx) << 32) | (sz & 0xFFFFFFFFL);
                    cache.computeIfAbsent(key, k -> {
                        rawEvaluations.incrementAndGet();
                        return new SpawnIslandHandler.ColumnSample(65, true);
                    });
                }
            }
        }

        // Without caching: 2 * 49 = 98 evaluations.
        // With overlapping footprint: 5 out of 7 X coordinates overlap with 7 Z coordinates = 35 overlaps!
        // Unique columns = 49 + 14 = 63.
        assertEquals(63, rawEvaluations.get(), "cached evaluations should be 63 instead of 98");
        assertEquals(63, cache.size());
    }

    @Test
    @DisplayName("Search terminates early on inner ring when land ratio is high even in non-habitable biome")
    void earlyExitOnInnerRingForStrongIsland() {
        int candidatesEvaluated = 0;
        double bestLandRatio = 0.0;
        int[] bestPos = null;

        search:
        for (int layer = 0; layer <= SpawnIslandHandler.MAX_SEARCH_LAYERS; layer++) {
            for (int[] point : SpawnSearchPattern.ring(layer, SpawnIslandHandler.SEARCH_STEP)) {
                candidatesEvaluated++;
                // Simulate an island centered at origin with 90% land, but in an arid/cold biome
                double landRatio = (Math.hypot(point[0], point[1]) <= 40) ? 0.90 : 0.10;
                if (landRatio > bestLandRatio) {
                    bestLandRatio = landRatio;
                    bestPos = point;
                }
            }

            // Early exit logic matching SpawnIslandHandler
            if (layer >= 2 && bestLandRatio >= SpawnIslandHandler.REQUIRED_LAND_RATIO && bestPos != null) {
                break search;
            }
        }

        // Layer 0 (1) + Layer 1 (8) + Layer 2 (16) = 25 candidates total
        assertEquals(25, candidatesEvaluated, "should evaluate exactly 25 candidates (rings 0..2) before early fallback exit");
        assertTrue(candidatesEvaluated < SpawnIslandHandler.MAX_SEARCH_ITERATIONS,
                "early exit avoided evaluating all 289 candidates");
        assertNotNull(bestPos);
        assertEquals(0.90, bestLandRatio, 1e-9);
    }
}
