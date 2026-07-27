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
 * The Pumpjack: drives a Wellhead whose deposit no longer has the pressure to flow on its own.
 * <p>
 * The visual centrepiece of an oilfield -- walking beam, counterweight, nodding horsehead --
 * and mechanically the answer to a field falling past its free-flow threshold.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityPumpjack extends TileEntityMultiblockPart<TileEntityPumpjack>
{
	public TileEntityPumpjack()
	{
		super(PetroleumGeometry.PUMPJACK_SIZE);
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
