/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * One tick of virtual generation.
 * <p>
 * For every metered plant that is <em>not</em> running for real, push its measured output onto the wire
 * network from the connector it feeds. Nothing is ever loaded to do this: the push reaches only consumers
 * that are loaded anyway, through the same proxies that already carry power across unloaded poles.
 * <p>
 * <b>Real or virtual, never both.</b> A plant whose generator, meter and connector are all loaded is
 * generating for real, and pushing its measurement as well would double its supply. If any of the three
 * is unloaded the real path is broken and the virtual one takes over.
 * <p>
 * <b>Coalesced by connector.</b> Several meters feeding one connector push once, with their rates summed
 * and capped at that connector's rate -- the same ceiling the real connector would impose.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public final class VirtualGenEngine
{
	private VirtualGenEngine()
	{
	}

	/**
	 * @param cityMode whether wires are in City Mode, which decides both the push and whether a
	 *                 fuel-burning plant qualifies
	 * @return total energy delivered virtually this tick
	 */
	public static int tick(VirtualGeneration registry, IVirtualGenWorld world, boolean cityMode)
	{
		if(!VirtualGenConfig.enabled)
		{
			for(VirtualSource source : registry.getSources())
				source.setLive(false, 0);
			return 0;
		}

		Map<Group, List<VirtualSource>> groups = new LinkedHashMap<>();
		for(VirtualSource source : registry.getSources())
		{
			source.setLive(false, 0);
			if(!source.isEnabled()||!source.qualifies(cityMode)||source.getVirtualRate() <= 0)
				continue;
			int dim = source.getDimension();
			if(!world.isDimensionLoaded(dim))
				continue;
			if(isRunningForReal(world, source))
				continue;
			groups.computeIfAbsent(new Group(dim, source.getSourcePos()), g -> new ArrayList<>()).add(source);
		}

		int total = 0;
		for(Map.Entry<Group, List<VirtualSource>> entry : groups.entrySet())
		{
			List<VirtualSource> members = entry.getValue();
			long sum = 0;
			int cap = 0;
			for(VirtualSource member : members)
			{
				sum += member.getVirtualRate();
				cap = Math.max(cap, member.getConnectorRate());
			}
			int amount = (int)Math.min(Integer.MAX_VALUE, sum);
			if(cap > 0)
				amount = Math.min(amount, cap);
			if(amount <= 0)
				continue;
			Group group = entry.getKey();
			int delivered = Math.max(0, Math.min(amount, world.push(group.dimension, group.pos, amount,
					cap > 0?cap: amount, cityMode)));
			total += delivered;
			distribute(members, delivered, sum);
		}
		return total;
	}

	/**
	 * @return true when generator, meter and connector are all loaded, so the real generator is supplying
	 */
	public static boolean isRunningForReal(IVirtualGenWorld world, VirtualSource source)
	{
		int dim = source.getDimension();
		return world.isLoaded(dim, source.getGeneratorPos())&&world.isLoaded(dim, source.getMeterPos())
				&&world.isLoaded(dim, source.getSourcePos());
	}

	/**
	 * Splits a group's delivery back over its members for their readouts, in proportion to rate, with the
	 * rounding remainder given to the first so the parts always sum to the whole.
	 */
	private static void distribute(List<VirtualSource> members, int delivered, long sum)
	{
		if(members.size()==1||sum <= 0)
		{
			members.get(0).setLive(true, delivered);
			for(int i = 1; i < members.size(); i++)
				members.get(i).setLive(true, 0);
			return;
		}
		int given = 0;
		for(int i = 1; i < members.size(); i++)
		{
			VirtualSource member = members.get(i);
			int share = (int)(delivered*(long)member.getVirtualRate()/sum);
			member.setLive(true, share);
			given += share;
		}
		members.get(0).setLive(true, delivered-given);
	}

	private static final class Group
	{
		final int dimension;
		final BlockPos pos;

		Group(int dimension, BlockPos pos)
		{
			this.dimension = dimension;
			this.pos = pos;
		}

		@Override
		public boolean equals(Object o)
		{
			if(!(o instanceof Group))
				return false;
			Group g = (Group)o;
			return g.dimension==dimension&&g.pos.equals(pos);
		}

		@Override
		public int hashCode()
		{
			return 31*pos.hashCode()+dimension;
		}
	}
}
