/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connectors that left a wire out of their model because the far end had not arrived yet, and
 * are owed a redraw when it does.
 * <p>
 * <strong>This is the half of a wire that goes missing and stays missing.</strong> Each end of a
 * wire draws its own half into its own chunk (see {@link CatenarySplit}), and an end skips the wire
 * altogether while the connector at the far end is not loaded on the client: the curve depends on
 * where the far connector's attachment point is, and it cannot be worked out without that tile
 * entity. That part is deliberate. What was missing was anything that put the wire back. When the
 * far end arrived -- its chunk came in later, or it walked into view distance -- only the far chunk
 * was built, with the far half in it. The near chunk kept the model it had built without the wire,
 * so half of it stayed invisible, carrying power, until something rebuilt that one 16-block
 * section. That is also why placing a block beside it only sometimes helped: only a block placed in
 * the connector's own section does it.
 * <p>
 * So a connector that skips a wire says so here, and the client checks a few times a second whether
 * the far ends it is waiting on have arrived, and rebuilds the waiting connector when one does.
 * Waiting on the far end's <em>tile entity</em> rather than hooking its arrival, because a
 * connector's wires reach the client three different ways -- a chunk, a description packet, or not
 * at all in single player, where both sides share one graph -- and a junction box is not even the
 * same class as the others. The one thing all of them have in common is the check that skipped the
 * wire.
 * <p>
 * Costs nothing while every wire in view is whole: the map is empty. An entry whose waiting
 * connector has itself gone out of range is dropped, so a line leading off past view distance
 * cannot fill it.
 *
 * @author LDImmersiveEngineering -- wire rendering
 */
public final class WireRedrawQueue
{
	private WireRedrawQueue()
	{
	}

	/** How often the client looks, in ticks. A wire appearing a quarter-second late is invisible. */
	public static final int INTERVAL = 5;

	/** Far end -> the connectors waiting on it. Written from chunk-building threads, hence concurrent. */
	private static final Map<BlockPos, Set<BlockPos>> WAITING = new ConcurrentHashMap<>();

	/** The dimension the entries belong to. The client is only ever in one. */
	private static volatile int dimension = Integer.MIN_VALUE;

	/**
	 * Note that the connector at {@code near} left out its wire to {@code far}. Client only; called
	 * while a chunk is being built, so it must not touch the world.
	 */
	public static void await(World world, BlockPos near, BlockPos far)
	{
		int dim = world.provider.getDimension();
		if(dim!=dimension)
		{
			WAITING.clear();
			dimension = dim;
		}
		WAITING.computeIfAbsent(far.toImmutable(), k -> ConcurrentHashMap.newKeySet()).add(near.toImmutable());
	}

	/**
	 * Rebuild every connector whose far end has now arrived. Client thread only.
	 */
	public static void poll(World world)
	{
		if(WAITING.isEmpty())
			return;
		if(world==null||world.provider.getDimension()!=dimension)
		{
			WAITING.clear();
			return;
		}
		Iterator<Map.Entry<BlockPos, Set<BlockPos>>> it = WAITING.entrySet().iterator();
		while(it.hasNext())
		{
			Map.Entry<BlockPos, Set<BlockPos>> entry = it.next();
			Set<BlockPos> near = entry.getValue();
			//A connector that has gone out of range has nothing to redraw, and will ask again when it
			//comes back and is built afresh.
			near.removeIf(pos -> !world.isBlockLoaded(pos));
			if(near.isEmpty())
			{
				it.remove();
				continue;
			}
			BlockPos far = entry.getKey();
			if(world.isBlockLoaded(far)&&world.getTileEntity(far) instanceof IImmersiveConnectable)
			{
				for(BlockPos pos : near)
					world.markBlockRangeForRenderUpdate(pos, pos);
				it.remove();
			}
		}
	}

	/** For tests, and for anything that wants to know whether a wire is still waiting. */
	public static boolean isWaiting(BlockPos far)
	{
		return WAITING.containsKey(far);
	}

	public static void clear()
	{
		WAITING.clear();
		dimension = Integer.MIN_VALUE;
	}
}
