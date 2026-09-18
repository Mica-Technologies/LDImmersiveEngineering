/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.conduit;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitIndex;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitWorldProbe;
import blusunrize.immersiveengineering.common.util.IELogger;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Keeps the conduit index honest: when a chunk loads, the entries recorded in <em>that chunk</em> are
 * checked against the blocks that are really there.
 * <p>
 * <strong>Why a sweep is needed at all.</strong> An entry is written as a block loads and dropped when
 * the block is broken, and {@code BlockConduit.breakBlock} catches every ordinary way a block leaves
 * the world -- a player, a piston, an explosion, another block replacing it. What it does not catch is
 * a world edit: WorldEdit, {@code /setblock} with flags that skip the break path, a mod shuffling
 * blocks about. The index would then describe hardware that is not there, and a walk would make a run
 * out of it.
 * <p>
 * <strong>Why on chunk load, and per chunk.</strong> A chunk arriving is the one moment the answer is
 * free -- the blocks are in memory and nothing has to be asked for -- and it is also the moment it
 * matters, because a stale entry only ever does damage to a walk, and a walk only happens near
 * somebody. {@link ConduitIndex} is keyed by chunk so this is a map lookup and a handful of block
 * reads, not a pass over a few thousand entries. A chunk with no conduit in it, which is nearly all of
 * them, costs one lookup that finds nothing.
 * <p>
 * Nothing here loads a chunk: the only chunk touched is the one that has just arrived.
 *
 * @author LDImmersiveEngineering -- conduits
 */
@Mod.EventBusSubscriber
public class ConduitIndexHandler
{
	@SubscribeEvent
	public static void onChunkLoad(ChunkEvent.Load event)
	{
		World world = event.getWorld();
		if(world==null||world.isRemote||event.getChunk()==null)
			return;
		//The cheapest possible exit for a save with no conduit on it, and for every chunk load before
		//the index has been read in: one emptiness test.
		if(ConduitIndex.INSTANCE.isEmpty())
			return;
		int chunkX = event.getChunk().x;
		int chunkZ = event.getChunk().z;
		//And the exit for nearly every chunk on a save that does have conduit: nothing is remembered
		//here, so there is nothing a sweep could drop. Without this every chunk a player flies past
		//queues a task to discover that, on a map where the index is never empty again.
		if(!ConduitIndex.INSTANCE.knowsChunk(world.provider.getDimension(),
				new BlockPos(chunkX << 4, 0, chunkZ << 4)))
			return;
		//	=================================
		//	Next tick, not now
		//	=================================
		//A chunk's tile entities are added to the world on their own schedule -- when a chunk arrives
		//in the middle of a tick's entity processing they queue up and land at the start of the next
		//one -- and every one of them writes its own index entry as it does. Sweeping in the middle of
		//that would be sweeping against a chunk whose hardware has not announced itself yet.
		//
		//The sweep reads block states rather than tile entities precisely so that it cannot be wrong
		//about this (see ConduitIndex.Hardware), so the deferral is belt as well as braces. It costs
		//nothing: the queue is drained once a tick and a chunk load is not a per-tick event.
		ApiUtils.addFutureServerTask(world, () -> {
			//The chunk may have gone again in between -- a player turning round at a border unloads
			//what they just loaded -- and sweeping a chunk that is no longer there would read every
			//block in it as air and delete the lot.
			if(!world.isBlockLoaded(new BlockPos(chunkX << 4, 64, chunkZ << 4)))
				return;
			int dropped = ConduitIndex.INSTANCE.sweepChunk(world.provider.getDimension(), chunkX,
					chunkZ, ConduitWorldProbe.asBuilt(world));
			if(dropped > 0)
				//Said out loud, because the only way this fires is somebody editing the world around
				//conduit, and the next question after "why did that run vanish" is "did anything
				//notice".
				IELogger.info("Conduit index: dropped "+dropped+" stale entr"
						+(dropped==1?"y": "ies")+" in chunk "+chunkX+", "+chunkZ
						+" of dim "+world.provider.getDimension());
		});
	}
}
