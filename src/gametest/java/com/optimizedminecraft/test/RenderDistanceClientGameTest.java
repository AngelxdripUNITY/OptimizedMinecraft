package com.optimizedminecraft.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ChunkTrackingView;
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
			// The server sends a rounded area, so the corners of the square never arrive and
			// TestClientWorldContext.waitForChunksRender() would wait forever; check along an axis instead.
			try {
				context.waitFor(client -> hasChunkAt(client, 6) && client.levelRenderer.hasRenderedAllSections(),
						SharedConstants.TICKS_PER_MINUTE * 2);
			} catch (Throwable t) {
				LOGGER.error("Initial world never finished rendering: {}", context.computeOnClient(RenderDistanceClientGameTest::describe));
				throw t;
			}

			// Raise the distance in a running world, like a player dragging the slider and closing
			// Video Settings (which is what sends the new value to the server).
			context.runOnClient(client -> {
				client.options.renderDistance().set(RENDER_DISTANCE);
				client.options.broadcastOptions();
			});
			context.waitTicks(40);

			// PlayerList stores whatever the client asked for; the player's tracking view is what ChunkMap actually uses.
			int serverViewDistance = singleplayer.getServer().computeOnServer(server ->
					server.getPlayerList().getPlayers().getFirst().getChunkTrackingView() instanceof ChunkTrackingView.Positioned positioned
							? positioned.viewDistance() : -1);
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
		return hasChunkAt(client, PROBE_DISTANCE);
	}

	private static boolean hasChunkAt(Minecraft client, int distance) {
		if (client.level == null || client.player == null) {
			return false;
		}
		ChunkPos pos = client.player.chunkPosition();
		return client.level.getChunkSource().hasChunk(pos.x + distance, pos.z);
	}

	private static String describe(Minecraft client) {
		if (client.level == null || client.player == null) {
			return "no level";
		}
		int distance = client.options.getEffectiveRenderDistance();
		ChunkPos pos = client.player.chunkPosition();
		int loaded = 0;
		for (int dx = -distance; dx <= distance; dx++) {
			for (int dz = -distance; dz <= distance; dz++) {
				if (client.level.getChunkSource().hasChunk(pos.x + dx, pos.z + dz)) {
					loaded++;
				}
			}
		}
		int side = 2 * distance + 1;
		return "effectiveRenderDistance=" + distance + " player=" + pos + " loaded=" + loaded + "/" + side * side
				+ " furthestRing=" + furthestLoadedRing(client)
				+ " allSectionsRendered=" + client.levelRenderer.hasRenderedAllSections()
				+ " " + client.levelRenderer.getSectionStatistics();
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
