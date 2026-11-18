package com.sereneworldgen.handler;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public class FlockGridSavedData extends SavedData {
    public static final String DATA_NAME = "sereneworldgen_flock_grid";

    public static final Factory<FlockGridSavedData> FACTORY = new Factory<>(
            FlockGridSavedData::new,
            FlockGridSavedData::load,
            null
    );

    private final FlockGrid grid = new FlockGrid();

    public FlockGridSavedData() {
    }

    public static FlockGridSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static FlockGridSavedData load(CompoundTag tag, HolderLookup.Provider provider) {
        FlockGridSavedData data = new FlockGridSavedData();
        if (tag.contains("Cells", 12)) {
            long[] cells = tag.getLongArray("Cells");
            for (long cell : cells) {
                data.grid.addRawKey(cell);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLongArray("Cells", grid.toLongArray());
        return tag;
    }

    public boolean tryReserve(int blockX, int blockZ) {
        boolean reserved = grid.tryReserve(blockX, blockZ);
        if (reserved) {
            setDirty();
        }
        return reserved;
    }

    public FlockGrid getGrid() {
        return grid;
    }
}

