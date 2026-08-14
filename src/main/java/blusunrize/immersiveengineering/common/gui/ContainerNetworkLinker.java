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
import blusunrize.immersiveengineering.common.items.ItemNetworkLinker;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetSync;
import blusunrize.immersiveengineering.common.util.network.MessageGridSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;

/**
 * Server-side anchor for a linker's chooser window.
 * <p>
 * <strong>It deliberately extends neither {@code ContainerGridBase} nor
 * {@code ContainerFluidNetBase}</strong>, for the same reason {@code ContainerNetworkTerminal}
 * does not: both console action packets accept anything from a player holding one of those open, so
 * inheriting from either would hand a pocket item the authority to rename, re-price, re-route and
 * delete a server's infrastructure from anywhere. The one thing this window may do arrives on its
 * own packet, {@code MessageLinkerSelect}, which gates on this class and does exactly one thing.
 * <p>
 * The list it draws is a list of names and colours, which is why it pushes the network state at all:
 * a client has no copy of either network unless something is open.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public class ContainerNetworkLinker extends Container
{
	/**
	 * Ticks between pushes. Twice a second, matching the consoles and the terminal.
	 */
	public static final int SYNC_INTERVAL = 10;

	private final EntityPlayer player;
	/**
	 * Which hand the tool is in. Kept so the packet edits the stack the window was opened from
	 * rather than whatever happens to be held when it is closed.
	 */
	public final EntityEquipmentSlot slot;
	/**
	 * Which network this window is choosing from. Taken from the item at open time and re-derived
	 * from the held stack by the packet, so swapping the tool out mid-window cannot cross the wires.
	 */
	public final boolean fluid;

	public ContainerNetworkLinker(EntityPlayer player, EntityEquipmentSlot slot, boolean fluid)
	{
		this.player = player;
		this.slot = slot;
		this.fluid = fluid;
	}

	/**
	 * @return the tool this window was opened from, or an empty stack if it is no longer there
	 */
	public ItemStack getTool(EntityPlayer holder)
	{
		ItemStack stack = holder.getItemStackFromSlot(slot);
		return stack.getItem() instanceof ItemNetworkLinker&&ItemNetworkLinker.isFluid(stack)==fluid
				?stack: ItemStack.EMPTY;
	}

	@Override
	public void addListener(IContainerListener listener)
	{
		super.addListener(listener);
		//Push immediately, so the window has a list to draw on its first frame rather than half a
		//second of "waiting for the server".
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

	private int syncTimer;

	private void sendSync()
	{
		if(!(player instanceof EntityPlayerMP))
			return;
		//Only the network being chosen from. The terminal sends both because it shows both; this
		//window shows one, and a packet nobody reads is a packet not worth sending.
		if(fluid)
			ImmersiveEngineering.packetHandler.sendTo(
					new MessageFluidNetSync(VirtualFluidNet.INSTANCE.writeToNBT(new NBTTagCompound(), true)),
					(EntityPlayerMP)player);
		else
			ImmersiveEngineering.packetHandler.sendTo(
					new MessageGridSync(VirtualGrid.INSTANCE.writeToNBT(new NBTTagCompound(), true)),
					(EntityPlayerMP)player);
	}

	@Override
	public boolean canInteractWith(@Nonnull EntityPlayer player)
	{
		//It closes when the tool leaves the hand it was opened from, and not otherwise: the window
		//exists to be answered in one click and then dismissed, and there is no block to walk away
		//from.
		return !getTool(player).isEmpty();
	}

	@Nonnull
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index)
	{
		//No slots to shift-click into.
		return ItemStack.EMPTY;
	}
}
