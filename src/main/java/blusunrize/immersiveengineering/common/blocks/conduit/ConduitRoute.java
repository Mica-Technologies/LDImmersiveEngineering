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
		JUNCTION,
		/**
		 * A ground feeder: the run goes straight through it and out the far side, and the walk does
		 * not stop. See {@link #across}.
		 */
		PASS_THROUGH
	}

	public interface Probe
	{
		Node nodeAt(BlockPos pos);

		/**
		 * @return the face a conduit is clipped to, or null if there is no conduit there. Only
		 * asked about positions {@link #nodeAt} called {@link Node#CONDUIT}.
		 */
		EnumFacing mountAt(BlockPos pos);

		/**
		 * @return the axis a ground feeder lets a run through on, or null if there is no feeder
		 * there. Only asked about positions {@link #nodeAt} called {@link Node#PASS_THROUGH}, so a
		 * probe over a world with no feeders in it never has to answer.
		 */
		default EnumFacing.Axis axisAt(BlockPos pos)
		{
			return null;
		}
	}

	/**
	 * How many feeders a run may cross in one go: a floor, a thick floor, or a floor with a layer of
	 * something under it, but not a tunnel bored through a mountain. A ceiling stops the walk
	 * wandering, and sixteen is well past any floor somebody builds and well short of anything that
	 * costs.
	 */
	public static final int MAX_STACKED_FEEDERS = 16;

	/**
	 * Where a step actually lands, once the feeders in the way have been crossed.
	 * <p>
	 * A feeder is <em>not</em> a node. It never appears in the wire graph, it holds no energy and it
	 * costs nothing per tick -- the run simply passes through it and the two lengths of conduit on
	 * either side are as joined as two lengths lying next to each other. That is the whole reason
	 * the block can be scenery rather than hardware.
	 */
	private static final class Landing
	{
		final BlockPos pos;
		/** How many blocks the step covered, feeders included: a run's length is physical. */
		final int blocks;

		Landing(BlockPos pos, int blocks)
		{
			this.pos = pos;
			this.blocks = blocks;
		}
	}

	/**
	 * Follow a step in {@code dir}, sliding through any feeders lined up with it.
	 * <p>
	 * A feeder only conducts along its own axis, so a run arriving across a feeder's grain is
	 * stopped by it exactly as a stone block would be. Placing one the wrong way round therefore
	 * fails visibly -- the arms do not join -- rather than quietly working in a direction nobody
	 * intended.
	 *
	 * @return where the step ends up, or null if it ran off into a wall or through too many feeders
	 */
	private static Landing across(BlockPos from, EnumFacing dir, Probe probe)
	{
		BlockPos at = from.offset(dir);
		int blocks = 1;
		int crossed = 0;
		while(probe.nodeAt(at)==Node.PASS_THROUGH)
		{
			if(probe.axisAt(at)!=dir.getAxis()||++crossed > MAX_STACKED_FEEDERS)
				return null;
			at = at.offset(dir);
			blocks++;
		}
		return new Landing(at, blocks);
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
			Landing first = across(start, dir, probe);
			if(first==null)
				continue;
			Node node = probe.nodeAt(first.pos);
			//Box, feeder, box: a floor with a box either side of it. The feeder is a length of run
			//like any other, so the two are joined. Two boxes touching each other are not -- that is
			//the "a run ends at the first box it meets" rule, and nothing here relaxes it.
			if(node==Node.JUNCTION)
			{
				if(first.blocks > 1)
					found.put(first.pos, first.blocks);
				continue;
			}
			if(node!=Node.CONDUIT)
				continue;
			EnumFacing mount = probe.mountAt(first.pos);
			//The step from the box to the conduit has to run along that conduit's surface. A
			//conduit lying on the floor does not pick up a box sitting on top of it.
			if(!ConduitGeometry.isInPlane(mount, dir))
				continue;
			if(visited.add(first.pos))
			{
				distance.put(first.pos, first.blocks);
				open.add(first.pos);
			}
		}

		explore(start, open, visited, distance, found, probe);
		return found;
	}

	/**
	 * Find every junction box on the runs a given conduit or feeder belongs to.
	 * <p>
	 * <strong>This is what tells a box that something changed out of earshot.</strong> A box
	 * rebuilds its runs when one of its own neighbours changes, which is right for every gesture
	 * that finishes a run <em>at</em> a box -- and wrong for the one that finishes it in the middle.
	 * Dropping a feeder into a floor with conduit already laid above and below it is precisely that
	 * gesture: the two halves become one run, and neither box is anywhere near the block that did
	 * it. Without this the run is joined on the wall and not in the graph, and stays that way until
	 * something else happens to poke a box or the chunk reloads -- a bug that looks like the feature
	 * intermittently not working, which is the worst kind to be handed.
	 * <p>
	 * Like {@link #junctionsFrom} the walk stops at the first box it meets, and for the same reason
	 * it is the right answer here: a box further along has a run of its own that this change did not
	 * touch, and waking it would be work to discover nothing. What differs is only that this starts
	 * from a length of run rather than from a box, and reports the boxes rather than their distances.
	 */
	public static Set<BlockPos> junctionsAround(BlockPos start, Probe probe)
	{
		Map<BlockPos, Integer> found = new HashMap<>();
		Set<BlockPos> visited = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		Map<BlockPos, Integer> distance = new HashMap<>();

		Node here = probe.nodeAt(start);
		if(here==Node.CONDUIT)
		{
			visited.add(start);
			distance.put(start, 0);
			open.add(start);
		}
		else if(here==Node.PASS_THROUGH)
		{
			//A feeder is not on any surface, so it has no plane to walk in. What it has is two ends,
			//and whatever is at each of them is the run.
			EnumFacing.Axis axis = probe.axisAt(start);
			for(EnumFacing dir : EnumFacing.VALUES)
			{
				if(axis!=null&&dir.getAxis()!=axis)
					continue;
				Landing end = across(start, dir, probe);
				if(end==null)
					continue;
				Node node = probe.nodeAt(end.pos);
				if(node==Node.JUNCTION)
					found.put(end.pos, end.blocks);
				else if(node==Node.CONDUIT&&ConduitGeometry.isInPlane(probe.mountAt(end.pos), dir)
						&&visited.add(end.pos))
				{
					distance.put(end.pos, end.blocks);
					open.add(end.pos);
				}
			}
		}

		explore(start, open, visited, distance, found, probe);
		return found.keySet();
	}

	/**
	 * The walk itself, shared by both entry points above: follow conduit until it runs out, noting
	 * every junction box met on the way and never walking through one.
	 */
	private static void explore(BlockPos start, Deque<BlockPos> open, Set<BlockPos> visited,
								Map<BlockPos, Integer> distance, Map<BlockPos, Integer> found,
								Probe probe)
	{
		int examined = 0;
		while(!open.isEmpty()&&examined++ < MAX_NODES)
		{
			BlockPos here = open.poll();
			EnumFacing mount = probe.mountAt(here);
			if(mount==null)
				continue;
			for(EnumFacing dir : ConduitGeometry.inPlane(mount))
			{
				Landing landing = across(here, dir, probe);
				if(landing==null)
					continue;
				BlockPos next = landing.pos;
				if(next.equals(start)||visited.contains(next))
					continue;
				int step = distance.get(here)+landing.blocks;
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
				//	=================================
				//	Whether the run may change surface
				//	=================================
				//A run stays on one surface, so ordinarily the next conduit has to be clipped to the
				//same face. Crossing a feeder is the exception, and it is the point of the block: a
				//run coming down a wall and out along the ceiling below is exactly what somebody
				//puts a feeder through a floor to do. The far conduit still has to have the step in
				//its own plane -- the same thing a junction box asks -- so the feeder licenses a
				//plane change without licensing a conduit that faces the wrong way entirely.
				boolean crossedAFeeder = landing.blocks > 1;
				if(crossedAFeeder
						?!ConduitGeometry.isInPlane(probe.mountAt(next), dir)
						:!ConduitGeometry.connects(mount, probe.mountAt(next), dir))
					continue;
				visited.add(next);
				distance.put(next, step);
				open.add(next);
			}
		}
	}
}
