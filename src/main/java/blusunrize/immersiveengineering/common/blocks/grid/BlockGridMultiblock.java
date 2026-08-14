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
				IEProperties.BOOLEANS[0], Properties.AnimationProperty,
				IEProperties.OBJ_TEXTURE_REMAP);
		setHardness(3.0F);
		setResistance(15.0F);
		//The console is one OBJ model drawn by its master across all four of its blocks, so
		//none of them may be a normal cube: an opaque block here culls the faces of the model
		//standing in front of it and lights the whole console from the master's corner. Every
		//IE multiblock that renders as one piece does exactly this -- see BlockMetalMultiblocks.
		setAllNotNormalBlock();
		lightOpacity = 0;
	}

	@Override
	public EnumPushReaction getPushReaction(IBlockState state)
	{
		return EnumPushReaction.BLOCK;
	}

	/**
	 * boolean0 is "the screen is lit".
	 * <p>
	 * The console is one model now, drawn entirely by its master, so nothing here has to say
	 * which quarter of a picture a block is wearing any more -- that was what boolean0 and
	 * boolean1 used to carry, and it was the mechanism behind a console that looked like two
	 * consoles. The one thing still worth varying per state is whether the display is alive,
	 * which the blockstate does by swapping the screen material's texture.
	 */
	@Override
	public IBlockState getActualState(IBlockState state, IBlockAccess world, BlockPos pos)
	{
		state = super.getActualState(state, world, pos);
		TileEntity te = world.getTileEntity(pos);
		if(te instanceof TileEntityGridConsole)
			state = state.withProperty(IEProperties.BOOLEANS[0], ((TileEntityGridConsole)te).isPowered());
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
