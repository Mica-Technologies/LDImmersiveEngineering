/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.blocks.signage.TileEntityUtilitySign;
import blusunrize.immersiveengineering.common.blocks.signage.UtilitySignKind;
import blusunrize.immersiveengineering.common.gui.ContainerUtilitySign;
import blusunrize.immersiveengineering.common.util.IELogger;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client to server: what somebody typed on a utility sign, and which plate they typed it on.
 * <p>
 * Sent once, when the window is closed or Done is pressed, rather than on every keystroke -- a
 * player typing a pole number should not be a packet per character.
 * <p>
 * The kind travels with the text because the editing window can change it too: picking a shape in
 * the window is the same gesture as hitting it with the hammer, and somebody who has just cycled to
 * an oval to see how it looks would not expect it to snap back when they press Done.
 *
 * @author LDImmersiveEngineering -- signage
 */
public class MessageSignText implements IMessage
{
	private BlockPos pos;
	private int kind;
	private String[] lines;

	public MessageSignText()
	{
	}

	public MessageSignText(BlockPos pos, int kind, String[] lines)
	{
		this.pos = pos;
		this.kind = kind;
		this.lines = lines;
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		pos = BlockPos.fromLong(buf.readLong());
		kind = buf.readInt();
		lines = new String[UtilitySignKind.MAX_LINES];
		for(int i = 0; i < lines.length; i++)
			//Length-capped at the far end as well as here: ByteBufUtils will happily carry a
			//megabyte of text somebody hand-built, and the tile clips what it is given.
			lines[i] = ByteBufUtils.readUTF8String(buf);
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeLong(pos.toLong());
		buf.writeInt(kind);
		for(int i = 0; i < UtilitySignKind.MAX_LINES; i++)
			ByteBufUtils.writeUTF8String(buf, i < lines.length&&lines[i]!=null?lines[i]: "");
	}

	public static class Handler implements IMessageHandler<MessageSignText, IMessage>
	{
		@Override
		public IMessage onMessage(MessageSignText message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> {
				if(!(player.openContainer instanceof ContainerUtilitySign))
				{
					IELogger.warn("Player "+player.getName()+" sent sign text with no sign open");
					return;
				}
				TileEntity te = player.world.getTileEntity(message.pos);
				//The container's own tile is the authority, not the position in the packet: a player
				//with one sign open must not be able to rewrite a different one.
				if(!(te instanceof TileEntityUtilitySign)
						||te!=((ContainerUtilitySign)player.openContainer).tile)
					return;
				TileEntityUtilitySign sign = (TileEntityUtilitySign)te;
				sign.setKind(UtilitySignKind.byIndex(message.kind));
				for(int i = 0; i < message.lines.length; i++)
					sign.setLine(i, message.lines[i]);
				sign.markDirty();
				sign.markContainingBlockForUpdate(null);
			});
			return null;
		}
	}
}
