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
 * The Industrial Burner: turns the fuels nothing else wants into process heat.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityIndustrialBurner extends TileEntityMultiblockPart<TileEntityIndustrialBurner>
{
	public TileEntityIndustrialBurner()
	{
		super(PetroleumGeometry.BURNER_SIZE);
	}

	@Override
	public void update()
	{
	}

	@Override
	public float[] getBlockBounds()
	{
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
