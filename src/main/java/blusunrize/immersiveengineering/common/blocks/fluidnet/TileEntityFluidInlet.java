/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.api.fluid.network.FluidDevice;
import blusunrize.immersiveengineering.api.fluid.network.FluidDeviceType;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fluid Inlet: the point where fluid leaves the world and enters a main.
 * <p>
 * It behaves like any ordinary tank to its neighbours -- a pipe, a Wellhead or a Distillation
 * Tower nozzle can all push into it -- and additionally draws from the block it is bolted to if
 * that block permits draining, so bolting one onto a buried tank's fill cap just works.
 * <p>
 * In city mode the internal buffer is shrunk to a single {@link FluidNetConfig#sipAmount}. That is
 * what turns the running cost into "one millibucket every five seconds": the source physically
 * cannot push more than one sip in, and the engine drains that sip only on the liveness check.
 * Anything larger would let a dry well look live for hours while the engine drained a big stale
 * buffer.
 * <p>
 * The deliberate mirror of {@code TileEntityGridFeed}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class TileEntityFluidInlet extends TileEntityFluidNetDevice
{
	/**
	 * A buffer, not a tank. Sized to the transfer cap so exactly one tick of intake fits: this
	 * fitting is a doorway, and storage is what the buried tanks are for.
	 */
	private final FluidTank buffer = new FluidTank(FluidNetConfig.defaultDeviceCap);

	@Override
	public FluidDeviceType getDeviceType()
	{
		return FluidDeviceType.INLET;
	}

	@Override
	protected void applyLimits()
	{
		int cap = device!=null?device.getTransferCap(): backupCap;
		int size = CityMode.petroleum()?Math.max(1, FluidNetConfig.sipAmount): Math.max(1, cap);
		buffer.setCapacity(size);
		//Shrinking the buffer below what it holds would leave an over-full tank that reports a
		//negative amount of room. Spilling the excess is the honest outcome: it happened because
		//somebody lowered a setting, and quietly keeping it would make the cap a lie.
		if(buffer.getFluidAmount() > size)
			buffer.drainInternal(buffer.getFluidAmount()-size, true);
	}

	//	=================================
	//		IFluidEndpoint
	//	=================================

	@Nullable
	@Override
	public String getOfferedFluid()
	{
		FluidStack held = buffer.getFluid();
		if(held!=null&&held.amount > 0&&held.getFluid()!=null)
			return held.getFluid().getName();
		//Nothing buffered: ask what the block behind us would give, so an Inlet bolted straight
		//onto a full tank types its main without anybody having to prime it first.
		IFluidHandler mounted = mountedHandler();
		if(mounted==null)
			return null;
		FluidStack drained = mounted.drain(1, false);
		return drained==null||drained.getFluid()==null?null: drained.getFluid().getName();
	}

	@Override
	public int extractForMain(String fluid, int max, boolean simulate)
	{
		if(max <= 0||fluid==null||world==null)
			return 0;
		Fluid target = FluidRegistry.getFluid(fluid);
		if(target==null)
			return 0;

		int taken = 0;
		FluidStack held = buffer.getFluid();
		if(held!=null&&held.getFluid()==target)
		{
			FluidStack drawn = buffer.drainInternal(Math.min(max, held.amount), !simulate);
			if(drawn!=null)
				taken += drawn.amount;
		}
		int left = max-taken;
		if(left > 0)
			taken += tapMountedBlock(target, left, simulate);
		return taken;
	}

	/**
	 * Draws directly from the block this fitting is bolted to, if it allows draining.
	 * <p>
	 * Only the mount face: "the fitting taps the thing it is attached to" is a rule a player can
	 * see from the outside, which a six-way scan would not be.
	 */
	private int tapMountedBlock(Fluid target, int amount, boolean simulate)
	{
		IFluidHandler mounted = mountedHandler();
		if(mounted==null)
			return 0;
		//By stack rather than by amount, so a tank holding something else is not drained by
		//accident. This is the guard that stops a main quietly emptying a player's water supply
		//because the fitting happened to be bolted to the wrong tank.
		FluidStack drawn = mounted.drain(new FluidStack(target, amount), !simulate);
		return drawn==null?0: Math.max(0, drawn.amount);
	}

	@Nullable
	private IFluidHandler mountedHandler()
	{
		TileEntity target = getMountedBlock();
		if(target==null)
			return null;
		EnumFacing from = facing.getOpposite();
		if(!target.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, from))
			return null;
		return target.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, from);
	}

	//	=================================
	//		FLUID PLUMBING
	//	=================================

	@Override
	public boolean hasCapability(@Nonnull Capability<?> capability, @Nullable EnumFacing facing)
	{
		return capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
				||super.hasCapability(capability, facing);
	}

	@Nullable
	@Override
	public <T> T getCapability(@Nonnull Capability<T> capability, @Nullable EnumFacing facing)
	{
		//Every side accepts: the fitting does not care which face a pipe arrives on. There is
		//deliberately no drain path -- an Outlet feeding an Inlet through a pipe would launder
		//fluid straight back into the main it left, and the ledger would show infinite work.
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(new FillOnly(buffer));
		return super.getCapability(capability, facing);
	}

	/**
	 * A fill-only view of the buffer.
	 */
	private static final class FillOnly implements IFluidHandler
	{
		private final FluidTank tank;

		FillOnly(FluidTank tank)
		{
			this.tank = tank;
		}

		@Override
		public net.minecraftforge.fluids.capability.IFluidTankProperties[] getTankProperties()
		{
			return tank.getTankProperties();
		}

		@Override
		public int fill(FluidStack resource, boolean doFill)
		{
			return tank.fill(resource, doFill);
		}

		@Nullable
		@Override
		public FluidStack drain(FluidStack resource, boolean doDrain)
		{
			return null;
		}

		@Nullable
		@Override
		public FluidStack drain(int maxDrain, boolean doDrain)
		{
			return null;
		}
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		if(!descPacket)
			nbt.setTag("buffer", buffer.writeToNBT(new NBTTagCompound()));
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		if(!descPacket)
		{
			applyLimits();
			buffer.readFromNBT(nbt.getCompoundTag("buffer"));
		}
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		applyLimits();
	}

	@Override
	protected String describeWorldHookup()
	{
		//Something is arriving, so the hookup is fine whatever it looks like.
		if(buffer.getFluidAmount() > 0||getOfferedFluid()!=null)
			return null;
		return "Nothing is arriving. Bolt this onto a tank or a wellhead, or run a pipe into it.";
	}

	/**
	 * @return how full the intake buffer is, for the in-world readout
	 */
	public int getBufferedFluid()
	{
		return buffer.getFluidAmount();
	}

	@Override
	protected java.util.List<String> buildStatusLines()
	{
		java.util.List<String> lines = super.buildStatusLines();
		FluidDevice self = getDevice();
		if(self!=null&&self.isLinked())
			lines.add("Buffered: "+buffer.getFluidAmount()+" / "+buffer.getCapacity()+" mB");
		return lines;
	}
}
