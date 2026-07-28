/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Following a conduit run from one junction box to the next.
 * <p>
 * A run is a path of conduit blocks, and this is what turns that path into the thing the wire graph
 * cares about: the set of boxes at the far ends of it, and how far away each is. The blocks in
 * between are never nodes in the graph -- a hundred-block corridor is still one edge, which is the
 * decision the whole feature's cost rests on.
 * <p>
 * Breadth-first with a visited set and an ArrayDeque, for the same reason the fluid pipe's flood
 * fill was rewritten that way: the obvious implementation, a list plus {@code contains}, is
 * quadratic, and somebody's ring main is exactly where that shows up.
 * <p>
 * World-free -- the world is a {@link Probe} the caller supplies -- so the walk can be tested
 * against a made-up building instead of a real one.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitRoute
{
	private ConduitRoute()
	{
	}

	/**
	 * A ceiling on how far a single walk will look. Generous -- a run this long is a deliberate
	 * cross-base trunk -- but finite, because a walk with no bound is a server hang waiting for
	 * somebody to build a loop across a rendered chunk boundary.
	 */
	public static final int MAX_NODES = 4096;

	public enum Node
	{
		/** Nothing that a run continues through. */
		NOTHING,
		/** A length of conduit, with a mounting face the probe reports. */
		CONDUIT,
		/** A junction box: the walk stops here and reports it. */
		JUNCTION
	}

	public interface Probe
	{
		Node nodeAt(BlockPos pos);

		/**
		 * @return the face a conduit is clipped to, or null if there is no conduit there. Only
		 * asked about positions {@link #nodeAt} called {@link Node#CONDUIT}.
		 */
		EnumFacing mountAt(BlockPos pos);
	}

	/**
	 * Walk out from a junction box and find the boxes it is joined to.
	 * <p>
	 * A conduit next to the box joins it if the step between them lies in the conduit's own plane --
	 * the same rule two conduits use with each other, so a box is simply something a run may end at.
	 * From there the walk follows conduit to conduit while they share a mounting face, because
	 * {@link ConduitGeometry#connects} says a run stays on one surface.
	 *
	 * @return each reachable box and the number of conduit blocks between it and the start. The
	 * starting box is never in the result, and neither is a box reachable only through another box:
	 * a run <em>ends</em> at the first box it meets.
	 */
	public static Map<BlockPos, Integer> junctionsFrom(BlockPos start, Probe probe)
	{
		Map<BlockPos, Integer> found = new HashMap<>();
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		Map<BlockPos, Integer> distance = new HashMap<>();

		for(EnumFacing dir : EnumFacing.VALUES)
		{
			BlockPos first = start.offset(dir);
			if(probe.nodeAt(first)!=Node.CONDUIT)
				continue;
			EnumFacing mount = probe.mountAt(first);
			//The step from the box to the conduit has to run along that conduit's surface. A
			//conduit lying on the floor does not pick up a box sitting on top of it.
			if(mount==null||!ConduitGeometry.isInPlane(mount, dir.getOpposite()))
				continue;
			if(visited.add(first))
			{
				distance.put(first, 1);
				open.add(first);
			}
		}

		int examined = 0;
		while(!open.isEmpty()&&examined++ < MAX_NODES)
		{
			BlockPos here = open.poll();
			EnumFacing mount = probe.mountAt(here);
			if(mount==null)
				continue;
			int step = distance.get(here)+1;
			for(EnumFacing dir : ConduitGeometry.inPlane(mount))
			{
				BlockPos next = here.offset(dir);
				if(next.equals(start)||visited.contains(next))
					continue;
				Node node = probe.nodeAt(next);
				if(node==Node.JUNCTION)
				{
					//First one wins, and shorter wins over longer: two paths to the same box are
					//one connection, at the length of the route somebody would actually trace.
					Integer existing = found.get(next);
					if(existing==null||existing > step)
						found.put(next, step);
					continue;
				}
				if(node!=Node.CONDUIT)
					continue;
				//A run stays on one surface, so the next conduit has to be clipped to the same face.
				if(!ConduitGeometry.connects(mount, probe.mountAt(next), dir))
					continue;
				visited.add(next);
				distance.put(next, step);
				open.add(next);
			}
		}
		return found;
	}
}
