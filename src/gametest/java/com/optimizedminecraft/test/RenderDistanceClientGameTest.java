package com.optimizedminecraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Launches the real client, creates a superflat singleplayer world, raises the
 * render distance above vanilla's 32 cap and checks that the server really
 * sends chunks that far out.
 */
public class RenderDistanceClientGameTest implements FabricClientGameTest {
	private static final int RENDER_DISTANCE = 40;
	/** A chunk this far from the player can only exist if the cap was lifted. */
	private static final int PROBE_DISTANCE = 36;
	private static final Logger LOGGER = LoggerFactory.getLogger("OptimizedMinecraftTest");

	@Override
	public void runTest(ClientGameTestContext context) {
		// Fail fast if any mixin no longer matches the game code.
		context.runOnClient(client -> MixinEnvironment.getCurrentEnvironment().audit());

		int sliderValue = context.computeOnClient(client -> {
			client.options.renderDistance().set(RENDER_DISTANCE);
			int value = client.options.renderDistance().get();
			client.options.renderDistance().set(8);
			return value;
		});
		if (sliderValue != RENDER_DISTANCE) {
			throw new AssertionError("Render distance option was clamped to " + sliderValue);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();

			// Raise the distance in a running world, like a player dragging the slider.
			context.runOnClient(client -> client.options.renderDistance().set(RENDER_DISTANCE));
			context.waitTicks(40);

			int serverViewDistance = singleplayer.getServer().computeOnServer(server -> server.getPlayerList().getViewDistance());
			if (serverViewDistance != RENDER_DISTANCE) {
				throw new AssertionError("Server view distance is " + serverViewDistance + ", expected " + RENDER_DISTANCE);
			}

			try {
				context.waitFor(RenderDistanceClientGameTest::hasFarChunk, SharedConstants.TICKS_PER_MINUTE * 5);
			} catch (Throwable t) {
				LOGGER.error("No chunk arrived {} chunks away; furthest loaded ring on the client: {}",
						PROBE_DISTANCE, context.computeOnClient(RenderDistanceClientGameTest::furthestLoadedRing));
				throw t;
			}
			LOGGER.info("Chunk {} chunks away arrived; furthest loaded ring: {}",
					PROBE_DISTANCE, context.computeOnClient(RenderDistanceClientGameTest::furthestLoadedRing));
			context.takeScreenshot("render-distance-" + RENDER_DISTANCE);
		}
		// CI greps for this line so a test that silently never ran can't pass.
		LOGGER.info("OPTIMIZEDMC GAMETEST PASSED");
	}

	private static boolean hasFarChunk(Minecraft client) {
		if (client.level == null || client.player == null) {
			return false;
		}
		ChunkPos pos = client.player.chunkPosition();
		return client.level.getChunkSource().hasChunk(pos.x + PROBE_DISTANCE, pos.z);
	}

	private static int furthestLoadedRing(Minecraft client) {
		if (client.level == null || client.player == null) {
			return -1;
		}
		ChunkPos pos = client.player.chunkPosition();
		int furthest = -1;
		for (int r = 0; r <= RENDER_DISTANCE + 2; r++) {
			if (client.level.getChunkSource().hasChunk(pos.x + r, pos.z)) {
				furthest = r;
			}
		}
		return furthest;
	}
}
