/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockTypes_MetalsAll;

/**
 * The house tank: a two by two by two box of iron sheetmetal under the garden, holding thirty-two
 * buckets.
 * <p>
 * The bottom rung of the buried ladder and deliberately the cheap one. It is meant to be the
 * first thing a player builds after they have a fuel worth keeping -- a couple of days of heating
 * oil, or enough diesel to keep one generator honest overnight -- rather than a reward for having
 * finished a refinery.
 * <p>
 * A separate class per tier, rather than one class carrying its size in NBT, because Forge
 * instantiates tile entities through a no-argument constructor on chunk load: a tank that read its
 * own dimensions back out of the save would have to defend against a corrupt or absent tag by
 * guessing, and guessing wrong would mis-address every block of the structure.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityDomesticTank extends TileEntityBuriedTank<TileEntityDomesticTank>
{
	public TileEntityDomesticTank()
	{
		super(BuriedTankGeometry.DOMESTIC);
	}

	@Override
	protected String getTankLabel()
	{
		return "Domestic Tank";
	}

	@Override
	protected BlockTypes_MetalsAll getWallMetal()
	{
		return BlockTypes_MetalsAll.IRON;
	}
}
