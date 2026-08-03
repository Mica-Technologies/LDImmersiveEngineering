/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.items.ItemIEShield;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MessageMagnetEquip implements IMessage
{
	int fetchSlot;

	public MessageMagnetEquip(int fetch)
	{
		this.fetchSlot = fetch;
	}

	public MessageMagnetEquip()
	{
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		this.fetchSlot = buf.readInt();
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeInt(this.fetchSlot);
	}

	public static class Handler implements IMessageHandler<MessageMagnetEquip, IMessage>
	{
		@Override
		public IMessage onMessage(MessageMagnetEquip message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> {
				ItemStack held = player.getHeldItem(EnumHand.OFF_HAND);
				if(message.fetchSlot >= 0)
				{
					//	=================================
					//	The slot came off the wire.
					//	=================================
					//
					// It was bounded below and not above, so mainInventory.get(999999) threw inside
					// a scheduled task -- an exception on the server thread, from a packet anybody
					// can send, at any time.
					if(!isValidSlot(player, message.fetchSlot))
						return;
					ItemStack s = player.inventory.mainInventory.get(message.fetchSlot);
					if(isMagnetShield(s))
					{
						((ItemIEShield)s.getItem()).getUpgrades(s).setInteger("prevSlot", message.fetchSlot);
						player.inventory.mainInventory.set(message.fetchSlot, held);
						player.setHeldItem(EnumHand.OFF_HAND, s);
					}
				}
				else
				{
					//	=================================
					//	And this branch trusted the offhand.
					//	=================================
					//
					// It cast whatever was there to ItemIEShield with no check of either kind. An
					// empty offhand is Items.AIR, so simply sending a negative slot while holding
					// nothing was a ClassCastException on the server thread -- no shield, no magnet
					// upgrade and no gesture required.
					if(!isMagnetShield(held))
						return;
					ItemIEShield shield = (ItemIEShield)held.getItem();
					//Bounded like the wire-supplied one. This comes out of the item's own NBT, which
					//survives being edited in creative or by another mod, so it is no more
					//trustworthy than the packet was.
					int prevSlot = shield.getUpgrades(held).getInteger("prevSlot");
					if(!isValidSlot(player, prevSlot))
					{
						//Clear the stale tag rather than leaving the shield permanently unable to
						//swap back.
						shield.getUpgrades(held).removeTag("prevSlot");
						return;
					}
					ItemStack s = player.inventory.mainInventory.get(prevSlot);
					player.inventory.mainInventory.set(prevSlot, held);
					player.setHeldItem(EnumHand.OFF_HAND, s);
					shield.getUpgrades(held).removeTag("prevSlot");
				}
			});
			return null;
		}

		private static boolean isValidSlot(EntityPlayerMP player, int slot)
		{
			return slot >= 0&&slot < player.inventory.mainInventory.size();
		}

		/**
		 * @return true only for a real shield carrying the magnet upgrade
		 * <p>
		 * Both halves matter: the {@code instanceof} stops the cast throwing, and the upgrade check
		 * is what makes this a gesture a player earned rather than one any client can perform.
		 */
		private static boolean isMagnetShield(ItemStack stack)
		{
			return !stack.isEmpty()&&stack.getItem() instanceof ItemIEShield
					&&((ItemIEShield)stack.getItem()).getUpgrades(stack).getBoolean("magnet");
		}
	}
}