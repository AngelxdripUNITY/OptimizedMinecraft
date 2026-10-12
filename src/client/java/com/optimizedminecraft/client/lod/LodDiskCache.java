package com.optimizedminecraft.client.lod;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.optimizedminecraft.OptimizedMC;

/**
 * Keeps generator-predicted cluster samples in the world folder, so rejoining a world shows
 * its LODs straight away instead of re-running the noise. Only the deterministic prediction
 * is stored (never captured chunk data), so edits to the world can't go stale here.
 */
final class LodDiskCache {
	/** Bump when sampling changes so old files are ignored. */
	private static final String VERSION = "v1";

	private final Path dir;
	private volatile boolean failed;

	LodDiskCache(Path dir) {
		this.dir = dir.resolve(VERSION);
	}

	boolean load(LodNode node, short[] heights, int[] colors) {
		Path file = this.file(node);
		if (!Files.isRegularFile(file)) {
			return false;
		}
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file)), 16384))) {
			for (int i = 0; i < heights.length; i++) {
				heights[i] = in.readShort();
			}
			for (int i = 0; i < colors.length; i++) {
				colors[i] = in.readInt();
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	void save(LodNode node, short[] heights, int[] colors) {
		if (this.failed) {
			return;
		}
		Path file = this.file(node);
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(tmp)), 16384))) {
				for (short h : heights) {
					out.writeShort(h);
				}
				for (int c : colors) {
					out.writeInt(c);
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			this.failed = true;
			OptimizedMC.LOGGER.warn("LOD disk cache disabled: could not write {}", file, e);
		}
	}

	private Path file(LodNode node) {
		return this.dir.resolve("L" + node.level).resolve(node.nx + "_" + node.nz + ".bin");
	}
}
