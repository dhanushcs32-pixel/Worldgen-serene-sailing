package com.sereneworldgen.handler;

import com.mojang.logging.LogUtils;
import com.sereneworldgen.datagen.DataGenerators;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SpawnIslandHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int SEARCH_STEP = 16;
    /**
     * How far from (0, 0) the spawn search looks. This matches the guaranteed starter-island bias
     * (SpawnIslandBias.DEFAULT_RADIUS = 130); the island shoreline is ~60 blocks out, so this is generous.
     */
    public static final int MAX_SEARCH_RADIUS = 128;
    public static final int MAX_SEARCH_LAYERS = MAX_SEARCH_RADIUS / SEARCH_STEP;
    public static final int FOOTPRINT_RADIUS = 24;
    public static final double REQUIRED_LAND_RATIO = 0.65;
    /**
     * Safety cap on candidates evaluated. Derived from the search geometry so it can never silently
     * cut the search short of MAX_SEARCH_RADIUS (it used to be a hard-coded 64, which stopped at ~64 blocks).
     */
    public static final int MAX_SEARCH_ITERATIONS = SpawnSearchPattern.candidateCount(MAX_SEARCH_LAYERS);

    /** How long (ticks) to keep retrying the starter flock while the spawn chunk finishes loading. */
    private static final int STARTER_FLOCK_MAX_WAIT_TICKS = 20 * 30;

    private static final class PendingFlock {
        final ServerLevel level;
        final BlockPos pos;
        int ticksLeft = STARTER_FLOCK_MAX_WAIT_TICKS;

        PendingFlock(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }
    }

    /** Server-thread only (all handlers below run on the server thread). */
    private static final List<PendingFlock> PENDING_FLOCKS = new ArrayList<>();

    /** One flock per 64x64 cell this session; independent of entity queries (see FlockGrid). */
    private static final FlockGrid FLOCK_GRID = new FlockGrid();

    /**
     * True only for worlds generated with the Serene Sailing "endless_ocean" noise settings.
     * Without this guard the handlers below would also move spawn and add sheep in vanilla worlds.
     */
    public static boolean isEndlessOcean(ServerLevel level) {
        return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noiseGenerator
                && noiseGenerator.generatorSettings().is(DataGenerators.ENDLESS_OCEAN_KEY);
    }

    @SubscribeEvent
    public static void onCreateSpawnPosition(LevelEvent.CreateSpawnPosition event) {
        try {
            if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
                return;
            }

            if (serverLevel.dimension() != Level.OVERWORLD || !isEndlessOcean(serverLevel)) {
                return;
            }

            LOGGER.info("[SereneOceanWorldgen] Evaluating world spawn for large island near (0, 0)...");

            BlockPos bestCandidate = null;
            double bestLandRatio = 0.0;
            int iterations = 0;

            // Spiral outward from (0, 0) in concentric square rings, nearest ring first
            search:
            for (int layer = 0; layer <= MAX_SEARCH_LAYERS; layer++) {
                for (int[] point : SpawnSearchPattern.ring(layer, SEARCH_STEP)) {
                    if (++iterations > MAX_SEARCH_ITERATIONS) {
                        LOGGER.warn("[SereneOceanWorldgen] Spawn search reached maximum safety iteration limit ({})", MAX_SEARCH_ITERATIONS);
                        break search;
                    }

                    EvaluationResult result = evaluateCandidate(serverLevel, point[0], point[1]);
                    if (result.isValidIsland()) {
                        setWorldSpawn(serverLevel, event, result.spawnPos(), result.landRatio());
                        return;
                    }
                    if (result.landRatio() > bestLandRatio) {
                        bestLandRatio = result.landRatio();
                        bestCandidate = result.spawnPos();
                    }
                }
            }

            // Fallback: If no candidate met the strict island validation threshold, choose the best candidate
            if (bestCandidate != null && bestLandRatio > 0.0) {
                LOGGER.warn("[SereneOceanWorldgen] Strict island threshold not met; falling back to best candidate at {} (dry land: {}%)",
                        bestCandidate, Math.round(bestLandRatio * 100));
                setWorldSpawn(serverLevel, event, bestCandidate, bestLandRatio);
            } else {
                LOGGER.warn("[SereneOceanWorldgen] No suitable island candidate found within radius; allowing vanilla spawn placement.");
            }
        } catch (Exception e) {
            LOGGER.error("[SereneOceanWorldgen] Failed to establish world spawn position: {}", e.getMessage());
        }
    }

    public static EvaluationResult evaluateCandidate(ServerLevel level, int candX, int candZ) {
        try {
            var chunkSource = level.getChunkSource();
            var generator = chunkSource.getGenerator();
            var randomState = chunkSource.randomState();

            // Query surface elevation at candidate center
            int surfaceY;
            ChunkAccess centerChunk = chunkSource.getChunkNow(candX >> 4, candZ >> 4);
            if (centerChunk != null) {
                surfaceY = centerChunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, candX & 15, candZ & 15);
            } else {
                surfaceY = generator.getBaseHeight(candX, candZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            }

            // Submerged candidates (below sea level Y=63) cannot serve as dry starter spawn
            if (surfaceY < 63) {
                return new EvaluationResult(new BlockPos(candX, surfaceY, candZ), 0.0, false);
            }

            BlockPos centerSurfacePos = new BlockPos(candX, surfaceY, candZ);

            int totalColumns = 0;
            int solidDryColumns = 0;

            // Sample footprint within FOOTPRINT_RADIUS using non-blocking queries
            for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx += 8) {
                for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz += 8) {
                    totalColumns++;
                    int sampleX = candX + dx;
                    int sampleZ = candZ + dz;

                    ChunkAccess sampleChunk = chunkSource.getChunkNow(sampleX >> 4, sampleZ >> 4);
                    if (sampleChunk != null) {
                        int colY = sampleChunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX & 15, sampleZ & 15) - 1;
                        if (colY >= 63) {
                            BlockState state = sampleChunk.getBlockState(new BlockPos(sampleX & 15, colY, sampleZ & 15));
                            if (state.getFluidState().isEmpty() && !state.isAir()) {
                                solidDryColumns++;
                            }
                        }
                    } else {
                        int colY = generator.getBaseHeight(sampleX, sampleZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) - 1;
                        if (colY >= 63) {
                            BlockState state = generator.getBaseColumn(sampleX, sampleZ, level, randomState).getBlock(colY);
                            if (state.getFluidState().isEmpty() && !state.isAir()) {
                                solidDryColumns++;
                            }
                        }
                    }
                }
            }

            double landRatio = totalColumns > 0 ? (double) solidDryColumns / totalColumns : 0.0;
            // Short-circuits: the (cheap) biome lookup only runs for footprints that are land-heavy enough
            boolean validIsland = (landRatio >= REQUIRED_LAND_RATIO)
                    && isHabitableBiome(generator, randomState, candX, surfaceY, candZ);

            BlockPos spawnPos = centerSurfacePos.above();
            return new EvaluationResult(spawnPos, landRatio, validIsland);
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Error evaluating candidate at ({}, {}): {}", candX, candZ, e.getMessage());
            return new EvaluationResult(new BlockPos(candX, 64, candZ), 0.0, false);
        }
    }

    /**
     * Whether the biome at the candidate can support a starter base (trees and/or grass with sheep).
     * <p>
     * This replaces a block-based "vegetation" check that could never fail: before chunks exist the
     * only block data available is {@code getBaseColumn}, which contains just stone/water/air (no
     * surface rules, so never grass or logs), and both branches of the old check set the flag to true.
     * The biome source is available before generation, so use it instead.
     */
    static boolean isHabitableBiome(ChunkGenerator generator, RandomState randomState, int x, int y, int z) {
        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
                QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), randomState.sampler());
        return biome.is(BiomeTags.IS_FOREST)
                || biome.is(BiomeTags.IS_TAIGA)
                || biome.is(BiomeTags.IS_JUNGLE)
                || biome.is(BiomeTags.IS_SAVANNA)
                || biome.is(Biomes.PLAINS)
                || biome.is(Biomes.CHERRY_GROVE);
    }

    private static void setWorldSpawn(ServerLevel serverLevel, LevelEvent.CreateSpawnPosition event, BlockPos spawnPos, double landRatio) {
        try {
            serverLevel.setDefaultSpawnPos(spawnPos, 0.0f);
            if (serverLevel.getLevelData() instanceof ServerLevelData serverLevelData) {
                serverLevelData.setSpawn(spawnPos, 0.0f);
            }
            if (event.getSettings() != null) {
                event.getSettings().setSpawn(spawnPos, 0.0f);
            }
            event.setCanceled(true);

            LOGGER.info("[SereneOceanWorldgen] Successfully established safe World Spawn at {} on island (dry land: {}%)",
                    spawnPos, Math.round(landRatio * 100));

            // The spawn chunk does not exist yet at this point (this runs during world creation, before
            // spawn chunks are prepared). Queue the flock and retry each tick until the chunk is loaded.
            PENDING_FLOCKS.add(new PendingFlock(serverLevel, spawnPos));
        } catch (Exception e) {
            LOGGER.error("[SereneOceanWorldgen] Error setting world spawn position: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_FLOCKS.isEmpty()) {
            return;
        }

        Iterator<PendingFlock> iterator = PENDING_FLOCKS.iterator();
        while (iterator.hasNext()) {
            PendingFlock pending = iterator.next();
            try {
                if (pending.level.hasChunk(pending.pos.getX() >> 4, pending.pos.getZ() >> 4)) {
                    FLOCK_GRID.tryReserve(pending.pos.getX(), pending.pos.getZ()); // chunk flocks skip this cell
                    spawnSheepFlock(pending.level, pending.pos, 2, 3);
                    iterator.remove();
                } else if (--pending.ticksLeft <= 0) {
                    LOGGER.warn("[SereneOceanWorldgen] Spawn chunk at {} never finished loading; starter sheep skipped", pending.pos);
                    iterator.remove();
                }
            } catch (Exception e) {
                LOGGER.warn("[SereneOceanWorldgen] Starter sheep spawn failed at {}: {}", pending.pos, e.getMessage());
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_FLOCKS.clear();
        FLOCK_GRID.clear();
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

            if (serverLevel.dimension() != Level.OVERWORLD || !isEndlessOcean(serverLevel)) {
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
                            if (serverLevel.hasChunk(chunkPos.x, chunkPos.z) && FLOCK_GRID.tryReserve(cx, cz)) {
                                // Secondary check for sheep from earlier sessions; the grid is the real limiter
                                AABB checkArea = new AABB(surfacePos).inflate(48.0);
                                List<Sheep> existing = serverLevel.getEntitiesOfClass(Sheep.class, checkArea);
                                if (existing.size() < 3) {
                                    spawnSheepFlock(serverLevel, surfacePos, 2, 3);
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
