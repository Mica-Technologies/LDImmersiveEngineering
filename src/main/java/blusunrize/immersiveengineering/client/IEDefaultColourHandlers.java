/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.client;

import blusunrize.immersiveengineering.client.models.smart.ConduitDisguiseModel;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IColouredBlock;
import blusunrize.immersiveengineering.common.items.IEItemInterfaces.IColouredItem;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.color.IBlockColor;
import net.minecraft.client.renderer.color.IItemColor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import javax.annotation.Nullable;

/**
 * @author BluSunrize - 03.10.2016
 */
public class IEDefaultColourHandlers implements IItemColor, IBlockColor
{
	public static IEDefaultColourHandlers INSTANCE = new IEDefaultColourHandlers();

	@Override
	public int colorMultiplier(IBlockState state, @Nullable IBlockAccess worldIn, @Nullable BlockPos pos, int tintIndex)
	{
		//A ground feeder is drawn with another block's quads, so it has to be tinted with that
		//block's colours or a feeder buried in a lawn is a grey square in the middle of it. Asked
		//before the IColouredBlock branch because BlockConduit is one, and answers white.
		Integer worn = ConduitDisguiseModel.disguiseColour(worldIn, pos, tintIndex);
		if(worn!=null)
			return worn;
		if(state.getBlock() instanceof IColouredBlock)
			return ((IColouredBlock)state.getBlock()).getRenderColour(state, worldIn, pos, tintIndex);
		return 0xffffff;
	}

	@Override
	public int colorMultiplier(ItemStack stack, int tintIndex)
	{
		if(stack.getItem() instanceof IColouredItem)
			return ((IColouredItem)stack.getItem()).getColourForIEItem(stack, tintIndex);
		return 0xffffff;
	}
}
