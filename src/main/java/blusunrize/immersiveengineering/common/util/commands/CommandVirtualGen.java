/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGenConfig;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualGeneration;
import blusunrize.immersiveengineering.api.energy.virtualgen.VirtualSource;
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
 * {@code /ie virtualgen} -- every metered plant on the server: where it is, what it measured, whether it
 * is running for real or virtually right now, and what it delivered on the last tick.
 * <p>
 * For the server operator more than the player: virtual generation is invisible by design, and this is
 * the one place that says which plants are supplying from memory.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class CommandVirtualGen extends CommandBase
{
	@Nonnull
	@Override
	public String getName()
	{
		return "virtualgen";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "/ie virtualgen -- lists every Generation Meter and what its plant is supplying";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 2;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
	{
		boolean city = CityMode.wires();
		msg(sender, TextFormatting.GOLD+"Virtual generation"+TextFormatting.RESET+": "
				+(VirtualGenConfig.enabled?"enabled": TextFormatting.RED+"disabled in config"+TextFormatting.RESET)
				+", wires in "+(city?"City Mode": "normal mode")+", "+VirtualGeneration.INSTANCE.size()+" meter(s)");
		for(VirtualSource source : VirtualGeneration.INSTANCE.getSources())
		{
			BlockPos m = source.getMeterPos();
			String state;
			if(!source.isEnabled())
				state = TextFormatting.GRAY+"disabled";
			else if(!source.qualifies(city))
				state = TextFormatting.YELLOW+"burns fuel -- not virtual outside City Mode";
			else if(source.isVirtualActive())
				state = TextFormatting.AQUA+"VIRTUAL, delivered "+source.getLastVirtualDelivered()+" IF last tick";
			else if(isMeterLoaded(source))
				state = TextFormatting.GREEN+"running for real";
			else
				state = TextFormatting.GRAY+"idle (nothing measured, or its dimension is unloaded)";
			msg(sender, " dim "+source.getDimension()+" "+m.getX()+" "+m.getY()+" "+m.getZ()+": "
					+source.getMeasuredRate()+" IF/t measured, "+source.getVirtualRate()+" IF/t virtual cap, "
					+(source.isFreeSource()?"fuel-free": "burns fuel")+" -- "+state+TextFormatting.RESET);
		}
	}

	private static boolean isMeterLoaded(VirtualSource source)
	{
		net.minecraft.world.World world = DimensionManager.getWorld(source.getDimension());
		return world!=null&&world.isBlockLoaded(source.getMeterPos());
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}
}
