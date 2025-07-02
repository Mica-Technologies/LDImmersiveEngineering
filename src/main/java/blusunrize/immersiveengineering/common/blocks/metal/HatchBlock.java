/*
 * BluSunrize
 * Copyright (c) 2025
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.api.IEProperties;
import blusunrize.immersiveengineering.api.utils.CapabilityUtils;
import blusunrize.immersiveengineering.common.blocks.IEBaseBlock;
import com.google.common.collect.ImmutableMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition.Builder;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.Map;

public class HatchBlock extends IEBaseBlock
{
	public HatchBlock(Properties props)
	{
		super(props);
	}

	@Override
	protected BlockState getInitDefaultState()
	{
		return super.getInitDefaultState();
	}

	@Nullable
	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context)
	{
		BlockState state = super.getStateForPlacement(context);
		if(state==null)
			return null;
		state = state.setValue(IEProperties.FACING_ALL, context.getClickedFace());
		return state;
	}

	@Override
	protected void createBlockStateDefinition(Builder<Block, BlockState> builder)
	{
		super.createBlockStateDefinition(builder);
		builder.add(IEProperties.FACING_ALL, BlockStateProperties.WATERLOGGED);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult)
	{
		if(!player.getMainHandItem().isEmpty())
			return InteractionResult.PASS;
		Direction dir = state.getValue(IEProperties.FACING_ALL);
		IItemHandler itemHandler = CapabilityUtils.findItemHandlerAtPos(level, pos.relative(dir.getOpposite()), dir, true);
		if(itemHandler!=null)
			for(int i = 0; i < itemHandler.getSlots(); i++)
			{
				ItemStack extractItem = itemHandler.extractItem(i, 64, false);
				if(!extractItem.isEmpty())
				{
					player.setItemInHand(InteractionHand.MAIN_HAND, extractItem);
					return InteractionResult.sidedSuccess(level.isClientSide);
				}
			}
		return super.useWithoutItem(state, level, pos, player, hitResult);
	}

	private static final Map<Direction, VoxelShape> SHAPES = ImmutableMap.<Direction, VoxelShape>builder()
			.put(Direction.DOWN, Shapes.box(.1875, .875, .1875, .8125, 1.0625, .8125))
			.put(Direction.UP, Shapes.box(.1875, -.0625, .1875, .8125, .125, .8125))
			.put(Direction.NORTH, Shapes.box(.1875, .1875, .875, .8125, .8125, 1.0625))
			.put(Direction.SOUTH, Shapes.box(.1875, .1875, -.0625, .8125, .8125, .125))
			.put(Direction.WEST, Shapes.box(.875, .1875, .1875, 1.0625, .8125, .8125))
			.put(Direction.EAST, Shapes.box(-.0625, .1875, .1875, .125, .8125, .8125))
			.build();

	@Override
	public VoxelShape getShape(BlockState state, BlockGetter worldIn, BlockPos pos, CollisionContext context)
	{
		return SHAPES.get(state.getValue(IEProperties.FACING_ALL));
	}
}
