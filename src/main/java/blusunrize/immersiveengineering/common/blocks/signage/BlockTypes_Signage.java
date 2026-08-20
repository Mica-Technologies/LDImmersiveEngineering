/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import net.minecraft.util.IStringSerializable;

import java.util.Locale;

/**
 * Metas of the {@code signage} block.
 * <p>
 * One, deliberately. The thirteen kinds of tag are not thirteen items: a player crafts one Utility
 * Pole Sign and hits it with a hammer until it is the one they meant, which is one recipe, one entry
 * in the creative tab and one thing to learn. The kind lives on the tile entity -- see
 * {@link UtilitySignKind}.
 * <p>
 * Metas are persisted in world saves, so constants may only be appended.
 *
 * @author LDImmersiveEngineering -- signage
 */
public enum BlockTypes_Signage implements IStringSerializable, BlockIEBase.IBlockEnum
{
	/** A tag bolted flat to a pole. Thirteen shapes, up to three lines of text. */
	UTILITY_SIGN;

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
