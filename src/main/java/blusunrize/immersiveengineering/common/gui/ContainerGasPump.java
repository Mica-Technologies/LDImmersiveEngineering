/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasPump;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for the Gas Station Pump's panel.
 * <p>
 * Carries no slots: the pump holds fluid, not items. Its only job beyond keeping the window open
 * is to <em>be</em> the permission check -- {@code MessagePumpSettings} accepts a change only from
 * a player with one of these open, so having this container is what proves the player is standing
 * at the pump they are re-pricing.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class ContainerGasPump extends Container
{
	public final TileEntityGasPump tile;

	public ContainerGasPump(InventoryPlayer inventoryPlayer, TileEntityGasPump tile)
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
