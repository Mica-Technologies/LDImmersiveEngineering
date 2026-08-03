/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.ApiUtils;
import blusunrize.immersiveengineering.api.crafting.IngredientStack;
import blusunrize.immersiveengineering.api.shader.ShaderRegistry;
import blusunrize.immersiveengineering.common.util.ItemNBTHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Collection;

public class MessageShaderManual implements IMessage
{
	MessageType key;
	String[] args;

	public MessageShaderManual(MessageType key, String... args)
	{
		this.key = key;
		this.args = args;
	}

	public MessageShaderManual()
	{
	}

	/**
	 * The most a single message may carry. The manual sends one shader name; the SYNC reply sends a
	 * player's whole collection, which is bounded by the registry.
	 */
	private static final int MAX_ARGS = 4096;

	@Override
	public void fromBytes(ByteBuf buf)
	{
		//	=================================
		//	Decoding runs on the netty thread.
		//	=================================
		//
		// values()[readInt()] threw ArrayIndexOutOfBounds straight off the wire -- before any
		// handler, before addScheduledTask, on the network thread rather than the server one. An
		// unrecognised type now reads as SYNC, which is the harmless one: it asks the server what
		// this player already has and does nothing else.
		int ordinal = buf.readInt();
		this.key = ordinal >= 0&&ordinal < MessageType.VALUES.length
				?MessageType.VALUES[ordinal]: MessageType.SYNC;
		//The length is a wire value too, and it sized an allocation. A claimed two billion entries
		//would have been an OutOfMemoryError for the cost of one packet.
		int l = Math.max(0, Math.min(MAX_ARGS, buf.readInt()));
		args = new String[l];
		for(int i = 0; i < l; i++)
			args[i] = ByteBufUtils.readUTF8String(buf);
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeInt(this.key.ordinal());
		if(args!=null)
		{
			buf.writeInt(this.args.length);
			for(String s : args)
				ByteBufUtils.writeUTF8String(buf, s);
		}
		else
			buf.writeInt(0);
	}

	public enum MessageType
	{
		SYNC,
		UNLOCK,
		SPAWN;

		/** Cached, because {@link #fromBytes} runs per packet on the netty thread. */
		static final MessageType[] VALUES = values();
	}

	public static class HandlerServer implements IMessageHandler<MessageShaderManual, IMessage>
	{
		@Override
		public IMessage onMessage(MessageShaderManual message, MessageContext ctx)
		{
			EntityPlayerMP player = ctx.getServerHandler().player;
			String playerName = player.getName();
			player.getServerWorld().addScheduledTask(() -> {
				if(message.key==MessageType.SYNC)
				{
					Collection<String> received = ShaderRegistry.receivedShaders.get(playerName);
					String[] ss = received.toArray(new String[0]);
					ImmersiveEngineering.packetHandler.sendTo(new MessageShaderManual(MessageType.SYNC, ss), player);
				}
				else if(message.key==MessageType.UNLOCK&&message.args.length > 0)
				{
					//	=================================
					//	The name is checked; the claim is not.
					//	=================================
					//
					// A client saying "I unlocked X" is still believed -- that is how upstream's
					// manual works and changing it is a separate decision about how shaders are
					// earned. What is no longer allowed is X being a name that does not exist:
					// unvalidated, this wrote arbitrary client strings into a server-side map that
					// is then serialised per player and replayed to them on every SYNC.
					if(isKnownShader(message.args[0]))
						ShaderRegistry.receivedShaders.put(playerName, message.args[0]);
				}
				else if(message.key==MessageType.SPAWN&&message.args.length > 0)
				{
					//An unknown name here dereferenced null for its replication cost. Same check,
					//and it has to be its own -- a client can send SPAWN without ever sending UNLOCK.
					if(!isKnownShader(message.args[0]))
						return;
					IngredientStack cost = ShaderRegistry.shaderRegistry.get(message.args[0]).replicationCost;
					if(player.capabilities.isCreativeMode
							||ApiUtils.consumePlayerIngredientAndConfirm(player, cost))
					{
						ItemStack shaderStack = new ItemStack(ShaderRegistry.itemShader);
						ItemNBTHelper.setString(shaderStack, "shader_name", message.args[0]);
						EntityItem entityitem = player.dropItem(shaderStack, false);
						if(entityitem!=null)
						{
							entityitem.setNoPickupDelay();
							entityitem.setOwner(player.getName());
						}
					}
				}
			});
			return null;
		}

		/**
		 * @return true if this is a shader the registry actually knows
		 * <p>
		 * Null-safe on both sides: the name arrives from the wire and may be absent, and the
		 * registry answers null for anything it has not been told about.
		 */
		private static boolean isKnownShader(String name)
		{
			return name!=null&&!name.isEmpty()
					&&ShaderRegistry.shaderRegistry.get(name)!=null;
		}
	}

	public static class HandlerClient implements IMessageHandler<MessageShaderManual, IMessage>
	{
		@Override
		public IMessage onMessage(MessageShaderManual message, MessageContext ctx)
		{
			Minecraft.getMinecraft().addScheduledTask(() -> {
				if(message.key==MessageType.SYNC)
				{
					EntityPlayer player = ImmersiveEngineering.proxy.getClientPlayer();
					if (player!=null)
					{
						String name = player.getName();
						for(String shader : message.args)
							if(shader!=null)
								ShaderRegistry.receivedShaders.put(name, shader);
					}
				}
			});
			return null;
		}
	}
}