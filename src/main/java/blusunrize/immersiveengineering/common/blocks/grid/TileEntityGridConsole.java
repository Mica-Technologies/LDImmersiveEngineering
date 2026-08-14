/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.IEEnums.SideConfig;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.api.energy.immersiveflux.FluxStorage;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IGuiTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IPlayerInteraction;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockGridConsole;
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
 * The Grid Management Console: a 2 wide x 1 deep x 2 tall control panel, and the only
 * place the whole virtual grid can be configured.
 * <p>
 * Its own power draw is a token standby load. Losing power darkens the screen and makes
 * the GUI read-only rather than refusing to open -- being locked out of your grid because
 * your grid is down would be a trap, not a challenge.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridConsole extends TileEntityMultiblockPart<TileEntityGridConsole>
		implements IGuiTile, IComparatorOverride, IPlayerInteraction, IIEInternalFluxHandler
{
	private final FluxStorage energyStorage = new FluxStorage(8000, 1024, 0);
	private IEForgeEnergyWrapper[] wrappers = IEForgeEnergyWrapper.getDefaultWrapperArray(this);

	/**
	 * Whether the screen is lit. Synced to the client for the model, and read by the GUI
	 * to decide whether to show its "no power" overlay.
	 */
	public boolean powered;
	private int standbyTimer;

	public TileEntityGridConsole()
	{
		super(GridConsoleGeometry.SIZE);
	}

	@Override
	public void update()
	{
		if(world.isRemote||isDummy())
			return;
		//The console is not part of the grid's energy engine; it just needs to keep its own
		//lights on. Checked every half second rather than every tick.
		if(++standbyTimer < 10)
			return;
		standbyTimer = 0;

		boolean wasPowered = powered;
		if(!GridConfig.consoleRequiresPower)
			powered = true;
		else if(CityMode.grid())
		{
			//City-mode parity with the feed units: presence, not consumption.
			powered = energyStorage.getEnergyStored() > 0;
			if(powered)
				spend(GridConfig.sipAmount);
		}
		else
		{
			int cost = GridConfig.consoleStandbyDraw*10;
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
	 * Deliberately not {@code extractEnergy}: this storage is built with an extract limit of
	 * zero so that nothing plugged into the console can siphon its buffer back out, and that
	 * limit applies to every caller -- including this one. Going through it meant the console
	 * could never spend a single flux, so it never drew its standby load and reported "NO
	 * POWER" forever no matter how much energy it was fed.
	 */
	private void spend(int amount)
	{
		if(amount > 0)
			energyStorage.modifyEnergyStored(-amount);
	}

	//	=================================
	//		GRID REGISTRATION
	//	=================================

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world!=null&&!world.isRemote&&formed&&!isDummy())
			//Registered purely so a segment can report how many consoles manage it; the
			//console carries no energy and the engine skips CONSOLE devices.
			VirtualGrid.INSTANCE.registerDevice(new DimensionBlockPos(getPos(), world), GridDeviceType.CONSOLE);
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		if(world!=null&&!world.isRemote&&!isDummy())
			VirtualGrid.INSTANCE.unregisterDevice(new DimensionBlockPos(getPos(), world));
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
		return Lib.GUIID_GridConsole;
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
		TileEntityGridConsole master = master();
		if(master==null)
			return false;
		if(!world.isRemote)
			player.openGui(blusunrize.immersiveengineering.ImmersiveEngineering.instance,
					Lib.GUIID_GridConsole, world, master.getPos().getX(), master.getPos().getY(),
					master.getPos().getZ());
		return true;
	}

	/**
	 * @return whether this console's screen is lit (always true on a dummy's master lookup)
	 */
	public boolean isPowered()
	{
		TileEntityGridConsole master = master();
		return master!=null&&master.powered;
	}

	//	=================================
	//		REDSTONE
	//	=================================

	@Override
	public int getComparatorInputOverride()
	{
		int total = VirtualGrid.INSTANCE.getSegmentCount();
		if(total <= 0)
			return 0;
		int live = 0;
		for(GridSegment segment : VirtualGrid.INSTANCE.getSegments())
			if(CityMode.grid()?segment.isEnergized(): segment.isOperational())
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
		TileEntityGridConsole master = master();
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

	/**
	 * The four cells are four different blocks now, so this has to answer per part rather
	 * than with one stack -- otherwise taking a console apart would pay out four terminals
	 * and eat the engineering blocks.
	 * <p>
	 * Read out of the structure the multiblock declares, the way
	 * {@code TileEntityMultiblockMetal} does, so the drops cannot disagree with the recipe
	 * or with the manual page: all three are the same array.
	 */
	@Override
	public ItemStack getOriginalBlock()
	{
		if(pos < 0)
			return new ItemStack(IEContent.blockGridDevice, 1,
					BlockTypes_GridDevice.CONSOLE_HOUSING.getMeta());
		ItemStack stack = MultiblockGridConsole.instance.getStructureManual()
				[GridConsoleGeometry.heightOf(pos)][0][GridConsoleGeometry.widthOf(pos)];
		return stack.copy();
	}

	@Override
	public float[] getBlockBounds()
	{
		return null;
	}

	@Override
	public AxisAlignedBB getRenderBoundingBox()
	{
		//The screen graphic spans the whole 2x2 face, so the master must not be culled when
		//only its upper half is on screen.
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
		TileEntityGridConsole master = master();
		return master!=null?master.getPos(): getPos();
	}
}
