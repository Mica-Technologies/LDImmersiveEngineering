/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.util.commands;

import blusunrize.immersiveengineering.api.DimensionBlockPos;
import blusunrize.immersiveengineering.api.energy.wires.GhostConnectionAudit;
import blusunrize.immersiveengineering.api.energy.wires.IImmersiveConnectable;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler;
import blusunrize.immersiveengineering.api.energy.wires.ImmersiveNetHandler.Connection;
import blusunrize.immersiveengineering.common.IESaveData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.server.command.CommandTreeBase;
import net.minecraftforge.server.command.CommandTreeHelp;

import javax.annotation.Nonnull;
import java.util.*;

/**
 * {@code /ie wires} -- wire network maintenance for server operators.
 *
 * @author LDImmersiveEngineering -- wire maintenance
 */
public class CommandWires extends CommandTreeBase
{
	{
		addSubcommand(new SubGhosts());
		addSubcommand(new CommandTreeHelp(this));
	}

	@Nonnull
	@Override
	public String getName()
	{
		return "wires";
	}

	@Nonnull
	@Override
	public String getUsage(@Nonnull ICommandSender sender)
	{
		return "Use \"/ie wires help\" for more information";
	}

	@Override
	public int getRequiredPermissionLevel()
	{
		return 2;
	}

	/**
	 * {@code /ie wires ghosts [check] [remove]} -- reports, and optionally removes, wires whose endpoint blocks are
	 * gone. See {@link GhostConnectionAudit} for how an endpoint is judged without loading it.
	 * <p>
	 * Only certain ghosts are ever removed. {@code check} settles the suspect ones by loading their chunks for the
	 * length of the command; a connector that turns out to be there takes its proxy back as the chunk unloads, so a
	 * check also repairs a network that lost its proxies.
	 */
	private static class SubGhosts extends CommandBase
	{
		/** Chunks one check may load, so a badly broken dimension is worked through over several runs. */
		private static final int CHECK_CHUNK_LIMIT = 256;

		@Nonnull
		@Override
		public String getName()
		{
			return "ghosts";
		}

		@Nonnull
		@Override
		public String getUsage(@Nonnull ICommandSender sender)
		{
			return "/ie wires ghosts [check] [remove] -- report wires whose endpoints are gone in this dimension; "
					+"check loads suspect endpoints' chunks to settle them, remove deletes the wires known to be broken";
		}

		@Override
		public int getRequiredPermissionLevel()
		{
			return 2;
		}

		@Override
		public void execute(@Nonnull MinecraftServer server, @Nonnull ICommandSender sender, @Nonnull String[] args)
				throws CommandException
		{
			World world = sender.getEntityWorld();
			if(world.isRemote)
				return;
			Set<String> given = new HashSet<>();
			for(String a : args)
				given.add(a.toLowerCase(Locale.ROOT));
			boolean check = given.remove("check"), remove = given.remove("remove");
			if(!given.isEmpty())
				throw new CommandException("Unknown option(s) "+given+". "+getUsage(sender));
			int dim = world.provider.getDimension();

			GhostConnectionAudit.Result result = audit(world, dim);
			List<Chunk> loadedForCheck = new ArrayList<>();
			int unchecked = 0;
			if(check&&!result.unproxiedNodes.isEmpty())
			{
				Set<ChunkPos> chunks = new LinkedHashSet<>();
				for(BlockPos p : result.unproxiedNodes)
					chunks.add(new ChunkPos(p));
				for(ChunkPos cp : chunks)
				{
					if(loadedForCheck.size() >= CHECK_CHUNK_LIMIT)
						unchecked++;
					else
						loadedForCheck.add(world.getChunk(cp.x, cp.z));
				}
				result = audit(world, dim);
			}

			try
			{
				report(sender, dim, result, check);
				if(unchecked > 0)
					msg(sender, TextFormatting.GRAY+"  "+unchecked+" suspect chunk(s) were left for the next check (limit "
							+CHECK_CHUNK_LIMIT+" per run)."+TextFormatting.RESET);
				if(!remove)
				{
					if(!result.missing.isEmpty())
						msg(sender, TextFormatting.GRAY+"Nothing changed. Use \"/ie wires ghosts "+(check?"check ": "")
								+"remove\" to delete the missing ones."+TextFormatting.RESET);
					else if(!result.unproxied.isEmpty())
						msg(sender, TextFormatting.GRAY+"Nothing changed. Use \"/ie wires ghosts check\" to settle the unproxied ones."
								+TextFormatting.RESET);
					return;
				}
				for(Connection con : result.missing)
					//The offsets are only used to clear the block-wire map along the catenary; with an endpoint gone
					//there is no model to ask, and the connection's own ends are close enough.
					ImmersiveNetHandler.INSTANCE.removeConnection(world, con, Vec3d.ZERO, Vec3d.ZERO);
				IESaveData.setDirty(dim);
				msg(sender, TextFormatting.GREEN+"Removed "+result.missing.size()+" wire(s)."+TextFormatting.RESET
						+(result.unproxied.isEmpty()?"": " Unproxied ones were left; \"check\" settles them."));
			}
			finally
			{
				//Hand the chunks back. Anything a player is standing in is kept by the player map, and a connector
				//that is really there leaves a proxy as its chunk goes.
				if(world.getChunkProvider() instanceof ChunkProviderServer)
					for(Chunk c : loadedForCheck)
						((ChunkProviderServer)world.getChunkProvider()).queueUnload(c);
			}
		}

		private static GhostConnectionAudit.Result audit(World world, int dim)
		{
			return GhostConnectionAudit.audit(
					ImmersiveNetHandler.INSTANCE.getAllConnections(world),
					world::isBlockLoaded,
					pos -> world.getTileEntity(pos) instanceof IImmersiveConnectable,
					pos -> ImmersiveNetHandler.INSTANCE.proxies.containsKey(new DimensionBlockPos(pos, dim)));
		}

		private static void report(ICommandSender sender, int dim, GhostConnectionAudit.Result result, boolean checked)
		{
			msg(sender, TextFormatting.GOLD+"Wire audit"+TextFormatting.RESET+", dimension "+dim+": "
					+result.inspected+" wire(s) inspected"+(checked?", suspects checked": "")+".");
			msg(sender, "  "+result.missing.size()+" wire(s) with a "+TextFormatting.RED+"missing"+TextFormatting.RESET
					+" endpoint (loaded, no connectable block): "+sample(result.missingNodes));
			msg(sender, "  "+result.unproxied.size()+" wire(s) with an "+TextFormatting.YELLOW+"unproxied"
					+TextFormatting.RESET+" endpoint (unloaded, no proxy -- deleted, or saved without its proxy): "
					+sample(result.unproxiedNodes));
		}

		private static String sample(Set<BlockPos> nodes)
		{
			if(nodes.isEmpty())
				return "none";
			StringBuilder sb = new StringBuilder();
			int i = 0;
			for(BlockPos p : nodes)
			{
				if(i++==5)
				{
					sb.append(", ... (").append(nodes.size()).append(" nodes)");
					break;
				}
				if(sb.length() > 0)
					sb.append(", ");
				sb.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
			}
			return sb.toString();
		}
	}

	private static void msg(ICommandSender sender, String text)
	{
		sender.sendMessage(new TextComponentString(text));
	}
}
