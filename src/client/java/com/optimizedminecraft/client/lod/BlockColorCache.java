package com.optimizedminecraft.client.lod;

import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;
import com.optimizedminecraft.OptimizedMC;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Colours LODs the way the real blocks look: the average colour of a block's actual top
 * texture (from the active resource packs), times the same biome/block tint the game
 * applies when rendering it. Map colours, used before, are far brighter than textures.
 *
 * Client thread only.
 */
final class BlockColorCache {
	record Face(int averageRgb, int tintIndex) {
	}

	private static final Face NONE = new Face(-1, -1);
	private final Map<BlockState, Face> faces = new IdentityHashMap<>();
	private final Map<Identifier, Integer> textureAverages = new HashMap<>();

	/** Colour of the top face of {@code state} at {@code pos}, or -1 if it has no usable top texture. */
	int topColor(ClientLevel level, BlockState state, BlockPos pos) {
		Face face = this.faces.computeIfAbsent(state, this::topFace);
		if (face.averageRgb() < 0) {
			return -1;
		}
		if (face.tintIndex() < 0) {
			return face.averageRgb();
		}
		int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, face.tintIndex());
		return tint == -1 ? face.averageRgb() : multiply(face.averageRgb(), tint);
	}

	/** Untinted average of a block's top texture (for predicted terrain, tinted later per biome). */
	int untintedTop(BlockState state, int fallback) {
		Face face = this.faces.computeIfAbsent(state, this::topFace);
		return face.averageRgb() < 0 ? fallback : face.averageRgb();
	}

	/** Water has no block model; its colour comes from the still-water texture. */
	int waterTexture() {
		return this.texture(Identifier.withDefaultNamespace("block/water_still"), 0xB0B0B0);
	}

	/** Untinted top-texture averages of the blocks predicted terrain is made of. */
	TerrainSampler.Palette palette() {
		return new TerrainSampler.Palette(
				this.untintedTop(Blocks.GRASS_BLOCK.defaultBlockState(), 0x939393),
				this.untintedTop(Blocks.OAK_LEAVES.defaultBlockState(), 0x8A8A8A),
				this.untintedTop(Blocks.SAND.defaultBlockState(), 0xDBD3A0),
				this.untintedTop(Blocks.RED_SAND.defaultBlockState(), 0xBE6621),
				this.untintedTop(Blocks.TERRACOTTA.defaultBlockState(), 0x985E43),
				this.untintedTop(Blocks.SNOW_BLOCK.defaultBlockState(), 0xF9FEFE),
				this.untintedTop(Blocks.STONE.defaultBlockState(), 0x7D7D7D),
				this.waterTexture());
	}

	int waterTint(ClientLevel level, BlockPos pos) {
		int tint = Minecraft.getInstance().getBlockColors().getColor(Blocks.WATER.defaultBlockState(), level, pos, 0);
		return tint == -1 ? level.getBiome(pos).value().getWaterColor() : tint;
	}

	static int multiply(int a, int b) {
		int r = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
		int g = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
		int bl = (a & 0xFF) * (b & 0xFF) / 255;
		return (r << 16) | (g << 8) | bl;
	}

	private Face topFace(BlockState state) {
		try {
			BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
			List<BlockModelPart> parts = model.collectParts(RandomSource.create(42L));
			for (BlockModelPart part : parts) {
				List<BakedQuad> quads = part.getQuads(Direction.UP);
				if (!quads.isEmpty()) {
					return this.face(quads.getFirst());
				}
			}
			for (BlockModelPart part : parts) {
				for (BakedQuad quad : part.getQuads(null)) {
					if (quad.direction() == Direction.UP) {
						return this.face(quad);
					}
				}
			}
		} catch (RuntimeException e) {
			OptimizedMC.LOGGER.debug("No top face for {}", state, e);
		}
		return NONE;
	}

	private Face face(BakedQuad quad) {
		int average = this.texture(quad.sprite().contents().name(), -1);
		return new Face(average, quad.tintIndex());
	}

	private int texture(Identifier sprite, int fallback) {
		int value = this.textureAverages.computeIfAbsent(sprite, this::loadAverage);
		return value >= 0 ? value : fallback;
	}

	/** Average of all mostly-opaque pixels of textures/&lt;sprite&gt;.png, so resource packs are respected. */
	private int loadAverage(Identifier sprite) {
		Identifier file = sprite.withPath(path -> "textures/" + path + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return -1;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			long r = 0;
			long g = 0;
			long b = 0;
			long count = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int argb = image.getPixel(x, y);
					if ((argb >>> 24) < 128) {
						continue;
					}
					r += (argb >> 16) & 0xFF;
					g += (argb >> 8) & 0xFF;
					b += argb & 0xFF;
					count++;
				}
			}
			if (count == 0) {
				return -1;
			}
			return (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
		} catch (Exception e) {
			OptimizedMC.LOGGER.debug("Could not read {}", file, e);
			return -1;
		}
	}
}
