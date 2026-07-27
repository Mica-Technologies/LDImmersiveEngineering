/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockIEBase;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

/**
 * Laid asphalt: what wet asphalt sets into, and what bitumen is ultimately for.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class BlockPetroleumDecoration extends BlockIEBase<BlockTypes_PetroleumDecoration>
{
	public BlockPetroleumDecoration()
	{
		super("petroleum_decoration", Material.ROCK,
				PropertyEnum.create("type", BlockTypes_PetroleumDecoration.class),
				ItemBlockIEBase.class);
		this.setHardness(1.8F);
		this.setResistance(10.0F);
	}

	/**
	 * A road that did not move traffic faster than the mud beside it would be a strange road.
	 * Modest on purpose -- this is a reason to pave, not a replacement for a minecart.
	 */
	@Override
	public float getSlipperiness(IBlockState state, IBlockAccess world, BlockPos pos, Entity entity)
	{
		return 0.68F;
	}
}
