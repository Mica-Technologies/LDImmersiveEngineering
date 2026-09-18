/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitLink;
import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduits;
import blusunrize.immersiveengineering.common.util.CityMode;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.DimensionManager;

import javax.annotation.Nonnull;

/**
 * {@code /ie virtualconduit} -- every conduit breakout that is holding a town up while its own chunk
 * is unloaded: where it is, which conductor, down which wire, and what it delivered on the last tick.
 * <p>
 * For the server operator, and written because the equivalent for metered plants is what made a
 * town-wide outage diagnosable at all. A feature that works while nobody is looking needs one place
 * that says what it is doing, or the only symptom is a dark town and a shrug.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class CommandVirtualConduit extends CommandBase
{
	@Nonnull
	@Override
	public String getName()
	{
		return "virtualconduit";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "/ie virtualconduit -- lists every conduit breakout supplying a wire while unloaded";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 2;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
	{
		boolean city = CityMode.conduits();
		msg(sender, TextFormatting.GOLD+"Virtual conduit"+TextFormatting.RESET+": "
				+(city?"conduits in City Mode"
				: TextFormatting.YELLOW+"conduits in normal mode -- nothing is supplied virtually"
				+TextFormatting.RESET)
				+", "+VirtualConduits.INSTANCE.size()+" breakout(s) recorded");
		for(VirtualConduitLink link : VirtualConduits.INSTANCE.getLinks())
		{
			BlockPos box = link.getBoxPos();
			String state;
			if(!city)
				state = TextFormatting.GRAY+"idle (normal mode)";
			else if(!link.isUsable())
				state = TextFormatting.GRAY+"idle (no wire on that face, or nothing measured)";
			else if(isLoaded(link))
				state = TextFormatting.GREEN+"box loaded -- running for real";
			else if(link.getLastDelivered() > 0)
				state = TextFormatting.AQUA+"VIRTUAL, delivered "+link.getLastDelivered()+" IF last tick";
			else
				state = TextFormatting.GRAY+"virtual, but nothing on the far side took any";
			msg(sender, " dim "+link.getDimension()+" "+box.getX()+" "+box.getY()+" "+box.getZ()
					+" ["+link.getChannel().getName()+"]: "+link.getRate()+" IF/t offered"
					+(link.getWireEnd()==null?"": " down "+link.getWireTypeName()+" to "
					+link.getWireEnd().getX()+" "+link.getWireEnd().getY()+" "+link.getWireEnd().getZ())
					+" -- "+state+TextFormatting.RESET);
		}
	}

	private static boolean isLoaded(VirtualConduitLink link)
	{
		net.minecraft.world.World world = DimensionManager.getWorld(link.getDimension());
		return world!=null&&world.isBlockLoaded(link.getBoxPos());
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}
}
