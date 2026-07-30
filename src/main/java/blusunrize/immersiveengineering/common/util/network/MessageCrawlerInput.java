/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.entities.EntityHydraulicCrawler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * The Hydraulic Crawler's operator holding a control down.
 * <p>
 * The one thing about this machine that vanilla does <em>not</em> already synchronise. Driving and
 * slewing come free off the rider's movement input and yaw, which is why they need no packet -- but a
 * key that is not a movement key is invisible to the server, so it has to be told.
 * <p>
 * <strong>No entity id in it.</strong> The machine is whatever the sender is riding, taken from the
 * server's own view of that. Trusting a client-supplied id would let anybody move the arm of any
 * crawler in the world, which for a machine that will shortly be demolishing things is not a
 * theoretical concern.
 *
 * @author LDImmersiveEngineering -- vehicles
 */
public class MessageCrawlerInput implements IMessage
{
	private byte flags;

	public MessageCrawlerInput(byte flags)
	{
		this.flags = flags;
	}

	public MessageCrawlerInput()
	{
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		flags = buf.readByte();
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeByte(flags);
	}

	public static class Handler implements IMessageHandler<MessageCrawlerInput, IMessage>
	{
		@Override
		public IMessage onMessage(MessageCrawlerInput message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> {
				Entity ridden = player.getRidingEntity();
				if(!(ridden instanceof EntityHydraulicCrawler))
					return;
				EntityHydraulicCrawler crawler = (EntityHydraulicCrawler)ridden;
				//And only from whoever is actually driving. A second passenger -- or a player riding
				//something that is itself riding the crawler -- must not be able to work the arm.
				if(crawler.getControllingPassenger()!=player)
					return;
				crawler.setControlFlags(message.flags);
			});
			return null;
		}
	}
}
