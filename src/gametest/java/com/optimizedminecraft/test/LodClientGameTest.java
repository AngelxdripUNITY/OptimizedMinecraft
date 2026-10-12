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
import com.optimizedminecraft.client.gui.NumberFieldWidget;
import com.optimizedminecraft.client.lod.LodManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
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

		TestWorldSave save;
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> settings.setSeed("optimizedmc"))
				.create()) {
			save = singleplayer.getWorldSave();
			LodManager.Stats stats = settle(context, singleplayer, "first visit");
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

		// Second visit: predicted terrain should come from the disk cache instead of the generator.
		try (TestSingleplayerContext singleplayer = save.open()) {
			LodManager.Stats before = context.computeOnClient(client -> LodManager.INSTANCE.stats());
			LodManager.Stats stats = settle(context, singleplayer, "second visit");
			long fromDisk = stats.clustersFromDisk() - before.clustersFromDisk();
			long sampled = stats.totalSamples() - before.totalSamples();
			LOGGER.info("Second visit: {} clusters from disk, {} new noise samples", fromDisk, sampled);
			if (fromDisk == 0) {
				throw new AssertionError("LOD disk cache was not used on the second visit");
			}

			testVideoSettings(context);
		}
		LOGGER.info("OPTIMIZEDMC LOD GAMETEST PASSED");
	}

	/** Opens Video Settings, types a render distance into the number box and checks it applied. */
	private static void testVideoSettings(ClientGameTestContext context) {
		context.setScreen(() -> new VideoSettingsScreen(null, Minecraft.getInstance(), Minecraft.getInstance().options));
		context.waitTicks(5);
		log("video-settings", context.takeScreenshot("video-settings"));

		// The box sits in the "Quality" section further down the list; scroll until it is on screen.
		double[] target = null;
		for (int attempt = 0; attempt < 30; attempt++) {
			target = context.computeOnClient(client -> {
				NumberFieldWidget field = findField(client.screen);
				if (field == null) {
					throw new AssertionError("Render distance number box not found on the Video Settings screen");
				}
				double scale = client.getWindow().getGuiScale();
				boolean visible = field.getY() > 40 && field.getBottom() < client.getWindow().getGuiScaledHeight() - 40;
				return new double[]{(field.getX() + field.getWidth() / 2.0) * scale, (field.getY() + field.getHeight() / 2.0) * scale, visible ? 1 : 0,
						client.getWindow().getWidth() / 2.0, client.getWindow().getHeight() / 2.0};
			});
			if (target[2] == 1) {
				break;
			}
			context.getInput().setCursorPos(target[3], target[4]);
			context.getInput().scroll(-1);
			context.waitTick();
		}
		log("video-settings-render-distance", context.takeScreenshot("video-settings-render-distance"));
		context.getInput().setCursorPos(target[0], target[1]);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.getInput().typeChars("20");
		context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
		context.waitTicks(2);
		int renderDistance = context.computeOnClient(client -> client.options.renderDistance().get());
		if (renderDistance != 20) {
			throw new AssertionError("Typing 20 into the render distance box gave " + renderDistance);
		}
		LOGGER.info("Render distance number box: typed 20, option is now {}", renderDistance);

		context.getInput().setCursorPos(target[0], target[1] + 40);
		context.getInput().scroll(-40);
		context.waitTicks(5);
		log("video-settings-bottom", context.takeScreenshot("video-settings-bottom"));
		context.setScreen(() -> null);
	}

	private static NumberFieldWidget findField(GuiEventListener listener) {
		if (listener instanceof NumberFieldWidget field && field.getMessage().getString().startsWith(Component.translatable("options.renderDistance").getString())) {
			return field;
		}
		if (listener instanceof ContainerEventHandler container) {
			for (GuiEventListener child : container.children()) {
				NumberFieldWidget found = findField(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	private static LodManager.Stats settle(ClientGameTestContext context, TestSingleplayerContext singleplayer, String label) {
		singleplayer.getServer().runCommand("gamemode spectator @a");
		singleplayer.getServer().runCommand("tp @a 0 160 0 0 12");
		context.waitTicks(20);
		long start = System.nanoTime();
		long samplesBefore = context.computeOnClient(client -> LodManager.INSTANCE.stats().totalSamples());
		context.waitFor(client -> {
			LodManager.Stats stats = LodManager.INSTANCE.stats();
			return stats.drawnClusters() >= 8 && stats.queued() == 0 && stats.building() == 0;
		}, SharedConstants.TICKS_PER_MINUTE * 5);
		context.waitTicks(20);
		double seconds = (System.nanoTime() - start) / 1e9;
		LodManager.Stats stats = context.computeOnClient(client -> LodManager.INSTANCE.stats());
		long samples = stats.totalSamples() - samplesBefore;
		LOGGER.info("LOD {} settled in {} s ({} noise samples, {} samples/s overall): {}", label, String.format("%.1f", seconds), samples,
				String.format("%.0f", samples / Math.max(seconds, 0.001)), stats);
		return stats;
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
