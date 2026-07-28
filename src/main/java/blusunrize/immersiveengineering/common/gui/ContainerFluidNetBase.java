/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.gui;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.fluid.network.VirtualFluidNet;
import blusunrize.immersiveengineering.common.util.network.MessageFluidNetSync;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IContainerListener;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nonnull;

/**
 * Shared plumbing for the windows that can reconfigure the fluid network.
 * <p>
 * Carries no slots -- none of them holds an inventory. Its jobs are to keep the viewer's copy of
 * the network up to date and to <em>be</em> the permission check: {@code MessageFluidNetAction}
 * accepts an action only from a player with one of these open, so having this container is what
 * proves the player is standing at something that may edit the network.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public abstract class ContainerFluidNetBase extends Container
{
	/**
	 * Ticks between state pushes while a window is open. Throughput changes every tick, so there is
	 * nothing to diff -- twice a second is plenty for a readout and keeps this off the per-tick
	 * budget.
	 */
	public static final int SYNC_INTERVAL = 10;

	protected final EntityPlayer player;
	private int syncTimer;

	protected ContainerFluidNetBase(EntityPlayer player)
	{
		this.player = player;
	}

	@Override
	public void addListener(IContainerListener listener)
	{
		super.addListener(listener);
		//Push immediately, so the window has something to draw on its first frame.
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

	/**
	 * Pushes the current state immediately and restarts the periodic timer.
	 * <p>
	 * Called the instant an action is applied. Without it a control could sit showing its old value
	 * for up to {@link #SYNC_INTERVAL} ticks after being clicked, which reads as the button simply
	 * not working.
	 */
	public void syncNow()
	{
		syncTimer = 0;
		sendSync();
	}

	private void sendSync()
	{
		if(!(player instanceof EntityPlayerMP))
			return;
		//The live flag is the whole point of this packet: without it the window would draw a
		//correct list of mains and fittings in which nothing is ever online, nothing has throughput
		//and every graph is flat.
		NBTTagCompound nbt = VirtualFluidNet.INSTANCE.writeToNBT(new NBTTagCompound(), true);
		ImmersiveEngineering.packetHandler.sendTo(new MessageFluidNetSync(nbt), (EntityPlayerMP)player);
	}

	@Nonnull
	@Override
	public ItemStack transferStackInSlot(EntityPlayer player, int index)
	{
		//No slots to shift-click into.
		return ItemStack.EMPTY;
	}

	/**
	 * @return true if {@code player} is close enough to {@code x, y, z} to keep the window open
	 */
	protected boolean isWithinReach(EntityPlayer player, double x, double y, double z)
	{
		return player.getDistanceSq(x+0.5, y+0.5, z+0.5) <= 64;
	}
}
