package com.optimizedminecraft.client.lod;

final class LodColors {
	private LodColors() {
	}

	/** Biome water colour, darker the deeper it is (same for predicted and captured water so the seam matches). */
	static int water(int biomeWaterColor, int depth) {
		return scale(biomeWaterColor, Math.max(0.45F, 1.0F - depth / 48.0F));
	}

	static int scale(int rgb, float factor) {
		int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
		int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
		int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
		return (r << 16) | (g << 8) | b;
	}
}
