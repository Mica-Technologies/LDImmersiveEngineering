/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxProvider;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Grid Feed Unit: the point where power leaves the world and enters a segment.
 * <p>
 * It behaves like any ordinary flux machine to its neighbours -- an IE connector, a cable
 * from another mod, or a generator can all push into it -- and additionally taps the block
 * it is bolted to if that block permits extraction, so bolting one onto a capacitor bank
 * just works.
 * <p>
 * In city mode the internal buffer is shrunk to a single {@link GridConfig#sipAmount}. That
 * is what turns the running cost into "one flux every five seconds": the source physically
 * cannot push more than one sip in, and the engine drains that sip only on the liveness
 * check.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridFeed extends TileEntityGridDevice implements IIEInternalFluxHandler
{
	private final FluxStorage energyStorage = new FluxStorage(GridConfig.defaultDeviceCap);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	@Override
	public GridDeviceType getDeviceType()
	{
		return GridDeviceType.FEED;
	}

	@Override
	protected void applyLimits()
	{
		int cap = device!=null?device.getTransferCap(): backupCap;
		//City mode: hold exactly one sip so a live source keeps it topped up and a dead one
		//leaves it empty within a single interval. Anything larger would let a dead source
		//look alive for hours while the engine drained a big stale buffer.
		int size = CityMode.grid()?Math.max(1, GridConfig.sipAmount): Math.max(0, cap);
		energyStorage.setCapacity(size);
		energyStorage.setLimitReceive(size);
		energyStorage.setMaxExtract(size);
	}

	//	=================================
	//		WIRE ENDPOINT
	//	=================================
	// A wire may be strung straight at the terminal post on the front of this box, with no connector
	// in between. From the catenary graph's side that makes this an "energy output": a place the
	// network may put energy, exactly as a connector bolted to a machine is.
	//
	// Nothing about how much arrives changes. The wire's own transfer rate caps the run, the
	// connector at the far end caps what it may send, line loss is charged by the same code that
	// charges it for every other wire, and this box's own intake buffer -- sized from the device's
	// transfer cap in applyLimits -- is the last word on what fits.

	@Override
	protected boolean canTakeLV()
	{
		return true;
	}

	@Override
	protected boolean canTakeMV()
	{
		return true;
	}

	@Override
	protected boolean canTakeHV()
	{
		return true;
	}

	@Override
	public boolean isEnergyOutput()
	{
		return true;
	}

	@Override
	public int outputEnergy(int amount, boolean simulate, int energyType)
	{
		if(amount <= 0||world==null||world.isRemote)
			return 0;
		//receiveEnergy on the storage rather than the IFluxReceiver path: this is the same buffer a
		//cable or a connector on any face fills, so a wire and a connector cannot between them put in
		//more than the box can hold in a tick.
		return energyStorage.receiveEnergy(amount, simulate);
	}

	//	=================================
	//		IGridEndpoint
	//	=================================

	@Override
	public int extractForGrid(int max, boolean simulate)
	{
		if(max <= 0)
			return 0;
		int taken = energyStorage.extractEnergy(max, simulate);
		int left = max-taken;
		if(left > 0)
			taken += tapMountedBlock(left, simulate);
		return taken;
	}

	/**
	 * Draws directly from the block this box is bolted to, if it allows extraction.
	 * <p>
	 * Only the mount face: "the box taps the thing it is attached to" is a rule a player
	 * can see from the outside, which a six-way scan would not be.
	 */
	private int tapMountedBlock(int amount, boolean simulate)
	{
		if(world==null)
			return 0;
		TileEntity target = Utils.getExistingTileEntity(world, pos.offset(facing));
		if(target==null||target instanceof TileEntityGridDevice)
			return 0;
		EnumFacing from = facing.getOpposite();
		if(target.hasCapability(CapabilityEnergy.ENERGY, from))
		{
			IEnergyStorage cap = target.getCapability(CapabilityEnergy.ENERGY, from);
			if(cap!=null&&cap.canExtract())
				return Math.max(0, cap.extractEnergy(amount, simulate));
		}
		if(target instanceof IFluxProvider&&((IFluxProvider)target).canConnectEnergy(from))
			return Math.max(0, ((IFluxProvider)target).extractEnergy(from, amount, simulate));
		return 0;
	}

	//	=================================
	//		FLUX PLUMBING
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
		//Accept from every side: the box does not care which face a cable arrives on.
		return SideConfig.INPUT;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		if(facing==null)
			return null;
		return wrappers[facing.ordinal()];
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		if(!descPacket)
			energyStorage.writeToNBT(nbt);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		if(!descPacket)
		{
			applyLimits();
			energyStorage.readFromNBT(nbt);
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
		if(energyStorage.getEnergyStored() > 0||tapMountedBlock(1, true) > 0)
			return null;
		return "No power is arriving. String a wire straight to this box, put a cable or "
				+"connector against it, or bolt it onto a capacitor or generator.";
	}

	/**
	 * @return how full the intake buffer is, for the in-world readout
	 */
	public int getBufferedEnergy()
	{
		return energyStorage.getEnergyStored();
	}
}
