/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.grid.*;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.grid.GridSaveData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /ie grid ...} -- inspection and recovery for the virtual grid.
 * <p>
 * Deliberately not a substitute for the Grid Management Console: these exist so a grid can
 * be diagnosed without one (or repaired when one has been destroyed), and so devices can be
 * wired up in a test world before the console is built.
 *
 * @author LDImmersiveEngineering -- virtual grid
 */
public class CommandGrid extends CommandTreeBase
{
	{
		addSubcommand(new SubList());
		addSubcommand(new SubInfo());
		addSubcommand(new SubCreate());
		addSubcommand(new SubDelete());
		addSubcommand(new SubOn());
		addSubcommand(new SubOff());
		addSubcommand(new SubAssign());
		addSubcommand(new SubUnassign());
		addSubcommand(new SubLink());
		addSubcommand(new SubUnlink());
		addSubcommand(new SubDevices());
		addSubcommand(new SubSchedule());
		addSubcommand(new SubUnstick());
		addSubcommand(new CommandTreeHelp(this));
	}

	@Nonnull
	@Override
	public String getName()
	{
		return "grid";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "Use \"/ie grid help\" for more information";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 4;
	}

	@Nonnull
	@Override
	public List<String> getTabCompletions(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
										  String[] args, BlockPos pos)
	{
		//Every subcommand except create/list takes a segment name first. The routing itself lives in
		//CommandCompletion, shared with /ie fluidnet: the two are mirrors, and two copies of an
		//argument index drift apart without anything failing.
		if(CommandCompletion.completesSubjectName(args, "create", "list"))
			return completeSegmentNames(args[1]);
		if(CommandCompletion.completesSecondName(args, "link", "unlink"))
			return completeSegmentNames(args[2]);
		return super.getTabCompletions(server, sender, args, pos);
	}

	private static List<String> completeSegmentNames(String prefix)
	{
		List<String> names = new ArrayList<>();
		for(GridSegment segment : VirtualGrid.INSTANCE.getSegments())
			names.add(segment.getName());
		return CommandCompletion.matchingPrefix(names, prefix);
	}

	/**
	 * Resolves a segment by name, or by UUID if the name lookup misses.
	 */
	private static GridSegment requireSegment(String name) throws CommandException
	{
		GridSegment segment = VirtualGrid.INSTANCE.getSegmentByName(name);
		if(segment==null)
		{
			UUID id = GridDevice.parseUUID(name);
			if(id!=null)
				segment = VirtualGrid.INSTANCE.getSegment(id);
		}
		if(segment==null)
			throw new CommandException("No grid segment named \""+name+"\"");
		return segment;
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}

	/**
	 * Package-private rather than private so the precedence below can be asserted. The order is the
	 * decision: a tripped segment that is also scheduled off has to read as tripped, or the command
	 * that exists to end this confusion becomes another source of it.
	 */
	static String describeState(GridSegment segment)
	{
		if(segment.isTripped())
			return TextFormatting.RED+"TRIPPED"+TextFormatting.RESET;
		if(!segment.isEnabled())
			return TextFormatting.GRAY+"off"+TextFormatting.RESET;
		//A segment held down by a kill switch or its own schedule reads as "on" everywhere
		//else in this command, which is exactly the confusion these commands exist to end.
		if(segment.isForcedOff())
			return TextFormatting.YELLOW+"held off (signal)"+TextFormatting.RESET;
		if(segment.isScheduleSuppressed())
			return TextFormatting.GRAY+"scheduled off"+TextFormatting.RESET;
		if(CityMode.grid())
			return segment.isEnergized()?TextFormatting.GREEN+"energized"+TextFormatting.RESET
					: TextFormatting.YELLOW+"no source"+TextFormatting.RESET;
		return TextFormatting.GREEN+"on"+TextFormatting.RESET;
	}

	private class SubList extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "list";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid list";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
		{
			if(VirtualGrid.INSTANCE.getSegmentCount()==0)
			{
				msg(sender, "No grid segments exist. Create one with /ie grid create <name>");
				return;
			}
			msg(sender, TextFormatting.GOLD+"Grid segments ("+VirtualGrid.INSTANCE.getSegmentCount()+"):"
					+TextFormatting.RESET+(CityMode.grid()?" "+TextFormatting.AQUA+"[city mode]": ""));
			for(GridSegment segment : VirtualGrid.INSTANCE.getSegments())
				msg(sender, "  "+segment.getName()+" -- "+describeState(segment)
						+", "+segment.getDeviceCount(GridDeviceType.FEED)+" feed / "
						+segment.getDeviceCount(GridDeviceType.SERVICE)+" service, "
						+segment.getStats().getLastTickOut()+" IF/t out");
		}
	}

	private class SubInfo extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "info";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid info <segment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			GridPolicy policy = segment.getPolicy();
			msg(sender, TextFormatting.GOLD+segment.getName()+TextFormatting.RESET+"  ("+segment.getId()+")");
			msg(sender, "  state: "+describeState(segment));
			msg(sender, "  caps: "+policy.getMaxInput()+" in / "+policy.getMaxOutput()+" out IF/t"
					+", loss "+String.format(Locale.ENGLISH, "%.2f%%", policy.getLossPct()*100));
			msg(sender, "  buffer: "+segment.getBuffer()+" / "+policy.getBufferCap()+" IF");
			msg(sender, "  last tick: "+segment.getStats().getLastTickIn()+" in, "
					+segment.getStats().getLastTickOut()+" out");
			msg(sender, "  lifetime: "+segment.getStats().getLifetimeIn()+" in, "
					+segment.getStats().getLifetimeOut()+" out");
			msg(sender, "  devices: "+segment.getDeviceCount()+" ("+segment.getOnlineDeviceCount()+" online)"
					+", "+segment.getDeviceCount(GridDeviceType.SIGNAL)+" signal");
			msg(sender, "  schedule: "+(policy.isScheduleEnabled()
					?policy.getScheduleOn()+" to "+policy.getScheduleOff()
					+(segment.isScheduleSuppressed()?" (asleep now)": " (awake now)")
					: "none"));
			msg(sender, "  failover top-up: "+(policy.isFailoverTopUp()?"on": "outage only"));
			if(segment.getFailover().isEmpty())
				msg(sender, "  failover: none");
			else
			{
				StringBuilder chain = new StringBuilder();
				for(UUID target : segment.getFailover())
				{
					GridSegment backup = VirtualGrid.INSTANCE.getSegment(target);
					chain.append(chain.length() > 0?" -> ": "").append(backup!=null?backup.getName(): "?");
				}
				msg(sender, "  failover: "+chain);
			}
		}
	}

	private class SubCreate extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "create";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid create <name> (surround a name containing spaces in <angle brackets>)";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			String name = String.join(" ", args);
			if(VirtualGrid.INSTANCE.getSegmentByName(name)!=null)
				throw new CommandException("A segment named \""+name+"\" already exists");
			GridSegment segment = VirtualGrid.INSTANCE.createSegment(name);
			GridSaveData.setDirty();
			msg(sender, "Created segment "+TextFormatting.GOLD+segment.getName()+TextFormatting.RESET);
		}
	}

	private class SubDelete extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "delete";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid delete <segment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			int devices = segment.getDeviceCount();
			VirtualGrid.INSTANCE.deleteSegment(segment.getId());
			GridSaveData.setDirty();
			msg(sender, "Deleted \""+segment.getName()+"\"; "+devices+" device(s) are now unlinked");
		}
	}

	private class SubOn extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "on";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid on <segment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			segment.setEnabled(true);
			GridSaveData.setDirty();
			msg(sender, "\""+segment.getName()+"\" switched on (breaker reset)");
		}
	}

	private class SubOff extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "off";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid off <segment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			segment.setEnabled(false);
			GridSaveData.setDirty();
			msg(sender, "\""+segment.getName()+"\" switched off");
		}
	}

	private class SubAssign extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "assign";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid assign <segment> [x y z] -- defaults to the block you are looking at range 0";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			BlockPos target = args.length >= 4
					?new BlockPos(parseInt(args[1]), parseInt(args[2]), parseInt(args[3]))
					: sender.getPosition();
			GridDevice device = findDeviceNear(sender, target);
			if(!VirtualGrid.INSTANCE.assignDevice(device, segment.getId()))
				throw new CommandException("Could not assign that device (cross-dimension transfer may be disabled)");
			GridSaveData.setDirty();
			msg(sender, "Assigned "+device.getType().getName()+" at "+device.getPos()
					+" to \""+segment.getName()+"\"");
		}
	}

	private class SubUnassign extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "unassign";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid unassign [x y z]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			BlockPos target = args.length >= 3
					?new BlockPos(parseInt(args[0]), parseInt(args[1]), parseInt(args[2]))
					: sender.getPosition();
			GridDevice device = findDeviceNear(sender, target);
			VirtualGrid.INSTANCE.assignDevice(device, null);
			GridSaveData.setDirty();
			msg(sender, "Unlinked "+device.getType().getName()+" at "+device.getPos());
		}
	}

	/**
	 * Reads or sets a segment's operating window. Times are day-time ticks, the same unit
	 * {@code /time set} takes, so "on at 12000" and "/time set 12000" mean the same instant.
	 */
	private class SubSchedule extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "schedule";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid schedule <segment> [off | <onTime> <offTime>]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			GridPolicy policy = segment.getPolicy();

			if(args.length==1)
			{
				if(!policy.isScheduleEnabled())
					msg(sender, "\""+segment.getName()+"\" runs whenever it is switched on.");
				else
					msg(sender, "\""+segment.getName()+"\" runs from "+policy.getScheduleOn()
							+" to "+policy.getScheduleOff()+" -- currently "
							+(segment.isScheduleSuppressed()?TextFormatting.GRAY+"asleep"
							: TextFormatting.GREEN+"awake")+TextFormatting.RESET);
				return;
			}

			if("off".equalsIgnoreCase(args[1]))
			{
				policy.setScheduleEnabled(false);
				GridSaveData.setDirty();
				msg(sender, "Schedule disabled for \""+segment.getName()+"\"");
				return;
			}
			if(args.length < 3)
				throw new CommandException(getUsage(sender));
			int on = parseInt(args[1], 0, GridPolicy.DAY_LENGTH-1);
			int off = parseInt(args[2], 0, GridPolicy.DAY_LENGTH-1);
			if(on==off)
				throw new CommandException("On and off must differ -- equal times would mean a window that never opens");
			policy.setScheduleOn(on);
			policy.setScheduleOff(off);
			policy.setScheduleEnabled(true);
			GridSaveData.setDirty();
			msg(sender, "\""+segment.getName()+"\" now runs from "+on+" to "+off
					+(on > off?" (across midnight)": ""));
		}
	}

	private class SubLink extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "link";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid link <segment> <backupSegment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 2)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			GridSegment backup = requireSegment(args[1]);
			if(!segment.addFailover(backup.getId()))
				throw new CommandException("Already linked, or a segment cannot back up itself");
			GridSaveData.setDirty();
			msg(sender, "\""+backup.getName()+"\" now backs up \""+segment.getName()+"\"");
		}
	}

	private class SubUnlink extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "unlink";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid unlink <segment> <backupSegment>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 2)
				throw new CommandException(getUsage(sender));
			GridSegment segment = requireSegment(args[0]);
			GridSegment backup = requireSegment(args[1]);
			if(!segment.removeFailover(backup.getId()))
				throw new CommandException("Those segments are not linked");
			GridSaveData.setDirty();
			msg(sender, "Removed failover link");
		}
	}

	private class SubDevices extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "devices";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid devices [segment] -- omit the segment to list unlinked devices";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			List<GridDevice> list;
			if(args.length < 1)
			{
				list = VirtualGrid.INSTANCE.getUnlinkedDevices();
				msg(sender, TextFormatting.GOLD+"Unlinked devices ("+list.size()+"):"+TextFormatting.RESET);
			}
			else
			{
				GridSegment segment = requireSegment(args[0]);
				list = segment.getDevices();
				msg(sender, TextFormatting.GOLD+segment.getName()+" devices ("+list.size()+"):"+TextFormatting.RESET);
			}
			for(GridDevice device : list)
				msg(sender, "  "+device.getType().getName()+" @ dim "+device.getDimension()+" "
						+device.getPos().getX()+","+device.getPos().getY()+","+device.getPos().getZ()
						+"  "+(device.isOnline()?TextFormatting.GREEN+"online": TextFormatting.GRAY+"offline")
						+TextFormatting.RESET+"  cap "+device.getTransferCap()
						+", prio "+device.getPriority()+(device.isCritical()?", critical": ""));
		}
	}

	private class SubUnstick extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "unstick";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie grid unstick <x> <y> <z> -- force-removes a stale device record";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 3)
				throw new CommandException(getUsage(sender));
			DimensionBlockPos pos = new DimensionBlockPos(parseInt(args[0]), parseInt(args[1]),
					parseInt(args[2]), sender.getEntityWorld().provider.getDimension());
			GridDevice removed = VirtualGrid.INSTANCE.unregisterDevice(pos);
			if(removed==null)
				throw new CommandException("No device registered at "+pos);
			GridSaveData.setDirty();
			msg(sender, "Removed stale "+removed.getType().getName()+" record at "+pos);
		}
	}

	/**
	 * Finds a registered device at, or within one block of, {@code target}. The tolerance
	 * exists because a player standing next to a box rarely has its exact coordinates.
	 */
	private static GridDevice findDeviceNear(ICommandSender sender, BlockPos target) throws CommandException
	{
		int dim = sender.getEntityWorld().provider.getDimension();
		GridDevice exact = VirtualGrid.INSTANCE.getDevice(new DimensionBlockPos(target, dim));
		if(exact!=null)
			return exact;
		GridDevice best = null;
		double bestDist = Double.MAX_VALUE;
		for(GridDevice device : VirtualGrid.INSTANCE.getDevices())
		{
			if(device.getDimension()!=dim||device.getType()==GridDeviceType.CONSOLE)
				continue;
			double dist = device.getPos().distanceSq(target);
			if(dist <= 9&&dist < bestDist)
			{
				bestDist = dist;
				best = device;
			}
		}
		if(best==null)
			throw new CommandException("No grid device found at or near "+target.getX()+" "
					+target.getY()+" "+target.getZ());
		return best;
	}
}
