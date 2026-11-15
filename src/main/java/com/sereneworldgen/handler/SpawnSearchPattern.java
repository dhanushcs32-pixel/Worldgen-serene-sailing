package com.sereneworldgen.handler;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the spawn search: concentric square rings of candidate points around (0, 0).
 * Kept free of Minecraft types so it can be unit-tested without bootstrapping the game.
 */
public final class SpawnSearchPattern {
    private SpawnSearchPattern() {}

    /**
     * Candidate {@code {x, z}} points on the square ring at {@code layer * step} blocks (Chebyshev distance).
     * Layer 0 is just the origin. Each point appears exactly once.
     */
    public static List<int[]> ring(int layer, int step) {
        List<int[]> points = new ArrayList<>();
        if (layer <= 0) {
            points.add(new int[]{0, 0});
            return points;
        }

        int radius = layer * step;

        // Top and bottom edges, including the corners
        for (int x = -radius; x <= radius; x += step) {
            points.add(new int[]{x, -radius});
            points.add(new int[]{x, radius});
        }

        // Left and right edges, excluding the corners already added
        for (int z = -radius + step; z <= radius - step; z += step) {
            points.add(new int[]{-radius, z});
            points.add(new int[]{radius, z});
        }
        return points;
    }

    /** Number of candidates in layers {@code 0..maxLayer} inclusive: a filled (2n+1) x (2n+1) grid. */
    public static int candidateCount(int maxLayer) {
        int side = 2 * Math.max(0, maxLayer) + 1;
        return side * side;
    }
}

