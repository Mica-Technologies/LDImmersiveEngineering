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
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * What one conductor of one junction box was delivering into the wire on its breakout face, kept so
 * that it can go on delivering while the box's own chunk is unloaded.
 * <p>
 * <strong>Why a conduit needs this at all.</strong> A run moves energy one hop per tick inside
 * {@code TileEntityJunctionBox.update()}, and an unloaded block does not tick. A wire does not have
 * the problem: {@code IICProxy} stands in for an unloaded connector and the route search steps over
 * it. There is no equivalent for a box, because a proxy is pass-through only -- it answers false to
 * {@code isEnergyOutput} and zero to {@code outputEnergy}, so it can carry energy past itself but
 * never receive any -- and the route search refuses to cross a bundle at all. So an inter-town
 * conduit, whose middle is unloaded essentially always, carries nothing. Fly the line and the far
 * town lights up; leave and it goes dark.
 * <p>
 * <strong>One record per conductor per box, not per run.</strong> The thing that has to keep
 * happening is the last step: the far box handing its conductor's energy to the wire strung to that
 * conductor's face. What the middle of the run is doing does not need modelling, because nothing
 * loaded can observe it. Recording it per conductor is also what keeps the sixteen separate -- a
 * bundle carrying a lighting circuit and a workshop circuit must not merge them the moment nobody is
 * standing there.
 * <p>
 * {@link #getWireTypeName()} and {@link #getWireEnd()} are the two halves of the filter
 * {@code handToWire} builds to keep a conductor's energy on its own face's wire. They are stored
 * rather than re-derived because deriving them needs the box's patch and wire tables, which are the
 * tile entity's state and therefore exactly what is missing when this record is used.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduitLink
{
	private final int dimension;
	private final BlockPos boxPos;
	private final WireChannel channel;

	/**
	 * What the conductor was handing to that wire, IF/t, measured while the box was loaded.
	 */
	private int rate;
	/**
	 * The wire on the breakout face, as {@code WireType.getUniqueName()}, and the node at its far
	 * end. Together they are the route filter: a route counts if its first hop is that wire type and
	 * arrives at that position.
	 */
	private String wireTypeName;
	private BlockPos wireEnd;

	/**
	 * Not saved: what this link actually delivered last tick, for the readouts and the command.
	 */
	private int lastDelivered;

	public VirtualConduitLink(int dimension, BlockPos boxPos, WireChannel channel)
	{
		this.dimension = dimension;
		this.boxPos = boxPos.toImmutable();
		this.channel = channel;
	}

	public int getDimension()
	{
		return dimension;
	}

	public BlockPos getBoxPos()
	{
		return boxPos;
	}

	public WireChannel getChannel()
	{
		return channel;
	}

	public int getRate()
	{
		return rate;
	}

	@Nullable
	public String getWireTypeName()
	{
		return wireTypeName;
	}

	@Nullable
	public BlockPos getWireEnd()
	{
		return wireEnd;
	}

	/**
	 * Record what this conductor is handing to its wire, from a box that is loaded and doing it.
	 *
	 * @return true if anything changed, so the caller knows whether the save is now dirty
	 */
	public boolean observe(int rate, String wireTypeName, BlockPos wireEnd)
	{
		BlockPos end = wireEnd==null?null: wireEnd.toImmutable();
		boolean changed = this.rate!=rate
				||!java.util.Objects.equals(this.wireTypeName, wireTypeName)
				||!java.util.Objects.equals(this.wireEnd, end);
		this.rate = rate;
		this.wireTypeName = wireTypeName;
		this.wireEnd = end;
		return changed;
	}

	/**
	 * @return true when this record has everything the push needs. A record without a wire is a
	 * conductor that breaks out to something bolted against the box rather than to a catenary, and
	 * there is nothing for an unloaded box to hand a neighbour that is also unloaded.
	 */
	public boolean isUsable()
	{
		return rate > 0&&wireTypeName!=null&&!wireTypeName.isEmpty()&&wireEnd!=null;
	}

	public int getLastDelivered()
	{
		return lastDelivered;
	}

	public void setLastDelivered(int lastDelivered)
	{
		this.lastDelivered = Math.max(0, lastDelivered);
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("dim", dimension);
		tag.setLong("box", boxPos.toLong());
		tag.setString("channel", channel.getName());
		tag.setInteger("rate", rate);
		if(wireTypeName!=null)
			tag.setString("wire", wireTypeName);
		if(wireEnd!=null)
			tag.setLong("end", wireEnd.toLong());
		return tag;
	}

	/**
	 * @return the link, or null if the tag names a conductor this build does not have -- which is
	 * how a save written by a version with more of them comes back without throwing.
	 */
	@Nullable
	public static VirtualConduitLink readFromNBT(NBTTagCompound tag)
	{
		WireChannel channel = WireChannel.byName(tag.getString("channel"));
		if(channel==null)
			return null;
		VirtualConduitLink link = new VirtualConduitLink(tag.getInteger("dim"),
				BlockPos.fromLong(tag.getLong("box")), channel);
		link.rate = Math.max(0, tag.getInteger("rate"));
		link.wireTypeName = tag.hasKey("wire")?tag.getString("wire"): null;
		link.wireEnd = tag.hasKey("end")?BlockPos.fromLong(tag.getLong("end")): null;
		return link;
	}

	@Override
	public String toString()
	{
		return "VirtualConduitLink{dim="+dimension+" "+boxPos.getX()+" "+boxPos.getY()+" "+boxPos.getZ()
				+" "+channel.getName()+" "+rate+" IF/t}";
	}
}
