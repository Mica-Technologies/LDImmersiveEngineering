/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code petroleum_device} block: the placeable, non-assembled oilfield parts.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public enum BlockTypes_PetroleumDevice implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Caps a drilled bore and collects what comes up it.
	 */
	WELLHEAD,
	/**
	 * Structural block the Drilling Derrick and Pumpjack are built from.
	 */
	OILFIELD_FRAME;

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
		//The wellhead is listed too: a creative build should not have to drill for one.
		return true;
	}
}
