/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

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
 * The virtual fluid network's world-facing hardware: Fluid Inlet, Fluid Outlet, Main Valve and
 * Console Housing.
 * <p>
 * A block of its own rather than more metas on an existing one, so the whole feature registers as
 * one unit. The deliberate mirror of {@code BlockGridDevice}.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class BlockFluidNetDevice extends BlockIETileProvider<BlockTypes_FluidNetDevice>
{
	public BlockFluidNetDevice()
	{
		super("fluidnet_device", Material.IRON,
				PropertyEnum.create("type", BlockTypes_FluidNetDevice.class),
				ItemBlockIEBase.class, IEProperties.FACING_ALL, IEProperties.BOOLEANS[0]);
		this.setHardness(3.0F);
		this.setResistance(15.0F);
		this.lightOpacity = 0;
		//The fittings are small wall-mounted shapes, not full cubes.
		for(BlockTypes_FluidNetDevice box : new BlockTypes_FluidNetDevice[]{
				BlockTypes_FluidNetDevice.FLUID_INLET, BlockTypes_FluidNetDevice.FLUID_OUTLET,
				BlockTypes_FluidNetDevice.MAIN_VALVE})
		{
			this.setNotNormalBlock(box.getMeta());
			this.setMetaBlockLayer(box.getMeta(), BlockRenderLayer.CUTOUT);
		}
	}

	//NOTE: deliberately no custom state mapping. Returning a name here makes IE look for a
	//separate "fluidnet_device_<name>.json" blockstate file; all four metas are described by the
	//single fluidnet_device.json instead.

	@Override
	public boolean isSideSolid(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side)
	{
		return state.getValue(property)==BlockTypes_FluidNetDevice.CONSOLE_HOUSING;
	}

	@Override
	public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
									EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ)
	{
		//Console Housing has no tile entity, so without this a right click is a completely silent
		//no-op and there is nothing in game telling you it still has to be hammered.
		if(state.getValue(property)==BlockTypes_FluidNetDevice.CONSOLE_HOUSING
				&&!world.isRemote&&!player.isSneaking()
				&&!Utils.isHammer(player.getHeldItem(hand)))
		{
			ChatUtils.sendServerNoSpamMessages(player,
					new TextComponentTranslation(Lib.CHAT_INFO+"fluidnet.consoleUnformed"));
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
		//Drop the network registration with the block. Without this the console's device list would
		//accumulate permanently offline ghosts for every fitting ever mined.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityFluidNetDevice)
			((TileEntityFluidNetDevice)te).onBlockBroken();
		super.breakBlock(world, pos, state);
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_FluidNetDevice type)
	{
		switch(type)
		{
			case FLUID_INLET:
				return new TileEntityFluidInlet();
			case FLUID_OUTLET:
				return new TileEntityFluidOutlet();
			case MAIN_VALVE:
				return new TileEntityFluidValve();
			case CONSOLE_HOUSING:
				//Inert until four of them are hammered into a Fluid Control Console.
				return null;
		}
		return null;
	}

	@Override
	public boolean hasTileEntity(IBlockState state)
	{
		return state.getValue(property)!=BlockTypes_FluidNetDevice.CONSOLE_HOUSING;
	}
}
