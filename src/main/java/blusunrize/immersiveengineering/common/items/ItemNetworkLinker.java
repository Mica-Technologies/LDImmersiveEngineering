/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.blocks.fluidnet.TileEntityFluidNetDevice;
import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridDevice;
import blusunrize.immersiveengineering.common.items.IEItemInterfaces.IGuiItem;
import blusunrize.immersiveengineering.common.util.link.NetworkLinker;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * The Grid Linker and the Fluid Linker: pick a network once, then wire a street.
 * <p>
 * A console is the right place to <em>manage</em> infrastructure and the wrong place to
 * <em>build</em> it. Assigning thirty service units one panel at a time, or walking back to a wall
 * between each one, is the complaint this item answers, and the shape of the answer is Flux
 * Networks': rightclick a box, choose a network from a short list, and every rightclick after that
 * links the box you clicked. Sneak-rightclick to choose again; sneak-rightclick the air to put the
 * tool back in its box.
 * <p>
 * <strong>Two items, one class.</strong> The playtest asked for a separate tool per network and it
 * is right to have one -- a tool that silently did the wrong network would be worse than no tool --
 * but they are the same object with a different label on it, so they are two metadata variants
 * rather than two registrations. That also keeps one window, one container and one packet instead
 * of two of each.
 * <p>
 * The tool adds to the console and the per-device panel rather than replacing either. Both still
 * work exactly as they did; this is the gesture for the third case, which is having a stack of
 * boxes in one hand and a street to walk down.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public class ItemNetworkLinker extends ItemIEBase implements IGuiItem
{
	public static final int META_GRID = 0;
	public static final int META_FLUID = 1;

	public ItemNetworkLinker()
	{
		super("network_linker", 1, "grid", "fluid");
	}

	/**
	 * @return true if that stack is a linker for the fluid network rather than the power grid
	 */
	public static boolean isFluid(ItemStack stack)
	{
		return is(stack, META_FLUID);
	}

	/**
	 * @return true if that stack is the linker for the power grid
	 */
	public static boolean isGrid(ItemStack stack)
	{
		return is(stack, META_GRID);
	}

	private static boolean is(ItemStack stack, int meta)
	{
		return stack!=null&&!stack.isEmpty()&&stack.getItem()==IEContent.itemNetworkLinker
				&&stack.getMetadata()==meta;
	}

	/**
	 * @return true if that stack is the linker matching the tile's network, and so should claim a
	 * click on it
	 */
	public static boolean matches(ItemStack stack, TileEntity tile)
	{
		if(tile instanceof TileEntityGridDevice)
			return isGrid(stack);
		if(tile instanceof TileEntityFluidNetDevice)
			return isFluid(stack);
		return false;
	}

	@Override
	public int getGuiID(ItemStack stack)
	{
		return Lib.GUIID_NetworkLinker;
	}

	//	=================================
	//		GESTURES
	//	=================================

	@Override
	@Nonnull
	public EnumActionResult onItemUse(EntityPlayer player, World world, @Nonnull BlockPos pos,
									  @Nonnull EnumHand hand, @Nonnull EnumFacing side,
									  float hitX, float hitY, float hitZ)
	{
		//This path is the *sneaking* click. A plain click on a box never reaches an item's onItemUse
		//-- the block claims it first -- so the assignment gesture lives in the two device tiles and
		//this one is only ever "let me choose again". See TileEntityGridDevice.interact.
		ItemStack stack = player.getHeldItem(hand);
		if(!player.isSneaking())
			return EnumActionResult.PASS;
		TileEntity tile = world.getTileEntity(pos);
		if(!matches(stack, tile))
			return EnumActionResult.PASS;
		if(!world.isRemote)
			NetworkLinker.openChooser(stack, player, hand, world, pos);
		return EnumActionResult.SUCCESS;
	}

	@Override
	@Nonnull
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, @Nonnull EnumHand hand)
	{
		ItemStack stack = player.getHeldItem(hand);
		if(!world.isRemote)
		{
			//Sneak on nothing empties the tool. Being able to drop a selection without hunting for a
			//particular block to click is what makes the gesture safe to experiment with -- the same
			//bargain the voltmeter's quick-assign already makes.
			if(player.isSneaking())
				NetworkLinker.clear(stack, player);
			else
				NetworkLinker.openChooser(stack, player, hand, null, null);
		}
		return new ActionResult<>(EnumActionResult.SUCCESS, stack);
	}

	@Override
	public boolean doesSneakBypassUse(ItemStack stack, net.minecraft.world.IBlockAccess world,
									  BlockPos pos, EntityPlayer player)
	{
		//False on purpose: sneaking must reach onItemUse above rather than the block's own
		//interaction, which is what makes sneak mean "choose again" on a box that is already linked.
		return false;
	}

	//	=================================
	//		READOUT
	//	=================================

	@Override
	public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
							   ITooltipFlag flag)
	{
		boolean fluid = stack.getMetadata()==META_FLUID;
		if(NetworkLinker.hasSelection(stack))
		{
			//A loaded linker behaves differently on every click, so what it is holding has to be
			//visible without clicking something to find out.
			tooltip.add(TextFormatting.GREEN+"Holding: "+TextFormatting.WHITE
					+NetworkLinker.describeSelection(stack)+TextFormatting.RESET);
			tooltip.add(TextFormatting.DARK_GRAY+"Rightclick a "+(fluid?"fitting": "box")
					+" to link it."+TextFormatting.RESET);
		}
		else
			tooltip.add(TextFormatting.GRAY+"Rightclick a "+(fluid?"fitting": "box")
					+" to choose a "+(fluid?"main": "segment")+"."+TextFormatting.RESET);
		tooltip.add(TextFormatting.DARK_GRAY+"Sneak-rightclick the air to clear it."
				+TextFormatting.RESET);
	}
}
