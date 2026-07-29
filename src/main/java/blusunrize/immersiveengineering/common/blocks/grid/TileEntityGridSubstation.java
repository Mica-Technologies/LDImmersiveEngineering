/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.GridConfig;
import blusunrize.immersiveengineering.api.energy.grid.GridDevice;
import blusunrize.immersiveengineering.api.energy.grid.GridDeviceType;
import blusunrize.immersiveengineering.api.energy.grid.GridSegment;
import blusunrize.immersiveengineering.api.energy.grid.IGridEndpoint;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.IStatusLineProvider;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.api.energy.immersiveflux.IFluxProvider;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.util.EnergyHelper;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A Substation: a transformer yard that is a Feed Unit and a Service Unit at once.
 * <p>
 * Everything it does, a pair of wall boxes already did. What it adds is scale and legibility --
 * twelve blocks you can see from across a valley, one thing to name in the console's device list
 * instead of two, and a transfer cap several times what a box gets, so a town's trunk connection
 * is a structure rather than a wall fitting somebody might mistake for decoration.
 * <p>
 * <strong>Two devices, two positions.</strong> The grid keys a device by its block position, so a
 * single cell could only ever be one of feed or service. The feed registers at the master corner
 * and the service at the far end of the front rank -- see {@link SubstationGeometry} -- which also
 * keeps the console's device list readable rather than showing one coordinate twice.
 * <p>
 * Both halves are assigned to segments exactly like any other box, from the same console, with the
 * same priorities and caps. A substation is a bigger fitting, not a second system.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class TileEntityGridSubstation extends TileEntityMultiblockPart<TileEntityGridSubstation>
		implements IGridEndpoint, IComparatorOverride, IStatusLineProvider
{
	/**
	 * What the yard is worth against a single box, as a multiplier on the ordinary device cap.
	 * <p>
	 * Twelve blocks and a lot of steel for four times the throughput of one wall fitting. That is
	 * deliberately not a linear return on materials: the substation is meant to be the tidy answer
	 * for a big connection, not the strictly optimal one, or every base would be a field of them.
	 */
	public static final int CAP_MULTIPLIER = 4;

	public EnumFacing facing = EnumFacing.NORTH;

	public TileEntityGridSubstation()
	{
		super(SubstationGeometry.SIZE);
	}

	// ------------------------------------------------------------------
	// Grid registration
	// ------------------------------------------------------------------

	/**
	 * @return the world position of one of this structure's two devices, or null before the
	 * structure knows where it is
	 */
	@Nullable
	public BlockPos devicePos(int structureIndex)
	{
		return formed?getBlockPosForPos(structureIndex): null;
	}

	@Override
	public void onLoad()
	{
		super.onLoad();
		if(world==null||world.isRemote||!formed||isDummy())
			return;
		attach(SubstationGeometry.FEED_INDEX, GridDeviceType.FEED);
		attach(SubstationGeometry.SERVICE_INDEX, GridDeviceType.SERVICE);
	}

	private void attach(int index, GridDeviceType type)
	{
		BlockPos at = devicePos(index);
		if(at==null)
			return;
		GridDevice device = VirtualGrid.INSTANCE.attach(new DimensionBlockPos(at, world), type, this);
		if(device==null)
			return;
		//Raised only if the player has not chosen their own figure. Overwriting a cap somebody set
		//at the console every time the chunk loaded would make the console's own field a lie.
		if(device.getTransferCap()==GridConfig.defaultDeviceCap)
			device.setTransferCap(GridConfig.defaultDeviceCap*CAP_MULTIPLIER);
	}

	@Override
	public void invalidate()
	{
		super.invalidate();
		detachBoth();
	}

	@Override
	public void onChunkUnload()
	{
		super.onChunkUnload();
		detachBoth();
	}

	private void detachBoth()
	{
		if(world==null||world.isRemote||isDummy())
			return;
		for(int index : new int[]{SubstationGeometry.FEED_INDEX, SubstationGeometry.SERVICE_INDEX})
		{
			BlockPos at = devicePos(index);
			if(at!=null)
				VirtualGrid.INSTANCE.detach(new DimensionBlockPos(at, world));
		}
	}

	/**
	 * Drop both registrations when the structure comes apart. Without this the console's device
	 * list accumulates a permanently offline ghost for every substation ever dismantled.
	 */
	public void onBlockBroken()
	{
		if(world==null||world.isRemote)
			return;
		for(int index : new int[]{SubstationGeometry.FEED_INDEX, SubstationGeometry.SERVICE_INDEX})
		{
			BlockPos at = devicePos(index);
			if(at!=null)
				VirtualGrid.INSTANCE.unregisterDevice(new DimensionBlockPos(at, world));
		}
	}

	// ------------------------------------------------------------------
	// Moving energy
	// ------------------------------------------------------------------

	/**
	 * The block the yard exchanges power with on a given side: whatever is against the outside of
	 * the structure, exactly as a wall box works. A substation does not accept wires directly --
	 * put a connector against it, like every other machine in the mod.
	 */
	@Nullable
	private TileEntity partner(int index)
	{
		BlockPos at = devicePos(index);
		if(at==null)
			return null;
		//The front face, so a yard built against a hillside still has one reachable side and the
		//player can see which one it is.
		return Utils.getExistingTileEntity(world, at.offset(facing));
	}

	@Override
	public int extractForGrid(int max, boolean simulate)
	{
		if(max <= 0)
			return 0;
		TileEntity source = partner(SubstationGeometry.FEED_INDEX);
		if(source==null)
			return 0;
		EnumFacing from = facing.getOpposite();
		//Capability first, then IE's own interface, matching what a Feed Unit does when it taps the
		//block it is bolted to. A substation is a bigger fitting, not a different mechanism.
		if(source.hasCapability(CapabilityEnergy.ENERGY, from))
		{
			IEnergyStorage cap = source.getCapability(CapabilityEnergy.ENERGY, from);
			if(cap!=null&&cap.canExtract())
				return Math.max(0, cap.extractEnergy(max, simulate));
		}
		if(source instanceof IFluxProvider&&((IFluxProvider)source).canConnectEnergy(from))
			return Math.max(0, ((IFluxProvider)source).extractEnergy(from, max, simulate));
		return 0;
	}

	@Override
	public int insertFromGrid(int max, boolean simulate)
	{
		if(max <= 0)
			return 0;
		TileEntity sink = partner(SubstationGeometry.SERVICE_INDEX);
		if(sink==null)
			return 0;
		//Through acceptingSide for the same reason the Service Unit does: a connector parked in front
		//of the yard takes flux only on the face it is bolted to, so asking it about the face that
		//touches us is asking the one question it will always answer no to.
		EnumFacing from = EnergyHelper.acceptingSide(sink, facing.getOpposite());
		return from==null?0: EnergyHelper.insertFlux(sink, from, max, simulate);
	}

	// ------------------------------------------------------------------
	// Readouts
	// ------------------------------------------------------------------

	@Override
	public int getComparatorInputOverride()
	{
		GridDevice feed = deviceAt(SubstationGeometry.FEED_INDEX);
		if(feed==null||!feed.isLinked())
			return 0;
		GridSegment segment = VirtualGrid.INSTANCE.getSegment(feed.getSegment());
		if(segment==null||!segment.isOperational())
			return 0;
		return 15;
	}

	@Nullable
	private GridDevice deviceAt(int index)
	{
		BlockPos at = devicePos(index);
		return at==null?null: VirtualGrid.INSTANCE.getDevice(new DimensionBlockPos(at, world));
	}

	@Override
	public List<String> getStatusLines()
	{
		List<String> lines = new ArrayList<>();
		lines.add(TextFormatting.GOLD+"Substation"+TextFormatting.RESET);
		describe(lines, "Feed", SubstationGeometry.FEED_INDEX);
		describe(lines, "Service", SubstationGeometry.SERVICE_INDEX);
		//	=================================
		//	Where the two ends actually are.
		//	=================================
		//
		// The yard is twelve identical blocks. Nothing about it says which end takes power and which
		// gives it, or that either of them only talks to the block directly in front -- so the first
		// person to build one reported being unable to find the inputs and outputs at all, which is
		// a fair description of a featureless box.
		//
		// Said in the terms somebody standing in front of it can act on: an end, a side, and the one
		// block that matters.
		lines.add(TextFormatting.GRAY+"Facing "+facing.getName()+": feed is the "
				+endName(SubstationGeometry.FEED_INDEX)+" end, service the "
				+endName(SubstationGeometry.SERVICE_INDEX)+" end."+TextFormatting.RESET);
		lines.add(TextFormatting.GRAY+"Each exchanges power with the single block in front of it. "
				+"A wire connector there is fed whichever way it faces."+TextFormatting.RESET);
		return lines;
	}

	/**
	 * @return "left" or "right" as seen by somebody standing in front of the yard
	 */
	private String endName(int index)
	{
		//Column zero is the left-hand one seen from the front, the same convention the console's
		//screen halves use. Naming it rather than giving a coordinate is the point: the player is
		//looking at the thing.
		return index%SubstationGeometry.WIDTH==0?"left": "right";
	}

	private void describe(List<String> lines, String label, int index)
	{
		GridDevice device = deviceAt(index);
		if(device==null||!device.isLinked())
		{
			//Both halves are assigned separately, so saying which one is unassigned is the
			//difference between a five-second fix and a puzzle.
			lines.add("  "+label+": "+TextFormatting.RED+"unlinked"+TextFormatting.RESET);
			return;
		}
		GridSegment segment = VirtualGrid.INSTANCE.getSegment(device.getSegment());
		lines.add("  "+label+": "+(segment==null?TextFormatting.RED+"segment missing"
				: segment.getName()+" -- "+device.getLastThroughput()+" / "
				+device.getTransferCap()+" IF")+TextFormatting.RESET);
	}

	// ------------------------------------------------------------------
	// Housekeeping
	// ------------------------------------------------------------------

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("facing", facing.ordinal());
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		int ordinal = nbt.getInteger("facing");
		facing = ordinal >= 0&&ordinal < EnumFacing.VALUES.length
				?EnumFacing.VALUES[ordinal]: EnumFacing.NORTH;
	}

	@Override
	public void update()
	{
		//Nothing per tick. The grid engine drives its devices from one global pass -- see
		//GridTickHandler -- and a substation is two of those devices, not a machine of its own.
		//TileEntityMultiblockPart insists on being ITickable, so this is the whole of it.
	}

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		//A substation moves flux and nothing else. The base class asks anyway.
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
	public float[] getBlockBounds()
	{
		return null;
	}

	@Override
	public ItemStack getOriginalBlock()
	{
		return new ItemStack(IEContent.blockGridDevice, 1,
				BlockTypes_GridDevice.SUBSTATION_FRAME.getMeta());
	}
}
