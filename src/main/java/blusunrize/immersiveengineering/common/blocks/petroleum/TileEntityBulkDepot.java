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
 * The depot: a nine by nine excavation six deep, lined in steel, holding four million millibuckets
 * -- a refinery's own stock, or a town's.
 * <p>
 * Nearly eight times the commercial tank for three and a half times the blocks, so building the
 * big one is worth the hole rather than merely possible. That ratio is the reason the tiers exist
 * at all; without it a player would simply build several small tanks and never dig.
 * <p>
 * At two hundred and ninety blocks it is the largest structure in the expansion by block count,
 * which is why the shell is hollow -- see {@code BuriedTankGeometry}. It is also the one tier
 * whose footprint reliably spans more than one chunk, which is the reason the fill cap is the
 * master rather than the buried origin corner.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class TileEntityBulkDepot extends TileEntityBuriedTank<TileEntityBulkDepot>
{
	public TileEntityBulkDepot()
	{
		super(BuriedTankGeometry.BULK);
	}

	@Override
	protected String getTankLabel()
	{
		return "Bulk Depot";
	}

	@Override
	protected BlockTypes_MetalsAll getWallMetal()
	{
		return BlockTypes_MetalsAll.STEEL;
	}
}
