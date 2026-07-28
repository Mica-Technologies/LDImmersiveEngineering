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

/**
 * What one channel of a bundle is: a wire type, and that wire's rate and loss.
 * <p>
 * The wire type is stored by <strong>unique name</strong> rather than as a {@code WireType}
 * reference, and its transfer rate and loss ratio are stored beside it as a snapshot. Two reasons,
 * and both matter:
 * <ul>
 * <li>{@code WireType} reaches into the item registry and the texture atlas, so holding one here
 * would make the entire channel model unloadable outside a running game. Everything in this
 * package is deliberately world-free, exactly as {@code api/fluid/network} keys its tables by
 * fluid registry name.</li>
 * <li>Path walking needs to take the minimum rate along a route and compound the loss. Doing that
 * against numbers already in hand is a comparison; doing it against {@code WireType.getValue(name)}
 * is a registry lookup per channel per connection per tick, which is the shape of the mistake the
 * wire network's performance history is a monument to.</li>
 * </ul>
 * <p>
 * The snapshot's cost: if a config change alters a wire's transfer rate, existing conduits keep the
 * old figure until their channel is re-patched. {@link #name} is the authority, so a refresh is a
 * matter of re-reading the registry for each spec -- see {@code ChannelSet.refresh}. That is a
 * server-start job, not a per-tick one.
 * <p>
 * Immutable. A channel is re-patched by replacing its spec, never by editing one.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class ChannelSpec
{
	private final String name;
	private final int transferRate;
	private final double lossRatio;

	public ChannelSpec(String name, int transferRate, double lossRatio)
	{
		if(name==null||name.isEmpty())
			throw new IllegalArgumentException("a channel spec needs a wire type name");
		this.name = name;
		this.transferRate = Math.max(0, transferRate);
		//Loss is a ratio, and a ratio outside 0..1 means either free energy or a wire that eats
		//more than it carries. Clamped rather than rejected: a bad config value should degrade the
		//wire, not refuse to load the save it is written into.
		this.lossRatio = Math.max(0, Math.min(1, lossRatio));
	}

	/**
	 * @return the wire type's unique name -- the authoritative half of this record
	 */
	public String getName()
	{
		return name;
	}

	public int getTransferRate()
	{
		return transferRate;
	}

	public double getLossRatio()
	{
		return lossRatio;
	}

	/**
	 * The two specs a route has to reconcile where it passes from one conduit to the next.
	 * <p>
	 * Rate takes the minimum, matching what the existing wire path walk does with {@code
	 * minimumType}: a route is as fat as its narrowest segment. Loss takes the <em>maximum</em>
	 * rather than the sum, because this is not a route total -- it is what a single hop should
	 * charge when its two ends disagree about the wire. Summing here would double-count the moment
	 * a third conduit joined.
	 * <p>
	 * The name is kept from whichever side is the narrower, so a readout naming the wire names the
	 * one actually limiting the run.
	 *
	 * @return the reconciled spec, or null if the two channels are not compatible at all
	 */
	@Nullable
	public ChannelSpec reconcile(@Nullable ChannelSpec other)
	{
		if(other==null)
			return null;
		if(transferRate <= other.transferRate)
			return new ChannelSpec(name, transferRate, Math.max(lossRatio, other.lossRatio));
		return new ChannelSpec(other.name, other.transferRate, Math.max(lossRatio, other.lossRatio));
	}

	public NBTTagCompound writeToNBT()
	{
		NBTTagCompound tag = new NBTTagCompound();
		tag.setString("wire", name);
		tag.setInteger("rate", transferRate);
		tag.setDouble("loss", lossRatio);
		return tag;
	}

	/**
	 * @return the spec, or null if the tag is missing the one field that cannot be defaulted
	 */
	@Nullable
	public static ChannelSpec readFromNBT(@Nullable NBTTagCompound tag)
	{
		if(tag==null)
			return null;
		String name = tag.getString("wire");
		if(name==null||name.isEmpty())
			return null;
		return new ChannelSpec(name, tag.getInteger("rate"), tag.getDouble("loss"));
	}

	@Override
	public boolean equals(Object obj)
	{
		if(this==obj)
			return true;
		if(!(obj instanceof ChannelSpec))
			return false;
		ChannelSpec other = (ChannelSpec)obj;
		return transferRate==other.transferRate
				&&Double.compare(lossRatio, other.lossRatio)==0
				&&name.equals(other.name);
	}

	@Override
	public int hashCode()
	{
		int result = name.hashCode();
		result = 31*result+transferRate;
		long loss = Double.doubleToLongBits(lossRatio);
		return 31*result+(int)(loss^(loss >>> 32));
	}

	@Override
	public String toString()
	{
		return name+" @"+transferRate+" IF/t, "+String.format("%.4f", lossRatio)+" loss";
	}
}
