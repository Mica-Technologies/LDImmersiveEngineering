/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * Grid Service Unit: the point where power leaves a segment and re-enters the world.
 * <p>
 * It powers whatever it touches, trying the block it is bolted to first and then the
 * remaining faces. That rule is what makes both useful placements work with no special
 * casing: bolt it straight onto a machine or capacitor to power that, or bolt it to a pole
 * and attach an IE connector to feed a wire network.
 * <p>
 * Delivery is push-only, exactly like a wire connector's {@code outputEnergy}. There is
 * deliberately no extractable capability: a second, unbudgeted path into the segment ledger
 * would let a pull-consumer bypass the per-tick caps the engine enforces.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridService extends TileEntityGridDevice implements INeighbourChangeTile
{
	/**
	 * Which faces had a flux receiver last time we looked. Recomputed on neighbour change
	 * rather than every tick.
	 */
	private final boolean[] receiverFaces = new boolean[6];
	private boolean facesDirty = true;

	@Override
	public GridDeviceType getDeviceType()
	{
		return GridDeviceType.SERVICE;
	}

	@Override
	public int insertFromGrid(int max, boolean simulate)
	{
		if(max <= 0||world==null)
			return 0;
		if(facesDirty)
			rescanFaces();

		int delivered = 0;
		//Mount face first: "the box powers the thing it is bolted to" stays true even when
		//other neighbours would also accept.
		delivered += deliverTo(facing, max, simulate);
		if(delivered >= max)
			return delivered;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(side==facing)
				continue;
			delivered += deliverTo(side, max-delivered, simulate);
			if(delivered >= max)
				break;
		}
		return delivered;
	}

	private int deliverTo(EnumFacing side, int amount, boolean simulate)
	{
		if(amount <= 0||!receiverFaces[side.ordinal()])
			return 0;
		TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
		//Never feed another grid box: a Service into a Feed would be a closed loop that
		//launders energy back into the grid it just left.
		if(target==null||target instanceof TileEntityGridDevice)
			return 0;
		return Math.max(0, EnergyHelper.insertFlux(target, side.getOpposite(), amount, simulate));
	}

	private void rescanFaces()
	{
		for(EnumFacing side : EnumFacing.VALUES)
		{
			TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
			receiverFaces[side.ordinal()] = target!=null&&!(target instanceof TileEntityGridDevice)
					&&EnergyHelper.isFluxReceiver(target, side.getOpposite());
		}
		facesDirty = false;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		facesDirty = true;
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		facesDirty = true;
	}

	@Override
	protected String describeWorldHookup()
	{
		if(hasReceiver())
			return null;
		return "Nothing adjacent accepts power. Bolt this onto a machine or capacitor, "
				+"or put a wire connector against it to feed a wire network.";
	}

	/**
	 * @return true if anything adjacent is currently able to accept flux
	 */
	public boolean hasReceiver()
	{
		if(facesDirty&&world!=null)
			rescanFaces();
		for(boolean face : receiverFaces)
			if(face)
				return true;
		return false;
	}
}
