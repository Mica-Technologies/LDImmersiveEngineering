/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.common.blocks.wooden.TileEntityWoodenBarrel;
import net.minecraftforge.fluids.FluidStack;

public class TileEntityMetalBarrel extends TileEntityWoodenBarrel
{
	@Override
	public void update()
	{
		if(world.isRemote)
			return;
		//An empty barrel has nothing to push, so there is no reason to read six neighbouring block
		//states to find out whether it is allowed to. The redstone poll ran unconditionally on every
		//metal barrel in the world, empty or not.
		if(tank.getFluidAmount() <= 0)
			return;
		if(world.getRedstonePowerFromNeighbors(getPos()) > 0)
			return;
		super.update();
	}

	@Override
	public boolean isFluidValid(FluidStack fluid)
	{
		return fluid!=null&&fluid.getFluid()!=null;
	}
}
