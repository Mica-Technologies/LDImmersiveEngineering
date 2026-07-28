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
 * The forecourt tank: a five by five hole four deep, lined with steel sheetmetal, holding the same
 * five hundred and twelve buckets as the Sheetmetal Tank that stands above ground.
 * <p>
 * Matching that capacity exactly is the point. This is not a bigger store -- it is the same store
 * put out of sight, and what it costs is a considerably larger hole. A player who wants volume
 * builds the depot; a player who wants their forecourt to look like a forecourt builds this.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityCommercialTank extends TileEntityBuriedTank<TileEntityCommercialTank>
{
	public TileEntityCommercialTank()
	{
		super(BuriedTankGeometry.COMMERCIAL);
	}

	@Override
	protected String getTankLabel()
	{
		return "Commercial Tank";
	}

	@Override
	protected BlockTypes_MetalsAll getWallMetal()
	{
		return BlockTypes_MetalsAll.STEEL;
	}
}
