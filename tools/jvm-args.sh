#!/usr/bin/env bash
# Prints JVM arguments for Minecraft that hand Java as much memory as this
# machine can spare. Paste the output into your launcher's "JVM arguments"
# box (Minecraft Launcher: Installations > Edit > More options;
# Prism/MultiMC: Settings > Java). Usage: tools/jvm-args.sh [heap-GB]
set -euo pipefail

if [[ $# -ge 1 ]]; then
	heap=$1
else
	if [[ "$(uname)" == "Darwin" ]]; then
		total_mb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
	else
		total_mb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 ))
	fi
	total=$(( total_mb / 1024 ))
	# Keep at least 4 GB (or a quarter of RAM) for the OS, browser, GPU driver,
	# plus 1 GB for Minecraft's own off-heap buffers.
	reserve=$(( total / 4 > 4 ? total / 4 : 4 ))
	heap=$(( total - reserve - 1 ))
	(( heap < 2 )) && heap=2
	# Beyond 32 GB the JVM loses compressed pointers and nothing in Minecraft needs more.
	(( heap > 31 )) && heap=31
	echo "# Detected ${total} GB RAM -> giving Minecraft ${heap} GB" >&2
fi

args="-Xms${heap}G -Xmx${heap}G -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -XX:+DisableExplicitGC -XX:+PerfDisableSharedMem"
[[ "$(uname)" == "Linux" ]] && args="$args -XX:+UseTransparentHugePages"
echo "$args"
