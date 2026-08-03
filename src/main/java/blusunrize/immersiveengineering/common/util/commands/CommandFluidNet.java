/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.fluid.network.*;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.fluidnet.FluidNetSaveData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /ie fluidnet ...} -- inspection and recovery for the virtual fluid network.
 * <p>
 * Deliberately not a substitute for the Fluid Control Console: these exist so a network can be
 * diagnosed without one (or repaired when one has been destroyed), and so fittings can be wired up
 * in a test world before the console is built.
 * <p>
 * The deliberate mirror of {@code CommandGrid}, with one subcommand the grid has no use for:
 * {@code fluid}, because a main carries exactly one thing and getting that wrong is the failure
 * mode unique to this network.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public class CommandFluidNet extends CommandTreeBase
{
	{
		addSubcommand(new SubList());
		addSubcommand(new SubInfo());
		addSubcommand(new SubCreate());
		addSubcommand(new SubDelete());
		addSubcommand(new SubOpen());
		addSubcommand(new SubClose());
		addSubcommand(new SubFluid());
		addSubcommand(new SubAssign());
		addSubcommand(new SubUnassign());
		addSubcommand(new SubLink());
		addSubcommand(new SubUnlink());
		addSubcommand(new SubDevices());
		addSubcommand(new SubSchedule());
		addSubcommand(new CommandTreeHelp(this));
	}

	@Nonnull
	@Override
	public String getName()
	{
		return "fluidnet";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "Use \"/ie fluidnet help\" for more information";
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
		//Every subcommand except create/list takes a main name first. The routing itself lives in
		//CommandCompletion, shared with /ie grid: the two are mirrors, and two copies of an argument
		//index drift apart without anything failing.
		if(CommandCompletion.completesSubjectName(args, "create", "list"))
			return completeMainNames(args[1]);
		if(CommandCompletion.completesSecondName(args, "link", "unlink"))
			return completeMainNames(args[2]);
		//The one argument the grid has no counterpart for: a main carries a named fluid where a
		//segment carries undifferentiated flux.
		if(CommandCompletion.completesThirdArgOf(args, "fluid"))
			return completeFluidNames(args[2]);
		return super.getTabCompletions(server, sender, args, pos);
	}

	private static List<String> completeMainNames(String prefix)
	{
		List<String> names = new ArrayList<>();
		for(FluidMain main : VirtualFluidNet.INSTANCE.getMains())
			names.add(main.getName());
		return CommandCompletion.matchingPrefix(names, prefix);
	}

	private static List<String> completeFluidNames(String prefix)
	{
		return CommandCompletion.matchingPrefix(FluidRegistry.getRegisteredFluids().keySet(), prefix);
	}

	/**
	 * Resolves a main by name, or by UUID if the name lookup misses.
	 */
	private static FluidMain requireMain(String name) throws CommandException
	{
		FluidMain main = VirtualFluidNet.INSTANCE.getMainByName(name);
		if(main==null)
		{
			UUID id = FluidDevice.parseUUID(name);
			if(id!=null)
				main = VirtualFluidNet.INSTANCE.getMain(id);
		}
		if(main==null)
			throw new CommandException("No fluid main named \""+name+"\"");
		return main;
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}

	/**
	 * Package-private rather than private so the precedence below can be asserted, and so it can be
	 * held against {@code CommandGrid.describeState}'s -- the two are mirrors and must stay so.
	 */
	static String describeState(FluidMain main)
	{
		if(main.isTripped())
			return TextFormatting.RED+"TRIPPED"+TextFormatting.RESET;
		if(!main.isEnabled())
			return TextFormatting.GRAY+"closed"+TextFormatting.RESET;
		//A main held closed by a valve or its own schedule reads as "open" everywhere else, which
		//is exactly the confusion these commands exist to end.
		if(main.isForcedClosed())
			return TextFormatting.YELLOW+"held closed (valve)"+TextFormatting.RESET;
		if(main.isScheduleSuppressed())
			return TextFormatting.GRAY+"scheduled closed"+TextFormatting.RESET;
		if(CityMode.petroleum())
			return main.isPressurised()?TextFormatting.GREEN+"pressurised"+TextFormatting.RESET
					: TextFormatting.YELLOW+"no source"+TextFormatting.RESET;
		return TextFormatting.GREEN+"open"+TextFormatting.RESET;
	}

	private static String describeFluid(FluidMain main)
	{
		return main.isTyped()?main.getFluid(): TextFormatting.GRAY+"untyped"+TextFormatting.RESET;
	}

	private static FluidDevice findDeviceNear(ICommandSender sender, BlockPos target) throws CommandException
	{
		int dim = sender.getEntityWorld().provider.getDimension();
		FluidDevice exact = VirtualFluidNet.INSTANCE.getDevice(new DimensionBlockPos(target, dim));
		if(exact!=null)
			return exact;
		FluidDevice best = null;
		double bestDist = Double.MAX_VALUE;
		for(FluidDevice device : VirtualFluidNet.INSTANCE.getDevices())
		{
			if(device.getDimension()!=dim||device.getType()==FluidDeviceType.CONSOLE)
				continue;
			double dist = device.getPos().distanceSq(target);
			if(dist <= 9&&dist < bestDist)
			{
				bestDist = dist;
				best = device;
			}
		}
		if(best==null)
			throw new CommandException("No fluid network fitting found at or near "+target.getX()+" "
					+target.getY()+" "+target.getZ());
		return best;
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
			return "/ie fluidnet list";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
		{
			if(VirtualFluidNet.INSTANCE.getMainCount()==0)
			{
				msg(sender, "No fluid mains exist. Create one with /ie fluidnet create <name>");
				return;
			}
			msg(sender, TextFormatting.GOLD+"Fluid mains ("+VirtualFluidNet.INSTANCE.getMainCount()+"):"
					+TextFormatting.RESET+(CityMode.petroleum()?" "+TextFormatting.AQUA+"[city mode]": ""));
			for(FluidMain main : VirtualFluidNet.INSTANCE.getMains())
				msg(sender, "  "+main.getName()+" -- "+describeState(main)
						+", "+describeFluid(main)
						+", "+main.getDeviceCount(FluidDeviceType.INLET)+" in / "
						+main.getDeviceCount(FluidDeviceType.OUTLET)+" out, "
						+main.getStats().getLastTickOut()+" mB/t delivered");
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
			return "/ie fluidnet info <main>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			FluidPolicy policy = main.getPolicy();
			msg(sender, TextFormatting.GOLD+main.getName()+TextFormatting.RESET+"  ("+main.getId()+")");
			msg(sender, "  state: "+describeState(main));
			msg(sender, "  carries: "+describeFluid(main));
			msg(sender, "  caps: "+policy.getMaxInput()+" in / "+policy.getMaxOutput()+" out mB/t"
					+", leak "+String.format(Locale.ENGLISH, "%.2f%%", policy.getLeakPct()*100));
			msg(sender, "  line pack: "+main.getPack()+" / "+policy.getPackCap()+" mB");
			msg(sender, "  last tick: "+main.getStats().getLastTickIn()+" in, "
					+main.getStats().getLastTickOut()+" out");
			msg(sender, "  lifetime: "+main.getStats().getLifetimeIn()+" in, "
					+main.getStats().getLifetimeOut()+" out");
			msg(sender, "  fittings: "+main.getDeviceCount()+" ("+main.getOnlineDeviceCount()+" online)"
					+", "+main.getDeviceCount(FluidDeviceType.VALVE)+" valve");
			msg(sender, "  schedule: "+(policy.isScheduleEnabled()
					?policy.getScheduleOn()+" to "+policy.getScheduleOff()
					+(main.isScheduleSuppressed()?" (closed now)": " (open now)")
					: "none"));
			msg(sender, "  failover top-up: "+(policy.isFailoverTopUp()?"on": "outage only"));
			if(main.getFailover().isEmpty())
				msg(sender, "  failover: none");
			else
			{
				StringBuilder chain = new StringBuilder();
				for(UUID target : main.getFailover())
				{
					FluidMain backup = VirtualFluidNet.INSTANCE.getMain(target);
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
			return "/ie fluidnet create <name> (surround a name containing spaces in <angle brackets>)";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			String name = String.join(" ", args);
			if(VirtualFluidNet.INSTANCE.getMainByName(name)!=null)
				throw new CommandException("A main named \""+name+"\" already exists");
			FluidMain main = VirtualFluidNet.INSTANCE.createMain(name);
			FluidNetSaveData.setDirty();
			msg(sender, "Created main "+TextFormatting.GOLD+main.getName()+TextFormatting.RESET
					+". It will take its fluid from the first inlet that has something to offer.");
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
			return "/ie fluidnet delete <main>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			int devices = main.getDeviceCount();
			VirtualFluidNet.INSTANCE.deleteMain(main.getId());
			FluidNetSaveData.setDirty();
			msg(sender, "Deleted \""+main.getName()+"\"; "+devices+" fitting(s) are now unlinked");
		}
	}

	private class SubOpen extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "open";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie fluidnet open <main>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			//setEnabled(true) is also the overpressure reset, exactly as it is on a real valve
			//station -- one control rather than a separate hidden unlatch.
			main.setEnabled(true);
			FluidNetSaveData.setDirty();
			msg(sender, "Opened \""+main.getName()+"\"");
		}
	}

	private class SubClose extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "close";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie fluidnet close <main>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			main.setEnabled(false);
			FluidNetSaveData.setDirty();
			msg(sender, "Closed \""+main.getName()+"\"");
		}
	}

	/**
	 * The one subcommand the grid has no counterpart for.
	 */
	private class SubFluid extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "fluid";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie fluidnet fluid <main> [<fluid>|clear] -- only while the line pack is empty";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			if(args.length < 2)
			{
				msg(sender, "\""+main.getName()+"\" carries "+describeFluid(main));
				return;
			}
			String requested = "clear".equalsIgnoreCase(args[1])?null: args[1];
			if(requested!=null&&FluidRegistry.getFluid(requested)==null)
				throw new CommandException("No fluid is registered as \""+requested+"\"");
			if(!main.setFluid(requested))
				//The refusal that matters: re-typing a main with fluid still in it would either
				//destroy it silently or start delivering the wrong thing into every machine on the
				//network, and a player cannot debug either.
				throw new CommandException("\""+main.getName()+"\" still holds "+main.getPack()
						+" mB. Drain it before changing what it carries.");
			FluidNetSaveData.setDirty();
			msg(sender, "\""+main.getName()+"\" now carries "+describeFluid(main));
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
			return "/ie fluidnet assign <main> [x y z] -- defaults to where you are standing";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			BlockPos target = args.length >= 4
					?new BlockPos(parseInt(args[1]), parseInt(args[2]), parseInt(args[3]))
					: sender.getPosition();
			FluidDevice device = findDeviceNear(sender, target);
			if(!VirtualFluidNet.INSTANCE.assignDevice(device, main.getId()))
				throw new CommandException("Could not assign that fitting (cross-dimension transfer may be disabled)");
			FluidNetSaveData.setDirty();
			msg(sender, "Assigned "+device.getType().getName()+" at "+device.getPos()
					+" to \""+main.getName()+"\"");
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
			return "/ie fluidnet unassign [x y z] -- defaults to where you are standing";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			BlockPos target = args.length >= 3
					?new BlockPos(parseInt(args[0]), parseInt(args[1]), parseInt(args[2]))
					: sender.getPosition();
			FluidDevice device = findDeviceNear(sender, target);
			VirtualFluidNet.INSTANCE.assignDevice(device, null);
			FluidNetSaveData.setDirty();
			msg(sender, "Unlinked "+device.getType().getName()+" at "+device.getPos());
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
			return "/ie fluidnet link <main> <backup>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 2)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			FluidMain backup = requireMain(args[1]);
			if(!main.addFailover(backup.getId()))
				throw new CommandException("\""+main.getName()+"\" is already backed by \""
						+backup.getName()+"\" (or is that main)");
			FluidNetSaveData.setDirty();
			msg(sender, "\""+main.getName()+"\" now falls back to \""+backup.getName()+"\""
					+(backup.isTyped()&&main.isTyped()&&!backup.getFluid().equals(main.getFluid())
					?TextFormatting.YELLOW+"  -- but they carry different fluids, so it will never"
					+" actually cover it"+TextFormatting.RESET: ""));
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
			return "/ie fluidnet unlink <main> <backup>";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 2)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			FluidMain backup = requireMain(args[1]);
			if(!main.removeFailover(backup.getId()))
				throw new CommandException("\""+main.getName()+"\" is not backed by \""+backup.getName()+"\"");
			FluidNetSaveData.setDirty();
			msg(sender, "Removed the failover link");
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
			return "/ie fluidnet devices [main] -- omit the main to list unlinked fittings";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			List<FluidDevice> devices;
			if(args.length < 1)
			{
				devices = VirtualFluidNet.INSTANCE.getUnlinkedDevices();
				msg(sender, TextFormatting.GOLD+"Unlinked fittings ("+devices.size()+"):"+TextFormatting.RESET);
			}
			else
			{
				FluidMain main = requireMain(args[0]);
				devices = main.getDevices();
				msg(sender, TextFormatting.GOLD+main.getName()+" ("+devices.size()+" fittings):"
						+TextFormatting.RESET);
			}
			for(FluidDevice device : devices)
				msg(sender, "  "+device.getType().getName()+" at "+device.getPos()
						+(device.isOnline()?TextFormatting.GREEN+" online": TextFormatting.GRAY+" unloaded")
						+TextFormatting.RESET
						+(device.getType().movesFluid()
						?", "+device.getLastThroughput()+" / "+device.getTransferCap()+" mB/t": "")
						+(device.isEnabled()?"": TextFormatting.RED+" [disabled]"+TextFormatting.RESET));
		}
	}

	/**
	 * The mirror of {@code /ie grid schedule}, and it was missing.
	 * <p>
	 * <strong>A main's schedule could be switched on but its window could never be set.</strong>
	 * {@link FluidPolicy} carries {@code scheduleEnabled}, {@code scheduleOn} and
	 * {@code scheduleOff} and serialises all three; {@code MessageFluidNetAction} reads the times
	 * if they are sent. But the fluid console's tab has only the on/off toggle -- the grid console's
	 * two time fields have no counterpart there -- and there was no command either. Enabling a
	 * schedule therefore locked a main to the default dusk window for good.
	 * <p>
	 * The console still cannot set the times; that is a layout change to a nine-hundred-line screen
	 * and wants doing deliberately. This closes the half that can be closed exactly, by mirroring
	 * the grid's subcommand line for line.
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
			return "/ie fluidnet schedule <main> [off | <onTime> <offTime>]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			FluidMain main = requireMain(args[0]);
			FluidPolicy policy = main.getPolicy();

			if(args.length==1)
			{
				if(!policy.isScheduleEnabled())
					msg(sender, "\""+main.getName()+"\" flows whenever it is open.");
				else
					msg(sender, "\""+main.getName()+"\" flows from "+policy.getScheduleOn()
							+" to "+policy.getScheduleOff()+" -- currently "
							+(main.isScheduleSuppressed()?TextFormatting.GRAY+"asleep"
							: TextFormatting.GREEN+"awake")+TextFormatting.RESET);
				return;
			}

			if("off".equalsIgnoreCase(args[1]))
			{
				policy.setScheduleEnabled(false);
				FluidNetSaveData.setDirty();
				msg(sender, "Schedule disabled for \""+main.getName()+"\"");
				return;
			}
			if(args.length < 3)
				throw new CommandException(getUsage(sender));
			int on = parseInt(args[1], 0, FluidPolicy.DAY_LENGTH-1);
			int off = parseInt(args[2], 0, FluidPolicy.DAY_LENGTH-1);
			//Equal endpoints mean a window that never opens -- deliberate in the policy, because an
			//always-open window is indistinguishable from no schedule and would hide the typo. Here
			//it is refused outright rather than silently closing somebody's main.
			if(on==off)
				throw new CommandException("On and off must differ -- equal times would mean a window that never opens");
			policy.setScheduleOn(on);
			policy.setScheduleOff(off);
			policy.setScheduleEnabled(true);
			FluidNetSaveData.setDirty();
			msg(sender, "\""+main.getName()+"\" now flows from "+on+" to "+off
					+(on > off?" (across midnight)": ""));
		}
	}
}
