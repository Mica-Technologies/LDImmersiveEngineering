/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.BlockIETileProvider;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * Surface-mounted conduit: the indoor counterpart to IE's catenary wires.
 * <p>
 * A wire sags between two points, which is right across a valley and wrong along a ceiling. This
 * lies flat against a face and turns in right angles, and that is the entire reason it exists.
 * <p>
 * The block is drawn by an ordinary multipart blockstate -- a hub against the mounting face plus
 * one arm per joined direction -- rather than by a renderer. Thirty small models, all axis-aligned
 * boxes generated alongside the texture, and nothing drawn per frame. A catenary renderer would be
 * both the wrong shape and far more expensive.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class BlockConduit extends BlockIETileProvider<BlockTypes_Conduit>
{
	public BlockConduit()
	{
		super("conduit", Material.IRON, PropertyEnum.create("type", BlockTypes_Conduit.class),
				ItemBlockIEBase.class, IEProperties.FACING_ALL,
				IEProperties.SIDECONNECTION[0], IEProperties.SIDECONNECTION[1],
				IEProperties.SIDECONNECTION[2], IEProperties.SIDECONNECTION[3],
				IEProperties.SIDECONNECTION[4], IEProperties.SIDECONNECTION[5]);
		this.setHardness(2.0F);
		this.setResistance(10.0F);
		this.lightOpacity = 0;
		this.setNotNormalBlock(BlockTypes_Conduit.CONDUIT_RUN.getMeta());
		this.setMetaBlockLayer(BlockTypes_Conduit.CONDUIT_RUN.getMeta(), BlockRenderLayer.CUTOUT);
	}

	@Override
	public boolean useCustomStateMapper()
	{
		return true;
	}

	@Override
	public String getCustomStateMapping(int meta, boolean itemBlock)
	{
		//The block is described by a multipart blockstate, which cannot also carry the
		//`inventory,...` variant the item model is looked up through -- so the two live in
		//separate files and this is what points the block half at conduit_run.json. Returning
		//null for the item leaves it resolving against conduit.json, exactly as IE's fences do.
		//Both files have to exist: a custom mapping with no matching file is one of the two
		//silent causes of a purple block in 1.12, and neither reports an error.
		return itemBlock?null: "run";
	}

	@Override
	public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		state = super.getActualState(state, world, pos);
		TileEntity tile = world.getTileEntity(pos);
		if(!(tile instanceof TileEntityConduit))
			return state;
		TileEntityConduit conduit = (TileEntityConduit)tile;
		//Written out in absolute facings rather than as arm indices: the blockstate file reads far
		//better as "sideconnection_north" than as "arm2", and the mapping between the two is
		//exactly what ConduitGeometry.armIndex is for.
		for(EnumFacing side : EnumFacing.VALUES)
			state = applyProperty(state, IEProperties.SIDECONNECTION[side.ordinal()],
					conduit.isConnected(side));
		return state;
	}

	@Override
	public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side)
	{
		return false;
	}

	@Override
	public boolean allowHammerHarvest(IBlockState state)
	{
		return true;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_Conduit type)
	{
		return new TileEntityConduit();
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		return true;
	}
}
