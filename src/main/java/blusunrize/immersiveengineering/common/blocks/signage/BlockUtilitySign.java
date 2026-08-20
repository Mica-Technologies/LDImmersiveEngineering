/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.signage;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.BlockIETileProvider;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Identification for a pole line: thirteen kinds of utility tag, bolted flat to whatever holds the
 * wires up.
 * <p>
 * <strong>A grid you cannot read is a grid you cannot maintain.</strong> That is the whole of the
 * argument for this block, and it came from somebody who does the reading: a hundred poles across a
 * map are a hundred identical poles until each one says which station feeds it, which feeder it is
 * on and who last inspected it. Every kind here is a sign that exists -- LADWP's and SCE's -- and
 * they are told apart the way the real ones are, by shape and colour before anybody is close enough
 * to read the number.
 * <p>
 * One block, one meta, one item. The kind is a listed integer property filled from the tile entity,
 * so the blockstate can be a plain {@code variants} file naming one flat plate model per kind and
 * facing -- fifty-two models' worth of nothing but a textured slab. Only the lettering costs
 * anything per frame, and only within forty-eight blocks; see {@code TileRenderUtilitySign}.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class BlockUtilitySign extends BlockIETileProvider<BlockTypes_Signage>
{
	/**
	 * Which of the thirteen plates this is. Listed so a blockstate can select on it, and filled from
	 * the tile entity through {@code IAttachedIntegerProperies} -- it is saved on the tile, not in
	 * the meta, because the text has to be there anyway.
	 */
	public static final PropertyInteger KIND = PropertyInteger.create(TileEntityUtilitySign.KIND,
			0, UtilitySignKind.VALUES.length-1);

	public BlockUtilitySign()
	{
		super("signage", Material.IRON, PropertyEnum.create("type", BlockTypes_Signage.class),
				ItemBlockIEBase.class, IEProperties.FACING_HORIZONTAL, KIND);
		this.setHardness(0.5F);
		this.setResistance(2.0F);
		this.lightOpacity = 0;
		for(BlockTypes_Signage type : BlockTypes_Signage.values())
		{
			this.setNotNormalBlock(type.getMeta());
			//Cutout: the oval, the round tag and both diamonds are shapes cut out of a square
			//sprite, and the corners have to be gone rather than black.
			this.setMetaBlockLayer(type.getMeta(), BlockRenderLayer.CUTOUT);
		}
	}

	@Override
	public boolean isFullCube(IBlockState state)
	{
		return false;
	}

	@Override
	public boolean isOpaqueCube(IBlockState state)
	{
		return false;
	}

	@Nullable
	@Override
	public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		//A tag you can walk through. Nobody wants to be stopped by a pole number, and a one-pixel
		//collision box on a ladder is a way to fall off one.
		return NULL_AABB;
	}

	@Override
	public boolean canPlaceBlockOnSide(World world, BlockPos pos, EnumFacing side)
	{
		//Something to bolt it to, and a horizontal face to bolt it to. A sign floating in the air
		//would draw fine and read as a bug.
		if(side.getAxis()==EnumFacing.Axis.Y)
			return false;
		BlockPos support = pos.offset(side.getOpposite());
		return world.getBlockState(support).isSideSolid(world, support, side);
	}

	@Override
	public void neighborChanged(IBlockState state, World world, BlockPos pos,
								net.minecraft.block.Block block, BlockPos fromPos)
	{
		super.neighborChanged(state, world, pos, block, fromPos);
		if(world.isRemote)
			return;
		TileEntity tile = world.getTileEntity(pos);
		if(!(tile instanceof TileEntityUtilitySign))
			return;
		//The pole came down. Vanilla signs and torches drop rather than hang in mid-air, and a tag
		//that stayed behind would be a tag nobody could tell was orphaned.
		EnumFacing facing = ((TileEntityUtilitySign)tile).getFacing();
		BlockPos support = pos.offset(facing);
		if(!world.getBlockState(support).isSideSolid(world, support, facing.getOpposite()))
		{
			dropBlockAsItem(world, pos, state, 0);
			world.setBlockToAir(pos);
		}
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_Signage type)
	{
		return new TileEntityUtilitySign();
	}

	@Override
	public boolean useCustomStateMapper()
	{
		return true;
	}

	@Override
	public String getCustomStateMapping(int meta, boolean itemBlock)
	{
		//The item resolves against signage.json's `inventory` variant; the block half has fifty-two
		//variants of its own and lives in its own file. Both have to exist -- a custom mapping with
		//no matching file is a purple block with nothing in the log.
		return itemBlock?null: "utility_sign";
	}
}
