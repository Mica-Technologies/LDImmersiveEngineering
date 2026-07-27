/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IProcessTile;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.blocks.metal.TileEntityMultiblockMetal;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Bolts onto a working machine and keeps it greased.
 * <p>
 * This is what lubricant is for. Every other cut off the column burns; lubricant is the one
 * that does not, and without somewhere to put it the heaviest useful fraction of a barrel is a
 * fluid with no job at all.
 * <p>
 * The benefit is expressed as an <strong>energy rebate</strong> rather than as a speed
 * multiplier, and that is a deliberate implementation choice as much as a design one: a speed
 * multiplier would mean reaching into the shared multiblock process queue that every machine in
 * the mod runs on, whereas a rebate is paid by topping the host's own buffer back up through
 * the public accessor it already exposes. Nothing shared has to change, and the effect a player
 * sees -- this machine costs less to run -- is the same either way.
 * <p>
 * Ticks, but does almost nothing: a modulo and an early return except on its interval, and
 * nothing at all when it is dry or not attached to anything.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityLubricationManifold extends TileEntityIEBase implements ITickable,
		IBlockOverlayText, IComparatorOverride, INeighbourChangeTile
{
	public static final int CAPACITY = 4000;
	/**
	 * Ticks between top-ups. Long enough to be free, short enough that a machine does not run
	 * unlubricated for a visible stretch after being switched on.
	 */
	public static final int INTERVAL = 20;
	/**
	 * Millibuckets consumed per interval while the host is actually working. A bucket lasts
	 * about eight minutes of continuous running, so keeping a machine greased is an occasional
	 * errand rather than a second supply chain.
	 */
	public static final int LUBRICANT_PER_INTERVAL = 2;
	/**
	 * Fraction of the host's energy cost the rebate covers.
	 */
	public static final float REBATE = 0.15F;

	private final FluidTank tank = new FluidTank(CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			return fluid!=null&&fluid.getFluid()!=null
					&&"ie_lubricant".equals(fluid.getFluid().getName());
		}
	};

	/**
	 * The machine being greased, resolved on neighbour change rather than searched for every
	 * interval.
	 */
	private TileEntityMultiblockMetal<?, ?> host;
	private boolean hostDirty = true;
	private boolean active;

	@Override
	public void onLoad()
	{
		super.onLoad();
		hostDirty = true;
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		hostDirty = true;
	}

	@Override
	public void update()
	{
		if(world==null||world.isRemote)
			return;
		if((world.getTotalWorldTime()+getStagger())%INTERVAL!=0)
			return;
		if(hostDirty)
			resolveHost();

		boolean wasActive = active;
		active = false;
		if(host!=null&&!host.isInvalid()&&isWorking(host))
		{
			int cost = LUBRICANT_PER_INTERVAL;
			//City mode keeps the errand -- a manifold still runs dry and still has to be filled
			//-- but stops metering the machine it serves, exactly as the generators do.
			if(CityMode.petroleum())
				cost = 1;
			FluidStack drawn = tank.drainInternal(cost, true);
			if(drawn!=null&&drawn.amount > 0)
			{
				active = true;
				rebate();
			}
		}
		if(active!=wasActive)
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	/**
	 * Tops the host's buffer back up by a fraction of what an interval of work costs it.
	 */
	private void rebate()
	{
		FluxStorage storage = host.getFluxStorage();
		if(storage==null)
			return;
		int perInterval = Math.round(host.getMaxEnergyStored(null)*REBATE/8F);
		if(perInterval > 0)
			storage.modifyEnergyStored(perInterval);
	}

	/**
	 * @return true if the host has something on its process queue. Greasing an idle machine
	 * would burn lubricant for nothing, which is the sort of quiet drain that is very annoying
	 * to diagnose.
	 */
	private static boolean isWorking(TileEntity machine)
	{
		if(!(machine instanceof IProcessTile))
			return false;
		int[] steps = ((IProcessTile)machine).getCurrentProcessesStep();
		return steps!=null&&steps.length > 0;
	}

	private void resolveHost()
	{
		host = null;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			TileEntity te = Utils.getExistingTileEntity(world, pos.offset(side));
			if(te instanceof TileEntityMultiblockMetal)
			{
				host = (TileEntityMultiblockMetal<?, ?>)te;
				break;
			}
		}
		hostDirty = false;
	}

	private int getStagger()
	{
		return Math.floorMod(pos.getX()^pos.getZ()*31, INTERVAL);
	}

	public boolean isActive()
	{
		return active;
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
			return CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY.cast(tank);
		return super.getCapability(capability, facing);
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		if(host==null)
			return new String[]{TextFormatting.YELLOW+"Not attached to a machine"+TextFormatting.RESET};
		return new String[]{
				active?TextFormatting.GREEN+"Lubricating"+TextFormatting.RESET
						: TextFormatting.GRAY+"Idle"+TextFormatting.RESET,
				tank.getFluidAmount()+" / "+CAPACITY+" mB"
		};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		return Math.min(15, (int)Math.ceil(15.0*tank.getFluidAmount()/CAPACITY));
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
		nbt.setBoolean("active", active);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		tank.readFromNBT(nbt.getCompoundTag("tank"));
		active = nbt.getBoolean("active");
	}
}
