package com.optimizedminecraft.mixin;

import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The player ticket tracker uses a chunk's distance from the player as its task
 * priority, but this queue only has {@link ChunkTaskPriorityQueue#PRIORITY_LEVEL_COUNT}
 * buckets (about 45). Past vanilla's 32 chunk limit that indexes out of bounds and kills
 * the chunk loading thread, so put anything further away in the lowest-priority bucket.
 */
@Mixin(ChunkTaskPriorityQueue.class)
public abstract class ChunkTaskPriorityQueueMixin {
	@ModifyVariable(method = "submit", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int optimizedmc$clampSubmitPriority(int priority) {
		return optimizedmc$clamp(priority);
	}

	@ModifyVariable(method = "resortChunkTasks", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int optimizedmc$clampOldPriority(int priority) {
		return optimizedmc$clamp(priority);
	}

	@ModifyVariable(method = "resortChunkTasks", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int optimizedmc$clampNewPriority(int priority) {
		return optimizedmc$clamp(priority);
	}

	private static int optimizedmc$clamp(int priority) {
		return Math.min(priority, ChunkTaskPriorityQueue.PRIORITY_LEVEL_COUNT - 1);
	}
}
