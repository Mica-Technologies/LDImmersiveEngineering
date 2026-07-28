/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.util.fluidnet.ClientFluidNetCache;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Server to client: the whole virtual fluid network state, for a player with a Fluid Control
 * Console GUI open.
 * <p>
 * Sent only to players actually viewing a console, twice a second, so it never touches the general
 * per-tick bandwidth. The payload is the same NBT the world save uses, which keeps client and
 * server on one model instead of two.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class MessageFluidNetSync implements IMessage
{
	private NBTTagCompound nbt;

	public MessageFluidNetSync()
	{
	}

	public MessageFluidNetSync(NBTTagCompound nbt)
	{
		this.nbt = nbt;
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		nbt = ByteBufUtils.readTag(buf);
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		ByteBufUtils.writeTag(buf, nbt);
	}

	public static class Handler implements IMessageHandler<MessageFluidNetSync, IMessage>
	{
		@Override
		@SideOnly(Side.CLIENT)
		public IMessage onMessage(MessageFluidNetSync message, MessageContext ctx)
		{
			Minecraft.getMinecraft().addScheduledTask(() -> {
				if(message.nbt!=null)
					ClientFluidNetCache.accept(message.nbt);
			});
			return null;
		}
	}
}
