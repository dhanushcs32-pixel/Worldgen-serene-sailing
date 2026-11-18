package com.sereneworldgen.handler;

import com.mojang.logging.LogUtils;
import com.sereneworldgen.datagen.DataGenerators;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
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
     * Total number of candidates in the radial search (rings 0 through MAX_SEARCH_LAYERS).
     */
    public static final int MAX_SEARCH_ITERATIONS = SpawnSearchPattern.candidateCount(MAX_SEARCH_LAYERS);

    /** How long (ticks) to keep retrying the starter flock while the spawn chunk finishes loading. */
    private static final int STARTER_FLOCK_MAX_WAIT_TICKS = 20 * 30;

    public record ColumnSample(int surfaceY, boolean isSolidDry) {}

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
            Long2ObjectMap<ColumnSample> columnCache = new Long2ObjectOpenHashMap<>();

            // Spiral outward from (0, 0) in concentric square rings, nearest ring first
            for (int layer = 0; layer <= MAX_SEARCH_LAYERS; layer++) {
                for (int[] point : SpawnSearchPattern.ring(layer, SEARCH_STEP)) {
                    EvaluationResult result = evaluateCandidate(serverLevel, point[0], point[1], columnCache);
                    if (result.isValidIsland()) {
                        setWorldSpawn(serverLevel, event, result.spawnPos(), result.landRatio());
                        return;
                    }
                    if (result.landRatio() > bestLandRatio) {
                        bestLandRatio = result.landRatio();
                        bestCandidate = result.spawnPos();
                    }
                }

                // If after searching the inner island core (rings 0..2, up to radius 32) we have already found
                // an island with strong dry land (>= 65%), but the climate is non-habitable (e.g. desert, badlands, snowy),
                // further outer rings will remain in the same macro-biome. Avoid scanning outer rings needlessly.
                if (layer >= 2 && bestLandRatio >= REQUIRED_LAND_RATIO && bestCandidate != null) {
                    LOGGER.info("[SereneOceanWorldgen] Strong dry island found at {} (dry land: {}%) in non-habitable climate; using early fallback.",
                            bestCandidate, Math.round(bestLandRatio * 100));
                    break;
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
            LOGGER.error("[SereneOceanWorldgen] Failed to establish world spawn position", e);
        }
    }

    public static EvaluationResult evaluateCandidate(ServerLevel level, int candX, int candZ) {
        return evaluateCandidate(level, candX, candZ, new Long2ObjectOpenHashMap<>());
    }

    public static EvaluationResult evaluateCandidate(ServerLevel level, int candX, int candZ, Long2ObjectMap<ColumnSample> cache) {
        try {
            ServerChunkCache chunkSource = level.getChunkSource();
            ChunkGenerator generator = chunkSource.getGenerator();
            RandomState randomState = chunkSource.randomState();

            // Query surface elevation at candidate center
            ColumnSample centerSample = getOrComputeColumn(level, chunkSource, generator, randomState, cache, candX, candZ);
            int surfaceY = centerSample.surfaceY();

            // Submerged candidates (below sea level Y=63) cannot serve as dry starter spawn
            if (surfaceY < 63 || !centerSample.isSolidDry()) {
                return new EvaluationResult(new BlockPos(candX, Math.max(surfaceY, 64), candZ), 0.0, false);
            }

            // surfaceY already represents the first free air block above ground (highest_solid_y + 1).
            // Player feet spawn directly at centerSurfacePos.
            BlockPos centerSurfacePos = new BlockPos(candX, surfaceY, candZ);

            int totalColumns = 0;
            int solidDryColumns = 0;

            // Sample footprint within FOOTPRINT_RADIUS using cached non-blocking queries
            for (int dx = -FOOTPRINT_RADIUS; dx <= FOOTPRINT_RADIUS; dx += 8) {
                for (int dz = -FOOTPRINT_RADIUS; dz <= FOOTPRINT_RADIUS; dz += 8) {
                    totalColumns++;
                    int sampleX = candX + dx;
                    int sampleZ = candZ + dz;

                    ColumnSample col = getOrComputeColumn(level, chunkSource, generator, randomState, cache, sampleX, sampleZ);
                    if (col.isSolidDry() && col.surfaceY() >= 63) {
                        solidDryColumns++;
                    }
                }
            }

            double landRatio = totalColumns > 0 ? (double) solidDryColumns / totalColumns : 0.0;
            boolean validIsland = (landRatio >= REQUIRED_LAND_RATIO)
                    && isHabitableBiome(generator, randomState, candX, surfaceY, candZ);

            return new EvaluationResult(centerSurfacePos, landRatio, validIsland);
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Error evaluating candidate at ({}, {})", candX, candZ, e);
            return new EvaluationResult(new BlockPos(candX, 64, candZ), 0.0, false);
        }
    }

    public static ColumnSample getOrComputeColumn(ServerLevel level, ServerChunkCache chunkSource, ChunkGenerator generator,
                                                  RandomState randomState, Long2ObjectMap<ColumnSample> cache, int x, int z) {
        long key = (((long) x) << 32) | (z & 0xFFFFFFFFL);
        ColumnSample cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        int surfaceY;
        boolean solidDry = false;
        ChunkAccess chunk = chunkSource.getChunkNow(x >> 4, z >> 4);
        if (chunk != null) {
            surfaceY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            if (surfaceY >= 63) {
                BlockState state = chunk.getBlockState(new BlockPos(x & 15, surfaceY - 1, z & 15));
                solidDry = state.getFluidState().isEmpty() && !state.isAir();
            }
        } else {
            surfaceY = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
            if (surfaceY >= 63) {
                BlockState state = generator.getBaseColumn(x, z, level, randomState).getBlock(surfaceY - 1);
                solidDry = state.getFluidState().isEmpty() && !state.isAir();
            }
        }

        ColumnSample sample = new ColumnSample(surfaceY, solidDry);
        cache.put(key, sample);
        return sample;
    }

    /**
     * Whether the biome at the candidate can support a starter base (trees and/or grass with sheep).
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
            LOGGER.error("[SereneOceanWorldgen] Error setting world spawn position", e);
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
                    FlockGridSavedData.get(pending.level).tryReserve(pending.pos.getX(), pending.pos.getZ());
                    spawnSheepFlock(pending.level, pending.pos, 2, 3);
                    iterator.remove();
                } else if (--pending.ticksLeft <= 0) {
                    LOGGER.warn("[SereneOceanWorldgen] Spawn chunk at {} never finished loading; starter sheep skipped", pending.pos);
                    iterator.remove();
                }
            } catch (Exception e) {
                LOGGER.warn("[SereneOceanWorldgen] Starter sheep spawn failed at {}", pending.pos, e);
                iterator.remove();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING_FLOCKS.clear();
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
                            if (serverLevel.hasChunk(chunkPos.x, chunkPos.z) && FlockGridSavedData.get(serverLevel).tryReserve(cx, cz)) {
                                // Secondary check for sheep from earlier sessions; the grid is the real limiter
                                AABB checkArea = new AABB(surfacePos).inflate(48.0);
                                List<Sheep> existing = serverLevel.getEntitiesOfClass(Sheep.class, checkArea);
                                if (existing.size() < 3) {
                                    spawnSheepFlock(serverLevel, surfacePos, 2, 3);
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.warn("[SereneOceanWorldgen] Error verifying chunk sheep population at {}", surfacePos, e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[SereneOceanWorldgen] Error during chunk load handler", e);
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
            LOGGER.warn("[SereneOceanWorldgen] Failed to spawn sheep flock near {}", centerPos, e);
        }
    }

    public record EvaluationResult(BlockPos spawnPos, double landRatio, boolean isValidIsland) {}
}
