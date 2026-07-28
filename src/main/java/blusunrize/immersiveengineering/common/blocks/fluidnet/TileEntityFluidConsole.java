/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.api.fluid.network.FluidDeviceType;
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.FluidNetConfig;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IGuiTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IEForgeEnergyWrapper;
import blusunrize.immersiveengineering.common.util.EnergyHelper.IIEInternalFluxHandler;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Fluid Control Console: a 2 wide x 1 deep x 2 tall control panel, and the only place the
 * whole virtual fluid network can be configured without commands.
 * <p>
 * Its own power draw is a token standby load -- it runs on flux even though it manages fluid,
 * because it is a screen and a switch bank, not a pump. Losing power darkens the screen and makes
 * the GUI read-only rather than refusing to open: being locked out of your gas network because the
 * lights are off would be a trap, not a challenge.
 * <p>
 * The deliberate mirror of {@code TileEntityGridConsole}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class TileEntityFluidConsole extends TileEntityMultiblockPart<TileEntityFluidConsole>
		implements IGuiTile, IComparatorOverride, IPlayerInteraction, IIEInternalFluxHandler
{
	private final FluxStorage energyStorage = new FluxStorage(8000, 1024, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Whether the screen is lit. Synced to the client for the model, and read by the GUI to decide
	 * whether to show its "no power" overlay.
	 */
	public boolean powered;
	private int standbyTimer;

	public TileEntityFluidConsole()
	{
		super(FluidConsoleGeometry.SIZE);
	}

	@Override
	public void update()
	{
		if(world.isRemote||isDummy())
			return;
		//The console is not part of the fluid engine; it just needs to keep its own lights on.
		//Checked every half second rather than every tick.
		if(++standbyTimer < 10)
			return;
		standbyTimer = 0;

		boolean wasPowered = powered;
		if(!FluidNetConfig.consoleRequiresPower)
			powered = true;
		else if(CityMode.petroleum())
		{
			//City-mode parity with the Inlets: presence, not consumption.
			powered = energyStorage.getEnergyStored() > 0;
			if(powered)
				spend(1);
		}
		else
		{
			int cost = FluidNetConfig.consoleStandbyDraw*10;
			powered = energyStorage.getEnergyStored() >= cost;
			if(powered)
				spend(cost);
		}
		if(powered!=wasPowered)
		{
			markDirty();
			markContainingBlockForUpdate(null);
		}
	}

	/**
	 * Burns the console's own standby draw.
	 * <p>
	 * Deliberately not {@code extractEnergy}: this storage is built with an extract limit of zero
	 * so nothing plugged into the console can siphon its buffer back out, and that limit applies to
	 * every caller -- including this one. The grid's console shipped that bug and reported "NO
	 * POWER" forever no matter how much energy it was fed.
	 */
	private void spend(int amount)
	{
		if(amount > 0)
			energyStorage.modifyEnergyStored(-amount);
	}

	//	=================================
	//		NETWORK REGISTRATION
	//	=================================

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote&&formed&&!isDummy())
			//Registered purely so a main can report how many consoles manage it; the console
			//carries no fluid and the engine skips CONSOLE devices.
			VirtualFluidNet.INSTANCE.registerDevice(new DimensionBlockPos(getPos(), world),
					FluidDeviceType.CONSOLE);
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		if(world!=null&&!world.isRemote&&!isDummy())
			VirtualFluidNet.INSTANCE.unregisterDevice(new DimensionBlockPos(getPos(), world));
	}

	//	=================================
	//		GUI
	//	=================================

	@Override
	public boolean canOpenGui()
	{
		return formed;
	}

	@Override
	public int getGuiID()
	{
		return Lib.GUIID_FluidConsole;
	}

	@Nullable
	@Override
	public TileEntity getGuiMaster()
	{
		return master();
	}

	@Override
	public boolean interact(EnumFacing side, EntityPlayer player, EnumHand hand, ItemStack heldItem,
							float hitX, float hitY, float hitZ)
	{
		if(!formed||Utils.isHammer(heldItem))
			return false;
		TileEntityFluidConsole master = master();
		if(master==null)
			return false;
		if(!world.isRemote)
			player.openGui(blusunrize.immersiveengineering.ImmersiveEngineering.instance,
					Lib.GUIID_FluidConsole, world, master.getPos().getX(), master.getPos().getY(),
					master.getPos().getZ());
		return true;
	}

	/**
	 * @return whether this console's screen is lit (asked of any block of it)
	 */
	public boolean isPowered()
	{
		TileEntityFluidConsole master = master();
		return master!=null&&master.powered;
	}

	//	=================================
	//		REDSTONE
	//	=================================

	@Override
	public int getComparatorInputOverride()
	{
		int total = VirtualFluidNet.INSTANCE.getMainCount();
		if(total <= 0)
			return 0;
		int live = 0;
		for(FluidMain main : VirtualFluidNet.INSTANCE.getMains())
			if(CityMode.petroleum()?main.isPressurised(): main.isOperational())
				live++;
		return Math.min(15, (int)Math.floor(15.0*live/total));
	}

	//	=================================
	//		FLUX
	//	=================================

	@Nonnull
	@Override
	public FluxStorage getFluxStorage()
	{
		TileEntityFluidConsole master = master();
		return master!=null?master.energyStorage: energyStorage;
	}

	@Nonnull
	@Override
	public SideConfig getEnergySideConfig(@Nullable EnumFacing facing)
	{
		return formed?SideConfig.INPUT: SideConfig.NONE;
	}

	@Override
	public IEForgeEnergyWrapper getCapabilityWrapper(EnumFacing facing)
	{
		if(facing==null||!formed)
			return null;
		return wrappers[facing.ordinal()];
	}

	//	=================================
	//		MULTIBLOCK PLUMBING
	//	=================================

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		//A console holds nothing. It would be a natural place to expect a tank, which is exactly
		//why it must not have one -- the whole point of the network is that fluid does not travel
		//through the thing that manages it.
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return false;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		return false;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		return new ItemStack(IEContent.blockFluidNetDevice, 1,
				BlockTypes_FluidNetDevice.CONSOLE_HOUSING.getMeta());
	}

	@Override
	public float[] getBlockBounds()
	{
		return null;
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		//The screen graphic spans the whole 2x2 face, so the master must not be culled when only
		//its upper half is on screen.
		return new AxisAlignedBB(getPos().add(-1, 0, -1), getPos().add(2, 2, 2));
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		powered = nbt.getBoolean("powered");
		if(!descPacket)
			energyStorage.readFromNBT(nbt);
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setBoolean("powered", powered);
		if(!descPacket)
			energyStorage.writeToNBT(nbt);
	}

	/**
	 * @return the master's block position, used to key the container on both sides
	 */
	public BlockPos getMasterPos()
	{
		TileEntityFluidConsole master = master();
		return master!=null?master.getPos(): getPos();
	}
}
