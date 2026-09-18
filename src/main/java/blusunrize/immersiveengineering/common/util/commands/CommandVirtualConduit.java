/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitConfig;
import blusunrize.immersiveengineering.api.energy.virtualconduit.VirtualConduitFeed;
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
 * {@code /ie virtualconduit} -- where power gets into a conduit run, where it leaves one, and which
 * of those are doing anything right now.
 * <p>
 * For the server operator, and written because the equivalent for metered plants is what made a
 * town-wide outage diagnosable at all: the last one was worked out from {@code /ie virtualgen}'s
 * output and nothing else. A feature that works while nobody is looking needs one place that says
 * what it is doing, or the only symptom is a dark town and a shrug.
 * <p>
 * Two lists, because an outage is nearly always one of two things and they have different answers.
 * <strong>Feeds</strong> say where the run is being supplied; none, and the run is dark however good
 * the rest of it is, and the fix is at the source end. <strong>Outlets</strong> say where a conductor
 * leaves; each one says whether its conductor is live, whether its box is loaded, and what the far
 * network actually took on the last tick -- so "nothing on the far side wanted any" and "this
 * conductor is dark" are told apart without going there.
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
		return "/ie virtualconduit -- lists every conduit feed and outlet, and what each is doing";
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
		VirtualConduits registry = VirtualConduits.INSTANCE;
		msg(sender, TextFormatting.GOLD+"Virtual conduit"+TextFormatting.RESET+": "
				+(VirtualConduitConfig.enabled?"enabled": TextFormatting.RED+"disabled in config"+TextFormatting.RESET)
				+", "+(city?"conduits in City Mode"
				: TextFormatting.YELLOW+"conduits in normal mode -- nothing is supplied virtually"
				+TextFormatting.RESET)
				+", "+registry.feedCount()+" feed(s), "+registry.size()+" outlet(s)");

		msg(sender, TextFormatting.GOLD+"Feeds"+TextFormatting.RESET
				+" -- where power enters a run. A run with none of these is dark whatever else is right.");
		for(VirtualConduitFeed feed : registry.getFeeds())
		{
			BlockPos box = feed.getBoxPos();
			msg(sender, " dim "+feed.getDimension()+" "+box.getX()+" "+box.getY()+" "+box.getZ()
					+" ["+feed.getChannel().getName()+"] -- "
					+(isLoaded(feed.getDimension(), box)
					?TextFormatting.GREEN+"box loaded": TextFormatting.GRAY+"box unloaded")
					+TextFormatting.RESET);
		}

		msg(sender, TextFormatting.GOLD+"Outlets"+TextFormatting.RESET
				+" -- where a conductor leaves a run.");
		for(VirtualConduitLink link : registry.getLinks())
		{
			BlockPos box = link.getBoxPos();
			boolean live = registry.isLive(link.getDimension(), box, link.getChannel());
			boolean loaded = isLoaded(link.getDimension(), box);
			String state;
			if(!city)
				state = TextFormatting.GRAY+"idle (normal mode)";
			else if(!VirtualConduitConfig.enabled)
				state = TextFormatting.GRAY+"idle (disabled in config)";
			else if(!link.isUsable())
				state = TextFormatting.GRAY+"idle (nothing on that face to deliver into)";
			else if(!live)
				//The one an outage nearly always is, and the one worth naming plainly: the hardware
				//is fine and nothing is feeding the conductor.
				state = TextFormatting.YELLOW+"DARK -- no feed reaches this conductor";
			else if(loaded)
				state = TextFormatting.GREEN+"live, box loaded -- running for real";
			else if(link.getLastDelivered() > 0)
				state = TextFormatting.AQUA+"VIRTUAL, delivered "+link.getLastDelivered()+" IF last tick";
			else
				state = TextFormatting.GRAY+"live and virtual, but nothing on the far side took any";
			msg(sender, " dim "+link.getDimension()+" "+box.getX()+" "+box.getY()+" "+box.getZ()
					+" ["+link.getChannel().getName()+"] "+describe(link)
					+": "+link.getRate()+" IF/t offered -- "+state+TextFormatting.RESET);
		}
	}

	/**
	 * Which kind of outlet, and where it pushes from -- the two things somebody standing at the pole
	 * needs to match against what they can see.
	 */
	private static String describe(VirtualConduitLink link)
	{
		String face = link.getFace()==null?"?": link.getFace().getName();
		if(link.getKind()==VirtualConduitLink.Kind.NEIGHBOUR)
		{
			BlockPos at = link.getOutletPos();
			return "connector on "+face+" face at "+at.getX()+" "+at.getY()+" "+at.getZ();
		}
		BlockPos end = link.getWireEnd();
		return "wire on "+face+" face"+(end==null?""
				: ", "+link.getWireTypeName()+" to "+end.getX()+" "+end.getY()+" "+end.getZ());
	}

	private static boolean isLoaded(int dimension, BlockPos pos)
	{
		net.minecraft.world.World world = DimensionManager.getWorld(dimension);
		return world!=null&&world.isBlockLoaded(pos);
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}
}
