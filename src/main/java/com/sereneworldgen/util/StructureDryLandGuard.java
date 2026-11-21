package com.sereneworldgen.util;

import com.sereneworldgen.datagen.DataGenerators;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import net.minecraft.world.level.levelgen.structure.structures.OceanRuinStructure;
import net.minecraft.world.level.levelgen.structure.structures.ShipwreckStructure;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutStructure;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionStructure;

/**
 * Rejects land-only surface structures (vanilla and modded) whose footprint falls over open ocean
 * in Endless Ocean worlds, while leaving aquatic, underground, and sky structures untouched.
 */
public final class StructureDryLandGuard {

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private StructureDryLandGuard() {}

    public static boolean isValidPlacement(Structure structure, Structure.GenerationContext context, Structure.GenerationStub stub) {
        if (!(context.chunkGenerator() instanceof NoiseBasedChunkGenerator noiseGen)
                || !noiseGen.generatorSettings().is(DataGenerators.ENDLESS_OCEAN_KEY)) {
            return true;
        }

        // Never touch underground/buried structures (strongholds, mineshafts, trial chambers, ancient cities, trail ruins).
        if (structure.step() != GenerationStep.Decoration.SURFACE_STRUCTURES) {
            return true;
        }

        // Always allow vanilla aquatic / coastal structures.
        if (structure instanceof ShipwreckStructure
                || structure instanceof OceanMonumentStructure
                || structure instanceof OceanRuinStructure
                || structure instanceof BuriedTreasureStructure) {
            return true;
        }

        // Always allow modded or vanilla structures restricted solely to ocean/beach/river biomes (e.g. ocean ruined portals, pirate ships).
        if (isExclusivelyAquaticStructure(structure)) {
            return true;
        }

        int seaLevel = context.chunkGenerator().getSeaLevel();
        BlockPos pos = stub.position();
        // Skip intentionally submerged seafloor structures and floating sky structures.
        if (pos.getY() < seaLevel - 2 || pos.getY() > seaLevel + 80) {
            return true;
        }

        int cx = pos.getX();
        int cz = pos.getZ();
        int centerFloor = oceanFloorY(context, cx, cz);

        // Swamp huts belong on shallow swamp water or land, never over deep ocean.
        if (structure instanceof SwampHutStructure) {
            return centerFloor >= seaLevel - 4;
        }

        // Fast 1-sample reject: the structure's own start center must sit on dry ground.
        if (centerFloor < seaLevel) {
            return false;
        }

        if (structure instanceof WoodlandMansionStructure) {
            return hasDryFootprint(context, cx, cz, seaLevel, 32, 16, 0.75, 1);
        }
        if (structure instanceof JigsawStructure) {
            // Covers all 5 vanilla villages, pillager outposts, and modded Jigsaw towns/towers/camps.
            return hasDryFootprint(context, cx, cz, seaLevel, 28, 14, 0.75, 1);
        }

        // Single-piece and other land surface structures (desert pyramids, jungle temples, igloos, land ruined portals, modded huts).
        return hasDryFootprint(context, cx, cz, seaLevel, 12, 0, 0.80, 0);
    }

    private static boolean isExclusivelyAquaticStructure(Structure structure) {
        boolean hasAny = false;
        boolean hasOcean = false;
        for (Holder<Biome> biome : structure.biomes()) {
            hasAny = true;
            if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) {
                hasOcean = true;
            } else if (!biome.is(BiomeTags.IS_BEACH) && !biome.is(BiomeTags.IS_RIVER)) {
                return false;
            }
        }
        return hasAny && hasOcean;
    }

    private static boolean hasDryFootprint(Structure.GenerationContext context, int cx, int cz, int seaLevel,
                                           int outerRadius, int innerRadius, double minDryRatio, int maxOuterDeepWater) {
        int total = 1;
        int dry = 1; // Center was already verified >= seaLevel

        if (innerRadius > 0) {
            int diagInner = (int) Math.round(innerRadius * 0.7071);
            for (int[] dir : DIRECTIONS) {
                int r = (dir[0] != 0 && dir[1] != 0) ? diagInner : innerRadius;
                int floorY = oceanFloorY(context, cx + dir[0] * r, cz + dir[1] * r);
                if (floorY < seaLevel - 3) {
                    return false; // No deep ocean directly under the core footprint
                }
                total++;
                if (floorY >= seaLevel) {
                    dry++;
                }
            }
        }

        int outerDeepWater = 0;
        int diagOuter = (int) Math.round(outerRadius * 0.7071);
        for (int[] dir : DIRECTIONS) {
            int r = (dir[0] != 0 && dir[1] != 0) ? diagOuter : outerRadius;
            int floorY = oceanFloorY(context, cx + dir[0] * r, cz + dir[1] * r);
            if (floorY < seaLevel - 3 && ++outerDeepWater > maxOuterDeepWater) {
                return false;
            }
            total++;
            if (floorY >= seaLevel) {
                dry++;
            }
        }

        return ((double) dry / total) >= minDryRatio;
    }

    private static int oceanFloorY(Structure.GenerationContext context, int x, int z) {
        return context.chunkGenerator().getBaseHeight(
                x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
    }
}

