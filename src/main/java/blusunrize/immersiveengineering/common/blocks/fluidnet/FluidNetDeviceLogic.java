/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.fluidnet;

/**
 * What a fluid fitting reports to the world, away from the world.
 * <p>
 * The comparator is the only readout a fitting offers to redstone, and it answers a different
 * question in each of the two modes the network runs in. That difference is a decision -- it is the
 * whole of city mode's bargain, stated once in a place a test can reach.
 *
 * @author LDImmersiveEngineering -- virtual fluid network
 */
public final class FluidNetDeviceLogic
{
	private FluidNetDeviceLogic()
	{
	}

	/** What a fitting's model shows when it belongs to no main at all. */
	public static final int UNLINKED_COLOUR = 0xFFFFFF;

	/**
	 * The comparator level for a fitting.
	 * <p>
	 * <strong>Throughput in normal mode, presence in city mode.</strong> City mode stops metering
	 * flow, so a proportional reading there would sit at whatever the last real tick happened to
	 * leave behind; it answers "is this main pressurised" instead, which is the question that still
	 * has a true answer. Normal mode rounds <em>up</em>, so a fitting moving anything at all reads
	 * at least 1 -- a line that is working must never read the same as a line that is dead.
	 * <p>
	 * An unlinked fitting, or one on a main that is closed, tripped or asleep, reads zero. There is
	 * no separate "broken" level: a comparator has one number, and "nothing is coming through" is
	 * the honest answer to all of those.
	 *
	 * @param linked      whether the fitting belongs to a main
	 * @param operational whether that main is open, untripped and inside its schedule
	 * @param cityMode    see {@code CityMode.petroleum()}
	 * @param pressurised whether the main has a live source, in city mode
	 * @param throughput  what moved through this fitting last tick
	 * @param cap         this fitting's transfer cap
	 */
	public static int comparatorLevel(boolean linked, boolean operational, boolean cityMode,
									  boolean pressurised, int throughput, int cap)
	{
		if(!linked||!operational)
			return 0;
		if(cityMode)
			return pressurised?15: 0;
		if(cap <= 0||throughput <= 0)
			return 0;
		return (int)Math.min(15, (long)Math.ceil(15.0*throughput/cap));
	}

	/**
	 * @return the colour a fitting's model is tinted, given its synced state
	 */
	public static int mainColour(boolean unlinked, int mainColour)
	{
		return unlinked?UNLINKED_COLOUR: mainColour;
	}
}
