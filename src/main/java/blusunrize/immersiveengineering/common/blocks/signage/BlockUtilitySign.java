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
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Identification for a pole line: sixteen kinds of utility tag, bolted flat to whatever holds the
 * wires up.
 * <p>
 * <strong>A grid you cannot read is a grid you cannot maintain.</strong> That is the whole of the
 * argument for this block, and it came from somebody who does the reading: a hundred poles across a
 * map are a hundred identical poles until each one says which station feeds it, which feeder it is
 * on and who last inspected it. Every kind here is a sign that exists -- LADWP's and SCE's -- and
 * they are told apart the way the real ones are, by shape and colour before anybody is close enough
 * to read the number.
 * <p>
 * One block, one meta, one item. The kind and how far the plate reaches back to what it is bolted to
 * are one listed integer property filled from the tile entity, so the blockstate can be a plain
 * {@code variants} file naming one flat model per plate, reach and facing -- a hundred and twelve
 * models' worth of nothing but a textured slab and a strap. Only the lettering costs anything per
 * frame, and only within forty-eight blocks; see {@code TileRenderUtilitySign}.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class BlockUtilitySign extends BlockIETileProvider<BlockTypes_Signage>
{
	/**
	 * Which of the sixteen plates this is <em>and</em> how far it reaches back to what it is bolted
	 * to, packed into one integer by {@link SignMount}. Listed so a blockstate can select on it, and
	 * filled from the tile entity through {@code IAttachedIntegerProperies} -- both halves are saved
	 * on the tile, not in the meta, because the text has to be there anyway.
	 * <p>
	 * One property rather than two because the model depends on both, and Forge's submaps cannot
	 * name a model from two of them at once -- see {@link SignMount}.
	 */
	public static final PropertyInteger PLATE = PropertyInteger.create(TileEntityUtilitySign.PLATE,
			0, SignMount.packedCount()-1);

	public BlockUtilitySign()
	{
		super("signage", Material.IRON, PropertyEnum.create("type", BlockTypes_Signage.class),
				ItemBlockIEBase.class, IEProperties.FACING_HORIZONTAL, PLATE);
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
		return hasSupport(world, pos.offset(side.getOpposite()), side);
	}

	/**
	 * Whether there is something at {@code support} worth bolting a tag to, looking at its
	 * {@code face}.
	 * <p>
	 * <strong>A pole is not a side-solid block, and it is the thing this block is for.</strong>
	 * Forge's {@code isSideSolid} falls through to "opaque, full cube, no redstone", which a wall
	 * is and a four-pixel pole is not, so the first version of this refused to hang a sign on
	 * anything shaped like the thing signs go on. The second test is what a pole passes: something
	 * solid whose own geometry comes within a standoff's reach of the face -- which lets in poles,
	 * fences and posts and still keeps out torches, flowers and air, none of which are solid
	 * materials.
	 */
	private static boolean hasSupport(IBlockAccess world, BlockPos support, EnumFacing face)
	{
		IBlockState state = world.getBlockState(support);
		if(state.isSideSolid(world, support, face))
			return true;
		if(!state.getMaterial().isSolid()||state.getBlock().isReplaceable(world, support))
			return false;
		return SignMount.gapPixels(state.getBoundingBox(world, support), face) <= SignMount.MAX;
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
		if(!hasSupport(world, pos.offset(facing), facing.getOpposite()))
		{
			dropBlockAsItem(world, pos, state, 0);
			world.setBlockToAir(pos);
			return;
		}
		//The pole is still there but may not be the same shape it was -- a light pole replaced by a
		//wall, or the other way about -- so the standoff is measured again rather than kept.
		((TileEntityUtilitySign)tile).refreshMount();
	}

	@Override
	public void onIEBlockPlacedBy(World world, BlockPos pos, IBlockState state, EnumFacing side,
								  float hitX, float hitY, float hitZ,
								  net.minecraft.entity.EntityLivingBase placer, ItemStack stack)
	{
		super.onIEBlockPlacedBy(world, pos, state, side, hitX, hitY, hitZ, placer, stack);
		//Here rather than in onBlockPlacedBy, and that is the whole of why the first cut measured
		//nothing: ItemBlockIEBase.placeBlockAt calls vanilla's placeBlockAt -- which is what runs
		//onBlockPlacedBy -- and only afterwards calls this, which is where IE sets the tile's
		//facing. A standoff measured before the facing is set is measured against whatever happens
		//to be north of the sign, which is usually air, which is no reach at all.
		TileEntity tile = world.getTileEntity(pos);
		if(tile instanceof TileEntityUtilitySign)
			((TileEntityUtilitySign)tile).refreshMount();
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
		//The item resolves against signage.json's `inventory` variant; the block half has variants
		//of its own and lives in its own file. Both have to exist -- a custom mapping with no
		//matching file is a purple block with nothing in the log.
		return itemBlock?null: "utility_sign";
	}
}
