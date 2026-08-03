/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.conduit;

/**
 * The junction box's decisions that do not need a world.
 * <p>
 * The bucket brigade itself already lives in {@code ConduitTransfer} and is tested there; this is
 * what was left inline on the tile entity. Three things, each of which is a choice rather than
 * arithmetic: what a comparator reads off a bundle, how much a channel will take, and how the
 * redstone channels resolve when several boxes on one run drive the same conductor.
 *
 * @author LDImmersiveEngineering -- conduits
 */
public final class JunctionBoxLogic
{
	private JunctionBoxLogic()
	{
	}

	/**
	 * What a comparator reads off a whole bundle.
	 * <p>
	 * <strong>The busiest conductor, not the total and not the average.</strong> A bundle where one
	 * channel is saturated and fifteen are idle is a bundle with a problem; a total would read the
	 * same as sixteen channels ticking over, and an average would hide it entirely. Floored at 1 for
	 * any non-empty bundle so "carrying something" and "carrying nothing" are distinguishable.
	 *
	 * @param held     per-channel contents
	 * @param capacity one channel's capacity
	 */
	public static int comparatorLevel(int[] held, int capacity)
	{
		if(held==null||capacity <= 0)
			return 0;
		int busiest = 0;
		for(int value : held)
			busiest = Math.max(busiest, value);
		if(busiest <= 0)
			return 0;
		return Math.max(1, Math.min(15, (int)((long)busiest*15/capacity)));
	}

	/**
	 * How much a channel will accept, given what it already holds.
	 *
	 * @return the amount actually taken, never negative and never past the capacity
	 */
	public static int credit(int held, int amount, int capacity)
	{
		if(amount <= 0)
			return 0;
		return Math.max(0, Math.min(capacity-held, amount));
	}

	/**
	 * Resolve the redstone channels across every box on a run.
	 * <p>
	 * <strong>Strongest wins, per channel.</strong> A conductor reaches every box on the run, so
	 * several boxes may be driving the same colour at once -- a lever at one end and a comparator at
	 * the other. Taking the maximum makes that behave the way a redstone wire does, which is the
	 * behaviour a player already has in their hands; summing would let two weak inputs forge a
	 * strong one, and last-writer-wins would make the answer depend on iteration order.
	 *
	 * @param channelCount  how many conductors a bundle carries
	 * @param contributions each {channelIndex, signal}, in any order
	 *
	 * @return the resolved signal per channel
	 */
	public static int[] strongestPerChannel(int channelCount, int[][] contributions)
	{
		int[] strongest = new int[Math.max(0, channelCount)];
		if(contributions==null)
			return strongest;
		for(int[] one : contributions)
		{
			if(one==null||one.length < 2)
				continue;
			int channel = one[0];
			if(channel < 0||channel >= strongest.length)
				continue;
			//Clamped to a redstone level rather than trusted: this comes off a neighbouring block,
			//and a mod is free to answer with whatever it likes.
			int signal = Math.max(0, Math.min(15, one[1]));
			strongest[channel] = Math.max(strongest[channel], signal);
		}
		return strongest;
	}
}
