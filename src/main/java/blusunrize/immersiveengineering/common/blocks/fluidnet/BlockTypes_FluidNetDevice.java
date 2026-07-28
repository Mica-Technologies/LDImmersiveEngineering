/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code fluidnet_device} block: the virtual fluid network's world-facing fittings.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public enum BlockTypes_FluidNetDevice implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Takes fluid out of a tank or a pipe and into its main.
	 */
	FLUID_INLET,
	/**
	 * Delivers out of a main into a machine, a generator or a buried tank.
	 */
	FLUID_OUTLET,
	/**
	 * Shuts a branch off, or reports whether it is flowing.
	 */
	MAIN_VALVE,
	/**
	 * Four of these, hammered, become a Fluid Control Console.
	 */
	CONSOLE_HOUSING;

	@Override
	public String getName()
	{
		return this.toString().toLowerCase(Locale.ENGLISH);
	}

	@Override
	public int getMeta()
	{
		return ordinal();
	}

	@Override
	public boolean listForCreative()
	{
		return true;
	}
}
