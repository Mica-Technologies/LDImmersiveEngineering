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
 * A place where power enters a conduit run from outside it: one conductor of one box, credited by
 * something that is not the run.
 * <p>
 * <strong>Why the feature needs this and a box's own {@code held} will not do.</strong> The first version
 * asked the far box whether its conductor was lit, and deleted the outlet when it was not. A box
 * loaded on its own goes dark in about a second -- {@code CITY_DECAY} is a twentieth of a channel a
 * tick and the peers that would re-light it are unloaded -- so walking to the far end of a line was
 * enough to delete the record holding the far town up, and the two ends of a run had to be loaded
 * together for the link to exist at all. That is precisely the situation the feature exists to
 * survive.
 * <p>
 * A feed is the other half of the answer. It says "this conductor is being fed here", it survives
 * the box going dark, and it survives the box unloading, so
 * {@link VirtualConduits#isLive} can flood from it across the saved bundles and say whether a
 * conductor anywhere on the run is live without anything having to be loaded.
 * <p>
 * <strong>Only external credits.</strong> Power arriving from a peer along the same run is the run
 * carrying what it was already given, not a new supply; counting it would let a run vouch for its
 * own liveness forever. See {@code TileEntityJunctionBox.credit} and, for why "external" can be
 * taken at face value at all, {@code ConduitRuns} -- the city push no longer lets a run feed itself
 * back through a wire network.
 *
 * @author LDImmersiveEngineering -- virtual conduit
 */
public class VirtualConduitFeed
{
	private final int dimension;
	private final BlockPos boxPos;
	private final WireChannel channel;

	public VirtualConduitFeed(int dimension, BlockPos boxPos, WireChannel channel)
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

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setInteger("dim", dimension);
		tag.setLong("box", boxPos.toLong());
		tag.setString("channel", channel.getName());
		return tag;
	}

	/**
	 * @return the feed, or null for a conductor this build does not have
	 */
	@Nullable
	public static VirtualConduitFeed readFromNBT(NBTTagCompound tag)
	{
		WireChannel channel = WireChannel.byName(tag.getString("channel"));
		if(channel==null)
			return null;
		return new VirtualConduitFeed(tag.getInteger("dim"), BlockPos.fromLong(tag.getLong("box")), channel);
	}

	@Override
	public String toString()
	{
		return "VirtualConduitFeed{dim="+dimension+" "+boxPos.getX()+" "+boxPos.getY()+" "+boxPos.getZ()
				+" "+channel.getName()+"}";
	}
}
