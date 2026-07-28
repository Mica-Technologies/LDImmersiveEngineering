/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.common.blocks.BlockIEMultiblock;
import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.Properties;

/**
 * Assembled fluid network structures. Currently only the Fluid Control Console.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class BlockFluidNetMultiblock extends BlockIEMultiblock<BlockTypes_FluidNetMultiblock>
{
	public BlockFluidNetMultiblock()
	{
		super("fluidnet_multiblock", Material.IRON,
				PropertyEnum.create("type", BlockTypes_FluidNetMultiblock.class), ItemBlockIEBase.class,
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
	public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		state = super.getActualState(state, world, pos);
		//The console's face is split across the structure: the upper row carries the screen, the
		//lower row the valve bank. Structure indices 2 and 3 are the upper row.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityFluidConsole)
			state = state.withProperty(IEProperties.BOOLEANS[0],
					FluidConsoleGeometry.isUpperRow(((TileEntityFluidConsole)te).pos));
		return state;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_FluidNetMultiblock type)
	{
		switch(type)
		{
			case FLUID_CONSOLE:
				return new TileEntityFluidConsole();
		}
		return null;
	}
}
