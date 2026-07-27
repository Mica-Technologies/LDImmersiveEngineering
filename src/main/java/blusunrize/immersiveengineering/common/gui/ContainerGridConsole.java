/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridConsole;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for the Grid Management Console GUI.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class ContainerGridConsole extends ContainerGridBase
{
	public final TileEntityGridConsole tile;

	public ContainerGridConsole(InventoryPlayer inventoryPlayer, TileEntityGridConsole tile)
	{
		super(inventoryPlayer.player);
		this.tile = tile;
	}

	@Override
	public boolean canInteractWith(@Nonnull EntityPlayer player)
	{
		if(tile==null||tile.isInvalid()||!tile.formed)
			return false;
		return isWithinReach(player, tile.getPos().getX(), tile.getPos().getY(), tile.getPos().getZ());
	}
}
