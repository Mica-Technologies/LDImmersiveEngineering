/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualconduit;

import blusunrize.immersiveengineering.api.energy.wires.conduit.WireChannel;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The server-wide table of conduit breakouts that were delivering into a wire.
 * <p>
 * Keyed by dimension, box and conductor, because that is the thing that keeps having to happen: one
 * conductor of one box handing its energy to the wire on its own face. See {@link VirtualConduitLink}
 * for why the middle of a run needs no entry and why the sixteen are kept apart.
 * <p>
 * A process-global singleton, exactly as {@code VirtualGeneration} and {@code VirtualGrid} are, and
 * cleared when a world's save data loads so a second world in one session starts clean.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduits
{
	public static final VirtualConduits INSTANCE = new VirtualConduits();

	private final Map<Key, VirtualConduitLink> links = new LinkedHashMap<>();
	@Nullable
	private Runnable dirtyListener;

	@Nullable
	public VirtualConduitLink get(int dimension, BlockPos boxPos, WireChannel channel)
	{
		return links.get(new Key(dimension, boxPos, channel));
	}

	/**
	 * Record what a loaded box is handing to the wire on a conductor's face.
	 * <p>
	 * Creating the record and updating it are one call because the caller -- a box that has just
	 * successfully sent something -- does not care which it is, and splitting them would mean every
	 * call site doing the same null dance.
	 */
	public VirtualConduitLink observe(int dimension, BlockPos boxPos, WireChannel channel,
									  int rate, String wireTypeName, BlockPos wireEnd)
	{
		Key key = new Key(dimension, boxPos, channel);
		VirtualConduitLink link = links.get(key);
		if(link==null)
		{
			link = new VirtualConduitLink(dimension, boxPos, channel);
			links.put(key, link);
			link.observe(rate, wireTypeName, wireEnd);
			markDirty();
			return link;
		}
		if(link.observe(rate, wireTypeName, wireEnd))
			markDirty();
		return link;
	}

	/**
	 * Forget one conductor of one box: its circuit went dark while somebody was watching, so it must
	 * not go on supplying once nobody is.
	 */
	public boolean remove(int dimension, BlockPos boxPos, WireChannel channel)
	{
		boolean removed = links.remove(new Key(dimension, boxPos, channel))!=null;
		if(removed)
			markDirty();
		return removed;
	}

	/**
	 * Forget a whole box, for when the block itself is gone.
	 *
	 * @return how many conductors were dropped
	 */
	public int removeBox(int dimension, BlockPos boxPos)
	{
		int dropped = 0;
		for(WireChannel channel : WireChannel.VALUES)
			if(links.remove(new Key(dimension, boxPos, channel))!=null)
				dropped++;
		if(dropped > 0)
			markDirty();
		return dropped;
	}

	public Collection<VirtualConduitLink> getLinks()
	{
		return Collections.unmodifiableCollection(links.values());
	}

	public int size()
	{
		return links.size();
	}

	public void clear()
	{
		links.clear();
	}

	public void setDirtyListener(@Nullable Runnable listener)
	{
		this.dirtyListener = listener;
	}

	public void markDirty()
	{
		if(dirtyListener!=null)
			dirtyListener.run();
	}

	public void readFromNBT(NBTTagCompound nbt)
	{
		links.clear();
		NBTTagList list = nbt.getTagList("links", 10);
		for(int i = 0; i < list.tagCount(); i++)
		{
			VirtualConduitLink link = VirtualConduitLink.readFromNBT(list.getCompoundTagAt(i));
			if(link!=null)
				links.put(new Key(link.getDimension(), link.getBoxPos(), link.getChannel()), link);
		}
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList list = new NBTTagList();
		for(VirtualConduitLink link : links.values())
			list.appendTag(link.writeToNBT());
		nbt.setTag("links", list);
		return nbt;
	}

	private static final class Key
	{
		private final int dimension;
		private final BlockPos pos;
		private final WireChannel channel;

		Key(int dimension, BlockPos pos, WireChannel channel)
		{
			this.dimension = dimension;
			//Immutable: a BlockPos.MutableBlockPos used as a key would change under the map.
			this.pos = pos.toImmutable();
			this.channel = channel;
		}

		@Override
		public boolean equals(Object o)
		{
			if(this==o)
				return true;
			if(!(o instanceof Key))
				return false;
			Key other = (Key)o;
			return dimension==other.dimension&&channel==other.channel&&pos.equals(other.pos);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(dimension, pos, channel);
		}
	}
}
