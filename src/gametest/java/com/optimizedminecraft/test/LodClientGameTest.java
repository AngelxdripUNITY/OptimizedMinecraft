package com.optimizedminecraft.test;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

import javax.imageio.ImageIO;

import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.client.lod.LodManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads a normal (non-flat) world with LODs on, waits for the cluster streaming to settle
 * and screenshots the horizon, the LOD level view and the chunk map.
 */
public class LodClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("OptimizedMinecraftTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		OMConfig config = OMConfig.get();
		config.lodEnabled = true;
		config.lodDistance = 256;
		config.lodDebugColors = false;
		config.showChunkMap = false;
		context.runOnClient(client -> client.options.renderDistance().set(8));

		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> settings.setSeed("optimizedmc"))
				.create()) {
			singleplayer.getServer().runCommand("gamemode spectator @a");
			singleplayer.getServer().runCommand("tp @a 0 160 0 0 12");
			context.waitTicks(20);

			long start = System.nanoTime();
			context.waitFor(client -> {
				LodManager.Stats stats = LodManager.INSTANCE.stats();
				return stats.drawnClusters() >= 8 && stats.queued() == 0 && stats.building() == 0;
			}, SharedConstants.TICKS_PER_MINUTE * 5);
			context.waitTicks(20);
			LodManager.Stats stats = context.computeOnClient(client -> LodManager.INSTANCE.stats());
			LOGGER.info("LOD settled in {} s: {}", (System.nanoTime() - start) / 1_000_000_000L, stats);
			if (stats.drawnQuads() == 0) {
				throw new AssertionError("LODs were built but nothing was drawn: " + stats);
			}

			log("lod-horizon", context.takeScreenshot("lod-horizon"));
			config.lodDebugColors = true;
			context.waitTicks(2);
			log("lod-levels", context.takeScreenshot("lod-levels"));
			config.lodDebugColors = false;
			config.showChunkMap = true;
			context.waitTicks(10);
			log("chunk-map", context.takeScreenshot("chunk-map"));
			config.showChunkMap = false;
		}
		LOGGER.info("OPTIMIZEDMC LOD GAMETEST PASSED");
	}

	/** Prints a small JPEG of the screenshot to the log so it can be checked from CI output. */
	private static void log(String name, Path path) {
		try {
			BufferedImage full = ImageIO.read(path.toFile());
			int width = 480;
			int height = full.getHeight() * width / full.getWidth();
			BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = small.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			graphics.drawImage(full, 0, 0, width, height, null);
			graphics.dispose();
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			ImageIO.write(small, "jpg", bytes);
			String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
			int parts = (encoded.length() + 3999) / 4000;
			for (int i = 0; i < parts; i++) {
				LOGGER.info("SCREENSHOT {} {}/{} {}", name, i + 1, parts, encoded.substring(i * 4000, Math.min(encoded.length(), (i + 1) * 4000)));
			}
		} catch (IOException e) {
			LOGGER.warn("Could not encode screenshot {}", path, e);
		}
	}
}
