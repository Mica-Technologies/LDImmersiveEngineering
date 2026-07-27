/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.BlockIEMultiblock;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.property.Properties;

/**
 * Assembled oilfield structures: the Drilling Derrick and the Pumpjack.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class BlockPetroleumMultiblock extends BlockIEMultiblock<BlockTypes_PetroleumMultiblock>
{
	public BlockPetroleumMultiblock()
	{
		super("petroleum_multiblock", Material.IRON,
				PropertyEnum.create("type", BlockTypes_PetroleumMultiblock.class),
				ItemBlockIEBase.class,
				IEProperties.BOOLEANS[0], Properties.AnimationProperty, IEProperties.OBJ_TEXTURE_REMAP);
		setHardness(3.0F);
		setResistance(15.0F);
	}

	@Override
	public EnumPushReaction getPushReaction(IBlockState state)
	{
		return EnumPushReaction.BLOCK;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_PetroleumMultiblock type)
	{
		switch(type)
		{
			case DERRICK:
				return new TileEntityDerrick();
			case PUMPJACK:
				return new TileEntityPumpjack();
		}
		return null;
	}
}
