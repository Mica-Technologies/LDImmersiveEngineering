/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * What run, if any, is behind a node on a wire network -- and therefore which destinations a push
 * from that node must not be delivered to.
 *
 * <h3>The latch this exists to break</h3>
 * In city mode a conductor is <em>energised or not</em> rather than carrying counted flux: any credit
 * at all fills it, and it stays lit for twenty ticks. That is the right model for presence and it has
 * one hole in it, which the live server found.
 * <p>
 * Put two or more breakouts of one run onto the same wire network -- three connectors bolted to three
 * boxes on one pole, which is what a pole <em>looks like</em> -- and the run feeds itself forever. Box
 * B1 hands its conductor to the connector bolted against it; that connector buffers the flux and, on
 * its own tick, pushes it out across the network; the push reaches a connector bolted to box B2 of the
 * same run; that connector inserts into B2, which reads a credit and fills to capacity. B2 hands it
 * back to its own connector, and round it goes. Both poles on the playtester's line did this: the far
 * boxes read a full channel in the server save with no run connecting them to anything at all.
 * <p>
 * <strong>A marker on the call stack cannot fix it.</strong> A connector does not push in the call
 * that fed it -- it buffers, and pushes from its own {@code update()} a tick later -- so by the time
 * the energy goes back out there is no stack left to have marked. The provenance has to come from the
 * topology instead, which is what this class is: work out the run behind the pushing node once, and
 * refuse the destinations that lead back into it.
 *
 * <h3>Why nearly every push pays nothing</h3>
 * {@code WireNetTransfer.city} runs for every connector on a server, every tick, on networks of
 * thousands of nodes. Almost none of those has a conduit anywhere near it, and the test that says so
 * is a lookup in the wire graph's own map -- no chunk, no tile entity, no allocation. Only a node that
 * really is a box, or really is bolted against one, goes on to walk a run and build a set.
 *
 * <h3>What counts as "the same run"</h3>
 * The run's boxes, and every block touching one of them. The touching blocks are where energy gets
 * back <em>in</em>: a wire strung to a box face arrives at the box itself, and a connector bolted to a
 * box face inserts into it from next door. Taking all six neighbours rather than only the patched
 * faces is deliberate -- it needs no tile entity, so it works for the boxes of an unloaded run, which
 * is exactly the case the virtual push is. It is also barely broader in practice: auto-patching claims
 * a face the moment wiring hardware is set against it, so a connector touching a box is essentially
 * always on that box's conductor anyway.
 * <p>
 * A <em>different</em> run behind a destination is untouched. Run to network to another run is a
 * series build somebody meant, and refusing it would break a legitimate thing to fix an illegitimate
 * one.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class ConduitRuns
{
	private ConduitRuns()
	{
	}

	/**
	 * A ceiling on one run's box count, the same one {@code TileEntityJunctionBox} walks its run
	 * under, so a pathological build cannot turn one push into an unbounded walk.
	 */
	private static final int MAX_BOXES_PER_RUN = 512;

	// ------------------------------------------------------------------
	// The pure half
	// ------------------------------------------------------------------

	/**
	 * Every box of one run, flooded over bundle edges from one of them.
	 * <p>
	 * Pure, and takes the graph as a function, so the decision can be asserted without a world: a run
	 * is a handful of boxes joined by bundles, and that is all this needs to know.
	 */
	public static Set<BlockPos> boxesOfRun(BlockPos start, Function<BlockPos, Collection<BlockPos>> bundles)
	{
		Set<BlockPos> seen = new HashSet<>();
		Deque<BlockPos> open = new ArrayDeque<>();
		seen.add(start);
		open.add(start);
		while(!open.isEmpty()&&seen.size() < MAX_BOXES_PER_RUN)
		{
			BlockPos here = open.poll();
			for(BlockPos peer : bundles.apply(here))
				if(seen.add(peer))
					open.add(peer);
		}
		return seen;
	}

	/**
	 * The positions a push from this run must skip: its boxes, and everything sitting against one.
	 *
	 * @return a set to test destinations against, cheap to ask and built once per push
	 */
	public static Set<BlockPos> shadowOf(Collection<BlockPos> runBoxes)
	{
		Set<BlockPos> shadow = new HashSet<>(runBoxes.size()*8);
		for(BlockPos box : runBoxes)
		{
			shadow.add(box);
			for(EnumFacing face : EnumFacing.VALUES)
				shadow.add(box.offset(face));
		}
		return shadow;
	}

	/**
	 * Is this destination one the push must skip?
	 * <p>
	 * A null shadow means the pushing node has no run behind it, which is the answer for virtually
	 * every node on a server -- and then this is a null check and the push is exactly what it always
	 * was. Stated as its own method so that "no run behind the origin changes nothing" is a thing a
	 * test can assert rather than a thing a comment claims.
	 */
	public static boolean blocks(@Nullable Set<BlockPos> shadow, BlockPos destination)
	{
		return shadow!=null&&shadow.contains(destination);
	}

	// ------------------------------------------------------------------
	// The half that reads the wire graph
	// ------------------------------------------------------------------

	/**
	 * @return true if a bundle ends at that position, which is to say there is a junction box there
	 * with a run on it. A map lookup in the global wire graph: no chunk is touched and none is loaded.
	 */
	public static boolean hasRun(int dimension, BlockPos pos)
	{
		Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(dimension, pos);
		if(conns==null)
			return false;
		for(Connection con : conns)
			if(con.isBundle())
				return true;
		return false;
	}

	/**
	 * The boxes one box shares a run with, one hop away.
	 */
	public static Collection<BlockPos> bundleNeighbours(int dimension, BlockPos pos)
	{
		Set<Connection> conns = ImmersiveNetHandler.INSTANCE.getConnections(dimension, pos);
		if(conns==null)
			return Collections.emptyList();
		Set<BlockPos> peers = new HashSet<>();
		for(Connection con : conns)
			if(con.isBundle())
				peers.add(con.end);
		return peers;
	}

	/**
	 * Which junction box a pushing node's energy came out of, or null -- which is the answer for
	 * virtually every node on a server.
	 * <p>
	 * A box is its own answer. Anything else is one only if it is touching a box that has a run, and
	 * is on a face that box breaks a conductor out onto. A box whose chunk is gone cannot be asked
	 * about its patch table, and then the conservative reading wins: assume it does break out there,
	 * because the failure this is guarding against is a run feeding itself, and the cost of being
	 * wrong the other way is one consumer beside an unloaded box going without.
	 */
	@Nullable
	public static BlockPos runBehind(World world, BlockPos pos)
	{
		int dimension = world.provider.getDimension();
		if(hasRun(dimension, pos))
			return pos;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			SCRATCH.setPos(pos.getX()+face.getXOffset(), pos.getY()+face.getYOffset(),
					pos.getZ()+face.getZOffset());
			if(!hasRun(dimension, SCRATCH))
				continue;
			BlockPos at = SCRATCH.toImmutable();
			TileEntity te = world.isBlockLoaded(at)?world.getTileEntity(at): null;
			if(!(te instanceof TileEntityJunctionBox)
					||((TileEntityJunctionBox)te).canConnectEnergy(face.getOpposite()))
				return at;
		}
		return null;
	}

	/**
	 * The six neighbours above, without six {@code BlockPos} to show for it.
	 * <p>
	 * This runs for every pushing node on a server every tick, and the answer is "no" for almost all
	 * of them, so the six probes have to cost nothing at all -- eighteen thousand throwaway positions
	 * a tick on a large network is exactly the shape of thing this mod's profiling history is about.
	 * A mutable position hashes and compares as the immutable one it stands for, so the wire graph's
	 * maps take it as a key; only the rare hit is copied into something that can be kept.
	 * <p>
	 * Server thread only, and used serially: nothing between {@code setPos} and the lookup can
	 * re-enter this method.
	 */
	private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

	/**
	 * The destinations a push from this node must skip, or null when it has no run behind it and
	 * nothing about the push changes.
	 */
	@Nullable
	public static Set<BlockPos> shadowFor(World world, BlockPos pos)
	{
		BlockPos box = runBehind(world, pos);
		return box==null?null: shadowOfRunAt(world, box);
	}

	/**
	 * The same, for a push whose run is already known -- the virtual one, where the origin box is
	 * unloaded by definition and there is nothing to work it out from but the record.
	 */
	public static Set<BlockPos> shadowOfRunAt(World world, BlockPos boxPos)
	{
		int dimension = world.provider.getDimension();
		return shadowOf(boxesOfRun(boxPos, at -> bundleNeighbours(dimension, at)));
	}
}
