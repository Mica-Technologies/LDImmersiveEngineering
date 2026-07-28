/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The payload a conduit connection carries: up to sixteen independent conductors on one edge.
 * <p>
 * This is the whole of the decision that keeps conduits affordable. A sixteen-channel run is
 * <em>one</em> {@code Connection} in {@code ImmersiveNetHandler}, not sixteen, so the connection
 * graph -- and everything that walks it every tick -- stays exactly the size it is today however
 * many circuits a corridor carries. What varies is the payload on the edge, and that is this
 * class.
 * <p>
 * Each channel keeps its own wire's rate and loss rather than dividing a shared allowance: a
 * bundle is sixteen wires in a tidy sleeve, and each behaves precisely as that wire behaves on its
 * own. A player who replaces sixteen catenaries with one conduit gets the same throughput and the
 * same losses, and pays a sixteenth of the tick cost for the privilege of it looking right.
 * <p>
 * <strong>World-free.</strong> Nothing here touches the item registry, the texture atlas or a
 * {@code World} -- see {@link ChannelSpec} for why the wire type is a name and a snapshot rather
 * than a reference. That is what makes the model testable, and it is not an accident.
 * <p>
 * Not thread-safe, and does not need to be: a connection's payload is touched from the server
 * thread only.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ChannelSet
{
	/**
	 * Which channels are patched, one bit each. Kept alongside the map so the common questions --
	 * "is this channel live", "do these two runs share anything" -- are a bit test rather than a
	 * map lookup. {@link #specs} is the authority; this is derived and must never disagree.
	 */
	private int mask;
	private final Map<WireChannel, ChannelSpec> specs = new EnumMap<>(WireChannel.class);

	/**
	 * What each channel moved on the last tick, for readouts. Transient by design: it is a
	 * measurement, not a setting, and writing it to disk would mean a freshly-loaded world claiming
	 * a conduit was busy at the instant it was saved.
	 */
	private final Map<WireChannel, Integer> lastThroughput = new EnumMap<>(WireChannel.class);

	public ChannelSet()
	{
	}

	public ChannelSet(ChannelSet copyOf)
	{
		mask = copyOf.mask;
		specs.putAll(copyOf.specs);
		//Throughput is deliberately not copied: a copy is a new edge, and it has not carried
		//anything yet.
	}

	/**
	 * Patch a channel, or re-patch it to a different wire.
	 *
	 * @return the spec previously on that channel, or null if it was free
	 */
	@Nullable
	public ChannelSpec patch(WireChannel channel, ChannelSpec spec)
	{
		if(channel==null||spec==null)
			throw new IllegalArgumentException("patch needs both a channel and a spec");
		mask |= channel.getMask();
		return specs.put(channel, spec);
	}

	/**
	 * Unpatch a channel. Its throughput reading goes with it -- a channel that is no longer there
	 * must not keep reporting what it carried before somebody pulled it.
	 *
	 * @return the spec that was on it, or null if it was already free
	 */
	@Nullable
	public ChannelSpec unpatch(WireChannel channel)
	{
		if(channel==null)
			return null;
		mask &= ~channel.getMask();
		lastThroughput.remove(channel);
		return specs.remove(channel);
	}

	public boolean isPatched(WireChannel channel)
	{
		return channel!=null&&(mask&channel.getMask())!=0;
	}

	@Nullable
	public ChannelSpec getSpec(WireChannel channel)
	{
		return channel==null?null: specs.get(channel);
	}

	/**
	 * @return the patched channels as a bit mask, for cheap comparison against another set
	 */
	public int getMask()
	{
		return mask;
	}

	public int size()
	{
		return specs.size();
	}

	public boolean isEmpty()
	{
		return specs.isEmpty();
	}

	/**
	 * The channels a route can actually carry from here to there, and what each costs.
	 * <p>
	 * Only channels patched at <em>both</em> ends survive -- a conductor that stops at a junction
	 * box does not continue past it -- and each survivor takes the reconciled spec, so the route is
	 * as fat as its narrowest segment. This is the bundle's version of what the existing wire path
	 * walk does with {@code minimumType}, done sixteen times over one edge instead of once over
	 * sixteen.
	 *
	 * @return a new set; neither input is modified
	 */
	public ChannelSet intersect(@Nullable ChannelSet other)
	{
		ChannelSet result = new ChannelSet();
		if(other==null)
			return result;
		int shared = mask&other.mask;
		if(shared==0)
			return result;
		for(WireChannel channel : WireChannel.VALUES)
		{
			if((shared&channel.getMask())==0)
				continue;
			ChannelSpec reconciled = specs.get(channel).reconcile(other.specs.get(channel));
			if(reconciled!=null)
				result.patch(channel, reconciled);
		}
		return result;
	}

	/**
	 * Re-read every channel's wire from the registry, replacing the cached rate and loss.
	 * <p>
	 * The snapshot in {@link ChannelSpec} is what keeps the transfer loop off the registry, and the
	 * price of it is that a config change to a wire's rate does not reach existing conduits by
	 * itself. This is how it reaches them: called once at server start with a resolver that knows
	 * about {@code WireType}, so the per-tick path never has to.
	 * <p>
	 * A channel whose wire has gone away entirely -- a mod removed, a type renamed -- is unpatched
	 * rather than left holding a stale figure. Losing the circuit is visible; silently carrying
	 * energy down a wire that no longer exists is not.
	 *
	 * @param resolver wire unique name to its current spec, or null if that wire is gone
	 * @return how many channels were dropped because their wire no longer exists
	 */
	public int refresh(Function<String, ChannelSpec> resolver)
	{
		int dropped = 0;
		for(WireChannel channel : WireChannel.VALUES)
		{
			ChannelSpec current = specs.get(channel);
			if(current==null)
				continue;
			ChannelSpec fresh = resolver.apply(current.getName());
			if(fresh==null)
			{
				unpatch(channel);
				dropped++;
			}
			else
				patch(channel, fresh);
		}
		return dropped;
	}

	public int getLastThroughput(WireChannel channel)
	{
		Integer value = channel==null?null: lastThroughput.get(channel);
		return value==null?0: value;
	}

	public void setLastThroughput(WireChannel channel, int amount)
	{
		//An unpatched channel cannot have carried anything, and letting it record a figure would
		//put a reading on a row the console does not draw.
		if(!isPatched(channel))
			return;
		lastThroughput.put(channel, Math.max(0, amount));
	}

	public void clearThroughput()
	{
		lastThroughput.clear();
	}

	/**
	 * @return the total across every channel, which is the number a bundle's overlay leads with
	 */
	public int getTotalThroughput()
	{
		int total = 0;
		for(Integer value : lastThroughput.values())
			total += value;
		return total;
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		for(Map.Entry<WireChannel, ChannelSpec> entry : specs.entrySet())
			tag.setTag(entry.getKey().getName(), entry.getValue().writeToNBT());
		return tag;
	}

	/**
	 * @return the set described by the tag. An absent or empty tag gives an empty set rather than
	 * null -- see {@code Connection.readFromNBT}, where "this save predates conduits" and "this
	 * conduit has nothing patched" have to be handled the same way for old worlds to load.
	 */
	public static ChannelSet readFromNBT(@Nullable NBTTagCompound tag)
	{
		ChannelSet set = new ChannelSet();
		if(tag==null)
			return set;
		for(WireChannel channel : WireChannel.VALUES)
		{
			if(!tag.hasKey(channel.getName()))
				continue;
			ChannelSpec spec = ChannelSpec.readFromNBT(tag.getCompoundTag(channel.getName()));
			//A channel whose spec will not parse is dropped, not defaulted. Guessing a wire type
			//would quietly rewire somebody's base.
			if(spec!=null)
				set.patch(channel, spec);
		}
		return set;
	}

	@Override
	public boolean equals(Object obj)
	{
		if(this==obj)
			return true;
		if(!(obj instanceof ChannelSet))
			return false;
		//Throughput is a measurement and is not part of identity: two edges patched the same way
		//are the same patching, whatever they happened to carry last tick.
		return specs.equals(((ChannelSet)obj).specs);
	}

	@Override
	public int hashCode()
	{
		return specs.hashCode();
	}

	@Override
	public String toString()
	{
		if(specs.isEmpty())
			return "ChannelSet[empty]";
		StringBuilder out = new StringBuilder("ChannelSet[");
		boolean first = true;
		for(WireChannel channel : WireChannel.VALUES)
		{
			ChannelSpec spec = specs.get(channel);
			if(spec==null)
				continue;
			if(!first)
				out.append(", ");
			out.append(channel.getName()).append('=').append(spec.getName());
			first = false;
		}
		return out.append(']').toString();
	}
}
