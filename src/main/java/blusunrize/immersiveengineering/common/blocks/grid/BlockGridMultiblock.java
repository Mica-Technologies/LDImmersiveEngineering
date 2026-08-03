/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.grid;

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
 * Assembled grid structures. Currently only the Grid Management Console.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class BlockGridMultiblock extends BlockIEMultiblock<BlockTypes_GridMultiblock>
{
	public BlockGridMultiblock()
	{
		super("grid_multiblock", Material.IRON,
				PropertyEnum.create("type", BlockTypes_GridMultiblock.class), ItemBlockIEBase.class,
				IEProperties.BOOLEANS[0], IEProperties.BOOLEANS[1], Properties.AnimationProperty,
				IEProperties.OBJ_TEXTURE_REMAP);
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
		//The console's face is split across the structure: the upper row carries the screen,
		//the lower row the switch bank. Structure indices 2 and 3 are the upper row.
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityGridConsole)
		{
			int index = ((TileEntityGridConsole)te).pos;
			state = state.withProperty(IEProperties.BOOLEANS[0], GridConsoleGeometry.isUpperRow(index));
			//And which half of the screen it wears. The blockstate applies boolean1 before boolean0,
			//so the lower row's own entry puts the switch panel back over whichever half this chose --
			//see the file, where that ordering is the whole mechanism.
			state = state.withProperty(IEProperties.BOOLEANS[1], GridConsoleGeometry.isRightColumn(index));
		}
		return state;
	}

	@Override
	public TileEntity createBasicTE(World world, BlockTypes_GridMultiblock type)
	{
		switch(type)
		{
			case GRID_CONSOLE:
				return new TileEntityGridConsole();
		}
		return null;
	}
}
