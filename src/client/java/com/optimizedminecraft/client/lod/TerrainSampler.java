package com.optimizedminecraft.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Predicts terrain height and colour straight from the world generator's noise, without
 * generating chunks. One sample costs one noise column instead of a full chunk (features,
 * lighting, saving), which is what makes LODs hundreds of chunks out affordable.
 *
 * Only available in singleplayer, where the integrated server's generator is in reach.
 * Thread-safe: vanilla calls the same methods from its parallel worldgen workers.
 */
final class TerrainSampler {
	private static final int SEARCH_STEP = 8;

	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final BiomeSource biomes;
	private final Climate.Sampler climate;
	private final LevelHeightAccessor heightAccessor;
	private final int seaLevel;
	private final int minY;
	/** Set for noise worlds (the normal overworld): enables the fast surface search. */
	private final NoiseRouter router;
	private final int maxY;

	TerrainSampler(ServerLevel level) {
		ServerChunkCache chunkSource = level.getChunkSource();
		this.generator = chunkSource.getGenerator();
		this.randomState = chunkSource.randomState();
		this.biomes = this.generator.getBiomeSource();
		this.climate = this.randomState.sampler();
		this.heightAccessor = level;
		this.seaLevel = this.generator.getSeaLevel();
		this.minY = level.getMinY();
		this.maxY = level.getMaxY();
		this.router = this.generator instanceof NoiseBasedChunkGenerator ? this.randomState.router() : null;
	}

	/**
	 * Top of the solid ground at (x, z), as top face Y. Vanilla's getBaseHeight builds a whole
	 * NoiseChunk and walks the column down from the build limit; instead start from the
	 * generator's own preliminary surface estimate and probe the final density a few times
	 * around it (about 6-10 noise evaluations).
	 */
	private int surface(int x, int z) {
		if (this.router == null) {
			return this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.heightAccessor, this.randomState);
		}
		int y = Mth.floor(this.router.preliminarySurfaceLevel().compute(new DensityFunction.SinglePointContext(x, 0, z)));
		y = Mth.clamp(y, this.minY + SEARCH_STEP, this.maxY - SEARCH_STEP);
		int solid;
		if (this.solid(x, y, z)) {
			// Climb until two steps of air in a row, so a cave pocket isn't mistaken for the surface.
			while (true) {
				if (y + SEARCH_STEP < this.maxY && this.solid(x, y + SEARCH_STEP, z)) {
					y += SEARCH_STEP;
				} else if (y + 2 * SEARCH_STEP < this.maxY && this.solid(x, y + 2 * SEARCH_STEP, z)) {
					y += 2 * SEARCH_STEP;
				} else {
					break;
				}
			}
			solid = y;
		} else {
			while (y - SEARCH_STEP > this.minY && !this.solid(x, y - SEARCH_STEP, z)) {
				y -= SEARCH_STEP;
			}
			solid = y - SEARCH_STEP;
			if (solid <= this.minY) {
				return this.minY;
			}
		}
		// Binary refine inside the step: solid is solid, solid + step is air.
		int step = SEARCH_STEP / 2;
		while (step >= 1) {
			if (this.solid(x, solid + step, z)) {
				solid += step;
			}
			step /= 2;
		}
		return solid + 1;
	}

	private boolean solid(int x, int y, int z) {
		return this.router.finalDensity().compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0;
	}

	/** Writes the surface height (top face Y) and RGB colour of column (x, z) into slot {@code i}. */
	void sample(int x, int z, int i, short[] heights, int[] colors) {
		int floor = this.surface(x, z);
		if (floor <= this.minY) {
			heights[i] = LodNode.NO_HEIGHT;
			return;
		}
		boolean water = floor < this.seaLevel;
		int surface = water ? this.seaLevel : floor;
		Holder<Biome> biome = this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surface), QuartPos.fromBlock(z), this.climate);
		heights[i] = (short) surface;
		colors[i] = water ? waterColor(biome.value(), this.seaLevel - floor) : this.landColor(biome, x, floor, z);
	}

	private int landColor(Holder<Biome> holder, int x, int y, int z) {
		Biome biome = holder.value();
		if (holder.is(BiomeTags.IS_BADLANDS)) {
			return 0xB8653A;
		}
		if (holder.is(BiomeTags.IS_BEACH) || holder.is(Biomes.DESERT)) {
			return 0xD6C995;
		}
		if (biome.coldEnoughToSnow(new BlockPos(x, y, z), this.seaLevel)) {
			return 0xEDF1F5;
		}
		if (holder.is(Biomes.STONY_PEAKS) || holder.is(Biomes.STONY_SHORE)) {
			return 0x8A8A8A;
		}
		if (holder.is(BiomeTags.IS_FOREST) || holder.is(BiomeTags.IS_TAIGA) || holder.is(BiomeTags.IS_JUNGLE)) {
			return LodColors.scale(biome.getFoliageColor(), 0.72F);
		}
		return LodColors.scale(biome.getGrassColor(x, z), 0.9F);
	}

	private static int waterColor(Biome biome, int depth) {
		return LodColors.water(biome.getWaterColor(), depth);
	}
}
