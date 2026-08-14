/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.client.models.IOBJModelCallback;
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
		//IOBJModelCallback.PROPERTY is what lets the Gas Station Pump hide and turn the groups of
		//its OBJ model per block. Without it on the block, getExtendedState throws when it tries
		//to hand the tile to the model -- an unlisted property has to be declared here first.
		super("petroleum_device", Material.IRON,
				PropertyEnum.create("type", BlockTypes_PetroleumDevice.class), ItemBlockIEBase.class,
				IOBJModelCallback.PROPERTY);
		this.setHardness(3.0F);
		this.setResistance(15.0F);
		this.lightOpacity = 0;
		//The wellhead is a valve stack, not a solid cube.
		this.setNotNormalBlock(BlockTypes_PetroleumDevice.WELLHEAD.getMeta());
		//All three propane bodies: none of them fills its block, and all three are drawn cut out.
		for(BlockTypes_PetroleumDevice vessel : new BlockTypes_PetroleumDevice[]{
				BlockTypes_PetroleumDevice.PROPANE_CYLINDER,
				BlockTypes_PetroleumDevice.PROPANE_TANK_UPRIGHT,
				BlockTypes_PetroleumDevice.PROPANE_TANK_TORPEDO})
		{
			this.setNotNormalBlock(vessel.getMeta());
			this.setMetaBlockLayer(vessel.getMeta(), BlockRenderLayer.CUTOUT);
		}
		this.setMetaBlockLayer(BlockTypes_PetroleumDevice.WELLHEAD.getMeta(), BlockRenderLayer.CUTOUT);
		//The assembled pump is a slim cabinet standing two blocks tall, drawn from the lower one.
		//A full-cube block there would let the world light the upper block as though it were solid
		//and leave the pump's head standing in its own shadow.
		this.setNotNormalBlock(BlockTypes_PetroleumDevice.GAS_PUMP.getMeta());
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
	public void breakBlock(World world, BlockPos pos, IBlockState state)
	{
		//Breaking any wall takes the whole tank apart. Leaving the rest formed would give a tank
		//with a hole in it that still reported its full capacity, and the block that vanished is
		//exactly the one nobody would think to look at.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityStorageTank)
			((TileEntityStorageTank)te).disassemble();
		//Breaking either half of an assembled Gas Station Pump takes it apart, so the half left
		//standing goes back to being a loose block rather than a headless pump still drawing a
		//cabinet that is no longer there.
		if(te instanceof TileEntityGasPump)
			((TileEntityGasPump)te).disassemble();
		super.breakBlock(world, pos, state);
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
			case PROPANE_TANK_UPRIGHT:
				return new TileEntityPropaneTankUpright();
			case PROPANE_TANK_TORPEDO:
				return new TileEntityPropaneTankTorpedo();
			case LUBRICATION_MANIFOLD:
				return new TileEntityLubricationManifold();
			case FLARE_STACK:
				return new TileEntityFlareStack();
			case GAS_PUMP:
				return new TileEntityGasPump();
			case FORECOURT_SIGN:
				return new TileEntityForecourtSign();
			case PORTABLE_GENERATOR:
				return new TileEntityPortableGenerator();
			case REINJECTION_WELL:
				return new TileEntityReinjectionWell();
			case STORAGE_TANK_WALL:
				return new TileEntityStorageTank();
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
