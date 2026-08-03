/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.petroleum;

import blusunrize.immersiveengineering.common.util.petroleum.PetroleumTickHandler;

/**
 * How much a wellhead may draw, and how much gas comes up with it.
 * <p>
 * <strong>The arithmetic that makes a flare stack mandatory rather than decorative.</strong> Oil
 * comes up with sour gas dissolved in it whether or not anything wants the gas, so a well with
 * nowhere to put its gas has to back up and stop. If the draw were clamped only against the oil
 * tank, the excess gas would be quietly destroyed on every pass and the flare stack, the scrubber
 * and the re-injection well would all be optional scenery -- the entire mid-game of this feature
 * rests on these three functions being right.
 * <p>
 * World-free and here rather than inline on the tile entity, for the reason
 * {@code ReservoirModel} is: this is a decision, and decisions in this feature get tested. The
 * wellhead was the one extraction machine with no test class at all, and this was the arithmetic
 * hiding in it.
 *
 * @author LDImmersiveEngineering -- petroleum
 */
public final class WellheadFlow
{
	private WellheadFlow()
	{
	}

	/**
	 * Buffer between production passes, sized to two of them at the configured peak rate: big
	 * enough that a pass is never wasted for want of room, small enough that a well nobody has
	 * plumbed yet does not quietly bank a tank's worth of oil.
	 * <p>
	 * Derived from the config rather than hard-coded, or raising {@code peakFlowRate} would leave
	 * the buffer behind and the well would throttle itself against a tank it had outgrown.
	 */
	public static int capacityFor(int peakFlowRate)
	{
		return Math.max(1000, 2*PetroleumTickHandler.PRODUCTION_INTERVAL*Math.max(1, peakFlowRate));
	}

	/**
	 * How much crude this pass may draw, given the room in both tanks.
	 * <p>
	 * The gas side is converted into the crude it corresponds to -- room for 1,000 mB of gas at a
	 * quarter-bucket per bucket is room for 4,000 mB of crude -- and the smaller of the two limits
	 * wins. Floored, never rounded: rounding up here would authorise a draw whose gas does not fit,
	 * and the overflow would be destroyed.
	 *
	 * @param oilRoom  free space in the crude tank, in mB
	 * @param gasRoom  free space in the associated-gas tank, in mB
	 * @param gasRatio mB of gas per mB of crude; zero or less disables the gas side entirely
	 *
	 * @return the largest draw that will not overfill either tank, never negative
	 */
	public static int drawRoom(int oilRoom, int gasRoom, double gasRatio)
	{
		int room = Math.max(0, oilRoom);
		if(gasRatio <= 0)
			return room;
		//A full gas tank stops the well outright, oil and all. That is the whole point.
		int allowedByGas = (int)Math.floor(Math.max(0, gasRoom)/gasRatio);
		return Math.min(room, allowedByGas);
	}

	/**
	 * How much sour gas comes up alongside a given draw of crude.
	 *
	 * @return mB of gas, never negative
	 */
	public static int associatedGas(int crude, double gasRatio)
	{
		if(crude <= 0||gasRatio <= 0)
			return 0;
		return (int)Math.floor(crude*gasRatio);
	}
}
