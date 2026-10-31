package com.sereneworldgen.handler;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

import java.util.List;

public class SpawnIslandHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int SEARCH_STEP = 16;
    public static final int MAX_SEARCH_RADIUS = 1024;
    public static final int FOOTPRINT_RADIUS = 24;
    public static final double REQUIRED_LAND_RATIO = 0.65;
    public static final int MAX_SEARCH_ITERATIONS = 64;

    @SubscribeEvent
    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            if (serverLevel.dimension() != Level.OVERWORLD) {
                return;
            }

            LOGGER.info("[SereneOceanWorldgen] Evaluating world spawn for large island near (0, 0)...");

            BlockPos bestCandidate = null;
            double bestLandRatio = -1.0;

            int maxLayers = Math.min(MAX_SEARCH_RADIUS / SEARCH_STEP, 32);
            int iterations = 0;

            // Spiral outward from (0, 0) in concentric square rings with strict safety limit
            outerLoop:
            for (int layer = 0; layer <= maxLayers; layer++) {
                int radius = layer * SEARCH_STEP;

                if (layer == 0) {
                    if (++iterations > MAX_SEARCH_ITERATIONS) {
                        break;
                    }
                    EvaluationResult result = evaluateCandidate(serverLevel, 0, 0);
                    if (result.isValidIsland()) {
                        setWorldSpawn(serverLevel, event, result.spawnPos(), result.landRatio());
                        return;
                    } else if (result.landRatio() > bestLandRatio) {
                        bestLandRatio = result.landRatio();
                        bestCandidate = result.spawnPos();
                    }
                    continue;
                }

                // Top and bottom edges of the concentric ring
                for (int x = -radius; x <= radius; x += SEARCH_STEP) {
                    for (int z : new int[]{-radius, radius}) {
                        if (++iterations > MAX_SEARCH_ITERATIONS) {
                            LOGGER.warn("[SereneOceanWorldgen] Spawn search reached maximum safety iteration limit ({})", MAX_SEARCH_ITERATIONS);
                            break outerLoop;
                        }
                        EvaluationResult result = evaluateCandidate(serverLevel, x, z);
                        if (result.isValidIsland()) {
                            setWorldSpawn(serverLevel, event, result.spawnPos(), result.landRatio());
                            return;
                        } else if (result.landRatio() > bestLandRatio) {
                            bestLandRatio = result.landRatio();
                            bestCandidate = result.spawnPos();
                        }
                    }
                }

                // Left and right edges (excluding corners already checked)
                for (int z = -radius + SEARCH_STEP; z <= radius - SEARCH_STEP; z += SEARCH_STEP) {
                    for (int x : new int[]{-radius, radius}) {
                        if (++iterations > MAX_SEARCH_ITERATIONS) {
                            LOGGER.warn("[SereneOceanWorldgen] Spawn search reached maximum safety iteration limit ({})", MAX_SEARCH_ITERATIONS);
                            break outerLoop;
                        }
                        EvaluationResult result = evaluateCandidate(serverLevel, x, z);
                        if (result.isValidIsland()) {
                            setWorldSpawn(serverLevel, event, result.spawnPos(), result.landRatio());
                            return;
                        } else if (result.landRatio() > bestLandRatio) {
                            bestLandRatio = result.landRatio();
                            bestCandidate = result.spawnPos();
                        }
                    }
                }
            }

            // Fallback if no candidate strictly reached 65% land ratio
            if (bestCandidate != null) {
                LOGGER.warn("[SereneOceanWorldgen] No candidate reached {:.0f}% land ratio; using best island candidate at {} ({:.1f}%)",
                        REQUIRED_LAND_RATIO * 100, bestCandidate, bestLandRatio * 100);
                setWorldSpawn(serverLevel, event, bestCandidate, bestLandRatio);
            } else {
                LOGGER.warn("[SereneOceanWorldgen] Unable to find suitable island; allowing default spawn.");
            }
        } catch (Exception e) {
            LOGGER.error("[SereneOceanWorldgen] Unexpected exception during spawn position evaluation, falling back safely", e);
        }
    }

    public static EvaluationResult evaluateCandidate(ServerLevel level, int candX, int candZ) {
        try {
            var chunkSource = level.getChunkSource();
            var generator = chunkSource.getGenerator();
            var randomState = chunkSource.randomState();

            int surfaceY;
            ChunkAccess centerChunk = chunkSource.getChunkNow(candX >> 4, candZ >> 4);
            if (centerChunk != null) {
                surfaceY = centerChunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candX & 15, candZ & 15) - 1;
            } else {
                surfaceY = generator.getBaseHeight(candX, candZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
            }

            // Candidate center must be at or above sea level (Y >= 63)
            if (surfaceY < 63) {
                return new EvaluationResult(new BlockPos(candX, 64, candZ), 0.0, false);
            }

            BlockPos centerSurfacePos = new BlockPos(candX, surfaceY, candZ);
            if (centerChunk != null) {
                BlockState centerState = centerChunk.getBlockState(new BlockPos(candX & 15, surfaceY, candZ & 15));
                if (!centerState.getFluidState().isEmpty() || centerState.isAir()) {
                    return new EvaluationResult(centerSurfacePos.above(), 0.0, false);
                }
            }

            int totalColumns = 0;
            int solidDryColumns = 0;
            boolean hasVegetation = false;

            // Sample footprint within FOOTPRINT_RADIUS using non-blocking queries
            for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx += 8) {
                for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz += 8) {
                    if (dx * dx + dz * dz > FOOTPRINT_RADIUS * FOOTPRINT_RADIUS) {
                        continue;
                    }

                    totalColumns++;
                    int sampleX = candX + dx;
                    int sampleZ = candZ + dz;

                    int colY;
                    ChunkAccess sampleChunk = chunkSource.getChunkNow(sampleX >> 4, sampleZ >> 4);
                    if (sampleChunk != null) {
                        colY = sampleChunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX & 15, sampleZ & 15) - 1;
                        if (colY >= 63) {
                            BlockPos pos = new BlockPos(sampleX & 15, colY, sampleZ & 15);
                            BlockState state = sampleChunk.getBlockState(pos);
                            if (state.getFluidState().isEmpty() && !state.isAir()) {
                                solidDryColumns++;
                                if (state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.SHORT_GRASS)) {
                                    hasVegetation = true;
                                }
                            }
                        }
                    } else {
                        colY = generator.getBaseHeight(sampleX, sampleZ, Heightmap.Types.WORLD_SURFACE_WG, level, randomState) - 1;
                        if (colY >= 63) {
                            solidDryColumns++;
                            hasVegetation = true;
                        }
                    }
                }
            }

            double landRatio = totalColumns > 0 ? (double) solidDryColumns / totalColumns : 0.0;
            boolean validIsland = (landRatio >= REQUIRED_LAND_RATIO) && hasVegetation;

            BlockPos spawnPos = centerSurfacePos.above();
            return new EvaluationResult(spawnPos, landRatio, validIsland);
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Error evaluating candidate at ({}, {}): {}", candX, candZ, e.getMessage());
            return new EvaluationResult(new BlockPos(candX, 64, candZ), 0.0, false);
        }
    }

    private static void setWorldSpawn(ServerLevel serverLevel, LevelEvent.CreateSpawnPosition event, BlockPos spawnPos, double landRatio) {
        try {
            serverLevel.setDefaultSpawnPos(spawnPos, 0.0f);
            if (serverLevel.getLevelData() instanceof ServerLevelData serverLevelData) {
                serverLevelData.setSpawn(spawnPos, 0.0f);
            }
            event.setCanceled(true);

            LOGGER.info("[SereneOceanWorldgen] Successfully established safe World Spawn at {} on island (dry land: {:.1f}%)",
                    spawnPos, landRatio * 100);

            // Defer starter sheep flock spawning until world and spawn chunks are loaded
            if (serverLevel.getServer() != null) {
                serverLevel.getServer().execute(() -> {
                    try {
                        if (serverLevel.hasChunk(spawnPos.getX() >> 4, spawnPos.getZ() >> 4)) {
                            spawnSheepFlock(serverLevel, spawnPos, 3, 4);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("[SereneOceanWorldgen] Deferred starter sheep spawn skipped: {}", e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            LOGGER.error("[SereneOceanWorldgen] Error setting world spawn position: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        try {
            if (!event.isNewChunk()) {
                return;
            }

            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            if (serverLevel.dimension() != Level.OVERWORLD) {
                return;
            }

            ChunkAccess chunk = event.getChunk();
            if (!(chunk instanceof LevelChunk)) {
                return;
            }

            ChunkPos chunkPos = chunk.getPos();
            int cx = chunkPos.getMinBlockX() + 8;
            int cz = chunkPos.getMinBlockZ() + 8;
            int surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
            if (surfaceY < 63) {
                return;
            }

            BlockPos surfacePos = new BlockPos(cx, surfaceY, cz);
            BlockState ground = chunk.getBlockState(new BlockPos(8, surfaceY - 1, 8));

            // Whenever grass/dirt blocks (Y >= 63) are present on the island chunk
            if (ground.is(BlockTags.ANIMALS_SPAWNABLE_ON) || ground.is(Blocks.GRASS_BLOCK) || ground.is(BlockTags.DIRT)) {
                if (serverLevel.getServer() != null) {
                    serverLevel.getServer().execute(() -> {
                        try {
                            if (serverLevel.hasChunk(chunkPos.x, chunkPos.z)) {
                                AABB checkArea = new AABB(surfacePos).inflate(48.0);
                                List<Sheep> existing = serverLevel.getEntitiesOfClass(Sheep.class, checkArea);
                                if (existing.isEmpty()) {
                                    spawnSheepFlock(serverLevel, surfacePos, 2, 4);
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[SereneOceanWorldgen] Error verifying chunk sheep population at {}: {}", surfacePos, e.getMessage());
                        }
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Error during chunk load handler: {}", e.getMessage());
        }
    }

    public static void spawnSheepFlock(ServerLevel serverLevel, BlockPos centerPos, int minCount, int maxCount) {
        try {
            if (serverLevel == null || centerPos == null) {
                return;
            }

            RandomSource random = serverLevel.getRandom();
            int count = minCount + (maxCount > minCount ? random.nextInt(maxCount - minCount + 1) : 0);
            int spawned = 0;

            for (int attempt = 0; attempt < count * 5 && spawned < count; attempt++) {
                int ox = (attempt == 0) ? 0 : random.nextInt(15) - 7;
                int oz = (attempt == 0) ? 0 : random.nextInt(15) - 7;
                int targetX = centerPos.getX() + ox;
                int targetZ = centerPos.getZ() + oz;

                // Non-blocking chunk check before querying height or block state
                if (!serverLevel.hasChunk(targetX >> 4, targetZ >> 4)) {
                    continue;
                }

                int targetY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
                BlockPos sheepPos = new BlockPos(targetX, targetY, targetZ);
                BlockState ground = serverLevel.getBlockState(sheepPos.below());

                if (targetY >= 63 && (ground.is(BlockTags.ANIMALS_SPAWNABLE_ON) || ground.is(Blocks.GRASS_BLOCK) || ground.is(BlockTags.DIRT))) {
                    Sheep sheep = EntityType.SHEEP.create(serverLevel);
                    if (sheep != null) {
                        sheep.moveTo(targetX + 0.5, targetY, targetZ + 0.5, random.nextFloat() * 360.0f, 0.0f);
                        sheep.finalizeSpawn(serverLevel, serverLevel.getCurrentDifficultyAt(sheepPos), MobSpawnType.CHUNK_GENERATION, null);
                        serverLevel.addFreshEntity(sheep);
                        spawned++;
                    }
                }
            }

            if (spawned > 0) {
                LOGGER.info("[SereneOceanWorldgen] Placed flock of {} sheep at {}", spawned, centerPos);
            }
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Failed to spawn sheep flock near {}: {}", centerPos, e.getMessage());
        }
    }

    public record EvaluationResult(BlockPos spawnPos, double landRatio, boolean isValidIsland) {}
}
