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
 * Metas of the {@code petroleum_multiblock} block: assembled oilfield structures.
 * <p>
 * Its own block rather than more metas on {@code metal_multiblock}, which holds exactly 16
 * values and cannot take a seventeenth -- block metadata is 4 bits. Metas are persisted in
 * world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public enum BlockTypes_PetroleumMultiblock implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Sinks a bore and leaves a Wellhead behind, then comes apart to travel to the next site.
	 */
	DERRICK,
	/**
	 * Drives a Wellhead whose deposit has lost the pressure to flow on its own.
	 */
	PUMPJACK,
	/**
	 * Splits crude into its cuts, drawn off at heights matching the column order.
	 */
	DISTILLATION_TOWER,
	/**
	 * Burns the fuels nothing else wants, for process heat rather than flux.
	 */
	INDUSTRIAL_BURNER;

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
		//Assembled with a hammer, never taken from the creative list.
		return false;
	}
}
