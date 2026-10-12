package com.optimizedminecraft.client.hud;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;
import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.OptimizedMC;
import com.optimizedminecraft.client.lod.LodManager;
import com.optimizedminecraft.client.lod.LodNode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/**
 * Overlay map of what is actually loaded around you: green = real chunks, shades of blue to
 * orange = LOD clusters by detail level (finest to coarsest), dark = nothing yet.
 */
public final class ChunkMapHud {
	private static final int SIZE = 160;
	private static final int REAL_CHUNK = 0xFF4CAF50;
	private static final int EMPTY = 0xC0141414;
	private static final int[] LEVEL_COLORS = {
			0xFF26C6DA, 0xFF42A5F5, 0xFF5C6BC0, 0xFF7E57C2, 0xFFAB47BC, 0xFFEC407A, 0xFFFF7043, 0xFF8D6E63
	};
	private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "chunk_map");

	private static DynamicTexture texture;
	private static long lastUpdate;

	private ChunkMapHud() {
	}

	public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		if (!OMConfig.get().showChunkMap || client.level == null || client.player == null) {
			return;
		}
		if (texture == null) {
			texture = new DynamicTexture(() -> "OptimizedMinecraft chunk map", SIZE, SIZE, true);
			client.getTextureManager().register(TEXTURE_ID, texture);
		}
		long now = System.currentTimeMillis();
		if (now - lastUpdate > 250) {
			lastUpdate = now;
			paint(client);
			texture.upload();
		}

		int x = 6;
		int y = 6;
		graphics.fill(x - 2, y - 2, x + SIZE + 2, y + SIZE + 2, 0xA0000000);
		graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x, y, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);

		List<String> lines = statLines(client);
		Font font = client.font;
		int textY = y + SIZE + 6;
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, font.width(line));
		}
		graphics.fill(x - 2, textY - 2, x + width + 2, textY + lines.size() * 10, 0xA0000000);
		for (String line : lines) {
			graphics.drawString(font, line, x, textY, 0xFFFFFFFF);
			textY += 10;
		}
	}

	private static void paint(Minecraft client) {
		NativeImage image = texture.getPixels();
		if (image == null) {
			return;
		}
		int renderDistance = client.options.getEffectiveRenderDistance();
		LodManager lods = LodManager.INSTANCE;
		int radiusChunks = Math.max(renderDistance + 2, lods.isActive() ? OMConfig.get().clampedLodDistance() : 0);
		double chunksPerPixel = (2.0 * radiusChunks + 1) / SIZE;
		ChunkPos center = client.player.chunkPosition();

		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				image.setPixel(px, py, EMPTY);
			}
		}
		// Loaded LOD clusters, coarse first so finer ones paint over them.
		List<LodNode> drawn = lods.readyNodes();
		drawn.sort((a, b) -> Integer.compare(b.level, a.level));
		for (LodNode node : drawn) {
			int x0 = toPixel((node.minX() >> 4) - center.x, radiusChunks, chunksPerPixel);
			int z0 = toPixel((node.minZ() >> 4) - center.z, radiusChunks, chunksPerPixel);
			int x1 = toPixel(((node.minX() + node.size()) >> 4) - center.x, radiusChunks, chunksPerPixel);
			int z1 = toPixel(((node.minZ() + node.size()) >> 4) - center.z, radiusChunks, chunksPerPixel);
			int color = LEVEL_COLORS[Math.min(node.level, LEVEL_COLORS.length - 1)];
			for (int pz = Math.max(0, z0); pz < Math.min(SIZE, Math.max(z1, z0 + 1)); pz++) {
				for (int px = Math.max(0, x0); px < Math.min(SIZE, Math.max(x1, x0 + 1)); px++) {
					boolean edge = px == x0 || pz == z0;
					image.setPixel(px, pz, edge ? darker(color) : color);
				}
			}
		}
		// Real chunks the client holds right now.
		for (int py = 0; py < SIZE; py++) {
			for (int px = 0; px < SIZE; px++) {
				int dx = (int) Math.floor(px * chunksPerPixel) - radiusChunks;
				int dz = (int) Math.floor(py * chunksPerPixel) - radiusChunks;
				if (Math.abs(dx) > renderDistance + 1 || Math.abs(dz) > renderDistance + 1) {
					continue;
				}
				if (client.level.getChunkSource().hasChunk(center.x + dx, center.z + dz)) {
					image.setPixel(px, py, REAL_CHUNK);
				}
			}
		}
		int mid = SIZE / 2;
		for (int d = -3; d <= 3; d++) {
			image.setPixel(mid + d, mid, 0xFFFFFFFF);
			image.setPixel(mid, mid + d, 0xFFFFFFFF);
		}
	}

	private static int toPixel(int chunkOffset, int radiusChunks, double chunksPerPixel) {
		return (int) Math.floor((chunkOffset + radiusChunks) / chunksPerPixel);
	}

	private static int darker(int argb) {
		return (argb & 0xFF000000) | ((argb >> 1) & 0x7F7F7F);
	}

	private static List<String> statLines(Minecraft client) {
		List<String> lines = new ArrayList<>();
		int renderDistance = client.options.getEffectiveRenderDistance();
		int loaded = client.level.getChunkSource().getLoadedChunksCount();
		lines.add(String.format("Real chunks: %,d loaded (render distance %d)", loaded, renderDistance));
		LodManager.Stats stats = LodManager.INSTANCE.stats();
		if (!stats.active()) {
			lines.add("LOD: off");
			return lines;
		}
		lines.add(String.format("LOD area: ~%,d chunks (%d chunk radius)", stats.coveredChunks(), OMConfig.get().clampedLodDistance()));
		lines.add(String.format("Clusters: %d drawn, %,d quads, %d ready / %d known", stats.drawnClusters(), stats.drawnQuads(),
				stats.readyClusters(), stats.knownClusters()));
		lines.add(String.format("Streaming: %d queued, %d building, %,.0f samples/s, %,d clusters from disk", stats.queued(), stats.building(),
				stats.samplesPerSecond(), stats.clustersFromDisk()));
		lines.add(String.format("GPU: %.1f MB, captured real chunks: %,d", stats.gpuBytes() / 1048576.0, stats.capturedChunks()));
		lines.add(stats.predicting() ? "Source: world generator + captured chunks" : "Source: captured chunks only (multiplayer)");
		return lines;
	}
}
