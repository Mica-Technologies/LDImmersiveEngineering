/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockOverlayText;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IComparatorOverride;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import blusunrize.immersiveengineering.common.blocks.metal.BlockTypes_MetalDecoration1;
import blusunrize.immersiveengineering.common.blocks.multiblocks.MultiblockLoadingGantry;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The Fluid Loading Gantry: the refinery's truck bay, and the answer to "I have four hundred
 * buckets and eleven empty jerrycans".
 * <p>
 * Filling containers by hand is fine for one. It is not fine for a crate of them, and until this
 * exists the only way to bulk-fill portable storage is to stand at a tank clicking. The gantry
 * takes empties out of the inventory on one side, fills them, and puts them into the inventory on
 * the other -- so it is a station in a line rather than a thing you operate.
 * <p>
 * <strong>No GUI, deliberately.</strong> A machine whose whole job is to sit between two chests
 * should be configured by where the chests are, not by a screen. That also means there is no
 * internal inventory to spill when the structure comes apart.
 * <p>
 * Runs on a staggered ten-tick interval and does nothing at all when either side is missing, so a
 * gantry standing in a half-built yard is very nearly free.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityLoadingGantry extends TileEntityMultiblockPart<TileEntityLoadingGantry>
		implements IBlockOverlayText, IComparatorOverride
{
	/**
	 * Sixteen buckets of working stock. The gantry is a filling head, not a tank: it is meant to be
	 * plumbed to one.
	 */
	public static final int TANK_CAPACITY = 16000;
	/**
	 * Ticks between passes. Fast enough that a crate of jerrycans is a job of seconds rather than
	 * minutes, slow enough that an idle gantry is invisible on a profiler.
	 */
	public static final int INTERVAL = 10;
	/**
	 * Containers filled per pass. One at a time reads as a machine doing work; a whole stack at
	 * once reads as a recipe, and this is not one.
	 */
	public static final int PER_PASS = 1;

	private static final IFluidTank[] NO_TANKS = new IFluidTank[0];

	public final FluidTank tank = new FluidTank(TANK_CAPACITY);

	/**
	 * Synced: it drives nothing but the overlay, and the overlay is the only way to tell a gantry
	 * waiting for empties from one waiting for fuel.
	 */
	public int status;
	public static final int STATUS_IDLE = 0;
	public static final int STATUS_FILLING = 1;
	public static final int STATUS_NO_FLUID = 2;
	public static final int STATUS_NO_OUTPUT = 3;

	private int stagger = -1;

	public TileEntityLoadingGantry()
	{
		super(PetroleumGeometry.GANTRY_SIZE);
	}

	@Override
	public void update()
	{
		ApiUtils.checkForNeedlessTicking(this);
		if(world.isRemote||isDummy()||!formed)
			return;
		if((world.getTotalWorldTime()+getStagger())%INTERVAL!=0)
			return;
		runPass();
	}

	private void runPass()
	{
		int previous = status;
		status = STATUS_IDLE;
		//Both chests stand in front of the gantry, one against each leg. They used to be on
		//opposite faces -- intake in front of the left leg, output behind the right -- which is
		//buildable but undiscoverable, and this machine has no interface to explain itself with.
		IItemHandler in = neighbourInventory(MultiblockLoadingGantry.INTAKE_POS, facing.getOpposite());
		IItemHandler out = neighbourInventory(MultiblockLoadingGantry.OUTPUT_POS, facing.getOpposite());
		if(in!=null&&out!=null)
			fill(in, out);
		if(status!=previous)
			markContainingBlockForUpdate(null);
		markDirty();
	}

	/**
	 * Moves at most {@link #PER_PASS} containers from the intake to the output, filling them on the
	 * way.
	 */
	private void fill(IItemHandler in, IItemHandler out)
	{
		if(tank.getFluidAmount() <= 0)
		{
			status = STATUS_NO_FLUID;
			return;
		}
		int done = 0;
		for(int slot = 0; slot < in.getSlots()&&done < PER_PASS; slot++)
		{
			ItemStack candidate = in.extractItem(slot, 1, true);
			if(candidate.isEmpty())
				continue;
			IFluidHandlerItem handler = FluidUtil.getFluidHandler(candidate.copy());
			if(handler==null)
				continue;
			FluidStack available = tank.getFluid();
			if(available==null)
				break;
			int accepted = handler.fill(available.copy(), false);
			if(accepted <= 0)
				continue;

			FluidStack offered = available.copy();
			offered.amount = accepted;
			handler.fill(offered, true);
			ItemStack filled = handler.getContainer();
			//Simulated first, and the empty is taken only once the full one is known to fit. The
			//other way round destroys a jerrycan the moment the output chest fills up, and a
			//machine that eats containers is a far worse failure than one that stops.
			ItemStack leftover = insert(out, filled, true);
			if(!leftover.isEmpty())
			{
				status = STATUS_NO_OUTPUT;
				return;
			}
			in.extractItem(slot, 1, false);
			insert(out, filled, false);
			tank.drainInternal(accepted, true);
			status = STATUS_FILLING;
			done++;
		}
	}

	private static ItemStack insert(IItemHandler out, ItemStack stack, boolean simulate)
	{
		ItemStack remaining = stack;
		for(int slot = 0; slot < out.getSlots()&&!remaining.isEmpty(); slot++)
			remaining = out.insertItem(slot, remaining, simulate);
		return remaining;
	}

	@Nullable
	private IItemHandler neighbourInventory(int structurePos, EnumFacing outward)
	{
		BlockPos at = getBlockPosForPos(structurePos).offset(outward);
		TileEntity te = Utils.getExistingTileEntity(world, at);
		if(te==null)
			return null;
		return te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, outward.getOpposite());
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
		if(!formed)
			return null;
		TileEntityLoadingGantry master = master();
		if(master==null)
			return null;
		FluidStack held = master.tank.getFluid();
		return new String[]{
				statusLine(master.status),
				held==null||held.amount <= 0?TextFormatting.GRAY+"Empty"+TextFormatting.RESET
						: held.getLocalizedName()+"  "+held.amount+" / "+TANK_CAPACITY+" mB"};
	}

	private static String statusLine(int status)
	{
		switch(status)
		{
			case STATUS_FILLING:
				return TextFormatting.GREEN+"Filling"+TextFormatting.RESET;
			case STATUS_NO_FLUID:
				return TextFormatting.RED+"No fuel"+TextFormatting.RESET;
			case STATUS_NO_OUTPUT:
				return TextFormatting.GOLD+"Output full"+TextFormatting.RESET;
			default:
				return TextFormatting.YELLOW+"Waiting for empties"+TextFormatting.RESET;
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
		if(pos!=MultiblockLoadingGantry.INTAKE_POS)
			return 0;
		TileEntityLoadingGantry master = master();
		if(master==null||master.tank.getFluidAmount() <= 0)
			return 0;
		return Math.max(1, Math.min(15, 15*master.tank.getFluidAmount()/TANK_CAPACITY));
	}

	//	=================================
	//		PORTS
	//	=================================

	@Nonnull
	@Override
	protected IFluidTank[] getAccessibleFluidTanks(@Nullable EnumFacing side)
	{
		TileEntityLoadingGantry master = master();
		//Every block of the gantry takes fluid. It is a nine-block frame straddling a bay, and
		//making the player find one particular leg to plumb would be fiddliness for its own sake.
		return master==null||!formed?NO_TANKS: new IFluidTank[]{master.tank};
	}

	@Override
	protected boolean canFillTankFrom(int iTank, EnumFacing side, FluidStack resource)
	{
		return formed;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, EnumFacing side)
	{
		//Fill only. A drainable gantry would be a fluid store that happens to also be a machine,
		//and the buried tanks are what storage is for.
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
		int cells = PetroleumGeometry.GANTRY_HEIGHT*PetroleumGeometry.GANTRY_DEPTH
				*PetroleumGeometry.GANTRY_WIDTH;
		if(pos >= 0&&pos < cells
				&&PetroleumGeometry.heightOf(PetroleumGeometry.GANTRY_SIZE, pos)==PetroleumGeometry.GANTRY_HEIGHT-1)
			return new ItemStack(IEContent.blockPetroleumDevice, 1,
					BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
		return new ItemStack(IEContent.blockMetalDecoration1, 1,
				BlockTypes_MetalDecoration1.STEEL_SCAFFOLDING_0.getMeta());
	}

	@Override
	public void readCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
		status = nbt.getInteger("status");
		if(!descPacket)
			tank.readFromNBT(nbt.getCompoundTag("tank"));
	}

	@Override
	public void writeCustomNBT(NBTTagCompound nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
		nbt.setInteger("status", status);
		if(!descPacket)
			nbt.setTag("tank", tank.writeToNBT(new NBTTagCompound()));
	}
}
