/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.petroleum.*;
import blusunrize.immersiveengineering.common.util.CityMode;
import blusunrize.immersiveengineering.common.util.petroleum.PetroleumSaveData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * {@code /ie reservoir ...} -- inspection and recovery for oil reservoirs.
 * <p>
 * Deposits are invisible: they are computed from the world seed rather than placed, so without
 * these there is no way to tell "this cell rolled nothing" apart from "the pumpjack is wired
 * up wrong". These exist to answer that question, and to put a field back after a mishap.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public class CommandReservoir extends CommandTreeBase
{
	{
		addSubcommand(new SubInfo());
		addSubcommand(new SubTypes());
		addSubcommand(new SubDeplete());
		addSubcommand(new SubRefill());
		addSubcommand(new CommandTreeHelp(this));
	}

	@Nonnull
	@Override
	public String getName()
	{
		return "reservoir";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "Use \"/ie reservoir help\" for more information";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 4;
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}

	/**
	 * Resolves the deposit under the sender, or under explicit block coordinates.
	 */
	private static Reservoir resolve(ICommandSender sender, String[] args, int firstArg)
			throws CommandException
	{
		World world = sender.getEntityWorld();
		BlockPos pos = sender.getPosition();
		if(args.length >= firstArg+2)
			pos = new BlockPos(CommandBase.parseInt(args[firstArg]), 0,
					CommandBase.parseInt(args[firstArg+1]));
		return ReservoirHandler.getReservoir(world.getSeed(), world.provider.getDimension(),
				pos.getX() >> 4, pos.getZ() >> 4);
	}

	private static String describe(Reservoir reservoir)
	{
		if(reservoir.isEmpty())
			return TextFormatting.GRAY+"nothing here"+TextFormatting.RESET;
		double percent = 100.0*reservoir.getFraction();
		String colour = percent > 60?TextFormatting.GREEN.toString()
				: percent > 15?TextFormatting.YELLOW.toString(): TextFormatting.RED.toString();
		return colour+String.format(Locale.ENGLISH, "%.1f%%", percent)+TextFormatting.RESET
				+" ("+reservoir.getRemaining()+" / "+reservoir.getOriginalCapacity()+" mB)";
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
			return "/ie reservoir info [x] [z]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args) throws CommandException
		{
			Reservoir reservoir = resolve(sender, args, 0);
			if(!PetroleumConfig.enabled)
				msg(sender, TextFormatting.YELLOW+"Petroleum is disabled in the config."+TextFormatting.RESET);

			msg(sender, TextFormatting.GOLD+"Reservoir"+TextFormatting.RESET
					+(CityMode.petroleum()?" "+TextFormatting.AQUA+"[city mode -- never depletes]"
					+TextFormatting.RESET: ""));
			if(reservoir.isEmpty())
			{
				msg(sender, "  "+describe(reservoir));
				msg(sender, TextFormatting.GRAY+"  Deposits are rolled per "
						+PetroleumConfig.cellChunkSize+"x"+PetroleumConfig.cellChunkSize
						+"-chunk cell; try further afield."+TextFormatting.RESET);
				return;
			}

			ReservoirType type = reservoir.resolveType();
			msg(sender, "  type: "+reservoir.getType()
					+(type==null?TextFormatting.RED+" (no longer registered)"+TextFormatting.RESET
					: " -> "+type.getFluidName()));
			msg(sender, "  remaining: "+describe(reservoir));
			boolean free = ReservoirModel.isFreeFlowing(reservoir);
			msg(sender, "  pressure: "+(free
					?TextFormatting.GREEN+"free-flowing"+TextFormatting.RESET+" (a wellhead is enough)"
					: TextFormatting.YELLOW+"depleted"+TextFormatting.RESET+" (needs a pump)"));
			msg(sender, String.format(Locale.ENGLISH, "  rate: %.3f mB/t unpumped, %.3f mB/t pumped",
					ReservoirModel.flowRate(reservoir, false),
					ReservoirModel.flowRate(reservoir, true)));
		}
	}

	private class SubTypes extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "types";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie reservoir types";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args)
		{
			if(ReservoirHandler.getTypes().isEmpty())
			{
				msg(sender, "No reservoir types are registered, so no world will roll any.");
				return;
			}
			int total = ReservoirHandler.getTotalWeight();
			msg(sender, TextFormatting.GOLD+"Reservoir types:"+TextFormatting.RESET
					+"  (cell chance "+PetroleumConfig.cellChance+")");
			for(ReservoirType type : ReservoirHandler.getTypes())
				msg(sender, String.format(Locale.ENGLISH, "  %s -> %s, weight %d (%.0f%%), %d-%d mB",
						type.getName(), type.getFluidName(), type.getWeight(),
						total > 0?100.0*type.getWeight()/total: 0,
						type.getMinCapacity(), type.getMaxCapacity()));
		}
	}

	private class SubDeplete extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "deplete";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie reservoir deplete <amount|all> [x] [z]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args) throws CommandException
		{
			if(args.length < 1)
				throw new CommandException(getUsage(sender));
			Reservoir reservoir = resolve(sender, args, 1);
			if(reservoir.isEmpty())
				throw new CommandException("There is no reservoir here");

			int amount = "all".equalsIgnoreCase(args[0])?reservoir.getRemaining()
					: CommandBase.parseInt(args[0], 0);
			int taken = reservoir.deplete(amount);
			PetroleumSaveData.setDirty();
			msg(sender, "Drew "+taken+" mB; now "+describe(reservoir));
		}
	}

	/**
	 * Puts fluid back. The recovery half of this command family -- for undoing a testing
	 * mistake or restoring a field lost to a bug, not a gameplay mechanic.
	 */
	private class SubRefill extends CommandBase
	{
		@Nonnull
		@Override
		public String getName()
		{
			return "refill";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie reservoir refill [amount|all] [x] [z]";
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
							@Nonnull String[] args) throws CommandException
		{
			Reservoir reservoir = resolve(sender, args, 1);
			if(reservoir.isEmpty())
				throw new CommandException("There is no reservoir here");

			int amount = args.length < 1||"all".equalsIgnoreCase(args[0])
					?reservoir.getOriginalCapacity(): CommandBase.parseInt(args[0], 0);
			int restored = reservoir.restore(amount);
			PetroleumSaveData.setDirty();
			msg(sender, "Restored "+restored+" mB; now "+describe(reservoir));
		}
	}
}
