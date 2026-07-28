/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.wires.conduit;

import blusunrize.immersiveengineering.api.energy.wires.WireType;

import javax.annotation.Nullable;

/**
 * The one place that knows about both {@link WireType} and {@link ChannelSpec}.
 * <p>
 * Everything else in this package is world-free on purpose -- see {@link ChannelSpec} -- and this
 * class is the seam that keeps it that way. Patching a channel goes through here once; carrying
 * energy down it never does.
 * <p>
 * Deliberately tiny and deliberately the only bridge. If a second one appears, the registry is
 * about to end up in the per-tick path, which is the shape of the mistake the wire network's
 * performance history is a monument to.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public class WireChannels
{
	private WireChannels()
	{
	}

	/**
	 * @return the spec for that wire, or null if it is not a wire that carries energy. Structural
	 * cable and rope are refused rather than given a zero-rate spec: a channel patched to a rope
	 * would be a circuit that looks live and moves nothing.
	 */
	@Nullable
	public static ChannelSpec specOf(@Nullable WireType type)
	{
		if(type==null||!type.isEnergyWire())
			return null;
		return new ChannelSpec(type.getUniqueName(), type.getTransferRate(), type.getLossRatio());
	}

	/**
	 * The resolver {@code ChannelSet.refresh} wants: a wire's unique name to its current numbers,
	 * or null if that wire has gone away.
	 * <p>
	 * Used at server start so that a config change to a wire's transfer rate reaches conduits built
	 * before it. Not used per tick, and must not become so.
	 */
	@Nullable
	public static ChannelSpec resolve(@Nullable String uniqueName)
	{
		if(uniqueName==null||uniqueName.isEmpty())
			return null;
		return specOf(WireType.getValue(uniqueName));
	}
}
