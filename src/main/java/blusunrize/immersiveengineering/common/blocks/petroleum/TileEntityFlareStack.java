/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.ILightValue;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Burns off gas nobody has anywhere better to put.
 * <p>
 * An oilfield produces gas whether or not there is a use for it, and a wellhead that backs up
 * stops producing oil. Before a scrubber exists, a flare is the answer: it accepts anything
 * gaseous and destroys it. Deliberately a <em>loss</em> -- it yields nothing at all -- so that
 * the moment a player notices how much is going up the stack, building a scrubber pays for
 * itself.
 * <p>
 * Not {@code ITickable}. Burning happens when gas is pushed in, which is the only moment
 * anything can change, so an idle flare costs exactly nothing.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityFlareStack extends TileEntityIEBase implements IBlockOverlayText,
		IComparatorOverride, ILightValue
{
	/**
	 * Small on purpose: a flare is a drain, not a buffer. Anything it holds is already lost, so
	 * holding more of it would only delay the accounting.
	 */
	public static final int CAPACITY = 2000;
	/**
	 * Millibuckets destroyed per delivery. Sized so a wellhead's gas stream is consumed as fast
	 * as it arrives and the well never backs up behind the flare.
	 */
	public static final int BURN_RATE = 200;
	/**
	 * How long one delivery keeps the flame alight. Longer than a wellhead's production
	 * interval, so a steady feed reads as a steady flame rather than a stutter.
	 */
	public static final int FLAME_TICKS = 40;

	private final FluidTank tank = new FluidTank(CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return isFlarable(fluid);
		}

		@Override
		public boolean canDrainFluidType(FluidStack fluid)
		{
			//Nothing comes back out. A flare is where gas goes to stop existing; letting it be
			//drained would make it a tank that happens to glow.
			return false;
		}
	};

	/**
	 * Ticks of flame left to draw, kept so a steady feed reads as a steady flame rather than a
	 * stutter between deliveries.
	 */
	private int flameTicks;
	private long lifetimeBurned;

	/**
	 * @return true if this is something a flare will take. Gases only -- letting it eat crude
	 * would make disposing of a spill free, and disposal is meant to cost something.
	 */
	public static boolean isFlarable(@Nullable FluidStack stack)
	{
		if(stack==null||stack.getFluid()==null)
			return false;
		String name = stack.getFluid().getName();
		return "ie_sour_gas".equals(name)||"natural_gas".equals(name)||"propane".equals(name);
	}

	/**
	 * Consumes whatever has just arrived. Called on delivery rather than on a timer, so an idle
	 * flare does no work at all.
	 */
	private void burn()
	{
		if(world==null||world.isRemote)
			return;
		//City mode keeps the spectacle and drops the accounting: the gas is not consumed and the
		//flame does not expire, so a flare in a city build burns forever off one delivery. Being
		//a skyline is the entire job there.
		FluidStack drained = CityMode.petroleum()
				?tank.drainInternal(1, true): tank.drainInternal(BURN_RATE, true);
		if(drained==null||drained.amount <= 0)
			return;
		lifetimeBurned += drained.amount;
		boolean wasLit = isLit();
		flameTicks = FLAME_TICKS;
		markDirty();
		if(!wasLit)
			markContainingBlockForUpdate(null);
		//Ask the block to wake us when the flame should go out. This is the whole reason the
		//flare needs no ticking: it is only ever interesting on delivery and on expiry, and a
		//scheduled update covers the second without putting it in a tick list.
		if(!CityMode.petroleum())
			world.scheduleUpdate(pos, getBlockType(), FLAME_TICKS);
	}

	public boolean isLit()
	{
		return flameTicks > 0;
	}

	public long getLifetimeBurned()
	{
		return lifetimeBurned;
	}

	/**
	 * Puts the flame out. Called by the block when the update the last delivery scheduled comes
	 * due; a fresh delivery in the meantime schedules a later one, so a steady feed never gaps.
	 */
	public void ageFlame()
	{
		if(flameTicks <= 0||CityMode.petroleum())
			return;
		flameTicks = 0;
		markDirty();
		markContainingBlockForUpdate(null);
	}

	@Override
	public int getLightValue()
	{
		return isLit()?14: 0;
	}

	//	=================================
	//		CAPABILITY
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
		if(capability==CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(new FlareIntake());
		return super.getCapability(capability, facing);
	}

	/**
	 * The face a pipe sees: fill-only, and burning the moment anything lands in it.
	 */
	private class FlareIntake implements IFluidHandler
	{
		@Override
		public IFluidTankProperties[] getTankProperties()
		{
			return tank.getTankProperties();
		}

		@Override
		public int fill(FluidStack resource, boolean doFill)
		{
			int accepted = tank.fill(resource, doFill);
			if(doFill&&accepted > 0)
				burn();
			return accepted;
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

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		return new String[]{
				isLit()?TextFormatting.GOLD+"Flaring"+TextFormatting.RESET
						: TextFormatting.GRAY+"Cold"+TextFormatting.RESET,
				"Burned off: "+lifetimeBurned+" mB"
		};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	/**
	 * Emits while burning, so the waste stream can drive a lamp or an alarm -- the cheapest way
	 * to notice that money is going up the stack.
	 */
	@Override
	public int getComparatorInputOverride()
	{
		return isLit()?15: 0;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		nbt.setInteger("flameTicks", flameTicks);
		if(!descPacket)
			nbt.setLong("burned", lifetimeBurned);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		flameTicks = nbt.getInteger("flameTicks");
		if(!descPacket)
			lifetimeBurned = nbt.getLong("burned");
	}
}
