/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code grid_device} block: the pole/wall-mount utility boxes and the
 * console housing that is hammered into a Grid Management Console.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public enum BlockTypes_GridDevice implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Takes power out of the world and into a grid segment.
	 */
	FEED_UNIT,
	/**
	 * Delivers power from a grid segment back into the world.
	 */
	SERVICE_UNIT,
	/**
	 * Inert on its own; four in a 2x2 wall make a Grid Management Console.
	 */
	CONSOLE_HOUSING,
	/**
	 * Bridges a segment to redstone, in either direction.
	 */
	SIGNAL_UNIT;
	//SUBSTATION_FRAME was removed with the Substation itself, before either shipped. It sat at
	//meta 4, the last value, so dropping it moved no other block's metadata.

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
