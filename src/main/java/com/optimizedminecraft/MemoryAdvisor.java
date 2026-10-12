package com.optimizedminecraft;

/**
 * Rough heap sizing for a given render distance.
 *
 * In singleplayer both the integrated server and the client keep every chunk
 * column inside the view distance in memory, so heap use grows with the square
 * of the render distance. The per-column figure is a deliberately generous
 * estimate for a normal (non-flat) world with both sides loaded.
 */
public final class MemoryAdvisor {
	private static final long BASE_MB = 1536;
	private static final double MB_PER_COLUMN = 0.35;

	private MemoryAdvisor() {
	}

	public static long recommendedHeapMb(int renderDistance) {
		long side = 2L * renderDistance + 1;
		return BASE_MB + Math.round(side * side * MB_PER_COLUMN);
	}

	public static long maxHeapMb() {
		return Runtime.getRuntime().maxMemory() / (1024 * 1024);
	}
}
