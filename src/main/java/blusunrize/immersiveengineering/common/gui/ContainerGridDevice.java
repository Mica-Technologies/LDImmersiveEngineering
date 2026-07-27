/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.common.blocks.grid.TileEntityGridDevice;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for a Feed or Service Unit's own panel.
 * <p>
 * The console remains the place to manage the grid as a whole, but assigning a freshly
 * placed box to a segment from the console means walking back to it and remembering which
 * segment you had selected. Doing it at the box is the natural gesture, so the same
 * settings are reachable from both.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class ContainerGridDevice extends ContainerGridBase
{
	public final TileEntityGridDevice tile;

	public ContainerGridDevice(InventoryPlayer inventoryPlayer, TileEntityGridDevice tile)
	{
		super(inventoryPlayer.player);
		this.tile = tile;
	}

	@Override
	public boolean canInteractWith(@Nonnull EntityPlayer player)
	{
		if(tile==null||tile.isInvalid())
			return false;
		return isWithinReach(player, tile.getPos().getX(), tile.getPos().getY(), tile.getPos().getZ());
	}
}
