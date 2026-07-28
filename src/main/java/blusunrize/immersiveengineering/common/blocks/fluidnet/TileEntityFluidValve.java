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
import blusunrize.immersiveengineering.api.fluid.network.FluidMain;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
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
 * Main Valve: the bridge between a main and IE's redstone hardware.
 * <p>
 * In <strong>input</strong> mode it is a shut-off -- a redstone signal holds its main closed, so a
 * Breaker Switch, a float switch or any logic circuit can stop a branch without anyone opening the
 * console. Inverted, it demands a keep-open signal instead, which makes it a dead-man's switch.
 * <p>
 * In <strong>output</strong> mode it reports: full power while its main is flowing, or --
 * inverted -- while it is <em>not</em>, which is the low-pressure alarm. Both directions cost
 * nothing per tick beyond one boolean, because the engine only calls back when the value it
 * publishes actually changed.
 * <p>
 * One block doing both jobs is deliberate, and it is more natural here than on the grid: on a real
 * network the thing that closes a branch and the thing that tells you whether the branch is live
 * are the same fitting.
 * <p>
 * It carries no fluid at all: {@code extractForMain} and {@code insertFromMain} keep the base
 * class's zero implementations.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class TileEntityFluidValve extends TileEntityFluidNetDevice implements IRedstoneOutput,
		INeighbourChangeTile
{
	/**
	 * What this valve is currently emitting, 0-15. Mirrored to the client for the status lamp and
	 * persisted so the level survives a reload before the first tick lands.
	 */
	private int rsOutput;
	/**
	 * Cached reading of the world's redstone at this block. Refreshed on neighbour change rather
	 * than polled, so the tick pass does no world lookups.
	 */
	private boolean rsInput;
	private boolean rsInputDirty = true;

	@Override
	public FluidDeviceType getDeviceType()
	{
		return FluidDeviceType.VALVE;
	}

	//	=================================
	//		IFluidEndpoint
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
		//Only reached on an actual change, so a steady network never issues a block update.
		if(world!=null)
		{
			world.notifyNeighborsOfStateChange(pos, getBlockType(), false);
			world.notifyNeighborsOfStateChange(pos.offset(facing), getBlockType(), false);
		}
		pushClientState();
	}

	@Override
	public void onNetConfigChanged(FluidDevice changed)
	{
		super.onNetConfigChanged(changed);
		//A valve that has just been unlinked, disabled or switched to input mode must stop
		//emitting; nothing else would ever clear a level the engine no longer maintains.
		if(!changed.isValveOutput()||!changed.isEnabled()||!changed.isLinked())
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
		//Both directions connect: a shut-off has to see the dust running into it just as much as
		//an indicator has to drive the dust running out.
		return true;
	}

	public int getRedstoneLevel()
	{
		return rsOutput;
	}

	/**
	 * A Valve never moves fluid, so the inherited throughput test would leave its lamp permanently
	 * idle. "Working" here means the signal is actually asserted, in whichever direction this valve
	 * is wired.
	 */
	@Override
	protected boolean isDoingWork(FluidDevice device)
	{
		return device.isValveOutput()?rsOutput > 0: isRedstoneHigh();
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	protected String getDeviceLabel()
	{
		return "Main Valve";
	}

	@Override
	protected List<String> buildStatusLines()
	{
		List<String> lines = super.buildStatusLines();
		FluidDevice self = getDevice();
		if(self==null)
			return lines;
		String mode = self.isValveOutput()
				?self.isValveInverted()?"indicator, inverted (low-pressure alarm)"
				: "indicator (emits while flowing)"
				:self.isValveInverted()?"shut-off, inverted (keep-open required)"
				: "shut-off (redstone closes it)";
		lines.add("Mode: "+TextFormatting.AQUA+mode+TextFormatting.RESET);
		if(self.isValveOutput())
			lines.add("Emitting: "+rsOutput);
		else
			lines.add("Redstone in: "+(isRedstoneHigh()?TextFormatting.GREEN+"high": TextFormatting.GRAY+"low")
					+TextFormatting.RESET
					+(self.isClosing(isRedstoneHigh())?TextFormatting.RED+"  holding the main closed": ""));
		return lines;
	}

	@Override
	protected String describeWorldHookup()
	{
		return null;
	}

	/**
	 * A Valve moves no fluid, so the base class's throughput-based comparator value would always
	 * read zero. Report the main's health instead, which is the only thing anyone would put a
	 * comparator here for.
	 */
	@Override
	public int getComparatorInputOverride()
	{
		FluidDevice self = getDevice();
		if(self==null||!self.isLinked())
			return 0;
		FluidMain main = VirtualFluidNet.INSTANCE.getMain(self.getMain());
		return main!=null&&main.isUp(CityMode.petroleum())?15: 0;
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
