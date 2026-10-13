# OptimizedMinecraft

A Fabric mod for Minecraft **1.21.11** that:

- **Draws terrain out to the horizon with Nanite-style LODs** (details below). The default reaches 512 chunks
  in every direction, which is over 800,000 chunks of area, on top of your normal render distance.
- **Lifts the render distance cap**, and the render distance is now a **number you type in** instead of a slider:
  up to 96 chunks by default, 125 at most. In singleplayer the built-in server really sends that many chunks.
  On a multiplayer server, the server decides: it needs this mod too (untested on dedicated servers so far),
  or use [Bobby](https://modrinth.com/mod/bobby) client-side.
- **Turns clouds off** (once; turn them back on in the settings if you want them).
- **Fog off**: no distance, weather or biome fog (Fog: Off / Reduced / Vanilla in the settings).
  Underwater, lava and blindness fog are left alone. Works with Sodium too.
- **Chunk map overlay** (press **F7**) showing what is really loaded: real chunks in green, LOD clusters
  coloured by detail level, plus live stats (clusters, quads, streaming queue, samples per second, GPU memory).
- **Unlocks the framerate on first launch** (Unlimited FPS, VSync off, once).
- **Warns you when Java doesn't have enough memory** for the render distance you picked.
- Ships **`tools/jvm-args`**, which gives Java as much RAM as your PC can spare (see below).

**Settings:** press **F8** in game, or click **OptimizedMinecraft...** in the top-left corner of the Options screen.
That screen has the render distance (type a number, up to 125), LOD distance and detail, fog, clouds and the chunk
map, and it works whether or not Sodium is installed. Without Sodium the same settings are also at the bottom of
Video Settings. Number boxes: click, type, press Enter (arrow keys or the mouse wheel step the value while it's
selected). With Sodium, its own render distance slider is also raised past 32.

Config file: `.minecraft/config/optimizedminecraft.json`

| Setting | Default | What it does |
|---|---|---|
| `lodEnabled` | `true` | Draw LOD terrain beyond the render distance |
| `lodDistance` | `512` | How far LODs reach, in chunks (up to 4096) |
| `lodPixelError` | `6` | Screen-space error budget in pixels; lower = sharper LODs, more work |
| `lodThreads` | `0` | LOD worker threads; 0 = CPU cores − 2 |
| `lodGpuBudgetMb` | `768` | GPU memory LOD meshes may use before old ones are dropped |
| `lodDiskCache` | `true` | Save predicted LOD terrain in the world folder so it loads instantly next time |
| `lodDebugColors` | `false` | Tint LODs by detail level, like Nanite's cluster view |
| `showChunkMap` | `false` | Show the chunk map overlay (also toggled with F7) |
| `disableClouds` | `true` | Turn clouds off once |
| `fog` | `"off"` | `"off"`, `"reduced"` (thin edge fog, weather fog `fogDistanceMultiplier`× further) or `"vanilla"` |
| `maxRenderDistance` | `96` | Highest render distance you can type in (32–125) |
| `uncapServerViewDistance` | `true` | Let the built-in/dedicated server send chunks beyond 32 |
| `unlockFramerateOnFirstLaunch` | `true` | Unlimited FPS + VSync off, applied once |
| `memoryWarnings` | `true` | Toast when `-Xmx` is too small for your render distance |

## How the LODs work (the Nanite part)

Unreal's Nanite splits geometry into clusters, keeps them in a hierarchy, and every frame picks the coarsest
cluster whose error is smaller than about a pixel on screen. Clusters stream in coarse-first. This mod does the
same for terrain:

- **Clusters:** terrain is a quadtree of clusters, each a fixed 64×64 height grid. Level 0 cells are 4 blocks
  wide; each level up doubles the cell size, up to 512-block cells. Every cluster costs the same to build and
  draw, however much ground it covers.
- **Picking detail:** every frame the quadtree is walked from the top, and a cluster is split into its four
  children only while one of its cells would cover more than `lodPixelError` pixels on screen. Detail goes
  where you can see it; far mountains are a handful of clusters.
- **Streaming:** background threads build clusters, highest on-screen error first. The horizon appears almost
  immediately and sharpens as finer clusters arrive; the coarser parent is drawn until all four children are
  ready. Clusters behind you are prefetched at low priority so turning around doesn't pop.
- **Where the data comes from:** in singleplayer, terrain is predicted straight from the world generator's noise.
  The generator's preliminary surface estimate is refined with a few density samples, so nothing is actually
  generated, saved or lit. Chunks you really load are captured at full LOD detail (trees and builds included)
  and override the prediction; in multiplayer that captured data is the only source.
- **Drawing:** one GPU buffer per cluster, drawn in its own depth range before vanilla terrain, so the far
  horizon doesn't flicker and real chunks always sit cleanly on top. Edge skirts hide cracks between levels.
- **Looks like the real terrain:** colours are the average of each block's actual top texture (from your
  resource packs) times the same biome tint the game uses, sides get vanilla's face shading, and everything is
  lit through the game's own lightmap, so LODs get darker at night exactly like real chunks.

Measured in CI on a 4-core GitHub runner with **2** LOD threads and software (CPU) rendering, in a normal
world with a 256-chunk LOD radius (~206,000 chunks of area): first visit ready in ~30–55 s, rejoining the world
in ~1 s from the disk cache, ~200,000 quads drawn, ~20–35 MB of GPU memory. A desktop CPU with more threads
is several times faster.

Limits: no LODs in the Nether (its roof would hide everything); predicted terrain has no trees, caves or
structures until you've seen the real chunks; don't run it together with Distant Horizons (pick one; turn
`LODs` off here if you prefer DH).

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for 1.21.11.
2. Put these in `.minecraft/mods`:
   - `optimizedminecraft-1.0.0.jar` (from the latest [build run](../../actions/workflows/build.yml) → artifact *OptimizedMinecraft*)
   - [Fabric API](https://modrinth.com/mod/fabric-api) for 1.21.11
   - The performance mods below. They do far more for FPS than any one mod can, so get them.
3. Give Java more memory (next section).

## Give Java as much memory as can be spared

Java's maximum memory (`-Xmx`) is fixed when the game launches, so no mod can raise it from inside the game.
It has to go into your launcher's JVM arguments. The scripts work out the number for your PC.
They leave a quarter of your RAM (at least 4 GB) for Windows, the GPU driver and Minecraft's off-heap buffers,
and cap at 31 GB, which is the most that still uses compressed pointers.

```
# Windows (PowerShell)
powershell -ExecutionPolicy Bypass -File tools\jvm-args.ps1
# Linux / macOS
tools/jvm-args.sh
```

Paste the line it prints into:
- **Minecraft Launcher:** Installations → your Fabric install → Edit → More options → JVM arguments.
  Replace the existing `-Xmx2G`.
- **Prism / MultiMC:** Settings → Java → JVM arguments (and set the memory sliders to the same value).

Example output for a 16 GB PC (exact numbers depend on what Windows reports): `-Xms11G -Xmx11G -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch ...`

ZGC keeps garbage-collection pauses under a millisecond even on a big heap, so you don't get the usual
big-heap stutter. Rough heap needed per render distance in singleplayer:
32 chunks → ~3 GB, 64 → ~7 GB, 96 → ~14.5 GB, 125 → ~23.5 GB.

## Recommended performance mods (all Fabric, all 1.21.11)

| Mod | What it does |
|---|---|
| [Sodium](https://modrinth.com/mod/sodium) | Replaces the chunk renderer. Usually 2–5× FPS. This is the biggest GPU-side win available. |
| [Lithium](https://modrinth.com/mod/lithium) | Faster game logic (ticking, AI, physics). No visual change. |
| [C2ME](https://modrinth.com/mod/c2me-fabric) | Multithreaded chunk generation/loading, which matters a lot at high render distances |
| [FerriteCore](https://modrinth.com/mod/ferrite-core) | Cuts memory use substantially |
| [ImmediatelyFast](https://modrinth.com/mod/immediatelyfast) | Faster text, GUI, entity and particle rendering |
| [ModernFix](https://modrinth.com/mod/modernfix) | Faster startup, lower memory |
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | A much more complete LOD mod (caves, trees, multiplayer server support). Use it *instead of* this mod's LODs, not with them. |
| [Bobby](https://modrinth.com/mod/bobby) | Keeps chunks client-side so render distance can exceed a multiplayer server's view distance |

On a laptop with two GPUs, make sure Java uses the dedicated one. On Windows: Settings → System → Display →
Graphics → add `javaw.exe` (from your launcher's runtime folder) → High performance.

## About Vulkan, and a "translator" so no mods break

This mod does **not** move Minecraft to Vulkan, and it can't do that without breaking mods. Here's why.

- Minecraft 1.21.11 renders with OpenGL. Since 1.21.5, Mojang has routed most rendering through an abstraction
  (`GpuDevice`) so a Vulkan backend can be added later. But many mods (Sodium, Iris, ImmediatelyFast, minimaps,
  shaders) also talk to OpenGL directly.
- A full Vulkan renderer for 1.21.11 already exists: [VulkanMod](https://github.com/xCollateral/VulkanMod).
  It's incompatible with Sodium, Iris and many rendering mods, for exactly that reason.
- The "translator so no mods break" also already exists: **Zink**, Mesa's OpenGL-on-Vulkan driver. It runs
  unmodified Minecraft and every mod on top of Vulkan. On Windows with NVIDIA/AMD drivers it's normally
  *slower* than native OpenGL, because the translation is extra work, and the API was never the bottleneck.
- Where Minecraft actually loses FPS is CPU-side: chunk meshing, draw call submission, entity rendering
  and game logic. That's what Sodium, Lithium and C2ME fix, and they're compatible with almost everything.

## Building

Needs JDK 25 to run Gradle (the mod itself targets Java 21).

```
./gradlew build                          # jar in build/libs/
./gradlew runProductionClientGameTest    # starts the real game and runs the tests below (Linux needs xvfb)
```

The game tests:
- **Render distance:** create a superflat world, raise render distance to 40 and wait for a chunk 36 chunks away
  to arrive. CI also runs this with `-PvanillaServer` (server patches off) and expects it to fail at 32.
- **LODs and settings:** load a normal world, wait for LOD streaming to settle, screenshot the horizon, the LOD
  levels and the chunk map. Rejoin and check that clusters come from the disk cache. Open Video Settings, type
  20 into the render distance box and check that it applied.
- CI runs everything a third time with the latest Sodium for 1.21.11 installed.
