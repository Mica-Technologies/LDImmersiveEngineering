/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.items;

import blusunrize.immersiveengineering.api.Lib;
import blusunrize.immersiveengineering.common.CommonProxy;
import blusunrize.immersiveengineering.common.items.IEItemInterfaces.IGuiItem;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The Engineer's Network Terminal: both networks in a pocket, read-only.
 * <p>
 * A console is a wall you have to walk to. That is right for <em>changing</em> things --
 * infrastructure should be managed from somewhere, not from anywhere -- but it is wrong for the
 * question people actually ask most often, which is "is the refinery still running". This answers
 * that from wherever you are standing and can do nothing else.
 * <p>
 * <strong>Read-only is enforced by the container, not by this item.</strong>
 * {@code ContainerNetworkTerminal} extends neither console base class, and both action packets gate
 * on the open container's type -- so there is no path from here to a mutation, whatever the client
 * sends. See that class for why the check lives there rather than here.
 * <p>
 * One item for both networks rather than one each. They are read together far more often than
 * separately -- a plant that has stopped is usually a fuel problem or a power problem and you do
 * not know which yet -- and two nearly-identical items would be a worse answer to that than two
 * tabs.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class ItemNetworkTerminal extends ItemIEBase implements IGuiItem
{
	public ItemNetworkTerminal()
	{
		super("network_terminal", 1);
	}

	@Override
	public int getGuiID(ItemStack stack)
	{
		return Lib.GUIID_NetworkTerminal;
	}

	@Override
	public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand)
	{
		if(!world.isRemote)
			CommonProxy.openGuiForItem(player, hand==EnumHand.MAIN_HAND
					?EntityEquipmentSlot.MAINHAND: EntityEquipmentSlot.OFFHAND);
		return new ActionResult<>(EnumActionResult.SUCCESS, player.getHeldItem(hand));
	}

	@Override
	public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
							   ITooltipFlag flag)
	{
		//Said on the item rather than only in the manual, because "why can I not switch this
		//segment off from here" is the first thing anyone will try.
		tooltip.add(TextFormatting.GRAY+"Reads the power grid and the fluid network."+TextFormatting.RESET);
		tooltip.add(TextFormatting.DARK_GRAY+"Read-only -- changes are made at a console."
				+TextFormatting.RESET);
	}
}
