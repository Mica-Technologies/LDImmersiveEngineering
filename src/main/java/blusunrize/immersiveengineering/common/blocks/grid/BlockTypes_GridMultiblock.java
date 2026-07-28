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
 * Metas of the {@code grid_multiblock} block: assembled grid structures.
 * <p>
 * This exists as its own block rather than another meta on {@code metal_multiblock}
 * because that enum is full -- it holds exactly 16 values and block metadata is 4 bits, so
 * a seventeenth could not be stored in a block state at all.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public enum BlockTypes_GridMultiblock implements IStringSerializable, BlockIEBase.IBlockEnum
{
	GRID_CONSOLE,
	/**
	 * A formed Substation: a Feed Unit and a Service Unit in one transformer yard.
	 */
	SUBSTATION;

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
		//Obtained by hammering four Console Housings, never from the creative list.
		return false;
	}
}
