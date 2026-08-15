/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

import blusunrize.immersiveengineering.common.blocks.conduit.ConduitGeometry.ArmMode;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import javax.annotation.Nullable;

/**
 * What each of a conduit's four arms is doing, and the save format that says so.
 * <p>
 * Three masks of four bits, indexed by {@link ConduitGeometry#inPlane} position: which arms exist
 * at all, which of them climb a corner, and which of them cap one. Split that way rather than
 * stored as one value per arm for a single reason, and it is the whole point of this class:
 * <p>
 * <strong>{@code connections} means exactly what it meant before corners existed.</strong> A world
 * saved when a run could only lie flat has that key and no other, so it loads with every arm
 * straight -- which is what those runs were. The two new masks are absent, read as zero, and a
 * player's base comes back looking as it did. Getting this wrong would not crash anything; it would
 * quietly redraw somebody's wiring, which is worse.
 * <p>
 * World-free, so the format can be tested without a game running.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class ConduitArms
{
	/** The key a run's arm mask has always been written under. Do not rename it. */
	private static final String CONNECTIONS = "connections";
	private static final String RISERS = "risers";
	private static final String WRAPS = "wraps";

	private static final int ALL = (1 << ConduitGeometry.ARMS)-1;

	/** One bit per arm: something joins in that direction, whatever shape the joint is. */
	private int connections;
	/** One bit per arm: that arm climbs the block in front of it. Always a subset of the above. */
	private int risers;
	/** One bit per arm: that arm draws the cube filling an outer corner. Likewise a subset. */
	private int wraps;

	public int getConnections()
	{
		return connections;
	}

	/** Which arms climb, for the one caller that wants the whole mask: the hitbox. */
	public int getRisers()
	{
		return risers;
	}

	public boolean isConnected(int arm)
	{
		return arm >= 0&&(connections&(1 << arm))!=0;
	}

	/**
	 * @param arm an index into {@link ConduitGeometry#inPlane}, or -1 for a direction that is not
	 *            in this conduit's plane at all
	 */
	public ArmMode mode(int arm)
	{
		if(!isConnected(arm))
			return ArmMode.NONE;
		int bit = 1 << arm;
		if((risers&bit)!=0)
			return ArmMode.RISER;
		return (wraps&bit)!=0?ArmMode.WRAP: ArmMode.STRAIGHT;
	}

	public void set(int arm, ArmMode mode)
	{
		if(arm < 0||arm >= ConduitGeometry.ARMS)
			return;
		int bit = 1 << arm;
		connections = mode==ArmMode.NONE?connections&~bit: connections|bit;
		risers = mode==ArmMode.RISER?risers|bit: risers&~bit;
		wraps = mode==ArmMode.WRAP?wraps|bit: wraps&~bit;
	}

	/**
	 * Take another set of arms as this one's.
	 *
	 * @return true if anything actually changed, so a caller can skip the block update and the
	 * packet it does not need. A conduit recomputes its arms on every neighbour change, and most
	 * neighbour changes are somebody walking past with a torch.
	 */
	public boolean copyFrom(ConduitArms other)
	{
		if(connections==other.connections&&risers==other.risers&&wraps==other.wraps)
			return false;
		connections = other.connections;
		risers = other.risers;
		wraps = other.wraps;
		return true;
	}

	public void writeToNBT(NBTTagCompound tag)
	{
		tag.setInteger(CONNECTIONS, connections);
		//Written only when there is something to say, so a flat run's tag is byte-identical to the
		//one it had before conduits could turn corners. Most conduit in most bases is flat.
		if(risers!=0)
			tag.setInteger(RISERS, risers);
		if(wraps!=0)
			tag.setInteger(WRAPS, wraps);
	}

	/**
	 * Absent masks read as zero, which is what makes an old world load: every arm it knew about was
	 * straight, and straight is what no riser and no wrap bit spells.
	 */
	public void readFromNBT(@Nullable NBTTagCompound tag)
	{
		if(tag==null)
		{
			connections = risers = wraps = 0;
			return;
		}
		connections = tag.getInteger(CONNECTIONS)&ALL;
		//Masked against the connections rather than against ALL: a riser on an arm that is not
		//there is a model drawn into empty space, and a tag can say anything.
		risers = tag.getInteger(RISERS)&connections;
		wraps = tag.getInteger(WRAPS)&connections&~risers;
	}
}
