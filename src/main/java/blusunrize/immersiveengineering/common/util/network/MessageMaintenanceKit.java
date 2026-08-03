/*
 * BluSunrize
 * Copyright (c) 2018
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.api.tool.IConfigurableTool;
import blusunrize.immersiveengineering.common.gui.ContainerMaintenanceKit;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;

public class MessageMaintenanceKit implements IMessage
{
	EntityEquipmentSlot slot;
	NBTTagCompound nbt;

	public MessageMaintenanceKit(EntityEquipmentSlot slot, NBTTagCompound nbt)
	{
		this.slot = slot;
		this.nbt = nbt;
	}

	public MessageMaintenanceKit()
	{
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		//	=================================
		//	fromString throws, and this runs on the netty thread.
		//	=================================
		//
		// EntityEquipmentSlot.fromString raises IllegalArgumentException for anything it does not
		// recognise, so a one-character change to this string was an exception in the network
		// pipeline rather than a rejected action. Resolved by hand instead, answering null for an
		// unknown name -- which costs nothing, because the handler never reads this field.
		this.slot = slotByName(ByteBufUtils.readUTF8String(buf));
		this.nbt = ByteBufUtils.readTag(buf);
	}

	/**
	 * @return the slot of that name, or null if there is none
	 */
	@Nullable
	private static EntityEquipmentSlot slotByName(@Nullable String name)
	{
		if(name==null)
			return null;
		for(EntityEquipmentSlot slot : EntityEquipmentSlot.values())
			if(slot.getName().equals(name))
				return slot;
		return null;
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		//The field is nullable now that decoding no longer throws on an unknown name, so the send
		//path has to tolerate one rather than NPE on the way out.
		ByteBufUtils.writeUTF8String(buf,
				this.slot==null?EntityEquipmentSlot.MAINHAND.getName(): this.slot.getName());
		ByteBufUtils.writeTag(buf, this.nbt);
	}

	public static class Handler implements IMessageHandler<MessageMaintenanceKit, IMessage>
	{
		@Override
		public IMessage onMessage(MessageMaintenanceKit message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> {
				if(player.openContainer instanceof ContainerMaintenanceKit)
				{
					ItemStack tool = ((ContainerMaintenanceKit)player.openContainer).inventorySlots.get(0).getStack();
					if(!tool.isEmpty()&&tool.getItem() instanceof IConfigurableTool)
						for(String key : message.nbt.getKeySet())
						{
							if(key.startsWith("b_"))
								((IConfigurableTool)tool.getItem()).applyConfigOption(tool, key.substring(2), message.nbt.getBoolean(key));
							else if(key.startsWith("f_"))
								((IConfigurableTool)tool.getItem()).applyConfigOption(tool, key.substring(2), message.nbt.getFloat(key));
						}
				}
			});
			return null;
		}
	}
}