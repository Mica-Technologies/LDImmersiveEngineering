/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.api.energy.virtualgen;

/**
 * Measures what a generator can deliver: the rolling peak, over a window of seconds, of the energy it
 * <em>offered</em> per second, expressed per tick.
 * <p>
 * <strong>Offered, not accepted.</strong> A plant whose consumers are all full has almost nothing
 * accepted from it, and a meter that measured that would report a plant worth nothing the moment it
 * unloaded. IE's generators hand their whole production to the block in front of them on every real
 * insert, whatever that block then takes, so the offer is the production.
 * <p>
 * <strong>A peak, not an average.</strong> A watermill's output breathes with its rotation and a load-gated
 * generator pauses when nothing wants power; the peak of per-second averages is what the plant sustains
 * when it is running, which is what a distant town needs from it.
 * <p>
 * Pure bookkeeping driven by a tick number, so it is testable without a world.
 *
 * @author LDImmersiveEngineering -- virtual generation
 */
public class OutputMeter
{
	private static final int TICKS_PER_SECOND = 20;

	/**
	 * Completed per-second totals, a ring indexed by second number modulo its length.
	 */
	private long[] seconds;
	/**
	 * The second number each ring slot was written for, so a slot left over from a gap reads as zero.
	 */
	private long[] stamps;
	private long currentSecond = Long.MIN_VALUE;
	private long currentTotal;

	public OutputMeter(int windowSeconds)
	{
		resize(windowSeconds);
	}

	private void resize(int windowSeconds)
	{
		int n = Math.max(1, windowSeconds);
		seconds = new long[n];
		stamps = new long[n];
		java.util.Arrays.fill(stamps, Long.MIN_VALUE);
		currentSecond = Long.MIN_VALUE;
		currentTotal = 0;
	}

	/**
	 * Records energy offered on a given tick. Several calls on one tick add up.
	 */
	public void record(long tick, int offered)
	{
		if(offered <= 0)
			return;
		roll(tick);
		currentTotal += offered;
	}

	/**
	 * @return the rolling peak rate in IF/t as of {@code tick}, over completed seconds only
	 */
	public int getRate(long tick)
	{
		roll(tick);
		long second = Math.floorDiv(tick, TICKS_PER_SECOND);
		long peak = 0;
		for(int i = 0; i < seconds.length; i++)
			if(stamps[i]!=Long.MIN_VALUE&&stamps[i] < second&&second-stamps[i] <= seconds.length)
				peak = Math.max(peak, seconds[i]);
		return (int)Math.min(Integer.MAX_VALUE, peak/TICKS_PER_SECOND);
	}

	/**
	 * Applies a changed window length. Measurements are discarded, because a ring cannot be re-sliced;
	 * the caller keeps its last known rate until the new window has something to say.
	 */
	public void setWindow(int windowSeconds)
	{
		if(Math.max(1, windowSeconds)!=seconds.length)
			resize(windowSeconds);
	}

	/**
	 * @return true if anything has been recorded within the window
	 */
	public boolean hasData(long tick)
	{
		roll(tick);
		long second = Math.floorDiv(tick, TICKS_PER_SECOND);
		if(currentSecond==second&&currentTotal > 0)
			return true;
		for(long stamp : stamps)
			if(stamp!=Long.MIN_VALUE&&second-stamp <= seconds.length)
				return true;
		return false;
	}

	private void roll(long tick)
	{
		long second = Math.floorDiv(tick, TICKS_PER_SECOND);
		if(second==currentSecond)
			return;
		if(currentSecond!=Long.MIN_VALUE&&currentTotal > 0)
		{
			int slot = (int)Math.floorMod(currentSecond, (long)seconds.length);
			seconds[slot] = currentTotal;
			stamps[slot] = currentSecond;
		}
		currentSecond = second;
		currentTotal = 0;
	}
}
