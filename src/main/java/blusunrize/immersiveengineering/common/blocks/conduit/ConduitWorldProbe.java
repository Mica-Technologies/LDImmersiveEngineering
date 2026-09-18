/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * A real world, in the shape {@link ConduitRoute} asks for.
 * <p>
 * One copy rather than one per caller. The walk is world-free so it can be tested, which means
 * somebody has to translate blocks into nodes, and having two places do it is how a junction box and
 * a conduit end up disagreeing about what a feeder is -- with the only symptom being a run that
 * draws itself joined and carries nothing.
 * <p>
 * <strong>This half answers for what can be seen and nothing else.</strong> A position in an unloaded
 * chunk reads as empty here, exactly as it always has, because asking the world would generate the
 * chunk and a run along a border would drag chunks in behind it forever. What fills that in is
 * {@link ConduitIndex}, wrapped around this by {@link #of(World)}: the saved table answers for
 * anything the world will not. Callers want {@link #of(World)}; this class alone is the world as it
 * can be seen, which is what the index's own sweep checks its entries against.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitWorldProbe implements ConduitRoute.Probe
{
	private final World world;

	ConduitWorldProbe(World world)
	{
		this.world = world;
	}

	/**
	 * The probe every walk in the game uses: the world, with the conduit index standing in for
	 * whatever is not loaded.
	 * <p>
	 * Composed here rather than at each call site so that "loaded first, remembered second" is stated
	 * once. See {@link ConduitIndex#backing} for the composition itself, which is pure and tested.
	 */
	static ConduitRoute.Probe of(World world)
	{
		return ConduitIndex.INSTANCE.backing(world.provider.getDimension(), new ConduitWorldProbe(world));
	}

	/**
	 * The world as it is actually built, for {@link ConduitIndex#sweepChunk}.
	 * <p>
	 * Reads block states rather than tile entities, for the reason {@link ConduitIndex.Hardware} gives
	 * at length: a chunk's blocks are right the moment the chunk exists, and its tile entities arrive
	 * on their own schedule -- and a sweep that ran in that window would find nothing anywhere and
	 * delete a whole chunk's worth of good index.
	 */
	public static ConduitIndex.Hardware asBuilt(World world)
	{
		return pos -> {
			if(!world.isBlockLoaded(pos))
				//Never asked about an unloaded position -- the sweep runs over the chunk that has just
				//arrived -- but "I cannot see it" must not read as "it is gone" if it ever is.
				return ConduitRoute.Node.NOTHING;
			IBlockState state = world.getBlockState(pos);
			if(!(state.getBlock() instanceof BlockConduit))
				return ConduitRoute.Node.NOTHING;
			int meta = state.getBlock().getMetaFromState(state);
			if(meta==BlockTypes_Conduit.JUNCTION_BOX.getMeta())
				return ConduitRoute.Node.JUNCTION;
			if(meta==BlockTypes_Conduit.GROUND_FEEDER.getMeta())
				return ConduitRoute.Node.PASS_THROUGH;
			return ConduitRoute.Node.CONDUIT;
		};
	}

	/**
	 * Unloaded means "not there" rather than "look": asking would generate the chunk, and a run
	 * along a border would drag chunks in behind it forever.
	 */
	@Nullable
	private TileEntity at(BlockPos pos)
	{
		return world.isBlockLoaded(pos)?world.getTileEntity(pos): null;
	}

	/**
	 * The question {@link #at} deliberately cannot answer: whether "nothing there" was a reading or
	 * a refusal to look. A caller that prunes saved state against a walk needs to know the
	 * difference -- see {@code ConduitRoute.Walk}.
	 */
	@Override
	public boolean isLoaded(BlockPos pos)
	{
		return world.isBlockLoaded(pos);
	}

	@Override
	public ConduitRoute.Node nodeAt(BlockPos pos)
	{
		TileEntity te = at(pos);
		if(te instanceof TileEntityConduit)
			return ConduitRoute.Node.CONDUIT;
		if(te instanceof TileEntityJunctionBox)
			return ConduitRoute.Node.JUNCTION;
		if(te instanceof TileEntityGroundFeeder)
			return ConduitRoute.Node.PASS_THROUGH;
		return ConduitRoute.Node.NOTHING;
	}

	@Override
	public EnumFacing mountAt(BlockPos pos)
	{
		TileEntity te = at(pos);
		return te instanceof TileEntityConduit?((TileEntityConduit)te).facing: null;
	}

	@Override
	public EnumFacing.Axis axisAt(BlockPos pos)
	{
		TileEntity te = at(pos);
		return te instanceof TileEntityGroundFeeder?((TileEntityGroundFeeder)te).getAxis(): null;
	}

	/**
	 * Solidity, the same question {@code ConduitPlacement} asks and the same one
	 * {@code TileEntityConduit} asks before it draws a riser. Unloaded is "no", for the reason
	 * {@link #at} gives: a walk must not generate the chunk it is looking into.
	 * <p>
	 * <strong>The one thing the index cannot stand in for.</strong> An inner corner turns around an
	 * ordinary world block -- a wall, a hillside, anything at all -- and remembering which of those are
	 * solid would be a copy of the map. {@code ConduitRoute.cornerSupported} never asks about a corner
	 * nothing can see: it takes the joint on trust instead, and says so, so that a walk which did
	 * cannot delete anything.
	 */
	@Override
	public boolean isMountable(BlockPos pos, EnumFacing face)
	{
		return world.isBlockLoaded(pos)&&world.getBlockState(pos).isSideSolid(world, pos, face);
	}
}
