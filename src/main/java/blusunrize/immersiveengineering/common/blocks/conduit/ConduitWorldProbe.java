/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

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
 *
 * @author LDImmersiveEngineering -- conduits
 */
class ConduitWorldProbe implements ConduitRoute.Probe
{
	private final World world;

	ConduitWorldProbe(World world)
	{
		this.world = world;
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
}
