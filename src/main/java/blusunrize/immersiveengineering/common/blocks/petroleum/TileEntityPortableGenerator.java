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
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.TileEntityIEBase;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
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
import java.util.HashSet;
import java.util.Set;

/**
 * The Portable Generator: one block, a pull cord, and a reason for gasoline to exist.
 * <p>
 * <strong>Gasoline had no generator at all.</strong> It runs handheld tools and is explicitly
 * refused by the Diesel Generator, which is correct -- a compression engine cannot burn it -- but
 * left the cut with no consumer that scales. This is that consumer, and it is deliberately the
 * <em>small</em> one: a thing you carry to a build site, run your lights off, and walk back to the
 * pump to refill. The gas station's first honest customer.
 * <p>
 * The engine-type split is the whole design. This burns the spark-ignition fuels -- gasoline and
 * ethanol -- and refuses diesel, exactly as the Diesel Generator refuses gasoline. Fuels get
 * personalities rather than being interchangeable flux juice.
 * <p>
 * It ticks only while it is actually running, and pushes flux to its neighbours the same way a
 * wire connector does. A generator that has run out of fuel costs one comparison a tick.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPortableGenerator extends TileEntityIEBase implements ITickable,
		IIEInternalFluxHandler, IBlockOverlayText, IComparatorOverride, INeighbourChangeTile
{
	/**
	 * Four buckets. About five minutes of running -- long enough that a trip to the forecourt is an
	 * errand rather than a chore, short enough that it stays a portable generator rather than a
	 * base's power supply.
	 */
	public static final int TANK_CAPACITY = 4000;
	/**
	 * Flux a tick at full chat. A twentieth of a Gas Turbine and a sixteenth of a Diesel Generator:
	 * this powers a work light and a small machine, and is never the answer to a factory.
	 */
	public static final int OUTPUT = 256;
	/**
	 * Millibuckets burnt per burn, and how many ticks apart the burns are.
	 * <p>
	 * <strong>Not a per-tick figure.</strong> It was one, at 5 mB/t, which emptied the tank in
	 * forty seconds and was worth 51,200 flux per bucket -- a fourteenth of what a Diesel Generator
	 * gets out of diesel, when the intent was about half. A unit test asserting the runtime this
	 * class documents is what turned that up; the numbers had simply never been multiplied out.
	 * <p>
	 * Two millibuckets every three ticks is 1500 ticks per bucket: five minutes on a full tank, and
	 * 384,000 flux per bucket against the Diesel Generator's ~717,000. A little over half the value
	 * per millibucket, which is the price of portability -- and still the best thing that has ever
	 * happened to a bucket of gasoline, which previously bought nothing at all.
	 * <p>
	 * Burning on an interval rather than every tick also means the fluid accounting runs a third as
	 * often, while the flux output stays per-tick and smooth.
	 */
	public static final int FUEL_PER_BURN = 2;
	public static final int BURN_INTERVAL = 3;

	/**
	 * @return how many ticks one bucket of fuel lasts
	 */
	public static int ticksPerBucket()
	{
		return 1000/FUEL_PER_BURN*BURN_INTERVAL;
	}

	/**
	 * @return flux produced from one bucket of fuel
	 */
	public static int fluxPerBucket()
	{
		return OUTPUT*ticksPerBucket();
	}

	/**
	 * Registry names of the fuels this will burn. Spark ignition only.
	 * <p>
	 * Strings rather than {@code Fluid}s for the same reason every other table in this expansion
	 * uses them: the list can then be read, and checked, without a fluid registry.
	 */
	private static final Set<String> FUELS = new HashSet<>();

	static
	{
		FUELS.add("ie_gasoline");
		FUELS.add("ethanol");
	}

	/**
	 * @return whether this generator will burn the named fluid
	 */
	public static boolean isFuel(@Nullable String fluidName)
	{
		return fluidName!=null&&FUELS.contains(fluidName);
	}

	public static boolean isFuel(@Nullable FluidStack stack)
	{
		return stack!=null&&stack.getFluid()!=null&&isFuel(stack.getFluid().getName());
	}

	public final FluidTank tank = new FluidTank(TANK_CAPACITY)
	{
		@Override
		public boolean canFillFluidType(FluidStack fluid)
		{
			//Guarded, or a nozzle pointed at the wrong pump quietly fills the generator with
			//diesel it can never burn and the player has no way to get it out again.
			return isFuel(fluid);
		}
	};

	private final FluxStorage energyStorage = new FluxStorage(OUTPUT*4, 0, OUTPUT);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Synced, because it drives the exhaust puff and the overlay. Nothing else about this block is
	 * worth a packet.
	 */
	public boolean running;
	private boolean[] receiverFaces = new boolean[6];
	private boolean facesDirty = true;
	/**
	 * Ticks of running still paid for by the last burn. Persisted, so reloading a chunk does not
	 * hand the player a free interval -- or charge them twice for one.
	 */
	private int burnTimer;

	@Override
	public void update()
	{
		if(world==null||world.isRemote)
		{
			if(running&&world!=null&&world.getTotalWorldTime()%3==0)
				world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
						getPos().getX()+.7, getPos().getY()+.9, getPos().getZ()+.5, 0, .02, 0);
			return;
		}

		boolean wasRunning = running;
		running = false;
		if(tank.getFluidAmount() > 0&&isFuel(tank.getFluid()))
		{
			//A generator with nowhere to put its output does not burn fuel. That is the same rule
			//the Gas Turbine follows, and it is what stops a forgotten generator emptying itself
			//against an open breaker.
			if(pushOutput() > 0||energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored())
			{
				//Fuel is paid for an interval at a time; output is produced every tick. The debt
				//counter is what keeps those two rhythms apart.
				if(burnTimer <= 0)
				{
					int burn = CityMode.petroleum()?1: FUEL_PER_BURN;
					if(tank.drainInternal(burn, true)!=null)
						burnTimer = BURN_INTERVAL;
				}
				if(burnTimer > 0)
				{
					burnTimer--;
					energyStorage.modifyEnergyStored(OUTPUT);
					running = true;
				}
			}
		}
		if(energyStorage.getEnergyStored() > 0)
			pushOutput();
		if(running!=wasRunning)
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	/**
	 * Pushes what is banked into whatever is adjacent, the way a wire connector does.
	 *
	 * @return flux actually delivered
	 */
	private int pushOutput()
	{
		if(energyStorage.getEnergyStored() <= 0)
			return 0;
		if(facesDirty)
			rescanFaces();
		int delivered = 0;
		for(EnumFacing side : EnumFacing.VALUES)
		{
			if(!receiverFaces[side.ordinal()])
				continue;
			int budget = Math.min(OUTPUT-delivered, energyStorage.getEnergyStored());
			if(budget <= 0)
				break;
			TileEntity target = Utils.getExistingTileEntity(world, getPos().offset(side));
			if(target==null)
				continue;
			int accepted = Math.max(0, EnergyHelper.insertFlux(target, side.getOpposite(), budget, false));
			if(accepted > 0)
			{
				energyStorage.modifyEnergyStored(-accepted);
				delivered += accepted;
			}
		}
		return delivered;
	}

	private void rescanFaces()
	{
		for(EnumFacing side : EnumFacing.VALUES)
		{
			TileEntity target = Utils.getExistingTileEntity(world, getPos().offset(side));
			receiverFaces[side.ordinal()] = target!=null
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

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public String[] getOverlayText(EntityPlayer player, RayTraceResult mop, boolean hammer)
	{
		FluidStack held = tank.getFluid();
		if(held==null||held.amount <= 0)
			return new String[]{TextFormatting.YELLOW+"Out of fuel"+TextFormatting.RESET,
					TextFormatting.GRAY+"Takes gasoline or ethanol"+TextFormatting.RESET};
		return new String[]{
				running?TextFormatting.GREEN+"Running  "+OUTPUT+" Flux/t"+TextFormatting.RESET
						: TextFormatting.GRAY+"Idle"+TextFormatting.RESET,
				held.getLocalizedName()+"  "+held.amount+" / "+TANK_CAPACITY+" mB"};
	}

	@Override
	public boolean useNixieFont(EntityPlayer player, RayTraceResult mop)
	{
		return false;
	}

	@Override
	public int getComparatorInputOverride()
	{
		if(tank.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, Math.min(15, 15*tank.getFluidAmount()/TANK_CAPACITY));
	}

	//	=================================
	//		FLUX
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
		return SideConfig.OUTPUT;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		return facing==null?null: wrappers[facing.ordinal()];
	}

	//	=================================
	//		FLUID
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
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		nbt.setBoolean("running", running);
		if(!descPacket)
		{
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
			nbt.setInteger("burnTimer", burnTimer);
			energyStorage.writeToNBT(nbt);
		}
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		running = nbt.getBoolean("running");
		if(!descPacket)
		{
			tank.readFromNBT(nbt.getCompoundTag("tank"));
			burnTimer = nbt.getInteger("burnTimer");
			energyStorage.readFromNBT(nbt);
		}
	}

	/**
	 * Kept so a future stagger, if this ever needs one, uses the shared hash rather than a fresh
	 * linear one -- the mistake this codebase has already made once.
	 */
	@SuppressWarnings("unused")
	private int stagger(int interval)
	{
		return ApiUtils.positionStagger(getPos().getX(), getPos().getZ(), interval);
	}
}
