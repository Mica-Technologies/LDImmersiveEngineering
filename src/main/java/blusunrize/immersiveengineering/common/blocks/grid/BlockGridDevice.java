/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.blocks.BlockIETileProvider;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import blusunrize.immersiveengineering.common.util.ChatUtils;
import blusunrize.immersiveengineering.common.util.Utils;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * The virtual grid's world-facing hardware: Feed Unit, Service Unit and Console Housing.
 * <p>
 * A block of its own rather than more metas on {@code metal_device1}, so the whole feature
 * registers and (if ever needed) un-registers as one unit.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class BlockGridDevice extends BlockIETileProvider<BlockTypes_GridDevice>
{
	public BlockGridDevice()
	{
		super("grid_device", Material.IRON, PropertyEnum.create("type", BlockTypes_GridDevice.class),
				ItemBlockIEBase.class, IEProperties.FACING_ALL, IEProperties.BOOLEANS[0]);
		this.setHardness(3.0F);
		this.setResistance(15.0F);
		this.lightOpacity = 0;
		//The utility boxes are small wall-mounted shapes, not full cubes.
		for(BlockTypes_GridDevice box : new BlockTypes_GridDevice[]{BlockTypes_GridDevice.FEED_UNIT,
				BlockTypes_GridDevice.SERVICE_UNIT, BlockTypes_GridDevice.SIGNAL_UNIT})
		{
			this.setNotNormalBlock(box.getMeta());
			this.setMetaBlockLayer(box.getMeta(), BlockRenderLayer.CUTOUT);
		}
	}

	//NOTE: deliberately no custom state mapping. Returning a name here makes IE look for a
	//separate "grid_device_<name>.json" blockstate file; all three metas are described by
	//the single grid_device.json instead.

	@Override
	public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side)
	{
		return state.getValue(property)==BlockTypes_GridDevice.CONSOLE_HOUSING;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
									EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ)
	{
		//Console Housing has no tile entity, so without this a right click is a completely
		//silent no-op and there is nothing in game telling you it still has to be hammered.
		if(state.getValue(property)==BlockTypes_GridDevice.CONSOLE_HOUSING
				&&!world.isRemote&&!player.isSneaking()
				&&!Utils.isHammer(player.getHeldItem(hand)))
		{
			ChatUtils.sendServerNoSpamMessages(player,
					new TextComponentTranslation(Lib.CHAT_INFO+"grid.consoleUnformed"));
			return true;
		}
		return super.onBlockActivated(world, pos, state, player, hand, side, hitX, hitY, hitZ);
	}

	@Override
	public boolean allowHammerHarvest(IBlockState state)
	{
		return true;
	}

	@Override
	public void breakBlock(World world, BlockPos pos, IBlockState state)
	{
		//Drop the grid registration with the block. Without this the console's device list
		//would accumulate permanently offline ghosts for every box ever mined.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityGridDevice)
			((TileEntityGridDevice)te).onBlockBroken();
		super.breakBlock(world, pos, state);
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_GridDevice type)
	{
		switch(type)
		{
			case FEED_UNIT:
				return new TileEntityGridFeed();
			case SERVICE_UNIT:
				return new TileEntityGridService();
			case SIGNAL_UNIT:
				return new TileEntityGridSignal();
			case CONSOLE_HOUSING:
				//Inert until four of them are hammered into a Grid Management Console.
				return null;
		}
		return null;
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		return state.getValue(property)!=BlockTypes_GridDevice.CONSOLE_HOUSING;
	}
}
