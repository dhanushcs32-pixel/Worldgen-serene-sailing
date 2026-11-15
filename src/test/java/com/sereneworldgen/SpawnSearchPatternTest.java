package com.sereneworldgen;

import com.sereneworldgen.handler.SpawnSearchPattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure geometry tests: no Minecraft bootstrap needed. */
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
}

