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
 * Metas of the {@code petroleum_decoration} block: what bitumen is ultimately for.
 * <p>
 * Roads are the end of the barrel. Bitumen is the residue nothing else wants, and a city pack
 * will happily absorb every drop of it -- which is what stops the heaviest cut being a waste
 * product the player has to dump.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public enum BlockTypes_PetroleumDecoration implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/**
	 * Plain blacktop.
	 */
	ASPHALT,
	/**
	 * Laid in a regular pattern, for pavements and yards.
	 */
	ASPHALT_TILE,
	/**
	 * Carries a painted lane marking.
	 */
	ASPHALT_MARKED;

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
