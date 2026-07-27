/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.INeighbourChangeTile;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IRedstoneOutput;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Grid Signal Unit: the bridge between a segment and IE's redstone hardware.
 * <p>
 * In <strong>input</strong> mode it is an external kill switch -- a redstone signal holds
 * its segment off, so a Breaker Switch, a daylight sensor or any logic circuit can stop a
 * segment without anyone opening the console. Inverted, it demands a keep-alive signal
 * instead, which makes it a dead-man's switch.
 * <p>
 * In <strong>output</strong> mode it reports: full power while its segment is up, or --
 * inverted -- while it is <em>down</em>, which is the fault lamp. Both directions cost
 * nothing per tick beyond one boolean, because the engine only calls back when the value
 * it publishes actually changed.
 * <p>
 * It carries no energy at all: {@code extractForGrid} and {@code insertFromGrid} keep the
 * base class's zero implementations.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridSignal extends TileEntityGridDevice implements IRedstoneOutput,
		INeighbourChangeTile
{
	/**
	 * What this unit is currently emitting, 0-15. Mirrored to the client for the status
	 * lamp and persisted so the level survives a reload before the first tick lands.
	 */
	private int rsOutput;
	/**
	 * Cached reading of the world's redstone at this block. Refreshed on neighbour change
	 * rather than polled, so the tick pass does no world lookups.
	 */
	private boolean rsInput;
	private boolean rsInputDirty = true;

	@Override
	public GridDeviceType getDeviceType()
	{
		return GridDeviceType.SIGNAL;
	}

	//	=================================
	//		IGridEndpoint
	//	=================================

	@Override
	public boolean isRedstoneHigh()
	{
		if(rsInputDirty&&world!=null)
		{
			rsInput = world.getRedstonePowerFromNeighbors(pos) > 0;
			rsInputDirty = false;
		}
		return rsInput;
	}

	@Override
	public void setRedstoneOutput(int level)
	{
		level = level < 0?0: Math.min(15, level);
		if(level==rsOutput)
			return;
		rsOutput = level;
		markDirty();
		//Only reached on an actual change, so a steady grid never issues a block update.
		if(world!=null)
		{
			world.notifyNeighborsOfStateChange(pos, getBlockType(), false);
			world.notifyNeighborsOfStateChange(pos.offset(facing), getBlockType(), false);
		}
		pushClientState();
	}

	@Override
	public void onGridConfigChanged(GridDevice changed)
	{
		super.onGridConfigChanged(changed);
		//A unit that has just been unlinked, disabled or switched to input mode must stop
		//emitting; nothing else would ever clear a level the engine no longer maintains.
		if(!changed.isSignalOutput()||!changed.isEnabled()||!changed.isLinked())
			setRedstoneOutput(0);
	}

	@Override
	public void onNeighborBlockChange(BlockPos otherPos)
	{
		rsInputDirty = true;
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		rsInputDirty = true;
	}

	//	=================================
	//		IRedstoneOutput
	//	=================================

	@Override
	public int getStrongRSOutput(IBlockState state, EnumFacing side)
	{
		return rsOutput;
	}

	@Override
	public boolean canConnectRedstone(IBlockState state, @Nullable EnumFacing side)
	{
		//Both directions connect: an input unit has to see the dust running into it just as
		//much as an output unit has to drive the dust running out.
		return true;
	}

	public int getRedstoneLevel()
	{
		return rsOutput;
	}

	/**
	 * A Signal Unit never moves flux, so the inherited throughput test would leave its lamp
	 * permanently idle. "Working" here means the signal is actually asserted, in whichever
	 * direction this unit is wired.
	 */
	@Override
	protected boolean isDoingWork(GridDevice device)
	{
		return device.isSignalOutput()?rsOutput > 0: isRedstoneHigh();
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	protected String getDeviceLabel()
	{
		return "Grid Signal Unit";
	}

	@Override
	protected List<String> buildStatusLines()
	{
		List<String> lines = super.buildStatusLines();
		GridDevice self = getDevice();
		if(self==null)
			return lines;
		String mode = self.isSignalOutput()
				?self.isSignalInverted()?"output, inverted (fault lamp)": "output (emits while up)"
				:self.isSignalInverted()?"input, inverted (keep-alive required)": "input (kill switch)";
		lines.add("Mode: "+TextFormatting.AQUA+mode+TextFormatting.RESET);
		if(self.isSignalOutput())
			lines.add("Emitting: "+rsOutput);
		else
			lines.add("Redstone in: "+(isRedstoneHigh()?TextFormatting.GREEN+"high": TextFormatting.GRAY+"low")
					+TextFormatting.RESET
					+(self.isKilling(isRedstoneHigh())?TextFormatting.RED+"  holding segment off": ""));
		return lines;
	}

	@Override
	protected String describeWorldHookup()
	{
		return null;
	}

	/**
	 * A Signal Unit moves no flux, so the base class's throughput-based comparator value
	 * would always read zero. Report the segment's health instead, which is the only thing
	 * anyone would put a comparator here for.
	 */
	@Override
	public int getComparatorInputOverride()
	{
		GridDevice self = getDevice();
		if(self==null||!self.isLinked())
			return 0;
		GridSegment segment = VirtualGrid.INSTANCE.getSegment(self.getSegment());
		return segment!=null&&segment.isUp(CityMode.grid())?15: 0;
	}

	//	=================================
	//		PERSISTENCE
	//	=================================

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("rsOutput", rsOutput);
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		rsOutput = nbt.getInteger("rsOutput");
	}
}
