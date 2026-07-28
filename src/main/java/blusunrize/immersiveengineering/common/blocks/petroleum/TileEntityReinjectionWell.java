/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.petroleum.PetroleumConfig;
import blusunrize.immersiveengineering.api.petroleum.Reservoir;
import blusunrize.immersiveengineering.api.petroleum.ReservoirHandler;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.petroleum.PetroleumSaveData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The Re-injection Well: the thinking player's alternative to the flare.
 * <p>
 * A field's pressure is the thing the whole extraction chain turns on, and until now the only
 * direction it moved was down. This puts water or gas back into the ground and gets a second
 * tranche out of a deposit that was on its way to being a slow seep -- which turns "I have sour
 * gas and nowhere to put it" from a disposal problem into a genuine engineering choice. Flare it,
 * scrub it, or send it back downhole.
 * <p>
 * <strong>It is bounded, and the bound is per deposit and permanent.</strong> Each field will
 * accept enhanced recovery worth {@link PetroleumConfig#reinjectionCapFraction} of its original
 * capacity across its entire life. Without that a player could cycle water in and crude out
 * forever, because restoring on its own only stops at the capacity and says nothing about how many
 * times you may refill to it.
 * <p>
 * Injectants are not interchangeable. Water is cheap and gets a poor return; natural gas is
 * valuable and gets a good one. That is the trade, and it is what makes the gas branch worth
 * having a third answer to.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityReinjectionWell extends TileEntityIEBase implements ITickable,
		IIEInternalFluxHandler, IBlockOverlayText, IComparatorOverride
{
	public static final int TANK_CAPACITY = 8000;
	public static final int ENERGY_CAPACITY = 32000;
	/**
	 * Flux per tick while injecting. Between a derrick and a pumpjack: this is heavy pumping
	 * against formation pressure, and it should read as expensive.
	 */
	public static final int ENERGY_PER_TICK = 384;
	/**
	 * Ticks between passes. The same twenty-tick rhythm the rest of the oilfield runs on, so a
	 * field of wells and injectors all breathe together rather than at cross purposes.
	 */
	public static final int INTERVAL = 20;
	/**
	 * Millibuckets of injectant per pass.
	 */
	public static final int INJECT_PER_PASS = 40;
	private static final int CITY_SIP = 1;

	/**
	 * What a millibucket of each injectant puts back, in thousandths.
	 * <p>
	 * Both are well under one on purpose: enhanced recovery is a way to reach oil that was already
	 * there, not a way to make it. Keyed by registry name so the table can be read -- and
	 * checked -- without a fluid registry.
	 */
	private static final Map<String, Integer> INJECTANTS = new HashMap<String, Integer>();

	static
	{
		//Water floods the formation and pushes oil towards the bore. Cheap, and correspondingly
		//poor: a third of what you put in comes back as recoverable crude.
		INJECTANTS.put("water", 350);
		//Gas re-pressurises it, which works considerably better -- and is the reason to build this
		//instead of a flare stack.
		INJECTANTS.put("natural_gas", 700);
		//Sour gas straight off the wellhead works too, at a discount for not being cleaned up. This
		//is the one that matters: it means a field can drive its own enhanced recovery with the
		//waste stream it was already producing.
		INJECTANTS.put("ie_sour_gas", 550);
	}

	/**
	 * @return thousandths of a millibucket recovered per millibucket injected, or 0 if this fluid
	 * is not an injectant
	 */
	public static int recoveryPerMille(@Nullable String fluidName)
	{
		if(fluidName==null)
			return 0;
		Integer value = INJECTANTS.get(fluidName);
		return value==null?0: value;
	}

	public static boolean isInjectant(@Nullable FluidStack stack)
	{
		return stack!=null&&stack.getFluid()!=null&&recoveryPerMille(stack.getFluid().getName()) > 0;
	}

	/**
	 * @return how much a volume of a given injectant puts back into the ground
	 */
	public static int recoveredFrom(int volume, int perMille)
	{
		if(volume <= 0||perMille <= 0)
			return 0;
		return (int)((long)volume*perMille/1000L);
	}

	public final FluidTank tank = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			//Guarded, or a mis-plumbed line fills the injector with diesel and pumps a barrel of
			//it into the ground, where it is gone for good.
			return isInjectant(fluid);
		}
	};

	private final FluxStorage energyStorage = new FluxStorage(ENERGY_CAPACITY, 4*ENERGY_PER_TICK, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Synced. Drives the overlay, and the overlay is the only thing that tells a player whether
	 * this field has any allowance left -- which is otherwise invisible and permanent.
	 */
	public int status;
	public static final int STATUS_IDLE = 0;
	public static final int STATUS_INJECTING = 1;
	public static final int STATUS_NO_POWER = 2;
	public static final int STATUS_SPENT = 3;
	public static final int STATUS_DRY_HOLE = 4;

	private int stagger = -1;

	@Override
	public void update()
	{
		if(world==null||world.isRemote)
			return;
		if((world.getTotalWorldTime()+getStagger())%INTERVAL!=0)
			return;

		int previous = status;
		status = STATUS_IDLE;
		Reservoir reservoir = getReservoir();
		if(reservoir==null||reservoir.getOriginalCapacity() <= 0)
			status = STATUS_DRY_HOLE;
		else
			inject(reservoir);
		if(status!=previous)
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	private void inject(Reservoir reservoir)
	{
		FluidStack held = tank.getFluid();
		if(held==null||held.getFluid()==null||held.amount <= 0)
			return;
		int perMille = recoveryPerMille(held.getFluid().getName());
		if(perMille <= 0)
			return;

		int allowance = reservoir.getRestoreAllowance(PetroleumConfig.reinjectionCapFraction);
		if(allowance <= 0)
		{
			status = STATUS_SPENT;
			return;
		}

		int cost = CityMode.petroleum()?CITY_SIP: ENERGY_PER_TICK*INTERVAL;
		if(energyStorage.getEnergyStored() < cost)
		{
			status = STATUS_NO_POWER;
			return;
		}

		int volume = Math.min(INJECT_PER_PASS, held.amount);
		//Trimmed so a pass never injects more than the allowance can absorb: the fluid would be
		//consumed and nothing would come of it, which is the sort of quiet loss that is very
		//annoying to notice.
		int recoverable = recoveredFrom(volume, perMille);
		if(recoverable > allowance)
		{
			volume = (int)Math.max(1, (long)allowance*1000L/perMille);
			recoverable = recoveredFrom(volume, perMille);
		}
		if(recoverable <= 0)
		{
			status = STATUS_SPENT;
			return;
		}

		//City mode keeps the machine, the plumbing and the power connection, and stops metering the
		//ground: the deposit never depletes there anyway, so restoring it would be arithmetic
		//nobody could observe.
		if(!CityMode.petroleum())
		{
			tank.drainInternal(volume, true);
			if(reservoir.restore(recoverable) > 0)
				PetroleumSaveData.setDirty();
		}
		else
			tank.drainInternal(Math.min(CITY_SIP, volume), true);
		energyStorage.modifyEnergyStored(-cost);
		status = STATUS_INJECTING;
	}

	@Nullable
	public Reservoir getReservoir()
	{
		if(world==null)
			return null;
		return ReservoirHandler.getReservoir(world.getSeed(), world.provider.getDimension(),
				getPos().getX() >> 4, getPos().getZ() >> 4);
	}

	private int getStagger()
	{
		if(stagger < 0)
			stagger = ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), INTERVAL);
		return stagger;
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		Reservoir reservoir = getReservoir();
		String allowance = TextFormatting.GRAY+"No deposit here"+TextFormatting.RESET;
		if(reservoir!=null&&reservoir.getOriginalCapacity() > 0)
		{
			int left = reservoir.getRestoreAllowance(PetroleumConfig.reinjectionCapFraction);
			allowance = left > 0
					?"Recovery allowance: "+left+" mB"
					: TextFormatting.GOLD+"Allowance used up"+TextFormatting.RESET;
		}
		FluidStack held = tank.getFluid();
		return new String[]{
				statusLine(status),
				held==null||held.amount <= 0?TextFormatting.GRAY+"No injectant"+TextFormatting.RESET
						: held.getLocalizedName()+"  "+held.amount+" / "+TANK_CAPACITY+" mB",
				allowance};
	}

	private static String statusLine(int status)
	{
		switch(status)
		{
			case STATUS_INJECTING:
				return TextFormatting.GREEN+"Injecting"+TextFormatting.RESET;
			case STATUS_NO_POWER:
				return TextFormatting.RED+"No power"+TextFormatting.RESET;
			case STATUS_SPENT:
				return TextFormatting.GOLD+"This field will take no more"+TextFormatting.RESET;
			case STATUS_DRY_HOLE:
				return TextFormatting.RED+"Dry hole -- nothing to recover"+TextFormatting.RESET;
			default:
				return TextFormatting.YELLOW+"Idle"+TextFormatting.RESET;
		}
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		//How much allowance is left, which is the one thing about this machine worth automating
		//against: a field that will take no more should switch its injector off.
		Reservoir reservoir = getReservoir();
		if(reservoir==null||reservoir.getOriginalCapacity() <= 0)
			return 0;
		double cap = PetroleumConfig.reinjectionCapFraction;
		int total = (int)(reservoir.getOriginalCapacity()*Math.min(1.0, cap));
		if(total <= 0)
			return 0;
		int left = reservoir.getRestoreAllowance(cap);
		return left <= 0?0: Math.max(1, Math.min(15, 15*left/total));
	}

	//	=================================
	//		PLUMBING
	//	=================================

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		return energyStorage;
	}

	@Nonnull
	@Override
	public SideConfig getEnergySideConfig(@Nullable EnumFacing facing)
	{
		return SideConfig.INPUT;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		return facing==null?null: wrappers[facing.ordinal()];
	}

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
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setInteger("status", status);
		if(!descPacket)
		{
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
			energyStorage.writeToNBT(nbt);
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		status = nbt.getInteger("status");
		if(!descPacket)
		{
			tank.readFromNBT(nbt.getCompoundTag("tank"));
			energyStorage.readFromNBT(nbt);
		}
	}
}
