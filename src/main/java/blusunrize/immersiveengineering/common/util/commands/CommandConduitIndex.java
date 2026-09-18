/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitIndex;
import blusunrize.immersiveengineering.common.blocks.conduit.ConduitRoute.Node;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nonnull;

/**
 * {@code /ie conduitindex} -- how much conduit hardware the server has written down, and where.
 * <p>
 * For the server operator, and written for the same reason {@code /ie virtualconduit} was: a feature
 * that works while nobody is looking needs one place that says what it knows, or the only symptom of
 * its being wrong is a dark town and a shrug. The last outage was diagnosed from {@code /ie virtualgen}
 * and nothing else.
 * <p>
 * <strong>What to read into the numbers.</strong> Runs form from this table, so a run that is not in
 * the wire graph and not in here is a run nobody has travelled since the index arrived -- fly it once,
 * in pieces if you like, and it links. A count far below the conduit somebody says they built means
 * the file has been deleted or has never been written; a count far above it means blocks have been
 * edited out from under the index, which the per-chunk sweep only finds when those chunks load again.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class CommandConduitIndex extends CommandBase
{
	@Nonnull
	@Override
	public String getName()
	{
		return "conduitindex";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "/ie conduitindex -- how much conduit hardware is indexed, per dimension";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 2;
	}

	@Override
	public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender,
						@Nonnull String[] args)
	{
		ConduitIndex index = ConduitIndex.INSTANCE;
		msg(sender, TextFormatting.GOLD+"Conduit index"+TextFormatting.RESET+": "+index.size()
				+" block(s) of hardware across "+index.dimensions().size()+" dimension(s)");
		if(index.isEmpty())
		{
			//Said plainly, because an empty table and a missing feature look identical from here, and
			//the answer to both is the same one gesture.
			msg(sender, TextFormatting.YELLOW+"Nothing indexed. Runs will only form while every block "
					+"of them is loaded at once -- travel a line once to fix it."+TextFormatting.RESET);
			return;
		}
		for(int dimension : index.dimensions())
			msg(sender, " dim "+dimension+": "+index.size(dimension)+" in "
					+index.chunkCount(dimension)+" chunk(s) -- "
					+index.count(dimension, Node.CONDUIT)+" conduit, "
					+index.count(dimension, Node.JUNCTION)+" box, "
					+index.count(dimension, Node.PASS_THROUGH)+" feeder");
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}
}
