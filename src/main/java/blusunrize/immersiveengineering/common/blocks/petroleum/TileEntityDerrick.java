/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.TileEntityMultiblockPart;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

import javax.annotation.Nullable;

/**
 * The Drilling Derrick: sinks a bore and leaves a Wellhead behind.
 * <p>
 * Temporary by design. It drills, hands over a Wellhead, then comes apart so it can travel to
 * the next site -- the rig is equipment, not architecture.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityDerrick extends TileEntityMultiblockPart<TileEntityDerrick>
{
	public TileEntityDerrick()
	{
		super(PetroleumGeometry.DERRICK_SIZE);
	}

	@Override
	public void update()
	{
	}

	@Override
	public float[] getBlockBounds()
	{
		//Null is "the whole block". The structure is made of full cubes; the shape comes from
		//the model, not from per-block bounds.
		return null;
	}

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

	@Override
	public ItemStack getOriginalBlock()
	{
		return new ItemStack(IEContent.blockPetroleumDevice, 1,
				BlockTypes_PetroleumDevice.OILFIELD_FRAME.getMeta());
	}
}
