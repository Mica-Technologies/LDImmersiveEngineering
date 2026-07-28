/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.FluidDeviceType;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;

/**
 * Fluid Outlet: the point where fluid leaves a main and re-enters the world.
 * <p>
 * It fills whatever it touches, trying the block it is bolted to first and then the remaining
 * faces. That rule is what makes both useful placements work with no special casing: bolt it
 * straight onto a boiler or a buried tank's cap to feed that, or bolt it to a wall and run a pipe
 * off it to feed a room full of machines.
 * <p>
 * Delivery is push-only, exactly like the Grid Service Unit's. There is deliberately no fluid
 * capability at all: a second, unbudgeted path into the main's ledger would let a pump bypass the
 * per-tick caps the engine enforces, and the whole point of the caps is that they are the only
 * throughput rule on the network.
 * <p>
 * The deliberate mirror of {@code TileEntityGridService}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class TileEntityFluidOutlet extends TileEntityFluidNetDevice implements INeighbourChangeTile
{
	/**
	 * Which faces had a fluid handler last time we looked. Recomputed on neighbour change rather
	 * than every tick.
	 */
	private final boolean[] receiverFaces = new boolean[6];
	private boolean facesDirty = true;

	@Override
	public FluidDeviceType getDeviceType()
	{
		return FluidDeviceType.OUTLET;
	}

	@Override
	public int insertFromMain(String fluid, int max, boolean simulate)
	{
		if(max <= 0||fluid==null||world==null)
			return 0;
		Fluid target = FluidRegistry.getFluid(fluid);
		if(target==null)
			return 0;
		if(facesDirty)
			rescanFaces();

		int delivered = 0;
		//Mount face first: "the fitting feeds the thing it is bolted to" stays true even when other
		//neighbours would also accept.
		delivered += deliverTo(facing, target, max, simulate);
		if(delivered >= max)
			return delivered;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(side==facing)
				continue;
			delivered += deliverTo(side, target, max-delivered, simulate);
			if(delivered >= max)
				break;
		}
		return delivered;
	}

	private int deliverTo(EnumFacing side, Fluid fluid, int amount, boolean simulate)
	{
		if(amount <= 0||!receiverFaces[side.ordinal()])
			return 0;
		IFluidHandler handler = handlerAt(side);
		if(handler==null)
			return 0;
		return Math.max(0, handler.fill(new FluidStack(fluid, amount), !simulate));
	}

	@Nullable
	private IFluidHandler handlerAt(EnumFacing side)
	{
		TileEntity target = Utils.getExistingTileEntity(world, pos.offset(side));
		//Never feed another fluid network fitting: an Outlet into an Inlet would be a closed loop
		//that launders fluid back into the main it just left.
		if(target==null||target instanceof TileEntityFluidNetDevice)
			return null;
		EnumFacing from = side.getOpposite();
		if(!target.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, from))
			return null;
		return target.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, from);
	}

	private void rescanFaces()
	{
		for(EnumFacing side : EnumFacing.VALUES)
			receiverFaces[side.ordinal()] = handlerAt(side)!=null;
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
		return "Nothing adjacent accepts fluid. Bolt this onto a machine or a tank's fill cap, "
				+"or run a pipe off it.";
	}

	/**
	 * @return true if anything adjacent is currently able to accept fluid
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
