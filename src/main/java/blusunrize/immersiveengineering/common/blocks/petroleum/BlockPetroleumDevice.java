/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.blocks.BlockIETileProvider;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.world.World;

/**
 * The oilfield's placeable hardware: the Wellhead, and the frame the larger structures are
 * assembled from.
 * <p>
 * A block of its own rather than more metas on an existing one, so the whole feature registers
 * as one unit.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class BlockPetroleumDevice extends BlockIETileProvider<BlockTypes_PetroleumDevice>
{
	public BlockPetroleumDevice()
	{
		super("petroleum_device", Material.IRON,
				PropertyEnum.create("type", BlockTypes_PetroleumDevice.class), ItemBlockIEBase.class);
		this.setHardness(3.0F);
		this.setResistance(15.0F);
		this.lightOpacity = 0;
		//The wellhead is a valve stack, not a solid cube.
		this.setNotNormalBlock(BlockTypes_PetroleumDevice.WELLHEAD.getMeta());
		this.setMetaBlockLayer(BlockTypes_PetroleumDevice.WELLHEAD.getMeta(), BlockRenderLayer.CUTOUT);
	}

	@Override
	public boolean allowHammerHarvest(IBlockState state)
	{
		return true;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_PetroleumDevice type)
	{
		switch(type)
		{
			case WELLHEAD:
				return new TileEntityWellhead();
			case LUBRICATION_MANIFOLD:
				return new TileEntityLubricationManifold();
			case FLARE_STACK:
				return new TileEntityFlareStack();
			case OILFIELD_FRAME:
				//Inert on its own; it only matters as part of an assembled structure.
				return null;
		}
		return null;
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		BlockTypes_PetroleumDevice type = state.getValue(property);
		return type!=BlockTypes_PetroleumDevice.OILFIELD_FRAME;
	}
}
