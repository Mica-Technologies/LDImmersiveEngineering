/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.ItemBlockIEBase;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The conduit block's item, which exists for one gesture: turning an outer corner by clicking the
 * end of the run.
 * <p>
 * Everything else about placing conduit is the block's business -- which face a length clips to
 * is {@link ConduitPlacement#mountFor}, asked through {@code getFacingForPlacement}. But which
 * <em>cell</em> a block lands in is decided here, before the block is asked anything, and the
 * length that continues a run round the edge of a wall belongs one diagonal step away from the
 * cell the click points at. {@link ConduitPlacement#wrapTarget} says where; this only moves the
 * click there. Runs on both sides, and gives both the same answer from the same world.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ItemBlockConduit extends ItemBlockIEBase
{
	public ItemBlockConduit(Block block)
	{
		super(block);
	}

	@Override
	public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand, EnumFacing side,
									  float hitX, float hitY, float hitZ)
	{
		//A box or a feeder goes where it is put. Only a length of conduit continues a run.
		if(getMetadata(player.getHeldItem(hand).getMetadata())==BlockTypes_Conduit.CONDUIT_RUN.getMeta()
				//The cell the click would have filled has to be one it could fill. A click on the end
				//of a run that is up against a block is not this gesture -- nothing lands there at all.
				&&world.getBlockState(pos.offset(side)).getBlock().isReplaceable(world, pos.offset(side)))
		{
			BlockPos support = ConduitPlacement.wrapTarget(pos, side, new TileEntityConduit.Surroundings(world));
			if(support!=null)
				return super.onItemUse(player, world, support, hand, side, hitX, hitY, hitZ);
		}
		return super.onItemUse(player, world, pos, hand, side, hitX, hitY, hitZ);
	}
}
