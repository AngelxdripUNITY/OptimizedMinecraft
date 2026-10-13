package com.optimizedminecraft.client.lod;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * Exact LOD data taken from chunks the client really received, at 4x4 blocks per cell.
 * It outranks the noise prediction, includes trees and buildings, and is the only data
 * source in multiplayer. It stays after the chunk itself unloads, so walked-through
 * terrain keeps showing at full LOD detail.
 */
final class CapturedChunks {
	static final int CELLS = 4;
	private static final int MAX_CHUNKS = 600_000;

	record Columns(short[] heights, int[] colors) {
	}

	private final ConcurrentHashMap<Long, Columns> chunks = new ConcurrentHashMap<>();

	int size() {
		return this.chunks.size();
	}

	void clear() {
		this.chunks.clear();
	}

	Columns get(int chunkX, int chunkZ) {
		return this.chunks.get(ChunkPos.asLong(chunkX, chunkZ));
	}

	/** Must run on the client thread (reads blocks and biomes from the client level). */
	void capture(ClientLevel level, LevelChunk chunk, BlockColorCache blockColors) {
		if (this.chunks.size() >= MAX_CHUNKS) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		short[] heights = new short[CELLS * CELLS];
		int[] colors = new int[CELLS * CELLS];
		BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		int minY = level.getMinY();
		for (int j = 0; j < CELLS; j++) {
			for (int i = 0; i < CELLS; i++) {
				int x = pos.getMinBlockX() + i * 4 + 2;
				int z = pos.getMinBlockZ() + j * 4 + 2;
				// MOTION_BLOCKING skips grass tufts and flowers but keeps leaves and water.
				int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
				int slot = i + j * CELLS;
				if (y < minY) {
					heights[slot] = LodNode.NO_HEIGHT;
					continue;
				}
				BlockState state = chunk.getBlockState(mutable.set(x, y, z));
				MapColor mapColor = state.getMapColor(level, mutable);
				if (mapColor == MapColor.NONE && y > minY) {
					state = chunk.getBlockState(mutable.set(x, y - 1, z));
					mapColor = state.getMapColor(level, mutable);
				}
				heights[slot] = (short) (y + 1);
				if (state.getFluidState().is(FluidTags.WATER)) {
					int water = BlockColorCache.multiply(blockColors.waterTexture(), blockColors.waterTint(level, mutable));
					colors[slot] = LodColors.water(water, waterDepth(chunk, mutable, minY));
				} else {
					int color = blockColors.topColor(level, state, mutable);
					colors[slot] = color >= 0 ? color : LodColors.scale(mapColor.col, 0.7F);
				}
			}
		}
		this.chunks.put(pos.toLong(), new Columns(heights, colors));
	}

	private static int waterDepth(LevelChunk chunk, BlockPos.MutableBlockPos pos, int minY) {
		int top = pos.getY();
		int y = top;
		while (y > minY && top - y < 64 && chunk.getBlockState(pos.setY(y - 1)).getFluidState().is(FluidTags.WATER)) {
			y--;
		}
		pos.setY(top);
		return top - y + 1;
	}

	/** Copies the captured column at block (x, z) into slot {@code i}; false if that chunk was never received. */
	boolean sample(int x, int z, int i, short[] heights, int[] colors) {
		Columns columns = this.chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
		if (columns == null) {
			return false;
		}
		int cell = ((x & 15) >> 2) + ((z & 15) >> 2) * CELLS;
		heights[i] = columns.heights[cell];
		colors[i] = columns.colors[cell];
		return true;
	}
}
