package com.optimizedminecraft.client.lod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.optimizedminecraft.OMConfig;
import com.optimizedminecraft.OptimizedMC;
import com.optimizedminecraft.client.mixin.GameRendererAccessor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Nanite-style terrain LOD: a quadtree of fixed-size clusters ({@link LodNode}), refined per
 * frame by projected screen-space error, streamed in coarse-first by background workers,
 * drawn as one GPU buffer per cluster in its own depth range before vanilla terrain.
 *
 * Threading: everything here runs on the client/render thread except {@link #build}, which
 * runs on the worker pool and talks back through {@link #uploads}.
 */
public final class LodManager {
	public static final LodManager INSTANCE = new LodManager();

	private static final RenderPipeline PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "pipeline/lod"))
			.withVertexShader(Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "core/lod"))
			.withFragmentShader(Identifier.fromNamespaceAndPath(OptimizedMC.MOD_ID, "core/lod"))
			.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
			.withSampler("Sampler2")
			.withCull(false)
			.build());

	private static final Vector4f[] LEVEL_TINTS = {
			new Vector4f(0.55F, 1.0F, 1.0F, 0), new Vector4f(0.5F, 0.75F, 1.0F, 0), new Vector4f(0.6F, 0.6F, 1.0F, 0),
			new Vector4f(0.8F, 0.55F, 1.0F, 0), new Vector4f(1.0F, 0.55F, 1.0F, 0), new Vector4f(1.0F, 0.55F, 0.7F, 0),
			new Vector4f(1.0F, 0.7F, 0.45F, 0), new Vector4f(0.8F, 0.65F, 0.55F, 0)
	};
	private static final int UPLOADS_PER_FRAME = 48;
	private static final long STALE_FRAMES = 240;

	private record Task(LodNode node, double score) {
	}

	private record Upload(LodNode node, LodMesher.Mesh mesh) {
	}

	private final ConcurrentHashMap<Long, LodNode> nodes = new ConcurrentHashMap<>();
	private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>(256, Comparator.comparingDouble(Task::score));
	private final ConcurrentLinkedQueue<Upload> uploads = new ConcurrentLinkedQueue<>();
	private final CapturedChunks captured = new CapturedChunks();
	private BlockColorCache blockColors = new BlockColorCache();
	private final AtomicLong frame = new AtomicLong();
	private final AtomicLong samples = new AtomicLong();
	private final AtomicLong diskLoads = new AtomicLong();
	private final AtomicInteger building = new AtomicInteger();
	private final List<LodNode> drawn = new ArrayList<>();

	private volatile int epoch;
	private volatile TerrainSampler sampler;
	private volatile LodDiskCache diskCache;
	private ClientLevel level;
	private boolean workersStarted;
	private MappableRingBuffer projectionBuffer;
	private final Matrix4f viewMatrix = new Matrix4f();
	private boolean haveView;

	// Per-frame selection state (render thread).
	private double camX;
	private double camY;
	private double camZ;
	private double lodRadius;
	private double innerRadius;
	private double projScale;
	private float pixelError;
	private Frustum frustum;
	/** Second, frustum-less traversal that only queues work, so turning around doesn't pop. */
	private boolean prefetching;
	private int worldMinY;
	private int worldMaxY;

	// Stats for the HUD.
	private int drawnQuads;
	private long gpuBytes;
	private long lastRateTime;
	private long lastRateSamples;
	private double sampleRate;

	private LodManager() {
	}

	/** Forces the pipeline to register while mods initialise. */
	public static void init() {
		PIPELINE.getLocation();
	}

	// ---------------------------------------------------------------- lifecycle

	public void tick(Minecraft client) {
		if (client.level != this.level) {
			this.reset(client.level, client.getSingleplayerServer());
		}
	}

	private void reset(ClientLevel newLevel, IntegratedServer server) {
		this.epoch++;
		this.queue.clear();
		Upload upload;
		while ((upload = this.uploads.poll()) != null) {
			if (upload.mesh() != null) {
				MemoryUtil.memFree(upload.mesh().vertices());
			}
		}
		for (LodNode node : this.nodes.values()) {
			if (node.buffer != null) {
				node.buffer.close();
				node.buffer = null;
			}
		}
		this.nodes.clear();
		this.drawn.clear();
		this.captured.clear();
		// Fresh cache per world, so resource pack changes since last time are picked up.
		this.blockColors = new BlockColorCache();
		this.level = newLevel;
		this.sampler = null;
		this.diskCache = null;
		this.gpuBytes = 0;
		if (newLevel != null && server != null && !newLevel.dimensionType().hasCeiling()) {
			ServerLevel serverLevel = server.getLevel(newLevel.dimension());
			if (serverLevel != null) {
				this.sampler = new TerrainSampler(serverLevel, this.blockColors.palette());
				if (OMConfig.get().lodDiskCache) {
					String dimension = newLevel.dimension().toString().replaceAll("[^a-zA-Z0-9_.-]+", "_");
					this.diskCache = new LodDiskCache(server.getWorldPath(LevelResource.ROOT).resolve("optimizedmc_lod").resolve(dimension));
				}
			}
		}
		if (newLevel != null) {
			this.worldMinY = newLevel.getMinY();
			this.worldMaxY = newLevel.getMaxY();
			this.startWorkers();
		}
	}

	private void startWorkers() {
		if (this.workersStarted) {
			return;
		}
		this.workersStarted = true;
		int threads = OMConfig.get().lodThreads;
		if (threads <= 0) {
			threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
		}
		for (int i = 0; i < threads; i++) {
			Thread thread = new Thread(this::workerLoop, "OptimizedMinecraft LOD " + i);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			thread.start();
		}
		OptimizedMC.LOGGER.info("LOD workers started: {}", threads);
	}

	public boolean isActive() {
		OMConfig config = OMConfig.get();
		return config.lodEnabled && config.clampedLodDistance() > 0 && this.level != null && !this.level.dimensionType().hasCeiling();
	}

	/** Fog end distance (blocks) to use while LODs are on screen, or 0. Read by the fog mixin. */
	public float fogEndBlocks() {
		if (!this.isActive()) {
			return 0;
		}
		int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
		int lod = OMConfig.get().clampedLodDistance();
		return lod > renderDistance ? lod * 16.0F : 0;
	}

	public void onChunkLoad(ClientLevel chunkLevel, LevelChunk chunk) {
		if (chunkLevel != this.level || !this.isActive()) {
			return;
		}
		this.captured.capture(chunkLevel, chunk, this.blockColors);
		int maxDirtyLevel = this.sampler == null ? LodNode.MAX_LEVEL : 1;
		int x = chunk.getPos().getMinBlockX();
		int z = chunk.getPos().getMinBlockZ();
		for (int l = 0; l <= maxDirtyLevel; l++) {
			int size = LodNode.size(l);
			LodNode node = this.nodes.get(LodNode.key(l, Math.floorDiv(x, size), Math.floorDiv(z, size)));
			if (node != null) {
				node.dirty = true;
			}
		}
	}

	public void setView(org.joml.Matrix4fc view) {
		this.viewMatrix.set(view);
		this.haveView = true;
	}

	// ---------------------------------------------------------------- workers

	private void workerLoop() {
		while (true) {
			try {
				Task task = this.queue.take();
				LodNode node = task.node();
				if (node.epoch != this.epoch || this.frame.get() - node.lastWantedFrame > STALE_FRAMES) {
					node.state = LodNode.IDLE;
					continue;
				}
				node.state = LodNode.BUILDING;
				this.building.incrementAndGet();
				try {
					this.build(node);
				} finally {
					this.building.decrementAndGet();
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				OptimizedMC.LOGGER.error("LOD worker failed", t);
			}
		}
	}

	private void build(LodNode node) {
		TerrainSampler sampler = this.sampler;
		LodDiskCache cache = this.diskCache;
		boolean dirty = node.dirty;
		node.dirty = false;
		int cell = node.cellSize();
		int originX = node.minX() - cell + cell / 2;
		int originZ = node.minZ() - cell + cell / 2;
		short[] heights = node.heights;
		int[] colors = node.colors;
		if (heights == null || !dirty) {
			// Fresh build: generator prediction (from disk if we've done this node before), then real chunks on top.
			heights = new short[LodNode.GRID * LodNode.GRID];
			colors = new int[LodNode.GRID * LodNode.GRID];
			if (sampler == null) {
				java.util.Arrays.fill(heights, LodNode.NO_HEIGHT);
			} else if (cache == null || !cache.load(node, heights, colors)) {
				for (int j = 0; j < LodNode.GRID; j++) {
					for (int i = 0; i < LodNode.GRID; i++) {
						sampler.sample(originX + i * cell, originZ + j * cell, i + j * LodNode.GRID, heights, colors);
					}
				}
				this.samples.addAndGet(LodNode.GRID * LodNode.GRID);
				if (cache != null) {
					cache.save(node, heights, colors);
				}
			} else {
				this.diskLoads.incrementAndGet();
			}
		}
		for (int j = 0; j < LodNode.GRID; j++) {
			for (int i = 0; i < LodNode.GRID; i++) {
				this.captured.sample(originX + i * cell, originZ + j * cell, i + j * LodNode.GRID, heights, colors);
			}
		}
		if (node.level <= 1 || sampler == null) {
			node.heights = heights;
			node.colors = colors;
		}
		LodMesher.Mesh mesh = LodMesher.build(heights, colors, cell);
		if (mesh != null) {
			node.minY = mesh.minY();
			node.maxY = mesh.maxY();
		}
		this.uploads.add(new Upload(node, mesh));
	}

	// ---------------------------------------------------------------- rendering

	/** Called at the start of the main world pass, before any vanilla terrain is drawn. */
	public void render(Vec3 camera) {
		long currentFrame = this.frame.incrementAndGet();
		this.drawn.clear();
		this.drawnQuads = 0;
		if (!this.isActive() || !this.haveView) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		this.processUploads();
		this.updateRate();

		OMConfig config = OMConfig.get();
		this.camX = camera.x;
		this.camY = camera.y;
		this.camZ = camera.z;
		int renderDistance = client.options.getEffectiveRenderDistance();
		this.innerRadius = Math.max(0, (renderDistance - 1) * 16.0);
		this.lodRadius = config.clampedLodDistance() * 16.0;
		this.pixelError = Math.max(1.0F, config.lodPixelError);

		float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		float fov = ((GameRendererAccessor) client.gameRenderer).optimizedmc$getFov(client.gameRenderer.getMainCamera(), partialTick, true);
		float aspect = (float) client.getWindow().getWidth() / Math.max(1, client.getWindow().getHeight());
		float near = (float) Math.max(0.5, this.innerRadius * 0.5);
		float far = (float) (this.lodRadius * 1.5 + 1024.0);
		Matrix4f projection = new Matrix4f().perspective(fov * (float) (Math.PI / 180.0), aspect, near, far);
		this.projScale = client.getWindow().getHeight() * projection.m11() / 2.0;
		this.frustum = new Frustum(new Matrix4f(this.viewMatrix), projection);
		this.frustum.prepare(this.camX, this.camY, this.camZ);

		int rootSize = LodNode.size(LodNode.MAX_LEVEL);
		int minRootX = (int) Math.floor((this.camX - this.lodRadius) / rootSize);
		int maxRootX = (int) Math.floor((this.camX + this.lodRadius) / rootSize);
		int minRootZ = (int) Math.floor((this.camZ - this.lodRadius) / rootSize);
		int maxRootZ = (int) Math.floor((this.camZ + this.lodRadius) / rootSize);
		for (int rx = minRootX; rx <= maxRootX; rx++) {
			for (int rz = minRootZ; rz <= maxRootZ; rz++) {
				this.visit(LodNode.MAX_LEVEL, rx, rz, currentFrame);
			}
		}
		if (currentFrame % 15 == 0 && this.queue.size() < 64) {
			this.prefetching = true;
			for (int rx = minRootX; rx <= maxRootX; rx++) {
				for (int rz = minRootZ; rz <= maxRootZ; rz++) {
					this.visit(LodNode.MAX_LEVEL, rx, rz, currentFrame);
				}
			}
			this.prefetching = false;
		}

		if (currentFrame % 60 == 0) {
			this.evict(currentFrame, config);
		}
		this.draw(client, projection, config);
	}

	private void visit(int level, int nx, int nz, long currentFrame) {
		LodNode node = this.nodes.get(LodNode.key(level, nx, nz));
		double distance = this.cullDistance(level, nx, nz, node);
		if (distance < 0) {
			return;
		}
		double errorPx = LodNode.cellSize(level) * this.projScale / Math.max(distance, 1.0);
		if (level > 0 && errorPx > this.pixelError) {
			boolean childrenReady = true;
			for (int c = 0; c < 4; c++) {
				int cx = nx * 2 + (c & 1);
				int cz = nz * 2 + (c >> 1);
				LodNode child = this.nodes.get(LodNode.key(level - 1, cx, cz));
				double childDistance = this.cullDistance(level - 1, cx, cz, child);
				if (childDistance >= 0 && (child == null || !child.ready)) {
					childrenReady = false;
					this.request(level - 1, cx, cz, childDistance, currentFrame);
				}
			}
			if (childrenReady) {
				for (int c = 0; c < 4; c++) {
					this.visit(level - 1, nx * 2 + (c & 1), nz * 2 + (c >> 1), currentFrame);
				}
				return;
			}
		}
		if (node != null && node.ready) {
			node.lastWantedFrame = currentFrame;
			if (node.dirty && node.state == LodNode.IDLE) {
				this.enqueue(node, distance);
			}
			if (!this.prefetching) {
				node.lastDrawnFrame = currentFrame;
				if (node.buffer != null) {
					this.drawn.add(node);
				}
			}
		} else {
			this.request(level, nx, nz, distance, currentFrame);
		}
	}

	/** Distance from the camera to the node, or -1 when it is off screen, out of range or fully covered by real chunks. */
	private double cullDistance(int level, int nx, int nz, LodNode node) {
		int size = LodNode.size(level);
		double x0 = (double) nx * size;
		double z0 = (double) nz * size;
		double x1 = x0 + size;
		double z1 = z0 + size;
		double dx = Math.max(0, Math.max(x0 - this.camX, this.camX - x1));
		double dz = Math.max(0, Math.max(z0 - this.camZ, this.camZ - z1));
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal > this.lodRadius) {
			return -1;
		}
		double farX = Math.max(Math.abs(this.camX - x0), Math.abs(this.camX - x1));
		double farZ = Math.max(Math.abs(this.camZ - z0), Math.abs(this.camZ - z1));
		if (Math.sqrt(farX * farX + farZ * farZ) < this.innerRadius - 16) {
			return -1;
		}
		double minY = node != null && node.ready ? node.minY : this.worldMinY;
		double maxY = node != null && node.ready ? node.maxY : this.worldMaxY;
		if (!this.prefetching && !this.frustum.isVisible(new AABB(x0, minY, z0, x1, maxY, z1))) {
			return -1;
		}
		double dy = Math.max(0, Math.max(minY - this.camY, this.camY - maxY));
		return Math.sqrt(horizontal * horizontal + dy * dy);
	}

	private void request(int level, int nx, int nz, double distance, long currentFrame) {
		LodNode node = this.nodes.computeIfAbsent(LodNode.key(level, nx, nz), k -> new LodNode(level, nx, nz, this.epoch));
		node.lastWantedFrame = currentFrame;
		if (!node.ready || node.dirty) {
			this.enqueue(node, distance);
		}
	}

	private void enqueue(LodNode node, double distance) {
		if (node.state != LodNode.IDLE) {
			return;
		}
		node.state = LodNode.QUEUED;
		// Biggest on-screen error first: coarse and near clusters stream in before fine/far ones;
		// off-screen prefetch work waits behind everything visible.
		double score = distance / node.cellSize();
		this.queue.add(new Task(node, this.prefetching ? score * 4 + 1000 : score));
	}

	private void processUploads() {
		Upload upload;
		int processed = 0;
		while (processed < UPLOADS_PER_FRAME && (upload = this.uploads.poll()) != null) {
			processed++;
			LodNode node = upload.node();
			LodMesher.Mesh mesh = upload.mesh();
			boolean live = node.epoch == this.epoch && this.nodes.get(node.key) == node;
			if (!live) {
				if (mesh != null) {
					MemoryUtil.memFree(mesh.vertices());
				}
				continue;
			}
			if (node.buffer != null) {
				node.buffer.close();
				this.gpuBytes -= node.gpuBytes;
				node.buffer = null;
				node.gpuBytes = 0;
				node.quadCount = 0;
			}
			if (mesh != null) {
				node.buffer = RenderSystem.getDevice().createBuffer(() -> "OptimizedMinecraft LOD cluster", GpuBuffer.USAGE_VERTEX, mesh.vertices());
				node.quadCount = mesh.quads();
				node.gpuBytes = (long) mesh.quads() * LodMesher.QUAD_BYTES;
				this.gpuBytes += node.gpuBytes;
				MemoryUtil.memFree(mesh.vertices());
			}
			node.ready = true;
			node.state = LodNode.IDLE;
		}
	}

	private void draw(Minecraft client, Matrix4f projection, OMConfig config) {
		if (this.drawn.isEmpty()) {
			return;
		}
		if (this.projectionBuffer == null) {
			this.projectionBuffer = new MappableRingBuffer(() -> "OptimizedMinecraft LOD projection", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, 64);
		}
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (GpuBuffer.MappedView view = encoder.mapBuffer(this.projectionBuffer.currentBuffer(), false, true)) {
			Std140Builder.intoBuffer(view.data()).putMat4f(projection);
		}

		// ColorModulator.a carries the radius covered by real chunks; the shader discards LOD there.
		float discardRadius = (float) Math.max(0, this.innerRadius - 8);
		DynamicUniforms.Transform[] transforms = new DynamicUniforms.Transform[this.drawn.size()];
		int maxQuads = 0;
		for (int i = 0; i < this.drawn.size(); i++) {
			LodNode node = this.drawn.get(i);
			Vector4f tint = config.lodDebugColors ? new Vector4f(LEVEL_TINTS[node.level]) : new Vector4f(1.0F, 1.0F, 1.0F, 0);
			tint.w = discardRadius;
			Vector3f offset = new Vector3f((float) (node.minX() - this.camX), (float) -this.camY, (float) (node.minZ() - this.camZ));
			transforms[i] = new DynamicUniforms.Transform(this.viewMatrix, tint, offset, new Matrix4f());
			maxQuads = Math.max(maxQuads, node.quadCount);
			this.drawnQuads += node.quadCount;
		}
		GpuBufferSlice[] slices = RenderSystem.getDynamicUniforms().writeTransforms(transforms);
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
		GpuBuffer indexBuffer = indices.getBuffer(maxQuads * 6);

		RenderTarget target = client.getMainRenderTarget();
		// LODs get their own near/far range (sharp depth out to the horizon), so start from a clear
		// depth buffer and clear it again afterwards: vanilla terrain, which is always closer, then
		// simply draws over them.
		try (RenderPass pass = encoder.createRenderPass(() -> "OptimizedMinecraft LOD", target.getColorTextureView(), OptionalInt.empty(),
				target.getDepthTextureView(), OptionalDouble.of(1.0))) {
			pass.setPipeline(PIPELINE);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("Projection", this.projectionBuffer.currentBuffer());
			// The same lightmap real terrain uses: day/night, gamma, night vision, lightning flashes.
			pass.bindTexture("Sampler2", client.gameRenderer.lightTexture().getTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setIndexBuffer(indexBuffer, indices.type());
			for (int i = 0; i < this.drawn.size(); i++) {
				LodNode node = this.drawn.get(i);
				pass.setUniform("DynamicTransforms", slices[i]);
				pass.setVertexBuffer(0, node.buffer);
				pass.drawIndexed(0, 0, node.quadCount * 6, 1);
			}
		}
		try (RenderPass clear = encoder.createRenderPass(() -> "OptimizedMinecraft LOD depth reset", target.getColorTextureView(), OptionalInt.empty(),
				target.getDepthTextureView(), OptionalDouble.of(1.0))) {
			// Clearing happens when the pass begins.
		}
		this.projectionBuffer.rotate();
	}

	private void evict(long currentFrame, OMConfig config) {
		long budget = Math.max(64L, config.lodGpuBudgetMb) * 1024L * 1024L;
		List<LodNode> candidates = new ArrayList<>();
		for (LodNode node : this.nodes.values()) {
			if (node.state != LodNode.IDLE) {
				continue;
			}
			if (!node.ready && currentFrame - node.lastWantedFrame > STALE_FRAMES) {
				this.nodes.remove(node.key, node);
			} else if (node.ready && node.lastDrawnFrame != currentFrame && currentFrame - node.lastWantedFrame > 30) {
				candidates.add(node);
			}
		}
		if (this.gpuBytes <= budget && this.nodes.size() < 20_000) {
			return;
		}
		candidates.sort(Comparator.comparingLong(n -> n.lastWantedFrame));
		for (LodNode node : candidates) {
			if (this.gpuBytes <= budget * 0.8 && this.nodes.size() < 16_000) {
				break;
			}
			if (node.buffer != null) {
				node.buffer.close();
				node.buffer = null;
			}
			this.gpuBytes -= node.gpuBytes;
			this.nodes.remove(node.key, node);
		}
	}

	private void updateRate() {
		long now = System.nanoTime();
		if (now - this.lastRateTime >= 1_000_000_000L) {
			long total = this.samples.get();
			if (this.lastRateTime != 0) {
				this.sampleRate = (total - this.lastRateSamples) * 1e9 / (now - this.lastRateTime);
			}
			this.lastRateSamples = total;
			this.lastRateTime = now;
		}
	}

	// ---------------------------------------------------------------- stats for the HUD and tests

	public List<LodNode> drawnNodes() {
		return this.drawn;
	}

	/** Every cluster with a mesh on the GPU, drawn this frame or not (behind you, for example). */
	public List<LodNode> readyNodes() {
		List<LodNode> ready = new ArrayList<>();
		for (LodNode node : this.nodes.values()) {
			if (node.ready) {
				ready.add(node);
			}
		}
		return ready;
	}

	public Stats stats() {
		int ready = 0;
		for (LodNode node : this.nodes.values()) {
			if (node.ready) {
				ready++;
			}
		}
		double radiusChunks = this.isActive() ? OMConfig.get().clampedLodDistance() : 0;
		long coveredChunks = (long) (Math.PI * radiusChunks * radiusChunks);
		return new Stats(this.isActive(), this.sampler != null, this.drawn.size(), this.drawnQuads, ready, this.nodes.size(), this.queue.size(),
				this.building.get(), this.gpuBytes, this.samples.get(), this.sampleRate, this.diskLoads.get(), this.captured.size(), coveredChunks);
	}

	public record Stats(boolean active, boolean predicting, int drawnClusters, int drawnQuads, int readyClusters, int knownClusters,
			int queued, int building, long gpuBytes, long totalSamples, double samplesPerSecond, long clustersFromDisk, int capturedChunks,
			long coveredChunks) {
	}
}
