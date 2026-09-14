/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.*;

/**
 * The server-wide table of metered plants.
 * <p>
 * Keyed by dimension and meter position, since a meter is the thing a player places and breaks. A
 * process-global singleton like {@code VirtualGrid}, cleared when a world's save data is loaded so a
 * second world in one session starts clean.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class VirtualGeneration
{
	public static final VirtualGeneration INSTANCE = new VirtualGeneration();

	private final Map<Key, VirtualSource> sources = new LinkedHashMap<>();
	@Nullable
	private Runnable dirtyListener;

	public VirtualGeneration()
	{
	}

	@Nullable
	public VirtualSource get(int dimension, BlockPos meterPos)
	{
		return sources.get(new Key(dimension, meterPos));
	}

	/**
	 * Returns the record for a meter, creating it if absent. The sides are refreshed either way, so a
	 * rotated meter cannot keep pushing from where its connector used to be.
	 */
	public VirtualSource getOrCreate(int dimension, BlockPos meterPos, BlockPos generatorPos, BlockPos sourcePos)
	{
		Key key = new Key(dimension, meterPos);
		VirtualSource source = sources.get(key);
		if(source==null)
		{
			source = new VirtualSource(dimension, meterPos, generatorPos, sourcePos);
			sources.put(key, source);
			markDirty();
		}
		else if(!source.getGeneratorPos().equals(generatorPos)||!source.getSourcePos().equals(sourcePos))
		{
			source.setSides(generatorPos, sourcePos);
			markDirty();
		}
		return source;
	}

	public boolean remove(int dimension, BlockPos meterPos)
	{
		boolean removed = sources.remove(new Key(dimension, meterPos))!=null;
		if(removed)
			markDirty();
		return removed;
	}

	public Collection<VirtualSource> getSources()
	{
		return Collections.unmodifiableCollection(sources.values());
	}

	public int size()
	{
		return sources.size();
	}

	public void clear()
	{
		sources.clear();
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
		sources.clear();
		NBTTagList list = nbt.getTagList("sources", 10);
		for(int i = 0; i < list.tagCount(); i++)
		{
			VirtualSource source = VirtualSource.readFromNBT(list.getCompoundTagAt(i));
			if(source!=null)
				sources.put(new Key(source.getDimension(), source.getMeterPos()), source);
		}
	}

	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		NBTTagList list = new NBTTagList();
		for(VirtualSource source : sources.values())
			list.appendTag(source.writeToNBT());
		nbt.setTag("sources", list);
		return nbt;
	}

	private static final class Key
	{
		final int dimension;
		final BlockPos pos;

		Key(int dimension, BlockPos pos)
		{
			this.dimension = dimension;
			this.pos = pos.toImmutable();
		}

		@Override
		public boolean equals(Object o)
		{
			if(!(o instanceof Key))
				return false;
			Key k = (Key)o;
			return k.dimension==dimension&&k.pos.equals(pos);
		}

		@Override
		public int hashCode()
		{
			return 31*pos.hashCode()+dimension;
		}
	}
}
