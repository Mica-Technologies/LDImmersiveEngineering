/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * Which wire, if any, is strung to each of a junction box's six faces.
 * <p>
 * The sibling of {@link ConduitPatch}: that one says which conductor leaves by a face, this one says
 * what is hung on it. The two are deliberately separate tables even though a wired face is always a
 * patched one, because they are removed by different events -- taking a wire down frees the face for
 * another wire and leaves the breakout exactly where it was, the same rule a connector taken off a
 * box has always followed.
 * <p>
 * <strong>One wire per face, and the face is where the player clicked.</strong> Six faces, six
 * wires, one circuit each: a box is a place where a bundle splits, and a face that carried two wires
 * would be two circuits on one conductor, which is a short. The alternative -- a box that simply
 * accumulates wires and works out later which conductor each of them meant -- is what asking "which
 * face?" at the moment of attachment saves everybody from.
 * <p>
 * The wire's kind is held as {@code WireType.getUniqueName()} rather than as a {@code WireType},
 * because that is what survives being written to disk and what the registry is keyed on -- and
 * because it keeps this class world-free, so its rules can be asserted without a game running.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class JunctionWires
{
	private final BlockPos[] ends = new BlockPos[6];
	private final String[] types = new String[6];

	/**
	 * @return the far end of the wire on that face, or null if there is none
	 */
	@Nullable
	public BlockPos endOf(@Nullable EnumFacing face)
	{
		return face==null?null: ends[face.ordinal()];
	}

	/**
	 * @return the unique name of the wire type on that face, or null if there is none
	 */
	@Nullable
	public String typeOf(@Nullable EnumFacing face)
	{
		return face==null?null: types[face.ordinal()];
	}

	public boolean has(@Nullable EnumFacing face)
	{
		return endOf(face)!=null;
	}

	/**
	 * @return one bit per face that already carries a wire
	 */
	public int mask()
	{
		int mask = 0;
		for(EnumFacing face : EnumFacing.VALUES)
			if(ends[face.ordinal()]!=null)
				mask |= 1<<face.ordinal();
		return mask;
	}

	public int count()
	{
		return Integer.bitCount(mask());
	}

	public boolean isEmpty()
	{
		return mask()==0;
	}

	/**
	 * Records the wire a player just strung to a face.
	 *
	 * @return true if anything changed
	 */
	public boolean set(EnumFacing face, BlockPos end, String type)
	{
		if(face==null||end==null||type==null)
			return false;
		if(end.equals(ends[face.ordinal()])&&type.equals(types[face.ordinal()]))
			return false;
		ends[face.ordinal()] = end;
		types[face.ordinal()] = type;
		return true;
	}

	/**
	 * @return true if anything changed
	 */
	public boolean clear(@Nullable EnumFacing face)
	{
		if(face==null||ends[face.ordinal()]==null)
			return false;
		ends[face.ordinal()] = null;
		types[face.ordinal()] = null;
		return true;
	}

	/**
	 * Which face a particular wire is on, which is the question every incoming packet of energy asks.
	 * <p>
	 * Matched on the wire's kind as well as its far end. Two nodes may be joined by more than one
	 * connection -- a conduit bundle and a strung wire between the same pair of boxes is the obvious
	 * case -- and answering with the wrong one would credit the wrong conductor.
	 *
	 * @return the face, or null if no wire here matches
	 */
	@Nullable
	public EnumFacing faceOf(@Nullable BlockPos end, @Nullable String type)
	{
		if(end==null||type==null)
			return null;
		for(EnumFacing face : EnumFacing.VALUES)
			if(end.equals(ends[face.ordinal()])&&type.equals(types[face.ordinal()]))
				return face;
		return null;
	}

	public void clearAll()
	{
		for(int i = 0; i < ends.length; i++)
		{
			ends[i] = null;
			types[i] = null;
		}
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		for(EnumFacing face : EnumFacing.VALUES)
		{
			BlockPos end = ends[face.ordinal()];
			if(end==null)
				continue;
			NBTTagCompound one = new NBTTagCompound();
			one.setIntArray("end", new int[]{end.getX(), end.getY(), end.getZ()});
			one.setString("type", types[face.ordinal()]);
			tag.setTag(face.getName(), one);
		}
		return tag;
	}

	public void readFromNBT(@Nullable NBTTagCompound tag)
	{
		clearAll();
		if(tag==null)
			return;
		for(EnumFacing face : EnumFacing.VALUES)
		{
			if(!tag.hasKey(face.getName()))
				continue;
			NBTTagCompound one = tag.getCompoundTag(face.getName());
			int[] end = one.getIntArray("end");
			String type = one.getString("type");
			//A malformed entry clears the face rather than guessing at it. The wire graph itself is
			//saved by IE and is the authority; this table only says which face a wire is on, and a
			//face that comes back blank is re-adopted on load rather than silently pointing nowhere.
			if(end.length < 3||type.isEmpty())
				continue;
			ends[face.ordinal()] = new BlockPos(end[0], end[1], end[2]);
			types[face.ordinal()] = type;
		}
	}
}
