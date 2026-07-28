/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.blocks.petroleum.TileEntityGasPump;
import blusunrize.immersiveengineering.common.gui.ContainerGasPump;
import blusunrize.immersiveengineering.common.util.IELogger;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: a change made on a Gas Station Pump's panel.
 * <p>
 * Two settings, and they are the only two the panel can change: the price, and whether to zero the
 * odometer. Both go through the open-container check, which is what stops a hand-crafted packet
 * re-pricing every forecourt on a server from across the map.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class MessagePumpSettings implements IMessage
{
	private BlockPos pos;
	private int price;
	private boolean resetOdometer;

	public MessagePumpSettings()
	{
	}

	public MessagePumpSettings(BlockPos pos, int price, boolean resetOdometer)
	{
		this.pos = pos;
		this.price = price;
		this.resetOdometer = resetOdometer;
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		pos = BlockPos.fromLong(buf.readLong());
		price = buf.readInt();
		resetOdometer = buf.readBoolean();
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeLong(pos.toLong());
		buf.writeInt(price);
		buf.writeBoolean(resetOdometer);
	}

	public static class Handler implements IMessageHandler<MessagePumpSettings, IMessage>
	{
		@Override
		public IMessage onMessage(MessagePumpSettings message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> {
				if(!(player.openContainer instanceof ContainerGasPump))
				{
					IELogger.warn("Player "+player.getName()+" sent pump settings with no pump open");
					return;
				}
				TileEntity te = player.world.getTileEntity(message.pos);
				//The container's own tile is the authority, not the position in the packet: a
				//player with one pump open must not be able to re-price a different one.
				if(!(te instanceof TileEntityGasPump)
						||te!=((ContainerGasPump)player.openContainer).tile)
					return;
				TileEntityGasPump pump = (TileEntityGasPump)te;
				pump.setPrice(message.price);
				if(message.resetOdometer)
					pump.resetOdometer();
			});
			return null;
		}
	}
}
