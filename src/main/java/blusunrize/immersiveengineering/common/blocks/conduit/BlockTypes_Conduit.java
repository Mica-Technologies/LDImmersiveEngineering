/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code conduit} block.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public enum BlockTypes_Conduit implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * A length of surface-mounted tubing. Carries a bundle; splits nothing.
	 */
	CONDUIT_RUN,
	/**
	 * Where a bundle splits. Dye a face and that conductor leaves by it.
	 */
	JUNCTION_BOX,
	/**
	 * A hole through a floor or a wall, wearing whatever is around it. Carries a bundle straight
	 * through along one axis and splits nothing -- the invisible counterpart to the box.
	 */
	GROUND_FEEDER;

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
