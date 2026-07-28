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
import net.minecraft.util.math.BlockPos;
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
		this.setNotNormalBlock(BlockTypes_PetroleumDevice.PROPANE_CYLINDER.getMeta());
		this.setMetaBlockLayer(BlockTypes_PetroleumDevice.PROPANE_CYLINDER.getMeta(), BlockRenderLayer.CUTOUT);
		this.setMetaBlockLayer(BlockTypes_PetroleumDevice.WELLHEAD.getMeta(), BlockRenderLayer.CUTOUT);
	}

	/**
	 * Wakes a Flare Stack when its flame should go out.
	 * <p>
	 * The flare schedules this itself on delivery instead of being ticked, so a cold flare --
	 * which is nearly all of them, nearly all of the time -- is in no tick list at all.
	 */
	@Override
	public void updateTick(World world, BlockPos pos, IBlockState state, java.util.Random rand)
	{
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityFlareStack)
			((TileEntityFlareStack)te).ageFlame();
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
			case PROPANE_CYLINDER:
				return new TileEntityPropaneCylinder();
			case LUBRICATION_MANIFOLD:
				return new TileEntityLubricationManifold();
			case FLARE_STACK:
				return new TileEntityFlareStack();
			case OILFIELD_FRAME:
			case TANK_FILL_CAP:
				//Inert on its own; it only matters as part of an assembled structure.
				return null;
		}
		return null;
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		BlockTypes_PetroleumDevice type = state.getValue(property);
		return type!=BlockTypes_PetroleumDevice.OILFIELD_FRAME
				&&type!=BlockTypes_PetroleumDevice.TANK_FILL_CAP;
	}
}
