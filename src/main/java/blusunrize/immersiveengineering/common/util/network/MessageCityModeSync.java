/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.network;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.common.util.CityMode;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server &rarr; client: the city-mode flags the world is actually being run with.
 * <p>
 * City mode is a property of the world, not of the person looking at it. Half of what it does is
 * server-side (a machine's redstone-driven buffer, its idle scan) and half is client-side (whether
 * that machine animates and loops its sound), and IE's config is per-installation, so without this
 * packet the two halves read two different settings. The symptom that got this written: a pack
 * whose dedicated server ran city mode but which shipped no {@code immersiveengineering.cfg}, so
 * every client -- and therefore every single-player world, which runs on the client's own
 * integrated server -- sat on the default and behaved like stock.
 * <p>
 * Sent once per player on login, and again to everyone whenever the config is reloaded so an
 * in-game config edit takes effect without a relog. The client installs it as an override in
 * {@link CityMode} and drops it on disconnect.
 * <p>
 * The wire format is a boolean for the master switch plus an int bitmask keyed by
 * {@link CityMode.Subsystem#ordinal()}. A subsystem this build does not know about arrives as a bit
 * nothing reads, which is exactly the right thing for it to do.
 *
 * @author LDImmersiveEngineering
 */
public class MessageCityModeSync implements IMessage
{
	private boolean master;
	private int subsystems;

	public MessageCityModeSync(CityMode.Flags flags)
	{
		this.master = flags.master();
		this.subsystems = flags.subsystemMask();
	}

	public MessageCityModeSync()
	{
	}

	/**
	 * @return the flags carried by this packet
	 */
	public CityMode.Flags getFlags()
	{
		return new CityMode.Flags(master, subsystems);
	}

	@Override
	public void fromBytes(ByteBuf buf)
	{
		this.master = buf.readBoolean();
		this.subsystems = buf.readInt();
	}

	@Override
	public void toBytes(ByteBuf buf)
	{
		buf.writeBoolean(master);
		buf.writeInt(subsystems);
	}

	/**
	 * Sends the server's current flags to one player. Safe to call for a fake or disconnecting
	 * player object; it simply does nothing.
	 */
	public static void sendTo(EntityPlayerMP player)
	{
		if(player==null||player.connection==null)
			return;
		ImmersiveEngineering.packetHandler.sendTo(new MessageCityModeSync(CityMode.fromConfig()), player);
	}

	/**
	 * Re-sends the server's flags to everyone online, for use after a config reload.
	 * <p>
	 * Deliberately tolerant of there being no server: the config is also read during pre-init, long
	 * before anything could be sent anywhere.
	 */
	public static void sendToAll()
	{
		MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
		if(server==null||server.getPlayerList()==null)
			return;
		for(EntityPlayerMP player : server.getPlayerList().getPlayers())
			sendTo(player);
	}

	public static class Handler implements IMessageHandler<MessageCityModeSync, IMessage>
	{
		@Override
		public IMessage onMessage(MessageCityModeSync message, MessageContext ctx)
		{
			//Read off the netty thread. The flags object is immutable and the field it lands in is
			//volatile, so the swap itself would be safe either way, but keeping it on the client
			//thread means a tick never sees the old value after a later tick saw the new one.
			CityMode.Flags flags = message.getFlags();
			Minecraft.getMinecraft().addScheduledTask(() -> CityMode.applyServerOverride(flags));
			return null;
		}
	}
}
