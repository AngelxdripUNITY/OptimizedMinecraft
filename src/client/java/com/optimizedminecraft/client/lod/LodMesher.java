package com.optimizedminecraft.client.lod;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * Turns a node's height grid into quads: greedy-merged tops (big flat areas and oceans
 * collapse into a few quads), side walls where terrain steps down, and skirts on the node
 * edge that hide cracks against neighbours drawn at a different level.
 *
 * Vertex layout matches POSITION_COLOR: 3 floats relative to the node origin + RGBA bytes.
 */
final class LodMesher {
	static final int VERTEX_BYTES = 16;
	static final int QUAD_BYTES = VERTEX_BYTES * 4;

	// Vanilla's fixed face shading: east/west faces 0.6, north/south 0.8, tops 1.0.
	private static final float WALL_SHADE_X = 0.6F;
	private static final float WALL_SHADE_Z = 0.8F;

	private LodMesher() {
	}

	record Mesh(ByteBuffer vertices, int quads, float minY, float maxY) {
	}

	/** Returns a mesh in memory from {@link MemoryUtil#memAlloc}; the caller frees it. Null when the node is empty. */
	static Mesh build(short[] heights, int[] colors, int cellSize) {
		int[] topColors = shadedTops(heights, colors, cellSize);
		int quads = emit(heights, topColors, colors, cellSize, null);
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (int j = 1; j <= LodNode.CELLS; j++) {
			for (int i = 1; i <= LodNode.CELLS; i++) {
				short h = heights[i + j * LodNode.GRID];
				if (h != LodNode.NO_HEIGHT) {
					minY = Math.min(minY, h - 2 * cellSize);
					maxY = Math.max(maxY, h);
				}
			}
		}
		if (quads == 0) {
			return null;
		}
		ByteBuffer buffer = MemoryUtil.memAlloc(quads * QUAD_BYTES);
		emit(heights, topColors, colors, cellSize, buffer);
		buffer.flip();
		return new Mesh(buffer, quads, minY, maxY);
	}

	/**
	 * Fake lighting for the tops, which are too coarse for real per-block shading: slopes facing
	 * away from the north-west sky darken, and cells sunk below their neighbours (valleys,
	 * river beds) darken like ambient occlusion would. Never brighter than vanilla's 1.0, and
	 * quantised so flat ground keeps one colour and still merges into large quads.
	 */
	private static int[] shadedTops(short[] heights, int[] colors, int cellSize) {
		int[] shaded = new int[heights.length];
		for (int j = 1; j <= LodNode.CELLS; j++) {
			for (int i = 1; i <= LodNode.CELLS; i++) {
				int index = i + j * LodNode.GRID;
				short h = heights[index];
				if (h == LodNode.NO_HEIGHT) {
					continue;
				}
				int east = heightOr(heights, index + 1, h);
				int west = heightOr(heights, index - 1, h);
				int south = heightOr(heights, index + LodNode.GRID, h);
				int north = heightOr(heights, index - LodNode.GRID, h);
				float slope = ((east - west) + (south - north)) / (2.0F * cellSize);
				float shade = Math.max(0.7F, Math.min(1.0F, 1.0F - 0.3F * slope));
				float sunk = (Math.max(0, east - h) + Math.max(0, west - h) + Math.max(0, south - h) + Math.max(0, north - h)) / (4.0F * cellSize);
				shade *= 1.0F - Math.min(0.25F, sunk * 0.25F);
				shade = Math.round(shade * 20.0F) / 20.0F;
				shaded[index] = shade == 1.0F ? colors[index] : LodColors.scale(colors[index], shade);
			}
		}
		return shaded;
	}

	private static int heightOr(short[] heights, int index, short fallback) {
		short h = heights[index];
		return h == LodNode.NO_HEIGHT ? fallback : h;
	}

	/** Counts quads when {@code out} is null, otherwise writes them. */
	private static int emit(short[] heights, int[] topColors, int[] colors, int cellSize, ByteBuffer out) {
		int quads = 0;
		int grid = LodNode.GRID;
		for (int j = 1; j <= LodNode.CELLS; j++) {
			float z0 = (j - 1) * cellSize;
			float z1 = z0 + cellSize;
			int i = 1;
			while (i <= LodNode.CELLS) {
				int index = i + j * grid;
				short h = heights[index];
				if (h == LodNode.NO_HEIGHT) {
					i++;
					continue;
				}
				int color = topColors[index];
				int end = i + 1;
				while (end <= LodNode.CELLS && heights[end + j * grid] == h && topColors[end + j * grid] == color) {
					end++;
				}
				if (out != null) {
					quad(out, (i - 1) * cellSize, h, z0, (end - 1) * cellSize, h, z0, (end - 1) * cellSize, h, z1, (i - 1) * cellSize, h, z1, color);
				}
				quads++;
				i = end;
			}
		}

		for (int j = 1; j <= LodNode.CELLS; j++) {
			for (int i = 1; i <= LodNode.CELLS; i++) {
				int index = i + j * grid;
				short h = heights[index];
				if (h == LodNode.NO_HEIGHT) {
					continue;
				}
				float x0 = (i - 1) * cellSize;
				float x1 = x0 + cellSize;
				float z0 = (j - 1) * cellSize;
				float z1 = z0 + cellSize;
				int color = colors[index];
				quads += wall(out, heights, index - 1, i == 1, h, cellSize, x0, z0, x0, z1, color, WALL_SHADE_X);
				quads += wall(out, heights, index + 1, i == LodNode.CELLS, h, cellSize, x1, z0, x1, z1, color, WALL_SHADE_X);
				quads += wall(out, heights, index - grid, j == 1, h, cellSize, x0, z0, x1, z0, color, WALL_SHADE_Z);
				quads += wall(out, heights, index + grid, j == LodNode.CELLS, h, cellSize, x0, z1, x1, z1, color, WALL_SHADE_Z);
			}
		}
		return quads;
	}

	private static int wall(ByteBuffer out, short[] heights, int neighbor, boolean nodeEdge, short h, int cellSize,
			float ax, float az, float bx, float bz, int color, float shade) {
		short nh = heights[neighbor];
		float bottom;
		if (nodeEdge) {
			// Skirt: always reach below the neighbour, which may be drawn by a node of another level.
			bottom = Math.min(h, nh == LodNode.NO_HEIGHT ? h : nh) - 2.0F * cellSize;
		} else if (nh == LodNode.NO_HEIGHT) {
			bottom = h - 2.0F * cellSize;
		} else if (nh < h) {
			bottom = nh;
		} else {
			return 0;
		}
		if (out != null) {
			quad(out, ax, bottom, az, bx, bottom, bz, bx, h, bz, ax, h, az, LodColors.scale(color, shade));
		}
		return 1;
	}

	private static void quad(ByteBuffer out, float x0, float y0, float z0, float x1, float y1, float z1,
			float x2, float y2, float z2, float x3, float y3, float z3, int rgb) {
		vertex(out, x0, y0, z0, rgb);
		vertex(out, x1, y1, z1, rgb);
		vertex(out, x2, y2, z2, rgb);
		vertex(out, x3, y3, z3, rgb);
	}

	private static void vertex(ByteBuffer out, float x, float y, float z, int rgb) {
		out.putFloat(x).putFloat(y).putFloat(z);
		out.put((byte) (rgb >> 16)).put((byte) (rgb >> 8)).put((byte) rgb).put((byte) 0xFF);
	}
}
