package com.sereneworldgen.handler;

import java.util.HashSet;
import java.util.Set;

/**
 * Allows at most one sheep flock per 64 x 64 block cell.
 * <p>
 * This replaces relying on {@code getEntitiesOfClass} to notice nearby sheep: during world creation freshly
 * spawned flocks are often not visible to that query yet, so every land chunk saw "no sheep" and spawned
 * its own flock (about 60 sheep on a 130 block island). Pure Java so it can be unit-tested.
 * Not thread-safe: use from the server thread only.
 */
public final class FlockGrid {
    public static final int CELL_SHIFT = 6; // 64 blocks

    private final Set<Long> occupiedCells = new HashSet<>();

    /** @return true if the cell containing this block position was free (and is now reserved). */
    public boolean tryReserve(int blockX, int blockZ) {
        int cellX = blockX >> CELL_SHIFT; // arithmetic shift == floor division, correct for negatives
        int cellZ = blockZ >> CELL_SHIFT;
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        return occupiedCells.add(key);
    }

    public void addRawKey(long key) {
        occupiedCells.add(key);
    }

    public long[] toLongArray() {
        long[] array = new long[occupiedCells.size()];
        int i = 0;
        for (long key : occupiedCells) {
            array[i++] = key;
        }
        return array;
    }

    public void clear() {
        occupiedCells.clear();
    }

    public int size() {
        return occupiedCells.size();
    }
}
