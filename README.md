# OptimizedMinecraft

A Fabric mod for Minecraft **1.21.11** that:

- **Lifts the render distance cap.** The slider goes past 32, up to 96 chunks by default and 125 at most.
  In singleplayer and on servers running this mod, the server really sends that many chunks.
- **Unlocks the framerate on first launch.** It sets Max Framerate to Unlimited and turns VSync off, once.
  After that, whatever you change in Video Settings is kept.
- **Warns you when Java doesn't have enough memory** for the render distance you picked. Every extra chunk
  of render distance costs memory, and the default 2 GB launcher setting runs out quickly.
- Ships **`tools/jvm-args`**, which gives Java as much RAM as your PC can spare (see below).

Config file: `.minecraft/config/optimizedminecraft.json`

| Setting | Default | What it does |
|---|---|---|
| `maxRenderDistance` | `96` | Highest value on the render distance slider (32–125) |
| `uncapServerViewDistance` | `true` | Let the built-in/dedicated server send chunks beyond 32 |
| `unlockFramerateOnFirstLaunch` | `true` | Unlimited FPS + VSync off, applied once |
| `memoryWarnings` | `true` | Toast when `-Xmx` is too small for your render distance |

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
| [Distant Horizons](https://modrinth.com/mod/distanthorizons) | Level-of-detail terrain out to hundreds or thousands of chunks, for a fraction of the cost of real chunks. The right tool if you want to see *really* far. |
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
./gradlew runProductionClientGameTest    # starts the game and checks render distance > 32 really works (Linux needs xvfb)
```
