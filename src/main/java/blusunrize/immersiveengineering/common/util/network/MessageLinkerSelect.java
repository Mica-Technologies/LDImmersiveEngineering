/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.common.gui.ContainerNetworkLinker;
import blusunrize.immersiveengineering.common.util.link.NetworkLinker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client to server: the one thing a linker's chooser window may do.
 * <p>
 * Deliberately not an operation code with arguments. The linker windows exist so a player can wire
 * a street without walking to a console, and a pocket item that could do anything a console can do
 * would be a hole in a permission model that otherwise makes people stand at the hardware they are
 * reconfiguring. So this packet carries a network id and nothing else, the handler re-checks the
 * lock rather than trusting that the list it came from was filtered, and the only container it will
 * answer to is {@link ContainerNetworkLinker}.
 * <p>
 * A null id means "empty the tool", which is the same gesture as sneak-rightclicking the air and
 * exists so the window has a way out that does not require closing it and finding some sky.
 *
 * @author LDImmersiveEngineering -- network linkers
 */
public class MessageLinkerSelect implements IMessage
{
	@Nullable
	private UUID chosen;

	public MessageLinkerSelect()
	{
	}

	public MessageLinkerSelect(@Nullable UUID chosen)
	{
		this.chosen = chosen;
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		chosen = buf.readBoolean()?new UUID(buf.readLong(), buf.readLong()): null;
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeBoolean(chosen!=null);
		if(chosen!=null)
		{
			buf.writeLong(chosen.getMostSignificantBits());
			buf.writeLong(chosen.getLeastSignificantBits());
		}
	}

	public static class Handler implements IMessageHandler<MessageLinkerSelect, IMessage>
	{
		@Override
		public IMessage onMessage(MessageLinkerSelect message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			player.getServerWorld().addScheduledTask(() -> apply(message, player));
			return null;
		}

		private static void apply(MessageLinkerSelect message, EntityPlayerMP player)
		{
			//The trust boundary. Having this container open is what proves the sender is holding a
			//linker; the slot it remembers is what proves the stack being written is the one the
			//window was opened from rather than whatever is in hand now.
			if(!(player.openContainer instanceof ContainerNetworkLinker))
				return;
			ContainerNetworkLinker container = (ContainerNetworkLinker)player.openContainer;
			ItemStack tool = container.getTool(player);
			if(tool.isEmpty())
				return;
			NetworkLinker.select(tool, player, message.chosen);
			player.closeScreen();
		}
	}
}
