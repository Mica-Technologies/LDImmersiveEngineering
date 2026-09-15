/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.common.util.network.MessageNoSpamChatComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

public class ChatUtils
{
	private static final int DELETION_ID = 3718126;
	private static int lastAdded;

	@SideOnly(Side.CLIENT)//Credit goes to WayOfFlowingTime
	public static void sendClientNoSpamMessages(ITextComponent[] messages)
	{
		GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
		for(int i = DELETION_ID+messages.length-1; i <= lastAdded; i++)
			chat.deleteChatLine(i);
		for(int i = 0; i < messages.length; i++)
			chat.printChatMessageWithOptionalDeletion(messages[i], DELETION_ID+i);
		lastAdded = DELETION_ID+messages.length-1;
	}

	public static void sendServerNoSpamMessages(EntityPlayer player, ITextComponent... messages)
	{
		if(messages.length > 0&&player instanceof EntityPlayerMP)
			ImmersiveEngineering.packetHandler.sendTo(new MessageNoSpamChatComponents(messages), (EntityPlayerMP)player);
	}

	/**
	 * Sends a multi-line readout as <em>one</em> no-spam message.
	 * <p>
	 * Every no-spam message deletes the lines the previous one printed, so sending a readout a line at a
	 * time -- which is what the grid and fluid-network boxes and the Generation Meter all did -- left only its
	 * last line on screen.
	 */
	public static void sendServerNoSpamLines(EntityPlayer player, List<String> lines)
	{
		ITextComponent[] components = new ITextComponent[lines.size()];
		for(int i = 0; i < components.length; i++)
			components[i] = new TextComponentString(lines.get(i));
		sendServerNoSpamMessages(player, components);
	}
}
