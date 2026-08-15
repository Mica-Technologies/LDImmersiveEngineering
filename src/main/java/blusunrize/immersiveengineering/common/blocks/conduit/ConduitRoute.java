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

		/**
		 * Whether a conduit could clip to that block on that face -- which is also what a run needs
		 * in the corner of an inner turn, since the riser lies against it on its way up.
		 * <p>
		 * Defaulted to false so a probe over a world with no walls in it -- most of the older
		 * tests -- keeps answering the questions it was written to answer. A world where nothing is
		 * solid has no inner corners in it, which is the right answer for a world with no walls.
		 */
		default boolean isMountable(BlockPos pos, EnumFacing face)
		{
			return false;
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
	 * A conduit next to the box joins it if the step between them lies in the conduit's own plane,
	 * so a box is simply something a run may end at. <strong>A run arrives at a box face on and
	 * never around a corner</strong> -- a box is a cube in the middle of its cell, so a run wrapping
	 * round the outside of one would have nowhere to arrive; put the box at the corner instead.
	 * <p>
	 * From there the walk follows the run, which since the corner rules landed may leave the surface
	 * it started on: see {@link #explore}.
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
	 * <p>
	 * Nine probes a node rather than four, because a run turns corners on its own: four steps along
	 * the surface, four diagonally around an outer corner, and one out of the surface for the
	 * inner one. That is a building-time cost and nothing else -- nothing here runs per tick, and
	 * {@link #MAX_NODES} still bounds the whole walk -- and what it buys is the property the cost of
	 * this feature rests on staying true with corners in it: <strong>a run between two boxes is
	 * still one edge in the wire graph, however many times it turns.</strong>
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
				if(landing!=null)
				{
					BlockPos next = landing.pos;
					int step = distance.get(here)+landing.blocks;
					Node node = probe.nodeAt(next);
					if(node==Node.JUNCTION)
					{
						//First one wins, and shorter wins over longer: two paths to the same box are
						//one connection, at the length of the route somebody would actually trace.
						if(!next.equals(start))
						{
							Integer existing = found.get(next);
							if(existing==null||existing > step)
								found.put(next, step);
						}
					}
					else if(node==Node.CONDUIT&&!next.equals(start)&&!visited.contains(next))
					{
						//	=================================
						//	Whether the run may change surface here
						//	=================================
						//Three ways for it to, and the flat case is still much the commonest:
						//
						//  - the same surface carries on into the next cell;
						//  - the next cell is clipped to the face this step is walking onto, which
						//    is a wall seen from the floor at its foot -- an inner corner, and it
						//    needs a block in the corner to turn around;
						//  - a feeder was crossed on the way, which licenses any plane containing
						//    the step. That is the point of the block: a run coming down a wall and
						//    out along the ceiling below is what somebody puts one through a floor
						//    to do.
						EnumFacing nextMount = probe.mountAt(next);
						boolean crossedAFeeder = landing.blocks > 1;
						boolean joins;
						if(crossedAFeeder)
							joins = ConduitGeometry.isInPlane(nextMount, dir);
						else
							joins = ConduitGeometry.connects(mount, nextMount, dir)
									||(ConduitGeometry.joinsInnerCorner(mount, nextMount, dir)
									&&cornerSupported(here, mount, nextMount, dir, probe));
						if(joins)
						{
							visited.add(next);
							distance.put(next, step);
							open.add(next);
						}
					}
				}

				//	=================================
				//	Around the outside of the corner
				//	=================================
				//The other half of the same idea, and the one that does not walk along a face at
				//all: the run reaches the edge of the block it is bolted to and follows that
				//block's next face round, so the piece it meets is a diagonal neighbour clipped to
				//the same supporting block. A junction box is never picked up this way -- a box is
				//a cube in the middle of its cell and a run has to arrive at one face on.
				BlockPos wrapped = ConduitGeometry.outerCornerCell(here, mount, dir);
				if(!wrapped.equals(start)&&!visited.contains(wrapped)
						&&probe.nodeAt(wrapped)==Node.CONDUIT
						&&probe.mountAt(wrapped)==ConduitGeometry.outerCornerMount(dir))
				{
					visited.add(wrapped);
					distance.put(wrapped, distance.get(here)+1);
					open.add(wrapped);
				}
			}

			//	=================================
			//	Up the wall this one is running into
			//	=================================
			//The climbing half of an inner corner, and the one step in the whole walk that leaves
			//the plane: the piece a riser meets is one cell out along this conduit's own mounting
			//axis, which is not one of its four arms at all. There is at most one -- a cell has one
			//neighbour that way -- so this is one probe per node rather than four.
			BlockPos above = here.offset(mount.getOpposite());
			if(!above.equals(start)&&!visited.contains(above)&&probe.nodeAt(above)==Node.CONDUIT)
			{
				EnumFacing aboveMount = probe.mountAt(above);
				if(ConduitGeometry.joinsInnerCorner(mount, aboveMount, mount.getOpposite())
						&&cornerSupported(here, mount, aboveMount, mount.getOpposite(), probe))
				{
					visited.add(above);
					distance.put(above, distance.get(here)+1);
					open.add(above);
				}
			}
		}
	}

	/**
	 * Whether the block an inner corner turns around is actually there.
	 * <p>
	 * Asked from both ends of the joint about the same block, which is why
	 * {@link ConduitGeometry#innerCornerSupport} works it out rather than each end doing it: a walk
	 * that agreed with the renderer at one end and not at the other would give a run that draws
	 * joined and carries nothing.
	 */
	private static boolean cornerSupported(BlockPos pos, EnumFacing mount, EnumFacing otherMount,
										   EnumFacing towards, Probe probe)
	{
		BlockPos corner = ConduitGeometry.innerCornerSupport(pos, mount, otherMount, towards);
		//Empty of conduit hardware as well as solid: a ground feeder is a solid cube a run goes
		//through rather than round, and a box is not solid at all.
		return probe.nodeAt(corner)==Node.NOTHING
				&&probe.isMountable(corner,
				ConduitGeometry.innerCornerSupportFace(mount, otherMount, towards));
	}
}
