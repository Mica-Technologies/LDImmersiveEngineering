/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.energy.grid.VirtualGrid;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetSync;
import blusunrize.immersiveengineering.common.util.network.MessageGridSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for the Engineer's Network Terminal.
 * <p>
 * It pushes both networks to the holder and accepts nothing back.
 * <p>
 * <strong>It deliberately extends neither {@code ContainerGridBase} nor
 * {@code ContainerFluidNetBase}</strong>, and that is the whole of the read-only enforcement.
 * Both action packets gate on {@code player.openContainer instanceof} their own base class, so a
 * terminal that inherited from either would hand a pocket item the full authority of a console --
 * the ability to re-price, re-route and delete a server's infrastructure from anywhere, with no
 * block to stand at and no way for anyone to notice. Read-only by construction beats read-only by
 * remembering to check.
 * <p>
 * The cost of that choice is this class duplicating the sync timer both bases already have. That is
 * about fifteen lines, and it is the right trade: the alternative couples a permission boundary to
 * an inheritance decision, and those drift apart the first time somebody refactors.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class ContainerNetworkTerminal extends Container
{
	/**
	 * Ticks between pushes. Twice a second, matching the consoles -- a readout does not need more,
	 * and this one may be open in a pocket while its holder walks around.
	 */
	public static final int SYNC_INTERVAL = 10;

	private final EntityPlayer player;
	private int syncTimer;

	public ContainerNetworkTerminal(EntityPlayer player)
	{
		this.player = player;
	}

	@Override
	public void addListener(IContainerListener listener)
	{
		super.addListener(listener);
		//Push immediately, so the window has something to draw on its first frame rather than
		//half a second of "waiting for the server".
		sendSync();
	}

	@Override
	public void detectAndSendChanges()
	{
		super.detectAndSendChanges();
		if(++syncTimer >= SYNC_INTERVAL)
		{
			syncTimer = 0;
			sendSync();
		}
	}

	private void sendSync()
	{
		if(!(player instanceof EntityPlayerMP))
			return;
		//Both, with the live flag: a terminal that showed a correct list of segments in which
		//nothing was ever online would be worse than no terminal.
		ImmersiveEngineering.packetHandler.sendTo(
				new MessageGridSync(VirtualGrid.INSTANCE.writeToNBT(new NBTTagCompound(), true)),
				(EntityPlayerMP)player);
		ImmersiveEngineering.packetHandler.sendTo(
				new MessageFluidNetSync(VirtualFluidNet.INSTANCE.writeToNBT(new NBTTagCompound(), true)),
				(EntityPlayerMP)player);
	}

	@Override
	public boolean canInteractWith(@Nonnull EntityPlayer player)
	{
		//No range check and no block to stand at: the point of the thing is that it works from
		//anywhere. It stays open until the player closes it.
		return true;
	}

	@Nonnull
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index)
	{
		//No slots to shift-click into.
		return ItemStack.EMPTY;
	}
}
