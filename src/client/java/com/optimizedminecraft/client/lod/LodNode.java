package com.optimizedminecraft.client.lod;

import com.mojang.blaze3d.buffers.GpuBuffer;

/**
 * One terrain cluster in the LOD quadtree: a fixed {@link #CELLS}x{@link #CELLS} height
 * grid. Level 0 cells are {@link #BASE_CELL} blocks wide; every level up doubles the cell
 * size, so a node always costs the same to sample and draw no matter how much ground it covers.
 */
public final class LodNode {
	static final int CELLS = 64;
	static final int BASE_CELL = 4;
	public static final int MAX_LEVEL = 7;
	/** Samples include a one-cell border so edge walls know their neighbours' heights. */
	static final int GRID = CELLS + 2;
	static final short NO_HEIGHT = Short.MIN_VALUE;

	static final int IDLE = 0;
	static final int QUEUED = 1;
	static final int BUILDING = 2;

	public final int level;
	final int nx;
	final int nz;
	final long key;

	/** IDLE, QUEUED or BUILDING; written by both the render thread and workers. */
	volatile int state = IDLE;
	/** Captured real chunks changed inside this node since it was last meshed. */
	volatile boolean dirty;
	/** A mesh has been uploaded at least once, so the node can be drawn. */
	volatile boolean ready;
	volatile long lastWantedFrame;
	volatile float minY = -64;
	volatile float maxY = 320;
	/** Bumped by the manager on world change so stale worker results are dropped. */
	final int epoch;

	/** Kept for levels 0-1 so captured chunks can be patched in without re-running the generator. */
	short[] heights;
	int[] colors;

	// Render thread only.
	GpuBuffer buffer;
	int quadCount;
	long gpuBytes;
	long lastDrawnFrame;

	LodNode(int level, int nx, int nz, int epoch) {
		this.level = level;
		this.nx = nx;
		this.nz = nz;
		this.key = key(level, nx, nz);
		this.epoch = epoch;
	}

	static long key(int level, int nx, int nz) {
		return ((long) level << 60) | (((long) nx & 0x3FFFFFFFL) << 30) | ((long) nz & 0x3FFFFFFFL);
	}

	static int cellSize(int level) {
		return BASE_CELL << level;
	}

	static int size(int level) {
		return CELLS * cellSize(level);
	}

	int cellSize() {
		return cellSize(this.level);
	}

	public int size() {
		return size(this.level);
	}

	public int minX() {
		return this.nx * this.size();
	}

	public int minZ() {
		return this.nz * this.size();
	}
}
