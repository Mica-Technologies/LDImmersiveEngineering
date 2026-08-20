/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.common.blocks.signage.TileEntityUtilitySign;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for a utility sign's editing window.
 * <p>
 * No slots -- a sign holds letters, not items. Its only job beyond keeping the window open is to
 * <em>be</em> the permission check: {@code MessageSignText} accepts a line only from a player with
 * one of these open on the sign in question, which is what stops a hand-made packet rewriting every
 * pole on a server from across the map.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class ContainerUtilitySign extends Container
{
	public final TileEntityUtilitySign tile;

	public ContainerUtilitySign(InventoryPlayer inventoryPlayer, TileEntityUtilitySign tile)
	{
		this.tile = tile;
	}

	@Override
	public boolean canInteractWith(@Nonnull EntityPlayer player)
	{
		if(tile==null||tile.isInvalid())
			return false;
		return player.getDistanceSq(tile.getPos().getX()+0.5, tile.getPos().getY()+0.5,
				tile.getPos().getZ()+0.5) <= 64;
	}

	@Nonnull
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index)
	{
		return ItemStack.EMPTY;
	}
}
